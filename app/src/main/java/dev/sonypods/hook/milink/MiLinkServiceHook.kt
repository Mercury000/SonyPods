package dev.sonypods.hook.milink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dev.sonypods.bridge.HookStateMirror
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.config.ConfigManager
import dev.sonypods.headphones.HeadphoneFormFactor
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.hook.HookContext
import dev.sonypods.hook.Log
import dev.sonypods.hook.callMethod
import dev.sonypods.hook.getObjectField
import dev.sonypods.hook.setObjectField
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import dev.sonypods.utils.miuiStrongToast.data.PodParams

@SuppressLint("MissingPermission")
object MiLinkServiceHook : HookContext() {
    internal const val TAG = "SonyPods-MiLink"
    private const val PREFS_NAME = "sonypods_milink_state"

    /**
     * MiLink carrier identity for over-ear (single-battery) headphones. Mirrors the
     * TWS default [dev.sonypods.config.ConfigManager.DEFAULT_FAKE_DEVICE_ID]; the fusion
     * device center classifies devices off this carrier, so a headphone must not ride
     * the TWS carrier or it would render as a case+left+right earbud set.
     */
    private const val HEADPHONES_DEVICE_ID = "01013A04"

    /** headsetPropertyChangeListener update types observed in the MiUI headset runtime. */
    private const val UPDATE_TYPE_BATTERY = 4
    private const val UPDATE_TYPE_ANC = 8
    private val knownSonyAddresses = linkedSetOf<String>()
    internal var context: Context? = null
    private var receiverRegistered = false
    private var stateSeeded = false
    internal var currentAddress: String? = null
    private var currentName: String? = null
    private var currentBattery: BatteryParams = BatteryParams()
    private var currentAnc = 1
    /** Over-ear devices carry a single battery and must not be projected onto
     * MiLink's TWS case/left/right slot layout. Follows the engine's capability-
     * derived form factor (SC BatterySupportType): left/right (+ case) is TWS,
     * anything else is an over-ear/neck headset. */
    private var currentFormFactor: String? = null
    internal val isOverEar: Boolean
        get() = currentFormFactor == HeadphoneFormFactor.HEADSET.name
    internal var currentSpatialAudioMode = ConfigManager.SPATIAL_AUDIO_OFF
    internal var lastAncBatteryController: Any? = null
    internal var lastProfileContext: Any? = null
    private val spatialAudioHook = MiLinkSpatialAudioHook(this)

    override fun onHook() {
        hookContextEntry()
        hookMxBluetoothRuntime()
        hookHeadsetRuntimeDisplay()
        spatialAudioHook.hookCirculateHeadsetServiceInfo()
    }

    /**
     * Fusion device center carrier identity. Over-ear headphones report the headphone
     * carrier; everything else keeps the user-configured disguise model. Called for the
     * MiLink process only, so the settings-injection and upstream hooks are unaffected.
     */
    override fun fakeDeviceId(): String =
        if (isOverEar) HEADPHONES_DEVICE_ID else super.fakeDeviceId()

    private fun hookContextEntry() {
        // Primary entry: every process has an Application, so the state receiver is up
        // before the fusion-center panel asks for battery/ANC.
        runCatching {
            hookAfter(findMethod("android.app.Application", "onCreate")) {
                registerStatusReceiver(instance as? Context)
            }
        }.onFailure { Log.d(TAG, "hook Application.onCreate skipped", it) }

        listOf(
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService",
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager"
        ).forEach { className ->
            runCatching {
                hookBefore(findMethod(className, "getInstanceForIsMiTWS", Context::class.java)) {
                    registerStatusReceiver(args[0] as? Context)
                }
            }.onFailure { Log.d(TAG, "hook $className.getInstanceForIsMiTWS skipped", it) }
        }
    }

