package dev.sonypods.hook
import com.mercury.sonypods.R
import dev.sonypods.utils.ModuleText
import dev.sonypods.hook.symbols.ResolvedSymbolBundle

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import dev.sonypods.utils.MiuiHeadsetSupport
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import dev.sonypods.utils.miuiStrongToast.data.PodParams
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

@SuppressLint("MissingPermission")
object SettingsHeadsetHook : HookContext() {
    private const val TAG = "SonyPods-Hook"
    private const val PREFS_NAME = "sonypods_milink_state"
    private const val REPUBLISH_DEBOUNCE_MS = 600L
    private const val INITIAL_UI_QUIET_MS = 350L
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
    private var isConnectedState = false
    private var hasAncState = false
    /** Set once the persisted ANC/transparency pair is in memory. */
    private var ancStateReady = false
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
    private var lastRepublishAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingFragmentUpdates = WeakHashMap<Any, Boolean>()
    private val pendingBatteryUpdates = WeakHashMap<Any, Boolean>()
    @Volatile private var initialUiReleaseAt = 0L
    @Volatile private var bootstrapScheduled = false
    @Volatile private var backgroundExecutor: ExecutorService = newBackgroundExecutor()
    private lateinit var activitySymbols: ResolvedSymbolBundle
    private lateinit var supportSymbols: ResolvedSymbolBundle
    private lateinit var batterySymbols: ResolvedSymbolBundle
    private lateinit var fragmentSymbols: ResolvedSymbolBundle
    private lateinit var serviceProxySymbols: ResolvedSymbolBundle
    private var pluginSymbols: ResolvedSymbolBundle? = null
    @Volatile private var runtimeHooksInstalled = false

    override fun onHook() {
        hookApplicationEntry()
    }

    private fun hookApplicationEntry() {
        hookBefore(
            findMethod(
                "android.app.Instrumentation",
                "callApplicationOnCreate",
                Application::class.java,
            ),
            logicalRole = "settings-headset-application-ready",
        ) {
            val application = requireNotNull(args.firstOrNull() as? Application) {
                "Settings Application is unavailable at callApplicationOnCreate"
            }
            onApplicationAvailable(application)
        }
    }

    @Synchronized
    private fun onApplicationAvailable(application: Context) {
        val appContext = application.applicationContext ?: application
        attachSymbolResolver(runtime.symbols(appClassLoader, appContext))
        if (!runtimeHooksInstalled) {
            activitySymbols = requireSymbols(SettingsActivitySymbols)
            supportSymbols = requireSymbols(SettingsSupportSymbols)
            batterySymbols = requireSymbols(SettingsBatterySymbols)
            fragmentSymbols = requireSymbols(SettingsFragmentSymbols)
            serviceProxySymbols = requireSymbols(SettingsServiceProxySymbols)
            pluginSymbols = runCatching { requireSymbols(SettingsActivityPluginSymbols) }
                .onFailure { Log.d(TAG, "optional Settings activity plugin unavailable", it) }
                .getOrNull()
            hookActivityEntry()
            hookSupportChecks()
            hookServiceProxy()
            hookBatteryView()
            hookFragmentState()
            runtimeHooksInstalled = true
        }
        ensureBackgroundExecutor()
        registerStatusReceiver(appContext)
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
        pendingFragmentUpdates.clear()
        pendingBatteryUpdates.clear()
        backgroundExecutor.shutdownNow()
        bootstrapScheduled = false
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
        onApplicationAvailable(context)
        // Existing fragment/battery maps are transferred between generations. Re-rendering
        // those tracked instances is sufficient; avoid the former recursive ActivityThread
        // object-graph scan, which could monopolize Settings' main thread.
        mainHandler.postDelayed({
            applyBatteryLayouts()
            updateBatteryViews()
            updateFragments()
        }, INITIAL_UI_QUIET_MS)
    }

