package dev.sonypods.hook

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.RemoteException
import dev.sonypods.bridge.HookStateMirror
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.config.ConfigManager
import dev.sonypods.device.SonyDeviceService
import dev.sonypods.headphones.HeadphoneFormFactor
import dev.sonypods.hook.symbols.ResolvedSymbolBundle
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.utils.MiuiHeadsetSupport
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import dev.sonypods.utils.miuiStrongToast.data.PodParams
import org.json.JSONObject

@SuppressLint("MissingPermission")
class BluetoothUpstreamHeadsetHook : HookContext() {
    private val TAG = "SonyPods-Hook"
    private val reloadCallbackBinders = mutableListOf<IBinder>()
    private val DESCRIPTOR = "com.android.bluetooth.ble.app.IMiuiHeadsetService"
    private data class CallbackRegistration(
        val callback: Any,
        val deathRecipient: IBinder.DeathRecipient,
    )

    private val callbackLock = Any()
    private val callbacks = linkedMapOf<IBinder, CallbackRegistration>()
    private val handler = Handler(Looper.getMainLooper())
    private val hookedBinderClasses = linkedSetOf<String>()
    private var lastSonyDevice: BluetoothDevice? = null
    private var context: Context? = null
    private var receiverRegistered = false
    private var currentBattery: BatteryParams? = null
    private var currentAnc = 1
    private var currentTransparencyVocalEnhancement = false
    private var hasTransparencyVocalEnhancementState = false
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var currentFormFactor: String? = null
    private lateinit var headsetSymbols: ResolvedSymbolBundle
    private lateinit var notificationSymbols: ResolvedSymbolBundle
    @Volatile private var runtimeHooksInstalled = false

    private val stateMirror = HookStateMirror { snapshot ->
        snapshot.deviceAddress?.let {
            currentAddress = it
            SonyDeviceService.rememberAddress(it)
        }
        snapshot.deviceName?.let { currentName = it }
        // UNKNOWN is the neutral profile's placeholder before the capability table lands; it
        // carries no information, and letting it overwrite a real value (which is also
        // persisted) would flip an over-ear device to the TWS layout.
        snapshot.formFactor?.takeIf { it != HeadphoneFormFactor.UNKNOWN.name }
            ?.let { currentFormFactor = it }
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
        currentTransparencyVocalEnhancement = snapshot.ambientVoiceMode
        hasTransparencyVocalEnhancementState = true
        Log.d(TAG, "state applied address=$currentAddress connected=${snapshot.connected} anc=$currentAnc battery=${currentBattery.debugString()}")
        // Never re-forward an empty/disconnected state into the real MIUI BT stack:
        // a stale or incomplete mirror would push "255,255,255,,,,,0000" as refreshStatus,
        // which the settings headset fragment renders as "-" and fights the correct
        // value injected by the settings hook (visible as a battery flash).
        if (snapshot.connected && (currentBattery?.hasAnyLevel == true || hasTransparencyVocalEnhancementState)) {
            notifyRealStatus("engine-state")
        } else {
            Log.d(TAG, "state mirror not connected or empty; skipped real refreshStatus")
        }
    }

    override fun onHook() {
        hookBefore(
            findMethod(
                "android.app.Instrumentation",
                "callApplicationOnCreate",
                Application::class.java,
            ),
            logicalRole = "bluetooth-extension-headset-application-ready",
        ) {
            val application = requireNotNull(args.firstOrNull() as? Application) {
                "Bluetooth Extension Application is unavailable at callApplicationOnCreate"
            }
            onApplicationAvailable(application)
        }
    }

    @Synchronized
    private fun onApplicationAvailable(application: Context) {
        if (runtimeHooksInstalled) return
        val appContext = application.applicationContext ?: application
        attachSymbolResolver(runtime.symbols(appClassLoader, appContext))
        headsetSymbols = requireSymbols(BluetoothExtensionHeadsetSymbols)
        notificationSymbols = requireSymbols(BluetoothExtensionNotificationSymbols)
        restoreReloadCallbacks()
        hookHeadsetServiceBinder()
        hookNotificationBatteryUpstream()
        runtimeHooksInstalled = true
    }

    override fun saveReloadState(state: Bundle) {
        val binders = Bundle()
        synchronized(callbackLock) { callbacks.keys.toList() }
            .forEachIndexed { index, binder -> binders.putBinder(index.toString(), binder) }
        if (!binders.isEmpty) state.putBundle(KEY_RELOAD_CALLBACK_BINDERS, binders)
    }