    private fun hookMxBluetoothRuntime() {
        val classes = listOf(
            "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager",
            "com.xiaomi.mxbluetoothsdk.service.MxBluetoothService"
        )
        classes.forEach { className ->
            hookBluetoothDeviceResult(className, "checkIsMiTWS") { 1 }
            hookBluetoothDeviceResult(className, "getDeviceId") { fakeDeviceId() }
            hookBluetoothDeviceResult(className, "getBatteryLevel") { 1 }
            hookBluetoothDeviceResult(className, "getAncState") { miLinkAncState() }
            hookBluetoothDeviceResult(className, "getDeviceRunInfo") { 0 }
            hookBluetoothDeviceResult(className, "getWearStatus") { "0,0" }
            hookBluetoothDeviceResult(className, "isLeAudio") { false }
            hookAncCommand(className, "openAnc", 2, 1)
            hookAncCommand(className, "closeAnc", 1, 0)
            hookAncCommand(className, "openTransparent", 3, 2)
        }
        classes.forEach { className ->
            hookStringAddressResult(className, "isMiTWS") { true }
            hookStringAddressResult(className, "isSupportAudioSwitch") { miLinkSwitchState() }
            hookStringAddressResult(className, "getRingFindState") { false }
        }
        spatialAudioHook.hookMxBluetoothRuntime(classes)
    }

    private fun hookHeadsetRuntimeDisplay() {
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getDeviceId") { fakeDeviceId() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.ProfileContext", "getBatteryLevel") { miLinkBatteryLevels() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getDeviceId") { fakeDeviceId() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getAncState") { miLinkAncState() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getBatteryLevelCache") { miLinkBatteryLevels() }
        hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getHeadsetPropertyBlock") { batteryPercentForMiLink() }
        hookStringAddressResult("com.miui.headset.runtime.AncBatteryController", "getSwitchState") { miLinkSwitchState() }
        hookAncStateBlock()
        spatialAudioHook.hookHeadsetRuntimeDisplay()
        hookHeadsetInfoNoArg("getDeviceId") { fakeDeviceId() }
        hookHeadsetInfoNoArg("component3") { fakeDeviceId() }
        hookHeadsetInfoNoArg("getPowers") { miLinkBatteryLevels() }
        hookHeadsetInfoNoArg("component4") { miLinkBatteryLevels() }
        hookHeadsetInfoNoArg("getMode") { miLinkAncState() }
        hookHeadsetInfoNoArg("component5") { miLinkAncState() }
        hookHeadsetInfoNoArg("getSwitchState") { miLinkSwitchState() }
        hookHeadsetInfoNoArg("component8") { miLinkSwitchState() }
    }

