package dev.sonypods.hook
import com.mercury.sonypods.R
import dev.sonypods.utils.ModuleText

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.mercury.sonypods.BuildConfig
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.config.ConfigManager
import dev.sonypods.device.SonyDeviceService
import dev.sonypods.headphones.HeadphoneFormFactor
import dev.sonypods.protocol.DseeGeneration
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.SoundQualityCodec
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import dev.sonypods.utils.miuiStrongToast.data.PodParams
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

@SuppressLint("MissingPermission")
object SettingsHeadsetHook : HookContext() {
    private const val TAG = "SonyPods-Settings"
    private const val PREFS_NAME = "sonypods_milink_state"
    private const val REPUBLISH_DEBOUNCE_MS = 600L
    private const val PKG_SETTINGS = "com.android.settings"
    private val batteryViews = WeakHashMap<Any, BluetoothDevice>()
    private val batteryValuesCache = WeakHashMap<Any, String>()
    private val headsetFragments = WeakHashMap<Any, Boolean>()
    private val batteryLabelOriginals = WeakHashMap<TextView, CharSequence>()
    private var reloadBatteryViews: WeakHashMap<Any, BluetoothDevice>? = null
    private var reloadBatteryValuesCache: WeakHashMap<Any, String>? = null
    private var reloadHeadsetFragments: WeakHashMap<Any, Boolean>? = null
    private var reloadBatteryLabelOriginals: WeakHashMap<TextView, CharSequence>? = null
    private var hasLiveSnapshot = false
    private var hasAncState = false
    private var context: Context? = null
    private var receiverRegistered = false
    private var stateReceiver: BroadcastReceiver? = null
    private var currentAddress: String? = null
    private var currentName: String? = null
    private var currentFormFactor: String? = null
    private var currentFirmware: String? = null
    private var currentBattery: BatteryParams = BatteryParams()
    private var currentAnc = 1
    private var currentTransparencyVocalEnhancement = false
    private var currentCodec: SoundQualityCodec? = null
    private var currentDseeGeneration: DseeGeneration? = null
    private var currentDseeActive = false
    private var currentLeaStreamingL: String? = null
    private var currentLeaStreamingR: String? = null
    private var proxyCheckSupportCalls = 0
    private var proxySetCommonCommandCalls = 0
    private var proxyGetDeviceConfigCalls = 0
    private var proxyGetCommonConfigCalls = 0
    private var lastRepublishAt = 0L

    override fun onHook() {
        hookActivityEntry()
        hookSupportChecks()
        hookServiceProxy()
        hookBatteryView()
        hookFragmentState()
    }

    override fun onBeforeReload() {
        stateReceiver?.let { receiver ->
            unregisterReceiverForReload(context, receiver)
        }
        stateReceiver = null
        unregisterRemoteConfigChangeListener()
        receiverRegistered = false
        reloadBatteryViews = WeakHashMap(batteryViews)
        reloadBatteryValuesCache = WeakHashMap(batteryValuesCache)
        reloadHeadsetFragments = WeakHashMap(headsetFragments)
        reloadBatteryLabelOriginals = WeakHashMap(batteryLabelOriginals)
        batteryViews.clear()
        batteryValuesCache.clear()
        headsetFragments.clear()
        batteryLabelOriginals.clear()
    }

    override fun onReloadRejected(snapshot: SonyStateSnapshot) {
        reloadBatteryViews?.let { batteryViews.putAll(it) }
        reloadBatteryValuesCache?.let { batteryValuesCache.putAll(it) }
        reloadHeadsetFragments?.let { headsetFragments.putAll(it) }
        reloadBatteryLabelOriginals?.let { batteryLabelOriginals.putAll(it) }
        reloadBatteryViews = null
        reloadBatteryValuesCache = null
        reloadHeadsetFragments = null
        reloadBatteryLabelOriginals = null
        context?.let { startAfterReload(it) }
    }

    internal fun startAfterReload(context: Context) {
        registerStatusReceiver(context)
    }