    override fun restoreReloadState(state: Bundle) {
        reloadCallbackBinders.clear()
        state.getBundle(KEY_RELOAD_CALLBACK_BINDERS)?.let { binders ->
            binders.keySet().sorted().forEach { key -> binders.getBinder(key)?.let(reloadCallbackBinders::add) }
        }
    }

    override fun onBeforeReload() {
        handler.removeCallbacksAndMessages(null)
        stateMirror.close()
        unregisterRemoteConfigChangeListener()
        receiverRegistered = false
        clearCallbacks()
    }

    override fun onReloadRejected(snapshot: SonyStateSnapshot) {
        if (runtimeHooksInstalled) restoreReloadCallbacks()
        context?.let {
            onApplicationAvailable(it)
            registerStatusReceiver(it)
        }
    }

    internal fun restoreDynamicHookClasses(classNames: List<String>) {
        if (!runtimeHooksInstalled || classNames.isEmpty()) return
        installHeadsetBinderHooks(headsetSymbols.clazz("binder"))
    }

    internal fun startAfterReload(context: Context) {
        onApplicationAvailable(context)
        registerStatusReceiver(context)
    }

    private fun hookNotificationBatteryUpstream() {
        val notificationApiMethod = notificationSymbols.method("notificationApiToast")
        run {
            runCatching {
                hookBefore(
                    notificationApiMethod

                ) {
                    val device = args[4] as? BluetoothDevice
                    if (!isSonyPod(device)) return@hookBefore
                    val battery = effectiveBattery() ?: return@hookBefore
                    val leftBattery = displayBattery(battery.left) ?: (args[1] as? Int ?: 0)
                    val rightBattery = displayBattery(battery.right) ?: (args[2] as? Int ?: 0)
                    val wearState = displayWearState(battery, args[3] as? Int ?: 1)
                    val notification = currentMiuiBluetoothNotification() ?: return@hookBefore
                    result = null
                    notificationSymbols.method("showConnectedToast").invoke(
                        notification,
                        args[0] as? Int ?: 2,
                        leftBattery,
                        rightBattery,
                        wearState,
                        device,
                        args[5] as? String,
                    )
                    Log.d(TAG, "showNewConnectedToast patched device=${device.describe()} left=$leftBattery right=$rightBattery wear=$wearState oldLeft=${args[1]} oldRight=${args[2]} oldWear=${args[3]}")
                }
                Log.d(TAG, "MiuiBluetoothNotificationApi.showNewConnectedToast hook installed")
            }.onFailure { Log.d(TAG, "hook MiuiBluetoothNotificationApi.showNewConnectedToast skipped", it) }
        }

        run {
            runCatching {
                hookBefore(notificationSymbols.method("invokeStatusBar")) {
                    val bundle = args[2] as? Bundle
                    if (shouldInterceptHeadsetWearIsland(bundle)) {
                        when (ConfigManager.islandMode()) {
                            ConfigManager.ISLAND_MODE_NONE, ConfigManager.ISLAND_MODE_MODULE -> {
                                result = null
                                Log.d(TAG, "invokeStatusBar swallowed headset_wear_notification island mode=${ConfigManager.islandMode()}")
                                return@hookBefore
                            }
                        }
                    }
                    patchHeadsetWearIslandBundle(bundle)
                    Log.d(TAG, "invokeStatusBar upstream action=${args[1]} bundle=$bundle focus=${bundle?.getString("miui.focus.param")}")
                }
                Log.d(TAG, "MiuiBluetoothNotification.invokeStatusBar debug hook installed")
            }.onFailure { Log.d(TAG, "hook MiuiBluetoothNotification.invokeStatusBar skipped", it) }
        }
        run {
            runCatching {
                hookAfter(notificationSymbols.method("updateParameters")) {
                    val request = args[0] ?: return@hookAfter
                    val device = notificationSymbols.field("requestDeviceField").get(request) as? BluetoothDevice
                    if (!isSonyPod(device)) return@hookAfter
                    val battery = effectiveBattery() ?: return@hookAfter
                    val leftBattery = displayBattery(battery.left)
                    val rightBattery = displayBattery(battery.right)
                    val wearState = displayWearState(battery, notificationSymbols.field("requestWearField").getInt(request))
                    leftBattery?.let { notificationSymbols.field("requestLeftField").setInt(request, it) }
                    rightBattery?.let { notificationSymbols.field("requestRightField").setInt(request, it) }
                    notificationSymbols.field("requestWearField").setInt(request, wearState)
                    Log.d(TAG, "updateParameters patched device=${device.describe()} left=$leftBattery right=$rightBattery wear=$wearState")
                }
                Log.d(TAG, "MiuiBluetoothNotification.updateParameters hook installed")
            }.onFailure { Log.d(TAG, "hook MiuiBluetoothNotification.updateParameters skipped", it) }
        }
    }