    private fun hookActivityEntry() {
        runCatching {
            hookBefore(activitySymbols.method("onCreate")) {
                val intent = (instance as? Context)?.let { context ->
                    runCatching { context.javaClass.getMethod("getIntent").invoke(context) as? Intent }.getOrNull()
                } ?: return@hookBefore
                val device = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
                Log.d(TAG, "Activity.onCreate before device=${device.describe()} support=${intent.getStringExtra("MIUI_HEADSET_SUPPORT")} comeFrom=${intent.getStringExtra("COME_FROM")} btAddress=${intent.getStringExtra("bluetoothaddress")} known=${SonyDeviceService.knownAddressSnapshot()} current=$currentAddress")
                if (!isSonyPod(device)) return@hookBefore
                val address = device?.address ?: return@hookBefore
                intent.putExtra("MIUI_HEADSET_SUPPORT", settingsSupport(address))
                intent.putExtra("COME_FROM", intent.getStringExtra("COME_FROM") ?: "MIUI_BLUETOOTH_SETTINGS")
                Log.d(TAG, "MiuiHeadsetActivity intent patched address=$address")
            }
            hookAfter(activitySymbols.method("getSupport")) {
                val device = activitySymbols.field("deviceField").get(instance) as? BluetoothDevice
                Log.d(TAG, "Activity.getSupport old=$result device=${device.describe()} isSony=${isSonyPod(device)}")
                if (!isSonyPod(device) || device == null) return@hookAfter
                result = settingsSupport(device.address)
                Log.d(TAG, "Activity.getSupport forced=$result")
            }
        }.onFailure { Log.d(TAG, "hook Settings headset activity skipped", it) }

        pluginSymbols?.let { symbols ->
            runCatching {
                hookBefore(symbols.method("onCreate")) {
                    val context = instance as? Context ?: return@hookBefore
                    val intent = runCatching { context.javaClass.getMethod("getIntent").invoke(context) as? Intent }
                        .getOrNull() ?: return@hookBefore
                    val device = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
                    Log.d(TAG, "Plugin.onCreate before device=${device.describe()} support=${intent.getStringExtra("MIUI_HEADSET_SUPPORT")} comeFrom=${intent.getStringExtra("COME_FROM")} btAddress=${intent.getStringExtra("bluetoothaddress")} known=${SonyDeviceService.knownAddressSnapshot()} current=$currentAddress")
                    if (!isSonyPod(device)) return@hookBefore
                    val address = device?.address ?: return@hookBefore
                    intent.putExtra("MIUI_HEADSET_SUPPORT", settingsSupport(address))
                    Log.d(TAG, "Settings activity plugin intent patched address=$address")
                }
            }.onFailure { Log.d(TAG, "hook Settings activity plugin skipped", it) }
        }
    }

    private fun hookSupportChecks() {
        runCatching {
            hookAfter(supportSymbols.method("checkSupport")) {
                val support = args[0] as? String ?: return@hookAfter
                val address = MiuiHeadsetSupport.addressOf(support) ?: return@hookAfter
                if (!isSonyAddress(address)) return@hookAfter
                result = true
                Log.d(TAG, "Settings headset support accepted Sony address=$address")
            }
        }.onFailure { Log.d(TAG, "hook Settings headset support skipped", it) }
        hookBleMmaConnect(supportSymbols.method("isBleMmaConnectContext"), "Context")
        hookBleMmaConnect(supportSymbols.method("isBleMmaConnectService"), "Service")
    }

    private fun hookBleMmaConnect(method: Method, source: String) {
        runCatching {
            hookAfter(method) {
                val device = args[1] as? BluetoothDevice
                val deviceId = args[2] as? String
                if (isSonyPod(device)) {
                    result = isDeviceConnected(device)
                    Log.d(TAG, "isBleMmaConnect($source) result=$result device=${device.describe()} deviceId=$deviceId")
                }
            }
        }.onFailure { Log.d(TAG, "hook isBleMmaConnect($source) skipped", it) }
    }