    private fun hookActivityEntry() {
        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetActivity", "onCreate", Bundle::class.java)) {
                val activity = instance as? Context ?: return@hookBefore
                registerStatusReceiver(activity)
                val intent = callMethod(instance, "getIntent") as? Intent ?: return@hookBefore
                val device = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
                Log.d(TAG, "Activity.onCreate before device=${device.describe()} support=${intent.getStringExtra("MIUI_HEADSET_SUPPORT")} comeFrom=${intent.getStringExtra("COME_FROM")} btAddress=${intent.getStringExtra("bluetoothaddress")} known=${SonyDeviceService.knownAddressSnapshot()} current=$currentAddress")
                if (!isSonyPod(device)) return@hookBefore
                intent.putExtra("MIUI_HEADSET_SUPPORT", fakeSupport())
                intent.putExtra("COME_FROM", intent.getStringExtra("COME_FROM") ?: "MIUI_BLUETOOTH_SETTINGS")
                intent.putExtra("DEVICE_ID", fakeDeviceId())
                Log.d(TAG, "MiuiHeadsetActivity intent patched address=${device?.address}")
            }
            hookActivityStringGetter("getDeviceID") { fakeDeviceId() }
            hookActivityStringGetter("getSupport") { fakeSupport() }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetActivity skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetActivityPlugin", "onCreate", Bundle::class.java)) {
                val activity = instance as? Context ?: return@hookBefore
                registerStatusReceiver(activity)
                val intent = callMethod(instance, "getIntent") as? Intent ?: return@hookBefore
                val device = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
                Log.d(TAG, "Plugin.onCreate before device=${device.describe()} support=${intent.getStringExtra("MIUI_HEADSET_SUPPORT")} comeFrom=${intent.getStringExtra("COME_FROM")} btAddress=${intent.getStringExtra("bluetoothaddress")} known=${SonyDeviceService.knownAddressSnapshot()} current=$currentAddress")
                if (!isSonyPod(device)) return@hookBefore
                intent.putExtra("MIUI_HEADSET_SUPPORT", fakeSupport())
                intent.putExtra("DEVICE_ID", fakeDeviceId())
                Log.d(TAG, "MiuiHeadsetActivityPlugin intent patched address=${device?.address}")
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetActivityPlugin skipped", it) }
    }

    private fun hookActivityStringGetter(methodName: String, value: () -> String) {
        runCatching {
            hookAfter(findMethodByParamCount("com.android.settings.bluetooth.MiuiHeadsetActivity", methodName, 0)) {
                val device = runCatching { getObjectField(instance, "mDevice") as? BluetoothDevice }.getOrNull()
                Log.d(TAG, "Activity.$methodName old=$result device=${device.describe()} isSony=${isSonyPod(device)}")
                if (!isSonyPod(device)) return@hookAfter
                result = value()
                Log.d(TAG, "Activity.$methodName forced=$result")
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetActivity.$methodName skipped", it) }
    }

    private fun hookSupportChecks() {
        hookStringStaticResult("com.android.settings.bluetooth.HeadsetIDConstants", "checkSupport") { support ->
            support.startsWith(fakeDeviceId()) || support.contains(fakeDeviceId())
        }
        hookStringStaticResult("com.android.settings.bluetooth.HeadsetIDConstants", "isTWS01Headset") { it == fakeDeviceId() }
        hookStringStaticResult("com.android.settings.bluetooth.HeadsetIDConstants", "isK77sHeadset") { false }
        hookBleMmaConnectByContext()
        hookBleMmaConnectByService()
    }

    private fun hookStringStaticResult(className: String, methodName: String, resultForValue: (String) -> Any) {
        runCatching {
            hookAfter(findMethod(className, methodName, String::class.java)) {
                val value = args[0] as? String ?: return@hookAfter
                Log.d(TAG, "$className.$methodName value=$value old=$result")
                val deviceId = fakeDeviceId()
                if (value != deviceId && !value.startsWith(deviceId)) return@hookAfter
                result = resultForValue(value)
                Log.d(TAG, "$className.$methodName forced value=$value result=$result")
            }
        }.onFailure { Log.d(TAG, "hook $className.$methodName(String) skipped", it) }
    }

    private fun hookBleMmaConnectByContext() {
        runCatching {
            hookAfter(findMethod("com.android.settings.bluetooth.HeadsetIDConstants", "isBleMmaConnect", Context::class.java, BluetoothDevice::class.java, String::class.java)) {
                val device = args[1] as? BluetoothDevice
                val deviceId = args[2] as? String
                Log.d(TAG, "isBleMmaConnect(Context) old=$result device=${device.describe()} deviceId=$deviceId service=${runCatching { callMethod(args[0], "getService") }.getOrNull()}")
                if (isSonyPod(device)) {
                    result = true
                    Log.d(TAG, "isBleMmaConnect(Context) forced true")
                }
            }
        }.onFailure { Log.d(TAG, "hook HeadsetIDConstants.isBleMmaConnect(Context) skipped", it) }
    }

    private fun hookBleMmaConnectByService() {
        runCatching {
            val serviceClass = findClass("com.android.bluetooth.ble.app.IMiuiHeadsetService")
            hookAfter(findMethod("com.android.settings.bluetooth.HeadsetIDConstants", "isBleMmaConnect", serviceClass, BluetoothDevice::class.java, String::class.java)) {
                val device = args[1] as? BluetoothDevice
                val deviceId = args[2] as? String
                Log.d(TAG, "isBleMmaConnect(Service) old=$result service=${args[0]} device=${device.describe()} deviceId=$deviceId")
                if (isSonyPod(device)) {
                    result = true
                    Log.d(TAG, "isBleMmaConnect(Service) forced true")
                }
            }
        }.onFailure { Log.d(TAG, "hook HeadsetIDConstants.isBleMmaConnect(Service) skipped", it) }
    }

    private fun hookServiceProxy() {
        val proxyClass = "com.android.bluetooth.ble.app.IMiuiHeadsetService\$Stub\$Proxy"
        hookProxyStringResult(proxyClass, "checkSupport", BluetoothDevice::class.java) { fakeSupport() }
        hookProxyStringArgResult(proxyClass, "getDeviceInfo") { fakeSupport() }
        hookProxyStringArgResult(proxyClass, "isSupportAudioSwitch") { "1" }
        hookProxyStringArgResult(proxyClass, "setCommonCommand", Int::class.java, String::class.java, BluetoothDevice::class.java) { commandArgs ->
            val command = commandArgs[0] as? Int
            // Command 102 is the wear-status probe. MIUI blocks ANC changes when it equals
            // RECORD_SYNCED ("0") with a "请连接并佩戴耳机" toast, so report RECORD_UNSYNCED
            // ("1", i.e. "worn/connected") to let the official updateAncMode/updateAncLevel
            // guards pass even if our fragment hook does not swallow a call.
            if (command == 102) "1" else "1"
        }
        hookProxyVoidDeviceNoop(proxyClass, "connect", BluetoothDevice::class.java)
        hookProxyVoidDeviceNoop(proxyClass, "getDeviceConfig", BluetoothDevice::class.java)
        hookProxyVoidDeviceStringNoop(proxyClass, "getCommonConfig", BluetoothDevice::class.java, String::class.java)
        hookProxyBooleanStringResult(proxyClass, "isMiTWS") { true }
        hookProxyBooleanStringResult(proxyClass, "checkIsMiTWS") { true }
        hookProxyBooleanStringResult(proxyClass, "getRingFindState") { false }
        hookProxyVoidDeviceCommand(proxyClass, "changeAncMode", Int::class.java, BluetoothDevice::class.java) { commandArgs ->
            val miMode = commandArgs[0] as? Int ?: return@hookProxyVoidDeviceCommand null
            sonyAncFromSettings(miMode)
        }
        hookProxyVoidDeviceCommand(proxyClass, "changeAncLevel", String::class.java, BluetoothDevice::class.java) { commandArgs ->
            val level = commandArgs[0] as? String ?: return@hookProxyVoidDeviceCommand null
            sonyAncFromLevelCommand(level)
        }
    }

    private fun hookProxyStringResult(className: String, methodName: String, vararg parameterTypes: Class<*>, result: () -> String) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                val isSony = isSonyPod(device)
                if (methodName == "checkSupport") proxyCheckSupportCalls++
                Log.d(TAG, "$methodName proxy call#${if (methodName == "checkSupport") proxyCheckSupportCalls else -1} device=${device.describe()} isSony=$isSony")
                if (!isSony) return@hookBefore
                this.result = result()
                Log.d(TAG, "$methodName proxy forced result=${this.result} address=${device?.address}")
            }
        }.onFailure { Log.d(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyStringArgResult(className: String, methodName: String, vararg parameterTypes: Class<*>, result: (List<Any?>) -> String) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                val address = args.firstOrNull { it is String } as? String
                val isSony = isSonyPod(device) || (address != null && isSonyAddress(address))
                if (methodName == "setCommonCommand") proxySetCommonCommandCalls++
                Log.d(TAG, "$methodName proxy call#${if (methodName == "setCommonCommand") proxySetCommonCommandCalls else -1} args=${args.describeArgs()} device=${device.describe()} addressArg=$address isSony=$isSony")
                if (!isSony) return@hookBefore
                this.result = result(args)
                Log.d(TAG, "$methodName proxy forced result=${this.result} address=${device?.address ?: address}")
            }
        }.onFailure { Log.d(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyBooleanStringResult(className: String, methodName: String, result: () -> Boolean) {
        runCatching {
            hookBefore(findMethod(className, methodName, String::class.java)) {
                val address = args[0] as? String ?: return@hookBefore
                val isSony = isSonyAddress(address)
                Log.d(TAG, "$methodName proxy string call address=$address isSony=$isSony oldKnown=${SonyDeviceService.knownAddressSnapshot()} current=$currentAddress")
                if (!isSony) return@hookBefore
                this.result = result()
                Log.d(TAG, "$methodName proxy forced result=${this.result} address=$address")
            }
        }.onFailure { Log.d(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceCommand(className: String, methodName: String, vararg parameterTypes: Class<*>, mode: (List<Any?>) -> Int?) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                Log.d(TAG, "$methodName proxy command args=${args.describeArgs()} device=${device.describe()} isSony=${isSonyPod(device)}")
                if (!isSonyPod(device)) return@hookBefore
                val sonyMode = mode(args) ?: return@hookBefore
                currentAnc = sonyMode
                hasAncState = true
                sendSonyAnc(sonyMode)
                sendAncChanged(sonyMode)
                this.result = null
                Log.d(TAG, "$methodName proxy command handled address=${device?.address} sonyMode=$sonyMode")
            }
        }.onFailure { Log.d(TAG, "hook proxy $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceNoop(className: String, methodName: String, vararg parameterTypes: Class<*>) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                if (methodName == "getDeviceConfig") proxyGetDeviceConfigCalls++
                val isSony = isSonyPod(device)
                Log.d(TAG, "$methodName proxy before#${if (methodName == "getDeviceConfig") proxyGetDeviceConfigCalls else -1} device=${device.describe()} isSony=$isSony")
                if (!isSony) return@hookBefore
                this.result = null
                Log.d(TAG, "$methodName proxy swallowed for virtual Oppo device")
            }
        }.onFailure { Log.d(TAG, "hook proxy noop $methodName skipped", it) }
    }

    private fun hookProxyVoidDeviceStringNoop(className: String, methodName: String, vararg parameterTypes: Class<*>) {
        runCatching {
            hookBefore(findMethod(className, methodName, *parameterTypes)) {
                val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
                proxyGetCommonConfigCalls++
                val isSony = isSonyPod(device)
                Log.d(TAG, "$methodName proxy before#$proxyGetCommonConfigCalls args=${args.describeArgs()} device=${device.describe()} isSony=$isSony")
                if (!isSony) return@hookBefore
                this.result = null
                Log.d(TAG, "$methodName proxy swallowed for virtual Oppo device")
            }
        }.onFailure { Log.d(TAG, "hook proxy noop $methodName skipped", it) }
    }

    private fun hookBatteryView() {
        runCatching {
            hookConstructorAfter(findConstructorByParamCount("com.android.settings.bluetooth.tws.MiuiHeadsetBattery", 4)) {
                val device = args[0] as? BluetoothDevice ?: return@hookConstructorAfter
                val ctx = args[1] as? Context
                registerStatusReceiver(ctx)
                Log.d(TAG, "Battery.<init> device=${device.describe()} isSony=${isSonyPod(device)} ctx=$ctx currentBattery=${settingsBatteryString()}")
                if (!isSonyPod(device)) return@hookConstructorAfter
                batteryViews[instance ?: return@hookConstructorAfter] = device
                requestBluetoothStatus("battery-init")
                updateBatteryView(instance)
                Log.d(TAG, "MiuiHeadsetBattery registered address=${device.address}")
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetBattery constructor skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.tws.MiuiHeadsetBattery", "onBatteryChanged", String::class.java)) {
                val device = batteryViews[instance] ?: findBatteryDevice(instance).also { found ->
                    if (instance != null && found != null && isSonyPod(found)) {
                        batteryViews[instance] = found
                    }
                }
                Log.d(TAG, "Battery.onBatteryChanged(String) original=${args[0]} mappedDevice=${device.describe()} isSony=${isSonyPod(device)} forced=${settingsBatteryString()}")
                if (!isSonyPod(device)) return@hookBefore
                result = null
                updateBatteryView(instance)
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetBattery.onBatteryChanged(String) skipped", it) }
    }

    private fun hookFragmentState() {
        runCatching {
            hookAfter(findMethodByParamCount("com.android.settings.bluetooth.MiuiHeadsetFragment", "onCreateView", 3)) {
                registerStatusReceiver(runCatching { getObjectField(instance, "mActivity") as? Context }.getOrNull())
                Log.d(TAG, "Fragment.onCreateView after ${fragmentDebug(instance)} isSony=${isSonyFragment(instance)}")
                if (!isSonyFragment(instance)) return@hookAfter
                instance?.let { headsetFragments[it] = true }
                requestBluetoothStatus("fragment-create")
                injectFragmentStatus(instance)
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.onCreateView skipped", it) }

        runCatching {
            hookAfter(findMethodByParamCount("com.android.settings.bluetooth.MiuiHeadsetFragment", "onServiceConnected", 0)) {
                Log.d(TAG, "Fragment.onServiceConnected after ${fragmentDebug(instance)} isSony=${isSonyFragment(instance)}")
                if (!isSonyFragment(instance)) return@hookAfter
                instance?.let { headsetFragments[it] = true }
                requestBluetoothStatus("service-connected")
                injectFragmentStatus(instance)
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.onServiceConnected skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetFragment", "refreshStatus", String::class.java, String::class.java)) {
                val key = args[0] as? String
                val data = args[1] as? String
                Log.d(TAG, "Fragment.refreshStatus before key=$key data=$data ${fragmentDebug(instance)} isSony=${isSonyFragment(instance)}")
                if (isSonyFragment(instance) && key?.startsWith("MMA_CONNECTION_FAILED") == true) {
                    Log.d(TAG, "Fragment.refreshStatus swallowed MMA failure for virtual Oppo device key=$key")
                    injectFragmentStatus(instance)
                    result = null
                }
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.refreshStatus skipped", it) }

        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetFragment", "handleConnectMmaFailed", String::class.java)) {
                Log.d(TAG, "Fragment.handleConnectMmaFailed arg=${args[0]} ${fragmentDebug(instance)} isSony=${isSonyFragment(instance)}")
                if (isSonyFragment(instance)) {
                    injectFragmentStatus(instance)
                    result = null
                    Log.d(TAG, "Fragment.handleConnectMmaFailed swallowed for virtual Oppo device")
                }
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.handleConnectMmaFailed skipped", it) }

        hookFragmentAncCommand("updateAncMode", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!) { commandArgs ->
            sonyAncFromSettings(commandArgs[0] as? Int ?: 0)
        }
        hookFragmentAncCommand("updateAncLevel", String::class.java, Boolean::class.javaPrimitiveType!!) { commandArgs ->
            val level = commandArgs[0] as? String ?: ""
            sonyAncFromLevelCommand(level)
        }
        runCatching {
            hookAfter(
                findMethod(
                    "com.android.settings.bluetooth.MiuiHeadsetFragment",
                    "updateAncUi",
                    String::class.java,
                    Boolean::class.javaPrimitiveType!!,
                ),
            ) {
                if (!isSonyFragment(instance)) return@hookAfter
                val rootView = getObjectField(instance, "mRootView") as? View ?: return@hookAfter
                // Sony has no NC depth tiers: every MIUI level maps onto plain noise
                // cancelling, so the four-step bar would only pretend to do something.
                // Keep the mode row and the transparency slider; hide just the depth
                // bar and its labels (updateAncUi is synchronous, so this sticks).
                listOf("ancAdjust", "ancAdjustText").forEach { name ->
                    findView(rootView, name)?.visibility = View.GONE
                }
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.updateAncUi depth-hide skipped", it) }
    }

    private fun hookFragmentAncCommand(methodName: String, vararg parameterTypes: Class<*>, mode: (List<Any?>) -> Int?) {
        runCatching {
            hookBefore(findMethod("com.android.settings.bluetooth.MiuiHeadsetFragment", methodName, *parameterTypes)) {
                Log.d(TAG, "MiuiHeadsetFragment.$methodName before args=${args.describeArgs()} ${fragmentDebug(instance)} isSony=${isSonyFragment(instance)}")
                if (!isSonyFragment(instance)) return@hookBefore
                val updateDevice = args.getOrNull(1) as? Boolean ?: true
                if (!updateDevice) return@hookBefore
                val sonyMode = mode(args) ?: return@hookBefore
                currentAnc = sonyMode
                hasAncState = true
                sendSonyAnc(sonyMode)
                sendAncChanged(sonyMode)
                runCatching { callMethod(instance, "updateAncUi", settingsAncLevel(), false) }
                injectFragmentStatus(instance)
                result = null
                Log.d(TAG, "MiuiHeadsetFragment.$methodName handled sonyMode=$sonyMode")
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.$methodName skipped", it) }
    }

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        context = ctx.applicationContext ?: ctx
        loadState()
        val filter = IntentFilter().apply {
            addAction(SonyBridge.ACTION_STATE)
            addAction(SonyPodsAction.ACTION_PODS_CONNECTED)
            addAction(SonyPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(SonyPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(SonyPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(SonyPodsAction.ACTION_PODS_AMBIENT_VOICE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    SonyBridge.ACTION_STATE -> {
                        val snapshot = intent.getBundleExtra(SonyStateSnapshot.EXTRA_SNAPSHOT)
                            ?.let { SonyStateSnapshot.fromBundle(it) }
                        if (snapshot != null && snapshot.deviceAddress != null) {
                            hasLiveSnapshot = true
                            currentAddress = snapshot.deviceAddress
                            SonyDeviceService.rememberAddress(snapshot.deviceAddress)
                            currentName = snapshot.deviceName
                            // UNKNOWN is the pre-capability-table placeholder and carries no
                            // information; keep the last real value (which is also what gets
                            // persisted) rather than falling back to the TWS layout.
                            snapshot.formFactor
                                ?.takeIf { it != HeadphoneFormFactor.UNKNOWN.name }
                                ?.let { currentFormFactor = it }
                            snapshot.firmwareVersion
                                ?.takeIf { it.isNotBlank() }
                                ?.let { currentFirmware = it }
                            currentBattery = snapshotBattery(snapshot)
                            // Reconcile the ANC/transparency-vocal state from the engine's live
                            // snapshot. Without this the local currentAnc/currentTransparencyVocalEnhancement
                            // stay at their initial defaults and injectFragmentStatus keeps pushing a
                            // stale level (e.g. "0200,false") that fights the real "0201,true" state,
                            // making the vocal-enhancement toggle appear unresponsive.
                            snapshot.noiseControlMode?.let { mode ->
                                currentAnc = when (mode) {
                                    NoiseControlMode.OFF -> 1
                                    NoiseControlMode.AMBIENT_SOUND -> 3
                                    else -> 2
                                }
                            }
                            currentTransparencyVocalEnhancement = snapshot.ambientVoiceMode
                            hasAncState = true
                            // Live sound-quality badge inputs. Assigned unconditionally: the
                            // repository nulls them on disconnect, and a stale LDAC/DSEE mark must
                            // not outlive the link that carried it.
                            currentCodec = snapshot.soundQualityCodec
                            currentDseeGeneration = snapshot.dseeGeneration
                            currentDseeActive = snapshot.dseeActive
                            currentLeaStreamingL = snapshot.leaStreamingStatusL
                            currentLeaStreamingR = snapshot.leaStreamingStatusR
                            SonyDeviceService.rememberAddress(currentAddress)
                            Log.d(TAG, "state snapshot address=$currentAddress formFactor=$currentFormFactor anc=$currentAnc voice=$currentTransparencyVocalEnhancement battery=${settingsBatteryString()}")
                            saveState(context)
                            rebindExistingBatteryViews()
                            applyBatteryLayouts()
                            updateBatteryViews()
                            updateFragments()
                        }
                    }
                    SonyPodsAction.ACTION_PODS_CONNECTED -> {
                        hasLiveSnapshot = true
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentName = intent.getStringExtra("device_name") ?: currentName
                        SonyDeviceService.rememberAddress(currentAddress)
                    }
                    SonyPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        hasLiveSnapshot = true
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentBattery = intent.batteryStatusFromExtras() ?: intent.parcelableStatus() ?: currentBattery
                        SonyDeviceService.rememberAddress(currentAddress)
                        saveState(context)
                        updateBatteryViews()
                        updateFragments()
                    }
                    SonyPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentAnc = intent.getIntExtra("status", currentAnc)
                        hasAncState = true
                        SonyDeviceService.rememberAddress(currentAddress)
                        saveState(context)
                        updateFragments()
                    }
                    SonyPodsAction.ACTION_PODS_AMBIENT_VOICE_CHANGED -> {
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentTransparencyVocalEnhancement = intent.getBooleanExtra("enabled", currentTransparencyVocalEnhancement)
                        hasAncState = true
                        SonyDeviceService.rememberAddress(currentAddress)
                        saveState(context)
                        updateFragments()
                    }
                }
                Log.d(TAG, "state action=${intent?.action} address=$currentAddress anc=$currentAnc battery=${settingsBatteryString()}")
            }
        }
        context?.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        stateReceiver = receiver
        receiverRegistered = true
        // Config changes arrive through the native remote-pref change callback
        // (HookContext.registerRemoteConfigChangeListener) instead of a custom broadcast.
        registerRemoteConfigChangeListener()
        rebindExistingBatteryViews()
        requestBluetoothStatus("receiver-register")
        Log.d(TAG, "registered status receiver context=$context")
    }

    override fun onRemoteConfigChanged() {
        // Battery layouts and injected fragments render from config (visibility flags,
        // fake device id); refresh them on the main thread like the broadcast path did.
        android.os.Handler(android.os.Looper.getMainLooper()).post { updateFragments() }
    }

    private fun requestBluetoothStatus(reason: String) {
        val ctx = context ?: return
        // Ask the engine to re-broadcast its current state. Without this the settings
        // process only receives a snapshot after the engine *changes* state (battery/
        // ANC tick), so opening the headset page can show "-" for the battery until
        // the user toggles ANC. CMD_REPUBLISH re-publishes the last known snapshot.
        // Debounce: page open fires this from several hooks (receiver-register,
        // battery-init, fragment-create, service-connected) in the same moment; each
        // would otherwise trigger a full cross-process republish round trip.
        val now = SystemClock.elapsedRealtime()
        if (now - lastRepublishAt > REPUBLISH_DEBOUNCE_MS) {
            lastRepublishAt = now
            SonyBridge.sendCommand(ctx, SonyBridge.CMD_REPUBLISH)
        }
        listOf(SonyPodsAction.ACTION_PODS_UI_INIT, SonyPodsAction.ACTION_REFRESH_STATUS).forEach { action ->
            ctx.sendBroadcast(Intent(action).apply {
                setPackage(BuildConfig.APPLICATION_ID)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
        Log.d(TAG, "requested bluetooth status reason=$reason")
    }

    private fun updateBatteryViews() {
        batteryViews.keys.toList().forEach { view ->
            runCatching { updateBatteryView(view) }
                .onFailure { Log.w(TAG, "update battery view failed", it) }
        }
    }

    /**
     * MiuiHeadsetBattery instances survive a libxposed reload, but their
     * WeakHashMap belongs to the old classloader. Re-discover the small set of
     * live battery objects from ActivityThread so an already-open Settings page
     * keeps receiving state immediately after the new generation starts.
     */
    private fun rebindExistingBatteryViews() {
        runCatching {
            val thread = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentActivityThread")
                .apply { isAccessible = true }
                .invoke(null)
                ?: return
            var type: Class<*>? = thread.javaClass
            var activities: Any? = null
            while (type != null && activities == null) {
                activities = runCatching {
                    type.getDeclaredField("mActivities").apply { isAccessible = true }.get(thread)
                }.getOrNull()
                type = type.superclass
            }
            val records = (activities as? Map<*, *>)?.values.orEmpty()
            val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            records.forEach { record ->
                val activity = findFieldValue(record, "activity") ?: return@forEach
                findObjectsByClassName(activity, "com.android.settings.bluetooth.tws.MiuiHeadsetBattery", seen)
                    .forEach { battery ->
                        val device = findBatteryDevice(battery) ?: return@forEach
                        if (isSonyPod(device)) batteryViews[battery] = device
                    }
            }
        }.onFailure { Log.d(TAG, "existing Settings battery rebind skipped", it) }
    }

    private fun findObjectsByClassName(
        root: Any?,
        className: String,
        seen: MutableSet<Any>,
        depth: Int = 0,
    ): List<Any> {
        if (root == null || depth > 7 || !seen.add(root)) return emptyList()
        if (root.javaClass.name == className) return listOf(root)
        if (root is String || root is Number || root is Boolean || root is Enum<*> ||
            root is Class<*> || root is ClassLoader || (root is Context && depth > 0) ||
                root is View
        ) return emptyList()
        val result = mutableListOf<Any>()
        when (root) {
            is Map<*, *> -> root.values.forEach {
                result.addAll(findObjectsByClassName(it, className, seen, depth + 1))
            }
            is Iterable<*> -> root.forEach {
                result.addAll(findObjectsByClassName(it, className, seen, depth + 1))
            }
            else -> {
                var type: Class<*>? = root.javaClass
                while (type != null) {
                    type.declaredFields.forEach { field ->
                        if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) return@forEach
                        val value = runCatching {
                            field.isAccessible = true
                            field.get(root)
                        }.getOrNull()
                        result.addAll(findObjectsByClassName(value, className, seen, depth + 1))
                    }
                    type = type.superclass
                }
            }
        }
        return result
    }

    private fun findBatteryDevice(owner: Any?): BluetoothDevice? {
        if (owner == null) return null
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(owner)
                }.getOrNull()?.let { value ->
                    if (value is BluetoothDevice) return value
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun findFieldValue(owner: Any?, fieldName: String): Any? {
        if (owner == null) return null
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            runCatching {
                return type.getDeclaredField(fieldName).apply { isAccessible = true }.get(owner)
            }
            type = type.superclass
        }
        return null
    }

    private fun isOverEar(): Boolean = currentFormFactor == "HEADSET"

    /** Applies (or reverts) the single-battery rendering to every battery view we know. */
    private fun applyBatteryLayouts() {
        batteryViews.keys.toList().forEach { view ->
            runCatching { applyBatteryLayout(view) }
                .onFailure { Log.w(TAG, "apply battery layout failed", it) }
        }
    }

    private fun updateBatteryView(view: Any?) {
        // Apply the single-vs-three slot layout immediately (it is driven by the persisted
        // form factor, which is stable). Render battery values from the persisted state right
        // away when we have any, then let the live snapshot refresh it moments later: waiting
        // for the first ACTION_STATE would leave the reading on "-" for the whole cross-process
        // REPUBLISH round trip and looks like a slow load.
        applyBatteryLayout(view)
        val values = settingsBatteryValues()
        val key = values.joinToString(",")
        if (!hasLiveSnapshot && !hasCurrentBattery()) {
            Log.d(TAG, "Battery update skipped: no battery data yet overEar=${isOverEar()}")
            return
        }
        if (batteryValuesCache[view] != key) {
            batteryValuesCache[view] = key
            callMethod(view, "onBatteryChanged", values[0], values[1], values[2])
            Log.d(TAG, "Battery.onBatteryChanged(int,int,int) forced=$key overEar=${isOverEar()}")
        }
    }

    /**
     * Official MIUI uses a single three-slot custom view (MiuiHeadsetBattery) for both
     * TWS earbuds (left/right/case) and over-ear headphones. For over-ear it just fills
     * the extra slots with "-". Our hooked Sony adapter instead hides the left/case slot
     * columns and lets the single right slot expand, mirroring how the module UI renders
     * a headset (single battery value shown in the right slot).
     *
     * Each slot is laid out as a column (icon + label + value) inside the battery card
     * container `groupBatteryCard`. Hiding only the leaf value/icon views would leave the
     * empty column with its label behind, so we resolve the slot column at runtime by
     * walking up from the slot's value view until we reach the direct child of the row.
     */
    private fun applyBatteryLayout(view: Any?) {
        val rootView = batteryRootView(view) ?: return
        val overEar = isOverEar()
        val card = findView(rootView, "groupBatteryCard")
        val rightColumn = batterySlotColumn(rootView, "rightBattery")
        val row = rightColumn?.parent as? ViewGroup
        if (row != null) {
            // row (@7F0B05F0) holds [left column, divider, right column, divider, box column].
            // Over-ear: hide every direct child except the right (value) column, which already
            // has weight=1 and therefore expands to fill the row.
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                val newVis = if (overEar && child !== rightColumn) View.GONE else View.VISIBLE
                if (child.visibility != newVis) child.visibility = newVis
            }
            if (overEar) {
                val lp = rightColumn.layoutParams
                if (lp != null && lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    rightColumn.layoutParams = lp
                }
            }
            Log.d(TAG, "battery layout applied overEar=$overEar row=$row rightColumn=$rightColumn")
        } else {
            // Fallback: hide the known left/box leaf pairs, keep right visible.
            setSlot(rootView, "leftBattery", if (overEar) View.GONE else View.VISIBLE)
            setSlot(rootView, "imageLeftBattery", if (overEar) View.GONE else View.VISIBLE)
            setSlot(rootView, "boxBattery", if (overEar) View.GONE else View.VISIBLE)
            setSlot(rootView, "imageBoxBattery", if (overEar) View.GONE else View.VISIBLE)
            Log.d(TAG, "battery layout fallback overEar=$overEar card=$card")
        }
        updateBatterySlotLabel(rootView, rightColumn, overEar)
    }

    /**
     * The right slot's label reads "右" (right). For over-ear it is replaced with
     * "电量"; the original text is remembered per TextView and restored for TWS.
     *
     * The label shares its resource id (textViewHeadset) across all three slots, so it is
     * resolved structurally: it is the TextView sibling of the slot's value inside the
     * inner vertical cell (image + value + label).
     */
    private fun updateBatterySlotLabel(rootView: View, rightColumn: View?, overEar: Boolean) {
        val value = findView(rootView, "rightBattery") ?: return
        val innerCell = value.parent as? ViewGroup ?: return
        val label = (0 until innerCell.childCount)
            .mapNotNull { innerCell.getChildAt(it) as? TextView }
            .firstOrNull { it !== value }
            ?: return
        if (overEar) {
            if (!batteryLabelOriginals.containsKey(label)) {
                batteryLabelOriginals[label] = label.text
            }
            label.text = ModuleText.get(rootView.context, R.string.battery_label)
        } else {
            batteryLabelOriginals.remove(label)?.let { label.text = it }
        }
    }

    /**
     * Resolves the per-slot column view for a battery value. The layout is:
     * groupBatteryCard -> CardView -> row(horizontal) -> [column(inner vertical-cell -> value), divider, ...].
     * The value's direct parent is the inner vertical cell; its parent is the actual horizontal
     * slot column (@7F0B01BC/@7F0B01BD/@7F0B01BB) that is a direct child of the row.
     */
    private fun batterySlotColumn(rootView: View, valueName: String): View? {
        val value = findView(rootView, valueName) ?: return null
        val cell = value.parent as? ViewGroup ?: return null
        return cell.parent as? View
    }

    private fun findView(rootView: View, name: String): View? {
        val id = rootView.resources.getIdentifier(name, "id", PKG_SETTINGS)
        return if (id != 0) rootView.findViewById<View>(id) else null
    }

    private fun setSlot(rootView: View, name: String, visibility: Int) {
        findView(rootView, name)?.visibility = visibility
    }

    /** The headset battery control keeps the inflated layout in a WeakReference mRootView. */
    private fun batteryRootView(view: Any?): View? {
        val ref = runCatching { getObjectField(view, "mRootView") }.getOrNull() as? WeakReference<*>
        return ref?.get() as? View
    }

    private fun updateFragments() {
        headsetFragments.keys.toList().forEach { fragment ->
            if (isSonyFragment(fragment)) {
                injectFragmentStatus(fragment)
            }
        }
    }

    private fun injectFragmentStatus(fragment: Any?) {
        runCatching {
            val payload = "${settingsAncMode()}|0100;0101;0102;0103;0200;0201|${settingsBatteryString()}|00"
            Log.d(TAG, "injectFragmentStatus payload=$payload ${fragmentDebug(fragment)}")
            callMethod(fragment, "updateAtUiInfo", payload)
            callMethod(fragment, "updateAncUi", settingsAncLevel(), false)
            val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
            val address = device?.address
            if (address != null) {
                val refreshPayload = settingsRefreshPayload()
                Log.d(TAG, "injectFragmentStatus refreshPayload=$refreshPayload address=$address")
                // Official internals post to worker handlers that may already be dead (stale
                // fragments in the map); a throw here must not skip the badge pass below.
                runCatching { callMethod(fragment, "refreshStatus", address, refreshPayload) }
                    .onFailure { Log.w(TAG, "refreshStatus injection failed (stale fragment?)", it) }
            }
            Log.d(TAG, "fragment status injected anc=$currentAnc battery=${settingsBatteryString()}")
        }.onFailure { Log.w(TAG, "inject fragment status failed", it) }
        updateSoundQualityBadges(fragment)
    }

    /** Official 18dp badge height (Sound Connect big_header_view). */
    private const val BADGE_HEIGHT_DP = 18
    private const val BADGE_SPACING_DP = 10

    /** Gap kept between the badge row and the battery card once the card's top margin is absorbed. */
    private const val BADGE_CARD_GAP_DP = 8

    /**
     * Live sound-quality badges between the headset picture and the battery card — the same
     * three marks (LE Audio / codec / DSEE, official order) as the module UI's row. Rendered
     * as drawables on the layout's ViewOverlay: nothing joins the view hierarchy, so the
     * stock spacing is untouched and a page without badges is pixel-identical to stock.
     * Drawables come from the module APK via createPackageContext (same pattern as
     * ModuleText), values from the engine snapshot the status receiver already tracks.
     */
    private fun updateSoundQualityBadges(fragment: Any?) {
        runCatching {
            val rootView = getObjectField(fragment, "mRootView") as? View
            if (rootView == null) {
                Log.d(TAG, "badges skip: no mRootView")
                return@runCatching
            }
            val ctx = rootView.context
            val host = findView(rootView, "linear_layout") as? ViewGroup
            val card = findView(rootView, "groupBatteryCard")
            if (host == null || card == null) {
                Log.d(TAG, "badges skip: linear_layout=${host != null} batteryCard=${card != null}")
                return@runCatching
            }
            val res = ctx.moduleResourcesOrNull()
            if (res == null) {
                Log.w(TAG, "badges skip: module resources unavailable")
                return@runCatching
            }
            val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            // LEA streaming statuses are cleared on disconnect by the repository, so a stale
            // mark cannot outlive its link — same unconditional-assignment rule as the codec.
            val leaRes = if (currentLeaStreamingL == SonyStateSnapshot.LEA_STREAMING_UNICAST ||
                currentLeaStreamingR == SonyStateSnapshot.LEA_STREAMING_UNICAST
            ) {
                if (dark) R.drawable.a_mdr_connection_leaudio_dark else R.drawable.a_mdr_connection_leaudio_light
            } else {
                null
            }
            val codecRes = codecBadgeRes(currentCodec, dark)
            val dseeRes = currentDseeGeneration?.takeIf { currentDseeActive }?.let { dseeBadgeRes(it, dark) }
            val resIds = listOf(leaRes, codecRes, dseeRes)
            val state = badgeOverlayStates[host] ?: BadgeOverlayState().also { badgeOverlayStates[host] = it }
            // A visibility toggle refreshes fragments via the remote-pref change
            // callback, so this clears the overlay without waiting for a status event.
            if (!ConfigManager.visibility().bluetoothBadge) {
                state.drawables.forEach { host.overlay.remove(it) }
                state.drawables.clear()
                state.resIds = emptyList()
                return@runCatching
            }
            state.resIds = resIds
            state.drawables.forEach { host.overlay.remove(it) }
            state.drawables.clear()
            val visible = resIds.any { it != null }
            if (visible) {
                resIds.forEach { id ->
                    if (id == null) return@forEach
                    runCatching { res.getDrawable(id, null) }.getOrNull()?.let { state.drawables.add(it) }
                }
                // Overlay drawables live in host coordinates; reposition whenever the card
                // moves (initial layout, battery layout switch, animation resizes).
                card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    positionBadges(host, card, badgeOverlayStates[host] ?: return@addOnLayoutChangeListener)
                    host.invalidate()
                }
                positionBadges(host, card, state)
                state.drawables.forEach { host.overlay.add(it) }
            }
            Log.i(
                TAG,
                "badges updated lea=${leaRes != null} codec=$currentCodec " +
                    "dsee=$currentDseeGeneration active=$currentDseeActive visible=$visible",
            )
        }.onFailure { Log.w(TAG, "update sound quality badges failed", it) }
    }

    /** Per-host overlay badge state; host is the settings linear_layout. */
    private class BadgeOverlayState {
        val drawables = mutableListOf<Drawable>()
        var resIds: List<Int?> = emptyList()
    }

    private val badgeOverlayStates = WeakHashMap<View, BadgeOverlayState>()

    /** Centers the badge drawables horizontally, bottoms resting just above the battery card. */
    private fun positionBadges(host: ViewGroup, card: View, state: BadgeOverlayState) {
        if (state.drawables.isEmpty() || card.parent !== host) return
        val density = host.resources.displayMetrics.density
        val height = (BADGE_HEIGHT_DP * density).toInt()
        val gap = (BADGE_CARD_GAP_DP * density).toInt()
        val spacing = (BADGE_SPACING_DP * density).toInt()
        val widths = state.drawables.map { d ->
            if (d.intrinsicHeight > 0) height * d.intrinsicWidth / d.intrinsicHeight else 0
        }
        val total = widths.sum() + spacing * (state.drawables.size - 1).coerceAtLeast(0)
        val contentWidth = (host.width - host.paddingLeft - host.paddingRight).coerceAtLeast(total)
        var x = host.paddingLeft + (contentWidth - total) / 2
        val bottom = card.top - gap
        val top = bottom - height
        state.drawables.forEachIndexed { index, drawable ->
            drawable.setBounds(x, top, x + widths[index], bottom)
            x += widths[index] + spacing
        }
    }

    private fun Context.moduleResourcesOrNull(): Resources? = runCatching {
        if (packageName == BuildConfig.APPLICATION_ID) {
            resources
        } else {
            createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
                .resources
        }
    }.getOrNull()

    /** SC `a_mdr_codec_*`; UNSETTLED/OTHER have no official badge. Mirrors PodDetailPage. */
    private fun codecBadgeRes(codec: SoundQualityCodec?, dark: Boolean): Int? = when (codec) {
        SoundQualityCodec.SBC ->
            if (dark) R.drawable.a_mdr_codec_sbc_dark else R.drawable.a_mdr_codec_sbc_light
        SoundQualityCodec.AAC ->
            if (dark) R.drawable.a_mdr_codec_aac_dark else R.drawable.a_mdr_codec_aac_light
        SoundQualityCodec.LDAC ->
            if (dark) R.drawable.a_mdr_codec_ldac_dark else R.drawable.a_mdr_codec_ldac_light
        SoundQualityCodec.APT_X ->
            if (dark) R.drawable.a_mdr_codec_aptx_dark else R.drawable.a_mdr_codec_aptx_light
        SoundQualityCodec.APT_X_HD ->
            if (dark) R.drawable.a_mdr_codec_aptxhd_dark else R.drawable.a_mdr_codec_aptxhd_light
        SoundQualityCodec.LC3 ->
            if (dark) R.drawable.a_mdr_codec_lc3_dark else R.drawable.a_mdr_codec_lc3_light
        else -> null
    }

    /** SC `a_mdr_dsee*` — one mark per DSEE generation. Mirrors PodDetailPage. */
    private fun dseeBadgeRes(generation: DseeGeneration, dark: Boolean): Int = when (generation) {
        DseeGeneration.DSEE_HX ->
            if (dark) R.drawable.a_mdr_dseehx_dark else R.drawable.a_mdr_dseehx_light
        DseeGeneration.DSEE ->
            if (dark) R.drawable.a_mdr_dsee_dark else R.drawable.a_mdr_dsee_light
        DseeGeneration.DSEE_HX_AI ->
            if (dark) R.drawable.a_mdr_dseehx_ai_dark else R.drawable.a_mdr_dseehx_ai_light
        DseeGeneration.DSEE_ULTIMATE ->
            if (dark) R.drawable.a_mdr_dsee_ult_dark else R.drawable.a_mdr_dsee_ult_light
    }

    private fun isSonyFragment(fragment: Any?): Boolean {
        val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        return isSonyPod(device)
    }

    internal fun isSonyPod(device: BluetoothDevice?): Boolean {
        val result = SonyDeviceService.isSony(device)
        if (result) {
            currentAddress = runCatching { device?.address }.getOrNull() ?: currentAddress
            currentName = runCatching { device?.name ?: device?.alias }.getOrNull() ?: currentName
        }
        return result
    }

    private fun BluetoothDevice?.describe(): String {
        if (this == null) return "null"
        val address = runCatching { this.address }.getOrNull()
        val name = runCatching { this.name }.getOrNull()
        val alias = runCatching { this.alias }.getOrNull()
        return "BluetoothDevice(address=$address,name=$name,alias=$alias)"
    }

    private fun List<Any?>.describeArgs(): String {
        return joinToString(prefix = "[", postfix = "]") { arg ->
            when (arg) {
                is BluetoothDevice -> arg.describe()
                else -> arg?.toString() ?: "null"
            }
        }
    }

    private fun fragmentDebug(fragment: Any?): String {
        val device = runCatching { getObjectField(fragment, "mDevice") as? BluetoothDevice }.getOrNull()
        val deviceId = runCatching { getObjectField(fragment, "mDeviceId") as? String }.getOrNull()
        val support = runCatching { getObjectField(fragment, "mSupport") as? String }.getOrNull()
        val service = runCatching { getObjectField(fragment, "mService") }.getOrNull()
        val hfp = runCatching { getObjectField(fragment, "mBluetoothHfp") }.getOrNull()
        val cached = runCatching { getObjectField(fragment, "mCachedDevice") }.getOrNull()
        val supportAnc = runCatching { getObjectField(fragment, "mSupportAnc") }.getOrNull()
        val ancCached = runCatching { getObjectField(fragment, "mAncCached") }.getOrNull()
        val pendingAnc = runCatching { getObjectField(fragment, "mPendingAnc") }.getOrNull()
        val ancPendingStatus = runCatching { getObjectField(fragment, "mAncPendingStatus") }.getOrNull()
        return "fragment(device=${device.describe()},deviceId=$deviceId,support=$support,service=$service,hfp=$hfp,cached=$cached,supportAnc=$supportAnc,ancCached=$ancCached,pendingAnc=$pendingAnc,ancPending=$ancPendingStatus)"
    }

    private fun isSonyAddress(address: String): Boolean {
        return SonyDeviceService.isKnownSonyAddress(address) ||
            address.equals(currentAddress, ignoreCase = true)
    }

    private fun settingsBatteryString(): String {
        return settingsBatteryValues().joinToString(",")
    }

    /** Maps the current snapshot into the three-slot MIUI encoding.
     *  Over-ear headphones expose a single value; it lands in the right slot and the
     *  left/case slots stay "not present" (255), while those slot views are hidden. */
    private fun settingsBatteryValues(): List<Int> {
        loadState()
        return if (isOverEar()) {
            listOf(
                255,
                batteryValue(currentBattery.left),
                255
            )
        } else {
            listOf(
                batteryValue(currentBattery.left),
                batteryValue(currentBattery.right),
                batteryValue(currentBattery.case)
            )
        }
    }

    private fun snapshotBattery(snapshot: SonyStateSnapshot): BatteryParams {
        val single = snapshot.batterySingle
        return if (single != null) {
            BatteryParams(
                left = PodParams(battery = single.coerceIn(0, 100), isConnected = true),
            )
        } else {
            // The repository already maps disconnected buds to null and preserves a
            // real 0% cradle level, so consumers must not reinterpret the value.
            fun pod(level: Int?) = level
                ?.let { PodParams(battery = it.coerceIn(0, 100), isConnected = true) }
            BatteryParams(
                left = pod(snapshot.batteryLeft),
                right = pod(snapshot.batteryRight),
                case = pod(snapshot.batteryCradle),
            )
        }
    }

    private fun batteryValue(params: PodParams?): Int {
        if (params?.isConnected != true) return 255
        val value = params.battery.coerceIn(0, 100)
        return if (params.isCharging) value or 128 else value
    }

    private fun settingsAncMode(): String {
        loadState()
        return when (currentAnc) {
            2, 5, 6, 7, 8 -> "1"
            3 -> "2"
            else -> "0"
        }
    }

    private fun settingsAncLevel(): String {
        loadState()
        // MIUI Settings level codes: 0103=Smart, 0101=Light, 0100=Medium, 0102=Deep,
        // 0200=Transparency, 0201=Transparency vocal enhancement, 0000=Off.
        return when (currentAnc) {
            2 -> "0100"
            5 -> "0103"
            6 -> "0101"
            7 -> "0100"
            8 -> "0102"
            3 -> if (currentTransparencyVocalEnhancement) "0201" else "0200"
            else -> "0000"
        }
    }

    private fun settingsRefreshPayload(): String {
        val battery = settingsBatteryString().split(",")
        val left = battery.getOrNull(0).orEmpty()
        val right = battery.getOrNull(1).orEmpty()
        val box = battery.getOrNull(2).orEmpty()
        val values = MutableList(16) { "" }
        values[0] = left
        values[1] = right
        values[2] = box
        // Slot 3 carries the firmware as "<code>+<display>"; MiuiHeadsetFragment.refreshStatus
        // routes a non-empty value to updateStatus(), which fills versionName. Code 0 keeps every
        // Xiaomi version-gated feature (wind noise >= 30259 etc.) off for Sony hardware.
        values[3] = currentFirmware?.takeIf { it.isNotBlank() }?.let { "0+$it" }.orEmpty()
        values[7] = settingsAncLevel()
        values[8] = "false"
        values[11] = "00"
        values[13] = "00"
        values[14] = "00"
        return values.joinToString(",")
    }

    private fun sonyAncFromSettings(mode: Int): Int {
        return when (mode) {
            1 -> 2
            2 -> 3
            else -> 1
        }
    }

    private fun sonyAncFromLevel(level: String): Int {
        // Convert MIUI Settings level code back to the internal Sony ANC state (1=Off 2=NC 3=Ambient).
        return when {
            level.startsWith("0103") -> 5
            level.startsWith("0101") -> 6
            level.startsWith("0100") -> 7
            level.startsWith("0102") -> 8
            level.startsWith("01") -> 7
            level.startsWith("02") -> 3
            else -> 1
        }
    }

    private fun sendSonyAmbientVoiceFromLevel(level: String) {
        when {
            level.startsWith("0201") -> sendSonyAmbientVoice(true)
            level.startsWith("0200") -> sendSonyAmbientVoice(false)
        }
    }

    private fun sonyAncFromLevelCommand(level: String): Int {
        // "02xx" is the transparency path (0200=transparency, 0201=transparency vocal
        // enhancement). It must return a non-null mode so the fragment hook swallows the
        // official call; returning null here let the real MiuiHeadsetFragment.updateAncLevel
        // run, whose wear-status guard (setCommonCommand(102)=="0") shows the
        // "请连接并佩戴耳机" toast and reverts the control.
        if (level.startsWith("02")) {
            currentAnc = 3
            hasAncState = true
            sendSonyAmbientVoiceFromLevel(level)
            return 3
        }
        return sonyAncFromLevel(level)
    }

    private fun sendSonyAnc(mode: Int) {
        val ctx = context ?: run {
            Log.d(TAG, "sendSonyAnc skipped: context is null mode=$mode")
            return
        }
        val sonyMode = when (mode) {
            2, 5, 6, 7, 8 -> NoiseControlMode.NOISE_CANCELLING
            3 -> NoiseControlMode.AMBIENT_SOUND
            else -> NoiseControlMode.OFF
        }
        SonyBridge.setNoiseControl(ctx, sonyMode)
        Log.d(TAG, "sendSonyAnc command sent mode=$mode sony=$sonyMode")
    }

    private fun sendSonyAmbientVoice(enabled: Boolean) {
        val ctx = context ?: run {
            Log.d(TAG, "sendSonyAmbientVoice skipped: context is null enabled=$enabled")
            return
        }
        currentTransparencyVocalEnhancement = enabled
        hasAncState = true
        SonyBridge.setAmbientVoice(ctx, enabled)
        ctx.sendBroadcast(Intent(SonyPodsAction.ACTION_PODS_AMBIENT_VOICE_CHANGED).apply {
            putExtra("enabled", enabled)
            setPackage(BuildConfig.APPLICATION_ID)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        ctx.sendBroadcast(Intent(SonyPodsAction.ACTION_REFRESH_STATUS).apply {
            setPackage(BuildConfig.APPLICATION_ID)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        Log.d(TAG, "sendSonyAmbientVoice command sent enabled=$enabled")
    }

    private fun sendAncChanged(mode: Int) {
        val ctx = context ?: return
        listOf(BuildConfig.APPLICATION_ID, "com.android.settings", "com.milink.service").forEach { targetPackage ->
            ctx.sendBroadcast(Intent(SonyPodsAction.ACTION_PODS_ANC_CHANGED).apply {
                putExtra("status", mode)
                setPackage(targetPackage)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableDevice(key: String): BluetoothDevice? {
        return runCatching { getParcelableExtra(key, BluetoothDevice::class.java) }.getOrNull()
            ?: runCatching { getParcelableExtra<BluetoothDevice>(key) }.getOrNull()
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
            .putString("firmware", currentFirmware)
            .putInt("anc", currentAnc)
            .putBoolean("transparency_vocal_enhancement", currentTransparencyVocalEnhancement)
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

    private fun loadState() {
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        currentAddress = prefs.getString("address", currentAddress)
        currentName = prefs.getString("name", currentName)
        if (currentFormFactor == null) {
            currentFormFactor = prefs.getString("form_factor", null)
        }
        if (currentFirmware.isNullOrBlank()) {
            currentFirmware = prefs.getString("firmware", null)
        }
        SonyDeviceService.rememberAddress(currentAddress)
        // Live snapshot data wins; persisted ANC/voice are only a bootstrap until the
        // first live state arrives. Overwriting here reverted the UI after every tap
        // (vocal-enhancement looked unclickable) because settingsAncLevel() calls
        // loadState() on every inject and reloaded the stale persisted value.
        if (hasAncState) return
        currentAnc = prefs.getInt("anc", currentAnc)
        currentTransparencyVocalEnhancement = prefs.getBoolean("transparency_vocal_enhancement", currentTransparencyVocalEnhancement)
        // Live snapshot data wins; persisted battery is only a bootstrap until the first
        // ACTION_STATE arrives. Overwriting live values here made the reading jump between
        // the live value and whatever was last saved.
        if (hasCurrentBattery()) return
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

    private fun hasCurrentBattery(): Boolean {
        return currentBattery.left?.isConnected == true ||
            currentBattery.right?.isConnected == true ||
            currentBattery.case?.isConnected == true
    }
}