    private fun hookHeadsetServiceBinder() {
        run {
            runCatching {
                hookAfter(headsetSymbols.method("onBind")) {
                    registerStatusReceiver(instance as? Context)
                    val binder = result ?: return@hookAfter
                    installHeadsetBinderHooks(binder.javaClass)
                }
                Log.d(TAG, "BluetoothHeadsetService.onBind hook installed package=$packageName")
            }.onFailure { Log.w(TAG, "hook BluetoothHeadsetService.onBind failed package=$packageName", it) }
            runCatching {
                hookAfter(headsetSymbols.method("onCreate")) {
                    registerStatusReceiver(instance as? Context)
                }
                Log.d(TAG, "BluetoothHeadsetService.onCreate hook installed package=$packageName")
            }.onFailure { Log.d(TAG, "hook BluetoothHeadsetService.onCreate skipped package=$packageName: ${it.message}") }
        }

        installHeadsetBinderHooks(headsetSymbols.clazz("binder"))
    }

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        context = ctx.applicationContext ?: ctx
        stateMirror.register(context)
        // Config changes arrive through the native remote-pref change callback
        // (HookContext.registerRemoteConfigChangeListener) instead of a custom broadcast.
        registerRemoteConfigChangeListener()
        receiverRegistered = true
        Log.d(TAG, "registered status mirror context=$context")
    }

    override fun onRemoteConfigChanged() {
        // Island/notification rendering inputs depend on config; republish the current
        // status on the main thread like the broadcast path did.
        handler.post { notifyRealStatus("config-changed") }
    }

    private fun installHeadsetBinderHooks(binderClass: Class<*>) {
        val className = binderClass.name
        if (!hookedBinderClasses.add(className)) return
        registerDynamicTarget(className)
        Log.d(TAG, "BluetoothHeadsetService binder class=$className")

        runCatching {
            hookBefore(headsetSymbols.method("checkSupport")) {
                val device = args[0] as? BluetoothDevice
                if (!isSonyPod(device)) return@hookBefore
                lastSonyDevice = device
                result = settingsSupport(device?.address ?: return@hookBefore)
                Log.d(TAG, "HeadsetBinder.checkSupport forced device=${device.describe()} support=$result")
            }
            Log.d(TAG, "HeadsetBinder.checkSupport hook installed")
        }.onFailure { Log.d(TAG, "hook HeadsetBinder.checkSupport skipped", it) }

        hookAddressStringResult("getDeviceInfo") { address -> settingsSupport(address) }
        hookAddressStringResult("isSupportAudioSwitch") { "1" }
        hookAddressBooleanResult("isMiTWS", true)
        hookAddressBooleanResult("checkIsMiTWS", true)
        hookAddressBooleanResult("getRingFindState", false)

        runCatching {
            hookBefore(headsetSymbols.method("setCommonCommand")) {
                val command = args[0] as? Int
                val value = args[1] as? String
                val device = args[2] as? BluetoothDevice
                if (!isSonyPod(device)) return@hookBefore
                lastSonyDevice = device
                result = when (command) {
                    102 -> "1"
                    123 -> "4"
                    else -> "1"
                }
                Log.d(TAG, "HeadsetBinder.setCommonCommand forced command=$command value=$value device=${device.describe()} result=$result")
                sendRealStatus(device, "setCommonCommand:$command")
            }
            Log.d(TAG, "HeadsetBinder.setCommonCommand hook installed")
        }.onFailure { Log.d(TAG, "hook HeadsetBinder.setCommonCommand skipped", it) }

        hookBinderVoidDevice("connect") { device, method -> sendRealStatus(device, method) }
        hookBinderVoidDevice("getDeviceConfig") { device, method -> sendRealStatus(device, method) }
        hookBinderVoidDeviceString("getCommonConfig") { device, method -> sendRealStatus(device, method) }
        hookBinderAncMode()
        hookBinderAncLevel()

        runCatching {
            hookBefore(headsetSymbols.method("register")) {
                val callback = args[0]
                if (callback != null && lastSonyDevice != null) {
                    rememberCallback(callback)
                    result = null
                    Log.d(TAG, "HeadsetBinder.register swallowed callback=$callback device=${lastSonyDevice.describe()}")
                    requestBluetoothStatus("register")
                    sendRealStatus(lastSonyDevice, "register")
                    sendRealStatusDelayed(lastSonyDevice, "register-refresh", 350L)
                }
            }
            hookBefore(headsetSymbols.method("registerCallbackDevice")) {
                val callback = args[0]
                val device = args[1] as? BluetoothDevice
                if (!isSonyPod(device) || callback == null) return@hookBefore
                lastSonyDevice = device
                rememberCallback(callback)
                result = null
                Log.d(TAG, "HeadsetBinder.registerCallbackDevice swallowed callback=$callback device=${device.describe()}")
                requestBluetoothStatus("registerCallbackDevice")
                sendRealStatus(device, "registerCallbackDevice")
                sendRealStatusDelayed(device, "registerCallbackDevice-refresh", 350L)
            }
            hookBefore(headsetSymbols.method("unregister")) {
                val callback = args[0]
                val device = args[1] as? BluetoothDevice
                if (!isSonyPod(device) || callback == null) return@hookBefore
                forgetCallback(callback)
                result = null
                Log.d(TAG, "HeadsetBinder.unregister swallowed callback=$callback device=${device.describe()}")
            }
            Log.d(TAG, "HeadsetBinder callback hooks installed")
        }.onFailure { Log.d(TAG, "hook HeadsetBinder callback methods skipped", it) }
    }

    /** Recreate callback proxies from Binder handles carried across reload. */
    private fun restoreReloadCallbacks() {
        if (reloadCallbackBinders.isEmpty()) return
        runCatching {
            val callbackFactory = headsetSymbols.method("callbackFactory")
            reloadCallbackBinders.forEach { binder ->
                val callback = callbackFactory.invoke(null, binder) ?: return@forEach
                rememberCallback(binder, callback)
            }
            val restoredCount = synchronized(callbackLock) { callbacks.size }
            Log.d(TAG, "restored $restoredCount MIUI headset callbacks after reload")
        }.onFailure { Log.w(TAG, "failed to restore MIUI headset callbacks after reload", it) }
        reloadCallbackBinders.clear()
    }

    private fun hookBinderVoidDevice(methodName: String, after: (BluetoothDevice?, String) -> Unit) {
        runCatching {
            hookBefore(headsetSymbols.method(methodName)) {
                val device = args[0] as? BluetoothDevice
                if (!isSonyPod(device)) return@hookBefore
                lastSonyDevice = device
                result = null
                Log.d(TAG, "HeadsetBinder.$methodName swallowed device=${device.describe()}")
                requestBluetoothStatus(methodName)
                after(device, methodName)
                sendRealStatusDelayed(device, "$methodName-refresh", 350L)
            }
        }.onFailure { Log.d(TAG, "hook HeadsetBinder.$methodName skipped", it) }
    }

    private fun hookAddressStringResult(methodName: String, forced: (String) -> String) {
        runCatching {
            hookBefore(headsetSymbols.method(methodName)) {
                val address = args[0] as? String
                if (address == null || !isSonyAddress(address)) return@hookBefore
                result = forced(address)
                Log.d(TAG, "HeadsetBinder.$methodName forced address=$address result=$result")
            }
            Log.d(TAG, "HeadsetBinder.$methodName hook installed")
        }.onFailure { Log.d(TAG, "hook HeadsetBinder.$methodName skipped", it) }
    }

    private fun hookAddressBooleanResult(methodName: String, forced: Boolean) {
        runCatching {
            hookBefore(headsetSymbols.method(methodName)) {
                val address = args[0] as? String
                if (address == null || !isSonyAddress(address)) return@hookBefore
                result = forced
                Log.d(TAG, "HeadsetBinder.$methodName forced address=$address result=$forced")
            }
            Log.d(TAG, "HeadsetBinder.$methodName hook installed")
        }.onFailure { Log.d(TAG, "hook HeadsetBinder.$methodName skipped", it) }
    }

    private fun hookBinderVoidDeviceString(methodName: String, after: (BluetoothDevice?, String) -> Unit) {
        runCatching {
            hookBefore(headsetSymbols.method(methodName)) {
                val device = args[0] as? BluetoothDevice
                val value = args[1] as? String
                if (!isSonyPod(device)) return@hookBefore
                lastSonyDevice = device
                result = null
                Log.d(TAG, "HeadsetBinder.$methodName swallowed value=$value device=${device.describe()}")
                requestBluetoothStatus("$methodName:$value")
                after(device, "$methodName:$value")
                sendRealStatusDelayed(device, "$methodName-refresh:$value", 350L)
            }
        }.onFailure { Log.d(TAG, "hook HeadsetBinder.$methodName skipped", it) }
    }

    private fun hookBinderAncMode() {
        runCatching {
            hookBefore(headsetSymbols.method("changeAncMode")) {
                val mode = args[0] as? Int
                val device = args[1] as? BluetoothDevice
                if (!isSonyPod(device)) return@hookBefore
                lastSonyDevice = device
                result = null
                Log.d(TAG, "HeadsetBinder.changeAncMode swallowed mode=$mode device=${device.describe()}")
                mode?.let { sendSonyAnc(sonyAncFromMiuiMode(it)) }
                sendRealStatus(device, "changeAncMode:$mode")
            }
        }.onFailure { Log.d(TAG, "hook HeadsetBinder.changeAncMode skipped", it) }
    }

    private fun hookBinderAncLevel() {
        runCatching {
            hookBefore(headsetSymbols.method("changeAncLevel")) {
                val level = args[0] as? String
                val device = args[1] as? BluetoothDevice
                if (!isSonyPod(device)) return@hookBefore
                lastSonyDevice = device
                result = null
                Log.d(TAG, "HeadsetBinder.changeAncLevel swallowed level=$level device=${device.describe()}")
                level?.let { sendSonyAncLevel(it) }
                sendRealStatus(device, "changeAncLevel:$level")
            }
        }.onFailure { Log.d(TAG, "hook HeadsetBinder.changeAncLevel skipped", it) }
    }
    private fun rememberCallback(callback: Any) {
        val binder = (callback as? IInterface)?.asBinder() ?: return
        rememberCallback(binder, callback)
    }

    private fun rememberCallback(binder: IBinder, callback: Any) {
        val deathRecipient = IBinder.DeathRecipient {
            val removed = removeCallback(binder, unlink = false)
            if (removed != null) Log.d(TAG, "MIUI headset callback binder died; removed callback=$callback")
        }
        val registration = CallbackRegistration(callback, deathRecipient)
        synchronized(callbackLock) {
            callbacks.remove(binder)?.let { previous ->
                runCatching { binder.unlinkToDeath(previous.deathRecipient, 0) }
            }
            callbacks[binder] = registration
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (_: RemoteException) {
                callbacks.remove(binder, registration)
                Log.d(TAG, "MIUI headset callback already dead; registration skipped callback=$callback")
            }
        }
    }

    private fun forgetCallback(callback: Any) {
        val binder = (callback as? IInterface)?.asBinder() ?: return
        removeCallback(binder)
    }

    private fun removeCallback(
        binder: IBinder,
        expected: CallbackRegistration? = null,
        unlink: Boolean = true,
    ): CallbackRegistration? {
        val removed = synchronized(callbackLock) {
            val current = callbacks[binder] ?: return@synchronized null
            if (expected != null && current !== expected) return@synchronized null
            callbacks.remove(binder)
        }
        if (unlink && removed != null) runCatching { binder.unlinkToDeath(removed.deathRecipient, 0) }
        return removed
    }

    private fun clearCallbacks() {
        val registrations = synchronized(callbackLock) {
            callbacks.toList().also { callbacks.clear() }
        }
        registrations.forEach { (binder, registration) ->
            runCatching { binder.unlinkToDeath(registration.deathRecipient, 0) }
        }
    }

    private fun isSonyPod(device: BluetoothDevice?): Boolean {
        return SonyDeviceService.isSony(device)
    }

    private fun notifyRealStatus(reason: String) {
        val device = lastSonyDevice
        if (device != null) {
            sendRealStatus(device, reason)
            return
        }
        val address = currentAddress ?: return
        sendRealStatus(address, reason)
    }

    private fun sendRealStatus(device: BluetoothDevice?, reason: String) {
        val address = device?.address ?: return
        sendRealStatus(address, reason)
    }

    private fun sendRealStatusDelayed(device: BluetoothDevice?, reason: String, delayMs: Long) {
        val address = device?.address ?: return
        handler.postDelayed({ sendRealStatus(address, reason) }, delayMs)
    }

    private fun sendRealStatus(address: String, reason: String) {
        val registrations = synchronized(callbackLock) { callbacks.toList() }
        if (registrations.isEmpty()) {
            Log.d(TAG, "send real status skipped: no callback reason=$reason address=$address")
            return
        }
        val payload = realRefreshPayload()
        handler.post {
            registrations.forEach { (binder, registration) ->
                val isCurrent = synchronized(callbackLock) { callbacks[binder] === registration }
                if (!isCurrent) return@forEach
                if (!binder.isBinderAlive) {
                    if (removeCallback(binder, registration, unlink = false) != null) {
                        Log.d(TAG, "removed dead MIUI headset callback before refreshStatus callback=${registration.callback}")
                    }
                    return@forEach
                }
                runCatching {
                    headsetSymbols.method("callbackRefresh").invoke(registration.callback, address, payload)
                    Log.d(TAG, "sent real refreshStatus reason=$reason address=$address payload=$payload callback=${registration.callback}")
                }.onFailure { error ->
                    val removed = removeCallback(binder, registration)
                    if (removed == null) return@onFailure
                    if (error.hasCause<DeadObjectException>()) {
                        Log.d(TAG, "MIUI headset callback died during refreshStatus; removed callback=${registration.callback}")
                    } else {
                        Log.w(TAG, "send real refreshStatus failed reason=$reason callback=${registration.callback}", error)
                    }
                }
            }
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private fun realRefreshPayload(): String {
        // State now arrives via broadcasts from the app-process Sony repository
        // (wired up in phase 3); the local caches are the single source here.
        return miuiRefreshPayload(currentBattery, currentAnc, currentTransparencyVocalEnhancement)
    }

    private companion object {
        const val KEY_RELOAD_CALLBACK_BINDERS = "sonypods.reload.miui_callback_binders"
    }

    private fun effectiveBattery(): BatteryParams? {
        return currentBattery
    }

    private fun miuiRefreshPayload(battery: BatteryParams?, anc: Int, transparencyVocalEnhancement: Boolean): String {
        val values = MutableList(16) { "" }
        // Over-ear (single battery) must land in the RIGHT slot to match the settings
        // hook's encoding; projecting it onto the left slot makes the two hooks fight
        // and the battery flash between its value and "-".
        val isOverEar = currentFormFactor == "HEADSET"
        if (isOverEar) {
            values[1] = miuiBatteryValue(battery?.left)
        } else {
            values[0] = miuiBatteryValue(battery?.left)
            values[1] = miuiBatteryValue(battery?.right)
            values[2] = miuiBatteryValue(battery?.case)
        }
        values[7] = miuiAncLevel(anc, transparencyVocalEnhancement)
        values[8] = "true"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun miuiBatteryValue(params: PodParams?): String {
        if (params?.isConnected != true) return "255"
        val value = params.battery.coerceIn(0, 100)
        return (if (params.isCharging) value or 128 else value).toString()
    }

    private fun miuiAncLevel(anc: Int, transparencyVocalEnhancement: Boolean): String {
        // MIUI level codes: 01xx = NC (0100=Medium used as the single Sony NC level),
        // 0200 = transparency, 0201 = transparency + voice, 0000 = off.
        return when (anc) {
            2 -> "0100"
            3 -> if (transparencyVocalEnhancement) "0201" else "0200"
            else -> "0000"
        }
    }

    private fun displayBattery(params: PodParams?): Int? {
        if (params?.isConnected != true) return null
        return params.battery.coerceIn(0, 100)
    }

    private fun displayWearState(battery: BatteryParams, fallback: Int): Int {
        val leftConnected = battery.left?.isConnected == true
        val rightConnected = battery.right?.isConnected == true
        return when {
            leftConnected && rightConnected -> 1
            leftConnected -> 3
            rightConnected -> 2
            fallback != 0 -> fallback
            else -> 1
        }
    }

    private fun currentMiuiBluetoothNotification(): Any? {
        return runCatching {
            headsetSymbols.field("notificationField").get(null)
        }.getOrNull()
    }

    private fun patchHeadsetWearIslandBundle(bundle: Bundle?) {
        if (bundle == null) return
        if (!shouldInterceptHeadsetWearIsland(bundle)) return
        if (ConfigManager.islandMode() != ConfigManager.ISLAND_MODE_OFFICIAL) return
        val battery = effectiveBattery() ?: return
        val leftText = displayBattery(battery.left)?.let { "$it%" }
        val rightText = displayBattery(battery.right)?.let { "$it%" }
        if (leftText == null && rightText == null) return
        patchIslandJson(bundle, "param", leftText, rightText)
        patchIslandJson(bundle, "island_param", leftText, rightText)
        Log.d(TAG, "patched headset_wear_notification island text left=$leftText right=$rightText")
    }

    private fun shouldInterceptHeadsetWearIsland(bundle: Bundle?): Boolean {
        return bundle?.getString("notifyId") == "headset_wear_notification"
    }

    private fun patchIslandJson(bundle: Bundle, key: String, leftText: String?, rightText: String?) {
        val raw = bundle.getString(key) ?: return
        runCatching {
            val json = JSONObject(raw)
            leftText?.let { putTextParams(json.optJSONObject("left"), it) }
            rightText?.let { putTextParams(json.optJSONObject("right"), it) }
            bundle.putString(key, json.toString())
        }.onFailure {
            Log.w(TAG, "patch island json failed key=$key raw=$raw", it)
        }
    }

    private fun putTextParams(area: JSONObject?, text: String) {
        if (area == null) return
        area.put(
            "textParams",
            JSONObject().apply {
                put("text", text)
                put("textColor", -1)
                put("turnAnim", true)
            }
        )
    }

    private fun requestBluetoothStatus(reason: String) {
        val ctx = context ?: return
        SonyBridge.sendCommand(ctx, SonyBridge.CMD_REFRESH)
        Log.d(TAG, "requested headphone refresh reason=$reason package=$packageName")
    }

    private fun sonyAncFromMiuiMode(mode: Int): Int {
        // Xiaomi mode 1 = noise cancelling, 2 = transparency -> Sony AMBIENT_SOUND, else off.
        return when (mode) {
            1 -> 2
            2 -> 3
            else -> 1
        }
    }

    private fun sonyAncFromMiuiLevel(level: String): Int {
        // MIUI binder level codes 01xx are NC intensity levels (Smart/Light/Medium/Deep);
        // Sony has a single NC mode, so they all collapse to NC. 02xx = transparency.
        return when {
            level.startsWith("01") -> 2
            level.startsWith("02") -> 3
            else -> 1
        }
    }

    private fun sendSonyAncLevel(level: String) {
        when {
            level.startsWith("0201") -> {
                currentAnc = 3
                sendSonyAmbientVoice(true)
            }
            level.startsWith("0200") -> {
                currentAnc = 3
                sendSonyAmbientVoice(false)
            }
            else -> sendSonyAnc(sonyAncFromMiuiLevel(level))
        }
    }

    private fun sendSonyAnc(mode: Int) {
        currentAnc = mode
        val ctx = context ?: run {
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
        Log.d(TAG, "sendSonyAnc command sent mode=$mode")
    }

    private fun sendSonyAmbientVoice(enabled: Boolean) {
        currentTransparencyVocalEnhancement = enabled
        hasTransparencyVocalEnhancementState = true
        val ctx = context ?: run {
            Log.d(TAG, "sendSonyAmbientVoice skipped: context is null enabled=$enabled")
            return
        }
        SonyBridge.setAmbientVoice(ctx, enabled)
        Log.d(TAG, "sendSonyAmbientVoice command sent enabled=$enabled")
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

    private fun BatteryParams?.debugString(): String {
        if (this == null) return "null"
        return "left=${left?.battery}/${left?.isCharging}/${left?.isConnected} right=${right?.battery}/${right?.isCharging}/${right?.isConnected} case=${case?.battery}/${case?.isCharging}/${case?.isConnected}"
    }

    private fun settingsSupport(address: String): String = MiuiHeadsetSupport.encode(address)

    private fun isSonyAddress(address: String): Boolean {
        return SonyDeviceService.isKnownSonyAddress(address)
    }

    private fun BluetoothDevice?.describe(): String {
        if (this == null) return "null"
        val address = runCatching { this.address }.getOrNull()
        val name = runCatching { this.name }.getOrNull()
        val alias = runCatching { this.alias }.getOrNull()
        return "BluetoothDevice(address=$address,name=$name,alias=$alias)"
    }
}