    internal fun hookBluetoothDeviceResult(className: String, methodName: String, result: () -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookAfter
                if (!isSonyPod(device)) return@hookAfter
                cacheRuntimeOwner(className, instance)
                captureRuntimeContext(instance)
                this.result = result()
                if (className == "com.miui.headset.runtime.AncBatteryController" && methodName == "getHeadsetPropertyBlock") {
                    notifyHeadsetPropertyChanged(instance, device, 4)
                }
            }
        }.onFailure { Log.d(TAG, "hook $className.$methodName(BluetoothDevice) skipped", it) }
    }

    internal fun hookStringAddressResult(className: String, methodName: String, result: () -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, String::class.java)) {
                val address = args[0] as? String ?: return@hookAfter
                if (!isSonyAddress(address)) return@hookAfter
                this.result = result()
            }
        }.onFailure { Log.d(TAG, "hook $className.$methodName(String) skipped", it) }
    }

    private fun hookAncCommand(className: String, methodName: String, sonyAnc: Int, result: Int) {
        runCatching {
            hookBefore(findMethod(className, methodName, BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isSonyPod(device)) return@hookBefore
                cacheRuntimeOwner(className, instance)
                captureRuntimeContext(instance)
                currentAnc = sonyAnc
                sendSonyAnc(sonyAnc)
                sendAncChanged(sonyAnc)
                this.result = result
            }
        }.onFailure { Log.d(TAG, "hook $className.$methodName command skipped", it) }
    }

    private fun hookAncStateBlock() {
        runCatching {
            hookBefore(findMethod("com.miui.headset.runtime.AncBatteryController", "setAncStateBlock", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!isSonyPod(device)) return@hookBefore
                lastAncBatteryController = instance
                captureRuntimeContext(instance)
                val miLinkMode = args[1] as? Int ?: return@hookBefore
                val sonyAnc = sonyAncFromMiLink(miLinkMode)
                val instanceContext = runCatching { getObjectField(instance, "context") as? Context }.getOrNull()
                if (instanceContext != null) {
                    context = instanceContext.applicationContext ?: instanceContext
                }
                currentAnc = sonyAnc
                sendSonyAnc(sonyAnc, instanceContext)
                sendAncChanged(sonyAnc, instanceContext)
                notifyHeadsetPropertyChanged(instance, device, 8)
                notifyHeadsetPropertyChanged(instance, device, 4)
                this.result = miLinkAncState()
            }
        }.onFailure { Log.d(TAG, "hook AncBatteryController.setAncStateBlock skipped", it) }
    }

    internal fun hookHeadsetInfoNoArg(methodName: String, result: () -> Any) {
        runCatching {
            hookAfter(findMethodByParamCount("com.miui.headset.api.HeadsetInfo", methodName, 0)) {
                if (!isTargetHeadsetInfo(instance)) return@hookAfter
                this.result = result()
            }
        }.onFailure { Log.d(TAG, "hook HeadsetInfo.$methodName skipped", it) }
    }

    private val stateMirror = HookStateMirror { snapshot -> applySnapshot(snapshot) }

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        context = ctx.applicationContext ?: ctx
        Log.d(TAG, "registering state mirror process=${runCatching { android.app.Application.getProcessName() }.getOrNull()} ctx=$ctx")
        // Recover the device identity before the panel can ask: the hooks decline to
        // answer for addresses they do not recognise as Sony, so an empty set at the
        // first query loses that round even once the snapshot arrives.
        // The credential-protected pref is only readable after the user (id 0) unlocks,
        // but this process can start earlier than that (e.g. boot providers). Guard the
        // seed: it is best-effort and is recovered from the broadcast snapshot anyway.
        runCatching { loadState() }
            .onFailure { Log.d(TAG, "loadState skipped (storage locked); will seed from snapshot", it) }
        stateMirror.register(context)
        runCatching {
            context?.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == SonyPodsAction.ACTION_CONFIG_CHANGED) applyPushedConfig(intent)
                }
            }, IntentFilter(SonyPodsAction.ACTION_CONFIG_CHANGED), Context.RECEIVER_EXPORTED)
        }
        receiverRegistered = true
    }

    private fun applySnapshot(snapshot: SonyStateSnapshot) {
        snapshot.deviceAddress?.let {
            currentAddress = it
            knownSonyAddresses.add(it.uppercase())
        }
        snapshot.deviceName?.let { currentName = it }
        snapshot.formFactor?.let { currentFormFactor = it }
        currentBattery = BatteryParams(
            left = (snapshot.batteryLeft ?: snapshot.batterySingle)
                ?.let { PodParams(battery = it, isConnected = true) },
            right = snapshot.batteryRight?.let { PodParams(battery = it, isConnected = true) },
            case = snapshot.batteryCradle?.let { PodParams(battery = it, isConnected = true) },
        )
        currentAnc = when (snapshot.noiseControlMode) {
            NoiseControlMode.NOISE_CANCELLING -> 2
            NoiseControlMode.AMBIENT_SOUND -> 3
            else -> 1
        }
        saveState(context)
        Log.d(TAG, "state applied battery=${snapshot.batteryLeft}/${snapshot.batteryRight} anc=$currentAnc formFactor=$currentFormFactor overEar=$isOverEar")
        pushStateToPanel()
    }

    /**
     * The fusion-center panel only *pulls* headphone state, and only when the system
     * decides to ask. A snapshot arriving after the panel rendered would otherwise sit
     * in our cache unseen — which is why the panel looked empty until the process was
     * restarted. Tell the runtime its properties changed so it queries us again.
     */
    private fun pushStateToPanel() {
        val address = currentAddress ?: return
        val device = runCatching {
            context?.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
        }.getOrNull() ?: return
        listOf(lastAncBatteryController, lastProfileContext)
            .filterNotNull()
            .distinctBy { it.javaClass.name }
            .forEach { owner ->
                notifyHeadsetPropertyChanged(owner, device, UPDATE_TYPE_BATTERY)
                notifyHeadsetPropertyChanged(owner, device, UPDATE_TYPE_ANC)
            }
    }

    internal fun isSonyPod(device: BluetoothDevice): Boolean {
        val address = runCatching { device.address }.getOrNull()
        if (address != null && isSonyAddress(address)) return true
        val name = runCatching { device.name ?: device.alias }.getOrNull().orEmpty()
        val normalized = name.trim().lowercase()
        val result = normalized.contains("sony") ||
            normalized.contains("linkbuds") ||
            normalized.startsWith("wf-") ||
            normalized.startsWith("wh-") ||
            normalized.startsWith("wi-") ||
            normalized.startsWith("xba-") ||
            normalized.startsWith("mdr-")
        if (result && address != null) {
            knownSonyAddresses.add(address.uppercase())
            currentAddress = address
            currentName = name
        }
        return result
    }

    internal fun isSonyAddress(address: String): Boolean {
        val normalized = address.uppercase()
        return normalized == currentAddress?.uppercase() || normalized in knownSonyAddresses
    }

    private fun isTargetHeadsetInfo(info: Any?): Boolean {
        if (info == null) return false
        listOf("getAddress", "component1").forEach { method ->
            val address = runCatching { callMethod(info, method) as? String }.getOrNull()
            if (address != null && isSonyAddress(address)) return true
        }
        return false
    }

    private fun miLinkAncState(): Int {
        loadState()
        return when (currentAnc) {
            2, 5, 6, 7, 8 -> 1
            3 -> 2
            else -> 0
        }
    }

    private fun sonyAncFromMiLink(mode: Int): Int {
        return when (mode) {
            1 -> 2
            2 -> 3
            else -> 1
        }
    }

    private fun miLinkBatteryLevels(): List<Int> {
        loadState()
        // Over-ear headphones present a single (overall) battery in MiLink slots 2/5,
        // matching SC's headset presentation. TWS instead uses case/left/right + charging.
        if (isOverEar) {
            val single = batteryValue(currentBattery.left)
                .takeIf { it >= 0 }
                ?: batteryValue(currentBattery.right)
                ?: 0
            val charging = if (single > 0) chargingValue(currentBattery.left) else 0
            return listOf(-1, -1, if (single > 0) single else -1, 0, 0, charging)
        }
        val left = batteryValue(currentBattery.left)
        val right = batteryValue(currentBattery.right)
        val box = batteryValue(currentBattery.case)
        return listOf(
            box,
            left,
            right,
            chargingValue(currentBattery.case),
            chargingValue(currentBattery.left),
            chargingValue(currentBattery.right)
        )
    }

    private fun batteryPercentForMiLink(): Int {
        loadState()
        if (isOverEar) {
            val single = batteryValue(currentBattery.left)
                .takeIf { it >= 0 }
                ?: batteryValue(currentBattery.right)
                ?: 0
            return single.coerceIn(0, 100)
        }
        val values = listOfNotNull(currentBattery.left, currentBattery.right)
            .filter { it.isConnected }
            .map { it.battery.coerceIn(0, 100) }
        return values.minOrNull() ?: 0
    }

    private fun batteryValue(params: dev.sonypods.utils.miuiStrongToast.data.PodParams?): Int {
        if (params?.isConnected != true) return -1
        return params.battery.coerceIn(0, 100)
    }

    private fun chargingValue(params: dev.sonypods.utils.miuiStrongToast.data.PodParams?): Int {
        return if (params?.isConnected == true && params.isCharging) 1 else 0
    }

    private fun sendSonyAnc(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: run {
            Log.d(TAG, "sendSonyAnc skipped: context is null mode=$mode")
            return
        }
        SonyBridge.setNoiseControl(
            ctx,
            when (mode) {
                2 -> NoiseControlMode.NOISE_CANCELLING
                3 -> NoiseControlMode.AMBIENT_SOUND
                else -> NoiseControlMode.OFF
            },
        )
    }

    private fun sendAncChanged(mode: Int, fallbackContext: Context? = null) {
        val ctx = fallbackContext ?: context ?: return
        listOf("com.milink.service", "com.android.settings").forEach { targetPackage ->
            ctx.sendBroadcast(Intent(SonyPodsAction.ACTION_PODS_ANC_CHANGED).apply {
                putExtra("status", mode)
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
    }

    internal fun miLinkSpatialMode(): Int {
        loadState()
        if (!spatialAudioPanelEnabled()) return -1
        return when (currentAudioEffectState()) {
            ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING -> if (miLinkDeviceSpatialType() == 1) 11 else 9
            ConfigManager.SPATIAL_AUDIO_FIXED -> 1
            else -> 0
        }
    }

    internal fun spatialModeFromMiLink(mode: Int): Int {
        return when (mode) {
            9, 11 -> ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING
            1 -> ConfigManager.SPATIAL_AUDIO_FIXED
            else -> ConfigManager.SPATIAL_AUDIO_OFF
        }
    }

    internal fun miLinkSpatialModeFromMode(mode: Int): Int {
        return when (mode) {
            ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING -> if (miLinkDeviceSpatialType() == 1) 11 else 9
            ConfigManager.SPATIAL_AUDIO_FIXED -> 1
            ConfigManager.SPATIAL_AUDIO_OFF -> 0
            else -> -1
        }
    }

    internal fun miLinkAudioEffectState(): Int {
        loadState()
        return currentAudioEffectState()
    }

    internal fun miLinkDeviceSpatialType(): Int {
        return if (spatialAudioPanelEnabled()) 1 else 0
    }

    internal fun miLinkSwitchState(): Int {
        // Audio-switch support gates the whole ANC/volume section of the fusion
        // device center panel; it is independent of spatial audio, keep it on.
        return 1
    }

    internal fun updateSpatialAudioMode(mode: Int) {
        currentSpatialAudioMode = mode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        saveState(context)
    }

    private fun currentAudioEffectState(): Int {
        return if (spatialAudioPanelEnabled()) {
            currentSpatialAudioMode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        } else {
            -1
        }
    }

    internal fun spatialAudioPanelEnabled(): Boolean {
        // The first batch of Sony targets (WH-1000XM4 / LinkBuds S / WF-1000XM5)
        // has no writable spatial-audio mode, so the fusion-center panel stays hidden.
        return false
    }

    internal fun isTargetAncBatteryModel(model: Any?): Boolean {
        val device = runCatching { callMethod(model, "getBluetoothDevice") as? BluetoothDevice }.getOrNull()
        return device?.let { isSonyPod(it) } == true
    }

    internal fun cacheRuntimeOwner(className: String, owner: Any?) {
        when (className) {
            "com.miui.headset.runtime.AncBatteryController" -> lastAncBatteryController = owner
            "com.miui.headset.runtime.ProfileContext" -> lastProfileContext = owner
        }
    }

    internal fun captureRuntimeContext(owner: Any?) {
        val ownerContext = runCatching { getObjectField(owner, "context") as? Context }.getOrNull()
            ?: runCatching { getObjectField(lastProfileContext, "context") as? Context }.getOrNull()
            ?: runCatching { getObjectField(lastAncBatteryController, "context") as? Context }.getOrNull()
            ?: return
        context = ownerContext.applicationContext ?: ownerContext
        // The panel reads battery/ANC from the state this receiver fills in. Registering
        // it only from getInstanceForIsMiTWS is not enough: on some HyperOS builds that
        // entry point never runs, leaving the panel with empty state forever. Register as
        // soon as any hooked runtime call gives us a context.
        registerStatusReceiver(context)
    }

    internal fun notifySpatialUiChanged(owner: Any?, device: BluetoothDevice, mode: Int) {
        val spatialMode = miLinkSpatialModeFromMode(mode)
        val audioEffectState = mode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        syncSpatialModel(owner, device, spatialMode)
        syncSpatialModel(lastAncBatteryController, device, spatialMode)
        listOf(owner, lastAncBatteryController, lastProfileContext).distinctBy { it?.javaClass?.name }.forEach { target ->
            notifyHeadsetPropertyChanged(target, device, 9)
            notifyHeadsetPropertyChanged(target, device, 4)
            notifyProfileAudioEffectListeners(target, audioEffectState)
        }
    }

    private fun syncSpatialModel(owner: Any?, device: BluetoothDevice, spatialMode: Int) {
        val model = runCatching { getObjectField(owner, "ancBatteryModel") }.getOrNull() ?: return
        if (!isTargetAncBatteryModel(model)) return
        runCatching { setObjectField(model, "spatialState", spatialMode) }
            .onFailure { }
        runCatching { setObjectField(model, "deviceSpatialType", miLinkDeviceSpatialType()) }
            .onFailure { }
    }

    private fun notifyProfileAudioEffectListeners(owner: Any?, audioEffectState: Int) {
        runCatching {
            val listener = getObjectField(owner, "audioEffectListener")
            callMethod(listener, "invoke", audioEffectState)
        }.onFailure { }
    }

    private fun notifyHeadsetPropertyChanged(controller: Any?, device: BluetoothDevice, updateType: Int) {
        val listener = runCatching { getObjectField(controller, "headsetPropertyChangeListener") }.getOrNull() ?: return
        runCatching {
            callMethod(listener, "invoke", device, updateType)
        }.onFailure { }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableStatus(): BatteryParams? {
        return runCatching { getParcelableExtra("status", BatteryParams::class.java) }.getOrNull()
            ?: runCatching { getParcelableExtra<BatteryParams>("status") }.getOrNull()
    }

    private fun Intent.batteryStatusFromExtras(): BatteryParams? {
        if (!hasExtra("left_connected") && !hasExtra("right_connected") && !hasExtra("case_connected")) return null
        return BatteryParams(
            left = PodParams(
                getIntExtra("left_battery", 0),
                getBooleanExtra("left_charging", false),
                getBooleanExtra("left_connected", false),
                0
            ),
            right = PodParams(
                getIntExtra("right_battery", 0),
                getBooleanExtra("right_charging", false),
                getBooleanExtra("right_connected", false),
                0
            ),
            case = PodParams(
                getIntExtra("case_battery", 0),
                getBooleanExtra("case_charging", false),
                getBooleanExtra("case_connected", false),
                0
            )
        )
    }

    private fun saveState(ctx: Context?) {
        val prefs = (ctx ?: context)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString("address", currentAddress)
            .putString("name", currentName)
            .putString("form_factor", currentFormFactor)
            .putInt("anc", currentAnc)
            .putInt("spatial_audio_mode", currentSpatialAudioMode)
            .putInt("left_battery", currentBattery.left?.battery ?: 0)
            .putBoolean("left_charging", currentBattery.left?.isCharging == true)
            .putBoolean("left_connected", currentBattery.left?.isConnected == true)
            .putInt("right_battery", currentBattery.right?.battery ?: 0)
            .putBoolean("right_charging", currentBattery.right?.isCharging == true)
            .putBoolean("right_connected", currentBattery.right?.isConnected == true)
            .putInt("case_battery", currentBattery.case?.battery ?: 0)
            .putBoolean("case_charging", currentBattery.case?.isCharging == true)
            .putBoolean("case_connected", currentBattery.case?.isConnected == true)
            .apply()
    }

    /**
     * Cold-start seed only, and only once per process.
     *
     * These prefs are shared by every milink process but SharedPreferences does not
     * synchronise across processes: re-reading them on each query used to overwrite
     * fresh broadcast state with whatever this process happened to have cached, which
     * made the panel's contents depend on which process wrote last.
     */
    private fun loadState() {
        if (stateSeeded) return
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        stateSeeded = true
        currentAddress = prefs.getString("address", currentAddress)
        currentName = prefs.getString("name", currentName)
        if (currentFormFactor == null) {
            currentFormFactor = prefs.getString("form_factor", null)
        }
        currentAnc = prefs.getInt("anc", currentAnc)
        currentSpatialAudioMode = prefs.getInt("spatial_audio_mode", currentSpatialAudioMode)
            .coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
        currentAddress?.let { knownSonyAddresses.add(it.uppercase()) }
        currentBattery = BatteryParams(
            left = PodParams(
                prefs.getInt("left_battery", currentBattery.left?.battery ?: 0),
                prefs.getBoolean("left_charging", currentBattery.left?.isCharging == true),
                prefs.getBoolean("left_connected", currentBattery.left?.isConnected == true),
                0
            ),
            right = PodParams(
                prefs.getInt("right_battery", currentBattery.right?.battery ?: 0),
                prefs.getBoolean("right_charging", currentBattery.right?.isCharging == true),
                prefs.getBoolean("right_connected", currentBattery.right?.isConnected == true),
                0
            ),
            case = PodParams(
                prefs.getInt("case_battery", currentBattery.case?.battery ?: 0),
                prefs.getBoolean("case_charging", currentBattery.case?.isCharging == true),
                prefs.getBoolean("case_connected", currentBattery.case?.isConnected == true),
                0
            )
        )
    }
}