    private fun hookServiceProxy() {
        hookProxyString("checkSupport") { args ->
            val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
            settingsSupport(device?.address ?: currentAddress.orEmpty())
        }
        hookProxyString("getDeviceInfo") { args ->
            settingsSupport(args.firstOrNull { it is String } as? String ?: currentAddress.orEmpty())
        }
        hookProxyString("isSupportAudioSwitch") { "1" }
        hookProxyString("setCommonCommand") { "1" }
        listOf("isMiTWS", "checkIsMiTWS").forEach { hookProxyBoolean(it, true) }
        hookProxyBoolean("getRingFindState", false)
        listOf("connect", "getDeviceConfig", "getCommonConfig").forEach(::hookProxyVoid)
        hookProxyAnc("changeAncMode") { args -> sonyAncFromSettings(args[0] as? Int ?: 0) }
        hookProxyAnc("changeAncLevel") { args -> sonyAncFromLevelCommand(args[0] as? String ?: "") }
    }

    private fun hookProxyString(symbol: String, value: (List<Any?>) -> String) {
        hookBefore(serviceProxySymbols.method(symbol)) {
            val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
            val address = args.firstOrNull { it is String } as? String
            if (!isSonyPod(device) && (address == null || !isSonyAddress(address))) return@hookBefore
            result = value(args)
        }
    }

    private fun hookProxyBoolean(symbol: String, value: Boolean) {
        hookBefore(serviceProxySymbols.method(symbol)) {
            val address = args.firstOrNull() as? String ?: return@hookBefore
            if (!isSonyAddress(address)) return@hookBefore
            result = value
        }
    }

    private fun hookProxyVoid(symbol: String) {
        hookBefore(serviceProxySymbols.method(symbol)) {
            val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
            if (!isSonyPod(device)) return@hookBefore
            result = null
        }
    }

    private fun hookProxyAnc(symbol: String, mode: (List<Any?>) -> Int) {
        hookBefore(serviceProxySymbols.method(symbol)) {
            val device = args.firstOrNull { it is BluetoothDevice } as? BluetoothDevice
            if (!isSonyPod(device)) return@hookBefore
            val sonyMode = mode(args)
            currentAnc = sonyMode
            hasAncState = true
            sendSonyAnc(sonyMode)
            sendAncChanged(sonyMode)
            result = null
        }
    }

    private fun hookBatteryView() {
        runCatching {
            hookConstructorAfter(findConstructorByParamCount("com.android.settings.bluetooth.tws.MiuiHeadsetBattery", 4)) {
                val device = args[0] as? BluetoothDevice ?: return@hookConstructorAfter
                if (!isSonyPod(device)) return@hookConstructorAfter
                val batteryView = instance ?: return@hookConstructorAfter
                batteryViews[batteryView] = device
                requestBluetoothStatus("battery-init")
                scheduleBatteryUpdate(batteryView)
                Log.d(TAG, "MiuiHeadsetBattery registered address=${device.address}")
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetBattery constructor skipped", it) }

        runCatching {
            hookBefore(batterySymbols.method("onBatteryChangedString")) {
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
            hookAfter(fragmentSymbols.method("onCreate")) {
                if (!isSonyFragment(instance)) return@hookAfter
                removeUnsupportedVirtualSurround(instance)
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.onCreate skipped", it) }

        runCatching {
            hookAfter(fragmentSymbols.method("onCreateView")) {
                Log.d(TAG, "Fragment.onCreateView after ${fragmentDebug(instance)} isSony=${isSonyFragment(instance)}")
                if (!isSonyFragment(instance)) return@hookAfter
                val fragment = instance ?: return@hookAfter
                headsetFragments[fragment] = true
                initialUiReleaseAt = maxOf(initialUiReleaseAt, SystemClock.uptimeMillis() + INITIAL_UI_QUIET_MS)
                paintRestoredVersion(fragment)
                requestBluetoothStatus("fragment-create")
                scheduleFragmentUpdate(fragment)
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.onCreateView skipped", it) }

        runCatching {
            hookAfter(fragmentSymbols.method("onServiceConnected")) {
                Log.d(TAG, "Fragment.onServiceConnected after ${fragmentDebug(instance)} isSony=${isSonyFragment(instance)}")
                if (!isSonyFragment(instance)) return@hookAfter
                val fragment = instance ?: return@hookAfter
                headsetFragments[fragment] = true
                paintRestoredVersion(fragment)
                requestBluetoothStatus("service-connected")
                scheduleFragmentUpdate(fragment)
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.onServiceConnected skipped", it) }

        runCatching {
            hookBefore(fragmentSymbols.method("refreshStatus")) {
                val key = args[0] as? String
                val data = args[1] as? String
                Log.d(TAG, "Fragment.refreshStatus before key=$key data=$data ${fragmentDebug(instance)} isSony=${isSonyFragment(instance)}")
                if (isSonyFragment(instance) && key?.startsWith("MMA_CONNECTION_FAILED") == true) {
                    Log.d(TAG, "Fragment.refreshStatus swallowed MMA failure for virtual Oppo device key=$key")
                    scheduleFragmentUpdate(instance)
                    result = null
                }
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.refreshStatus skipped", it) }

        runCatching {
            hookBefore(fragmentSymbols.method("handleConnectMmaFailed")) {
                Log.d(TAG, "Fragment.handleConnectMmaFailed arg=${args[0]} ${fragmentDebug(instance)} isSony=${isSonyFragment(instance)}")
                if (isSonyFragment(instance)) {
                    scheduleFragmentUpdate(instance)
                    result = null
                    Log.d(TAG, "Fragment.handleConnectMmaFailed swallowed for virtual Oppo device")
                }
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.handleConnectMmaFailed skipped", it) }

        runCatching {
            hookBefore(fragmentSymbols.method("refreshStatusUi")) {
                if (!isSonyFragment(instance)) return@hookBefore
                val ours = currentFirmware?.takeIf { it.isNotBlank() } ?: return@hookBefore
                val shown = args.getOrNull(0) as? String
                // refreshStatusUi is the only writer of R.id.versionName, and MIUI calls it
                // with whatever firmware the virtual Oppo device reports (updateStatus keeps
                // the display half of its "<code>+<display>" payload). Hold it to the
                // firmware we track, the same way the ANC row and the battery reading are
                // held. An empty argument is a pure visibility refresh and passes through.
                if (shown.isNullOrEmpty() || shown == ours) return@hookBefore
                Log.d(TAG, "refreshStatusUi version swallowed shown=$shown tracked=$ours")
                result = null
                runCatching { fragmentSymbols.method("refreshStatusUi").invoke(instance, ours) }
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.refreshStatusUi version guard skipped", it) }

        hookFragmentAncCommand("updateAncMode") { commandArgs ->
            sonyAncFromSettings(commandArgs[0] as? Int ?: 0)
        }
        hookFragmentAncCommand("updateAncLevel") { commandArgs ->
            val level = commandArgs[0] as? String ?: ""
            sonyAncFromLevelCommand(level)
        }
        runCatching {
            hookBefore(
                fragmentSymbols.method("updateAncUi"),
            ) {
                if (!isSonyFragment(instance)) return@hookBefore
                // Only once an ANC/transparency state is known (live snapshot or restored
                // from prefs). Before that currentAnc is still the field default, and
                // holding that against the page would flash "off" for the round trip.
                if (!hasAncState && !ancStateReady) return@hookBefore
                val requested = args.getOrNull(0) as? String
                val tracked = settingsAncLevel()
                if (requested == null || requested == tracked) return@hookBefore
                // updateAncUi is the page's ANC row render, and the level it is handed can
                // come from anywhere — its own cached level, a device refresh — none of
                // which describes the Sony headset. Same treatment the battery reading
                // gets: swallow the stock value and paint ours. The nested call re-enters
                // with the tracked level and passes through to the real render.
                Log.d(TAG, "updateAncUi stock level swallowed requested=$requested tracked=$tracked")
                result = null
                runCatching { fragmentSymbols.method("updateAncUi").invoke(instance, tracked, false) }
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.updateAncUi level guard skipped", it) }
        runCatching {
            hookAfter(
                fragmentSymbols.method("updateAncUi"),
            ) {
                if (!isSonyFragment(instance)) return@hookAfter
                val rootView = fragmentSymbols.field("rootViewField").get(instance) as? View ?: return@hookAfter
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

    private fun removeUnsupportedVirtualSurround(fragment: Any?) {
        val preference = runCatching {
            callMethod(fragment, "findPreference", "virtualSurroundSound")
        }.getOrNull() ?: return
        val parent = runCatching { callMethod(preference, "getParent") }.getOrNull() ?: return
        val removed = runCatching {
            callMethod(parent, "removePreference", preference) as? Boolean == true
        }.getOrDefault(false)
        if (removed) {
            runCatching { fragmentSymbols.field("virtualSurroundField").set(fragment, null) }
            val preferenceCount = runCatching {
                callMethod(parent, "getPreferenceCount") as? Int
            }.getOrNull()
            if (preferenceCount == 0) {
                val container = runCatching { callMethod(parent, "getParent") }.getOrNull()
                if (container != null) {
                    runCatching { callMethod(container, "removePreference", parent) }
                }
            }
            Log.d(TAG, "removed unsupported virtualSurroundSound from Sony headset page")
        }
    }

    /**
     * The version row opens on the layout's "connect the headset to read the version" hint
     * and is only replaced once a firmware arrives — which on this page is the deferred
     * injection. Paint the firmware restored from prefs as soon as the fragment exists, so
     * the hint is not the first thing the user reads.
     */
    private fun paintRestoredVersion(fragment: Any?) {
        val firmware = currentFirmware?.takeIf { it.isNotBlank() } ?: return
        runCatching { fragmentSymbols.method("refreshStatusUi").invoke(fragment, firmware) }
    }

    private fun hookFragmentAncCommand(methodName: String, mode: (List<Any?>) -> Int?) {
        runCatching {
            hookBefore(fragmentSymbols.method(methodName)) {
                Log.d(TAG, "MiuiHeadsetFragment.$methodName before args=${args.describeArgs()} ${fragmentDebug(instance)} isSony=${isSonyFragment(instance)}")
                if (!isSonyFragment(instance)) return@hookBefore
                val updateDevice = args.getOrNull(1) as? Boolean ?: true
                if (!updateDevice) return@hookBefore
                val sonyMode = mode(args) ?: return@hookBefore
                currentAnc = sonyMode
                hasAncState = true
                sendSonyAnc(sonyMode)
                sendAncChanged(sonyMode)
                runCatching { fragmentSymbols.method("updateAncUi").invoke(instance, settingsAncLevel(), false) }
                injectFragmentStatus(instance)
                result = null
                Log.d(TAG, "MiuiHeadsetFragment.$methodName handled sonyMode=$sonyMode")
            }
        }.onFailure { Log.d(TAG, "hook MiuiHeadsetFragment.$methodName skipped", it) }
    }

    private fun registerStatusReceiver(ctx: Context?) {
        if (ctx == null || receiverRegistered) return
        context = ctx.applicationContext ?: ctx
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
                        if (snapshot != null) {
                            isConnectedState = snapshot.connected
                            if (snapshot.deviceAddress != null) {
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
                                currentBattery = if (snapshot.connected) snapshotBattery(snapshot) else BatteryParams()
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
                                hasAncState = snapshot.connected
                                // Live sound-quality badge inputs. Assigned unconditionally: the
                                // repository nulls them on disconnect, and a stale LDAC/DSEE mark must
                                // not outlive the link that carried it.
                                currentCodec = snapshot.soundQualityCodec
                                currentDseeGeneration = snapshot.dseeGeneration
                                currentDseeActive = snapshot.dseeActive
                                currentLeaStreamingL = snapshot.leaStreamingStatusL
                                currentLeaStreamingR = snapshot.leaStreamingStatusR
                                SonyDeviceService.rememberAddress(currentAddress)
                                Log.d(TAG, "state snapshot address=$currentAddress connected=${snapshot.connected} formFactor=$currentFormFactor anc=$currentAnc voice=$currentTransparencyVocalEnhancement battery=${settingsBatteryString()}")
                                saveState(context)
                                // Live instances are already registered by their constructor and
                                // fragment hooks. A full ActivityThread object-graph walk here was
                                // the remaining deterministic UI-thread stall on every snapshot.
                                updateBatteryViews()
                                updateFragments()
                            } else if (!snapshot.connected) {
                                hasAncState = false
                                currentBattery = BatteryParams()
                                updateBatteryViews()
                                updateFragments()
                            }
                        }
                    }
                    SonyPodsAction.ACTION_PODS_CONNECTED -> {
                        hasLiveSnapshot = true
                        isConnectedState = true
                        currentAddress = intent.getStringExtra("address") ?: currentAddress
                        currentName = intent.getStringExtra("device_name") ?: currentName
                        SonyDeviceService.rememberAddress(currentAddress)
                    }
                    SonyPodsAction.ACTION_PODS_DISCONNECTED -> {
                        isConnectedState = false
                        hasAncState = false
                        currentBattery = BatteryParams()
                        updateBatteryViews()
                        updateFragments()
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
        // Disk-backed state, RemotePreferences, config parsing and cross-process
        // broadcasts must never execute on Settings' main thread. They are serialized on
        // the hook worker, then only the final view mutation returns to the main looper.
        scheduleBackgroundBootstrap()
        Log.d(TAG, "registered status receiver context=$context")
    }

    override fun onRemoteConfigChanged() {
        mainHandler.post { updateFragments() }
    }

    private fun newBackgroundExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "SonyPods-SettingsHook").apply { isDaemon = true }
        }

    @Synchronized
    private fun ensureBackgroundExecutor(): ExecutorService {
        if (backgroundExecutor.isShutdown) backgroundExecutor = newBackgroundExecutor()
        return backgroundExecutor
    }

    private fun runInBackground(task: () -> Unit) {
        try {
            ensureBackgroundExecutor().execute(task)
        } catch (_: RejectedExecutionException) {
            ensureBackgroundExecutor().execute(task)
        }
    }

    private fun scheduleBackgroundBootstrap() {
        if (bootstrapScheduled) return
        bootstrapScheduled = true
        runInBackground {
            loadState()
            registerRemoteConfigChangeListener()
            requestBluetoothStatusNow("receiver-register")
            mainHandler.post {
                updateBatteryViews()
                updateFragments()
            }
        }
    }

    private fun requestBluetoothStatus(reason: String) {
        runInBackground { requestBluetoothStatusNow(reason) }
    }

    @Synchronized
    private fun requestBluetoothStatusNow(reason: String) {
        val ctx = context ?: return
        // Ask the engine to re-broadcast its current state. Without this the settings
        // process only receives a snapshot after the engine *changes* state (battery/
        // ANC tick), so opening the headset page can show "-" for the battery until
        // the user toggles ANC. CMD_REPUBLISH re-publishes the last known snapshot.
        // Debounce: page open fires this from several hooks (receiver-register,
        // battery-init, fragment-create, service-connected) in the same moment; each
        // would otherwise trigger a full cross-process republish round trip.
        val now = SystemClock.elapsedRealtime()
        if (now - lastRepublishAt <= REPUBLISH_DEBOUNCE_MS) return
        lastRepublishAt = now
        SonyBridge.sendCommand(ctx, SonyBridge.CMD_REPUBLISH)
        listOf(SonyPodsAction.ACTION_PODS_UI_INIT, SonyPodsAction.ACTION_REFRESH_STATUS).forEach { action ->
            ctx.sendBroadcast(Intent(action).apply {
                setPackage(BuildConfig.APPLICATION_ID)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }
        Log.d(TAG, "requested bluetooth status reason=$reason")
    }

    private fun scheduleFragmentUpdate(fragment: Any?) {
        val target = fragment ?: return
        val schedule = {
            if (pendingFragmentUpdates.put(target, true) == null) {
                val delay = (initialUiReleaseAt - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                mainHandler.postDelayed({
                    pendingFragmentUpdates.remove(target)
                    if (isSonyFragment(target)) injectFragmentStatus(target)
                }, delay)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) schedule() else mainHandler.post(schedule)
    }

    private fun scheduleBatteryUpdate(view: Any?) {
        val target = view ?: return
        val schedule = {
            if (pendingBatteryUpdates.put(target, true) == null) {
                val delay = (initialUiReleaseAt - SystemClock.uptimeMillis()).coerceAtLeast(0L)
                mainHandler.postDelayed({
                    pendingBatteryUpdates.remove(target)
                    if (batteryViews.containsKey(target)) updateBatteryView(target)
                }, delay)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) schedule() else mainHandler.post(schedule)
    }

    private fun updateBatteryViews() {
        batteryViews.keys.toList().forEach(::scheduleBatteryUpdate)
    }

    private fun findBatteryDevice(owner: Any?): BluetoothDevice? =
        owner?.let { runCatching { batterySymbols.field("deviceField").get(it) as? BluetoothDevice }.getOrNull() }

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
            batterySymbols.method("onBatteryChangedInts").invoke(view, values[0], values[1], values[2])
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
        val ref = runCatching { batterySymbols.field("rootViewField").get(view) }.getOrNull() as? WeakReference<*>
        return ref?.get() as? View
    }

    private fun updateFragments() {
        headsetFragments.keys.toList().forEach(::scheduleFragmentUpdate)
    }

    private fun injectFragmentStatus(fragment: Any?) {
        runCatching {
            val device = runCatching { fragmentSymbols.field("deviceField").get(fragment) as? BluetoothDevice }.getOrNull()
            if (!isDeviceConnected(device)) {
                Log.d(TAG, "injectFragmentStatus skipped (device disconnected) ${fragmentDebug(fragment)}")
                return
            }
            val payload = "${settingsAncMode()}|0100;0101;0102;0103;0200;0201|${settingsBatteryString()}|00"
            Log.d(TAG, "injectFragmentStatus payload=$payload ${fragmentDebug(fragment)}")
            fragmentSymbols.method("updateAtUiInfo").invoke(fragment, payload)
            fragmentSymbols.method("updateAncUi").invoke(fragment, settingsAncLevel(), false)
            val address = device?.address
            if (address != null) {
                val refreshPayload = settingsRefreshPayload(device)
                Log.d(TAG, "injectFragmentStatus refreshPayload=$refreshPayload address=$address")
                // Official internals post to worker handlers that may already be dead (stale
                // fragments in the map); a throw here must not skip the badge pass below.
                runCatching { fragmentSymbols.method("refreshStatus").invoke(fragment, address, refreshPayload) }
                    .onFailure { Log.w(TAG, "refreshStatus injection failed (stale fragment?)", it) }
            }
            Log.d(TAG, "fragment status injected connected=true anc=$currentAnc battery=${settingsBatteryString()}")
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
            val rootView = fragmentSymbols.field("rootViewField").get(fragment) as? View
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
            Log.d(
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
        val device = runCatching { fragmentSymbols.field("deviceField").get(fragment) as? BluetoothDevice }.getOrNull()
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
        val device = runCatching { fragmentSymbols.field("deviceField").get(fragment) as? BluetoothDevice }.getOrNull()
        fun value(symbol: String) = runCatching { fragmentSymbols.field(symbol).get(fragment) }.getOrNull()
        val deviceId = value("deviceIdField") as? String
        val support = value("supportField") as? String
        val service = value("serviceField")
        val hfp = value("bluetoothHfpField")
        val cached = value("cachedDeviceField")
        val supportAnc = value("supportAncField")
        val ancCached = value("ancCachedField") as? String
        val pendingAnc = value("pendingAncField") as? String
        val ancPendingStatus = value("ancPendingStatusField")
        return "fragment(device=${device.describe()},deviceId=$deviceId,support=$support,service=$service,hfp=$hfp,cached=$cached,supportAnc=$supportAnc,ancCached=$ancCached,pendingAnc=$pendingAnc,ancPending=$ancPendingStatus)"
    }

    private fun isSonyAddress(address: String): Boolean {
        return SonyDeviceService.isKnownSonyAddress(address) ||
            address.equals(currentAddress, ignoreCase = true)
    }

    private fun isDeviceConnected(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val rawConnected = runCatching {
            callMethod(device, "isConnected") as? Boolean
        }.getOrNull()
        if (rawConnected != null) return rawConnected
        val address = runCatching { device.address }.getOrNull() ?: return false
        return isSonyAddress(address) && isConnectedState
    }

    private fun settingsSupport(address: String): String = MiuiHeadsetSupport.encode(address)

    private fun settingsBatteryString(): String {
        return settingsBatteryValues().joinToString(",")
    }

    /** Maps the current snapshot into the three-slot MIUI encoding.
     *  Over-ear headphones expose a single value; it lands in the right slot and the
     *  left/case slots stay "not present" (255), while those slot views are hidden. */
    private fun settingsBatteryValues(): List<Int> {
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
        return when (currentAnc) {
            2, 5, 6, 7, 8 -> "1"
            3 -> "2"
            else -> "0"
        }
    }

    private fun settingsAncLevel(): String {
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

    private fun settingsRefreshPayload(device: BluetoothDevice? = null): String {
        val connected = isDeviceConnected(device)
        val battery = if (connected) settingsBatteryString().split(",") else listOf("255", "255", "255")
        val left = battery.getOrNull(0).orEmpty()
        val right = battery.getOrNull(1).orEmpty()
        val box = battery.getOrNull(2).orEmpty()
        val values = MutableList(16) { "" }
        values[0] = left
        values[1] = right
        values[2] = box
        // Slot 3 carries the firmware as "<code>+<display>"; MiuiHeadsetFragment.refreshStatus
        // routes a non-empty value to updateStatus(), which fills versionName.
        // Gated on isDeviceConnected so firmware version is only populated when connected.
        values[3] = if (connected) {
            currentFirmware?.takeIf { it.isNotBlank() }?.let { "0+$it" }.orEmpty()
        } else {
            ""
        }
        values[7] = if (connected) settingsAncLevel() else "0000"
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
            // Sound-quality badge inputs. They have no other persisted home, so without
            // these a cold Settings process paints the version and the battery from prefs
            // but no badge, and the badge then appears on its own a moment later.
            .putString("codec", currentCodec?.name)
            .putString("dsee_generation", currentDseeGeneration?.name)
            .putBoolean("dsee_active", currentDseeActive)
            .putString("lea_streaming_left", currentLeaStreamingL)
            .putString("lea_streaming_right", currentLeaStreamingR)
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
        // Badge inputs are restored here rather than next to the ANC/battery blocks: they
        // have no live value before the first snapshot, and those blocks are behind early
        // returns a live ANC or battery state can trigger. Live snapshot values still win.
        if (!hasLiveSnapshot) {
            currentCodec = prefs.getString("codec", null)?.let { name ->
                runCatching { SoundQualityCodec.valueOf(name) }.getOrNull()
            }
            currentDseeGeneration = prefs.getString("dsee_generation", null)?.let { name ->
                runCatching { DseeGeneration.valueOf(name) }.getOrNull()
            }
            currentDseeActive = prefs.getBoolean("dsee_active", currentDseeActive)
            currentLeaStreamingL = prefs.getString("lea_streaming_left", null)
            currentLeaStreamingR = prefs.getString("lea_streaming_right", null)
        }
        // Live snapshot data wins; persisted ANC/voice are only a bootstrap until the
        // first live state arrives. Overwriting here reverted the UI after every tap
        // (vocal-enhancement looked unclickable) because settingsAncLevel() calls
        // loadState() on every inject and reloaded the stale persisted value.
        if (hasAncState) return
        currentAnc = prefs.getInt("anc", currentAnc)
        currentTransparencyVocalEnhancement = prefs.getBoolean("transparency_vocal_enhancement", currentTransparencyVocalEnhancement)
        // A restored pair is a real state too: the ANC row guard may hold it against the
        // page's own repaint. The field default (off) before this point may not.
        ancStateReady = true
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
