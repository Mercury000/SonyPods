package dev.sonypods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.SharedPreferences
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.SystemClock
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.config.ConfigManager
import dev.sonypods.data.SonyHeadphoneRepository
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.utils.miuiStrongToast.MiuiStrongToastUtil
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.PodParams
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hosts the Sony Tandem engine inside the `com.android.bluetooth` process.
 *
 * Running here (rather than in the module app) is what makes the system surfaces —
 * notification, focus island, system bluetooth settings, fusion device center —
 * keep working when the module app process is gone: the bluetooth process is always
 * alive, owns the classic/BLE stack, and can inject battery into it directly.
 *
 * Responsibilities:
 *  - own the [SonyHeadphoneRepository] singleton for this process,
 *  - accept [SonyBridge.ACTION_COMMAND] broadcasts from every consumer,
 *  - broadcast [SonyStateSnapshot] to consumers whenever the state changes,
 *  - inject the aggregated battery level into the system bluetooth stack.
 */
object SonyEngineHost {
    private const val TAG = "SonyPods-Engine"
    private const val STARTUP_ANNOUNCE_COUNT = 10
    private const val STARTUP_ANNOUNCE_INTERVAL_MS = 3_000L
    private const val RECONCILE_INTERVAL_MS = 15_000L
    private const val CONNECT_COOLDOWN_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var repository: SonyHeadphoneRepository? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var adapterService: Any? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    private var started = false
    private var lastSnapshot: SonyStateSnapshot? = null
    private var lastRenderedBattery: BatteryParams? = null
    private var lastRenderedAddress: String? = null
    private var lastConnectAttemptMs = 0L

    /** Address + which sides report; the connect animation replays when this changes. */
    private var lastConnectAnimationKey: String? = null

    @Volatile
    private var a2dpProxy: BluetoothProfile? = null

    fun start(context: Context, adapterService: Any?, prefs: SharedPreferences? = null) {
        adapterService?.let { this.adapterService = it }
        prefs?.let { this.prefs = it }
        if (started) return
        val ctx = context.applicationContext ?: context
        appContext = ctx
        started = true

        // The engine reads its model-image catalog from our own assets, which the
        // bluetooth app's context cannot see.
        val moduleContext = runCatching {
            ctx.createPackageContext("com.mercury.sonypods", Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull()
        val repo = SonyHeadphoneRepository.getInstance(moduleContext ?: ctx, ctx)
        repository = repo

        registerCommandReceiver(ctx)
        registerConfigReceiver(ctx)

        scope.launch {
            repo.state.collect { uiState ->
                val snapshot = SonyStateSnapshot.fromUiState(uiState)
                if (snapshot != lastSnapshot) {
                    lastSnapshot = snapshot
                    publish(ctx, snapshot)
                }
            }
        }
        // Consumers that registered before the engine existed have nothing to show
        // and their replay requests were lost; announce ourselves for a while.
        scope.launch {
            repeat(STARTUP_ANNOUNCE_COUNT) {
                publish(ctx, snapshot())
                delay(STARTUP_ANNOUNCE_INTERVAL_MS)
            }
        }

        registerUnlockReceiver(ctx)
        bindA2dpProxy(ctx)
        scope.launch {
            while (true) {
                delay(RECONCILE_INTERVAL_MS)
                runCatching { reconcileConnection() }
                    .onFailure { Log.w(TAG, "connection reconcile failed", it) }
            }
        }
        Log.d(TAG, "engine started in ${ctx.packageName} moduleContext=${moduleContext != null}")
    }

    fun onAdapterService(service: Any?) {
        if (service != null) adapterService = service
    }

    /** Audio routing changing usually means a bud joined or left; re-read state now. */
    fun refreshNow(reason: String) {
        val repo = repository ?: return
        if (!repo.state.value.deviceInfo.protocolReady) return
        Log.d(TAG, "refresh requested: $reason")
        runCatching { repo.refreshBasics() }
            .onFailure { Log.w(TAG, "refresh failed reason=$reason", it) }
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice, force: Boolean = false) {
        val repo = repository ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        val current = repo.state.value
        val alreadyLive = current.connectedDevice?.address.equals(address, ignoreCase = true) &&
            current.deviceInfo.protocolReady
        if (alreadyLive && !force) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastConnectAttemptMs < CONNECT_COOLDOWN_MS) return
        lastConnectAttemptMs = now
        val name = runCatching { device.name }.getOrNull() ?: "Sony audio device"
        Log.d(TAG, "connecting Tandem session to $name ($address)")
        repo.connect(address, name)
    }

    /**
     * Re-attaches the Tandem session when it is missing but the headphones are there.
     *
     * Needed because the session is otherwise only established on an A2DP state
     * *change*: after a reboot the headphones are usually connected again before this
     * hook can observe anything, and an attempt made while the stack is still settling
     * can fail with nothing to retry it.
     *
     * Guard: if A2DP-connected devices exist but none is a Sony device, skip silently.
     * This prevents repeated Sony SPP attempts against non-Sony headphones (e.g. EDIFIER).
     */
    @SuppressLint("MissingPermission")
    private fun reconcileConnection() {
        val repo = repository ?: return
        if (repo.state.value.deviceInfo.protocolReady) return
        val allConnected = runCatching { a2dpProxy?.connectedDevices }.getOrNull()
        if (!allConnected.isNullOrEmpty() && allConnected.none { HeadsetStateDispatcher.isSonyPod(it) }) {
            // A2DP is active, but only non-Sony devices are connected — do nothing.
            Log.d(TAG, "reconcile skipped: ${allConnected.size} non-Sony A2DP device(s) connected")
            return
        }
        val device = allConnected?.firstOrNull { HeadsetStateDispatcher.isSonyPod(it) } ?: return
        Log.d(TAG, "reconciling: ${device.address} is connected but has no Tandem session")
        connectDevice(device, force = true)
    }

    @SuppressLint("MissingPermission")
    private fun connectedSonyDevice(): BluetoothDevice? =
        runCatching {
            a2dpProxy?.connectedDevices?.firstOrNull { HeadsetStateDispatcher.isSonyPod(it) }
        }.getOrNull()

    /**
     * Everything published while the device is still locked lands in consumers that
     * cannot render it yet — our provider and resources live in credential-encrypted
     * storage. Republish once the user unlocks so the panels and notification catch up.
     */
    private fun registerUnlockReceiver(context: Context) {
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        Log.d(TAG, "user unlocked (${intent?.action}); republishing state")
                        lastRenderedAddress = null
                        lastRenderedBattery = null
                        publish(context, snapshot())
                    }
                },
                IntentFilter().apply {
                    addAction(Intent.ACTION_USER_UNLOCKED)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                Context.RECEIVER_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "unlock receiver registration failed", it) }
    }

    private fun bindA2dpProxy(context: Context) {
        runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            adapter.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dpProxy = proxy
                            Log.d(TAG, "A2DP proxy bound")
                            reconcileConnection()
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.A2DP) a2dpProxy = null
                    }
                },
                BluetoothProfile.A2DP,
            )
        }.onFailure { Log.w(TAG, "A2DP proxy bind failed", it) }
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice(device: BluetoothDevice) {
        val repo = repository ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        if (repo.state.value.connectedDevice?.address.equals(address, ignoreCase = true)) {
            Log.d(TAG, "disconnecting Tandem session from $address")
            repo.disconnect()
        }
    }

    /** Latest known state; hooks render system surfaces from this. */
    fun snapshot(): SonyStateSnapshot = lastSnapshot ?: SonyStateSnapshot()

    // ── Commands ──

    private fun registerCommandReceiver(context: Context) {
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        handleCommand(intent ?: return)
                    }
                },
                IntentFilter(SonyBridge.ACTION_COMMAND),
                Context.RECEIVER_EXPORTED,
            )
            Log.d(TAG, "command receiver registered")
        }.onFailure { Log.w(TAG, "command receiver registration failed", it) }
    }

    private var configReceiverRegistered = false

    /**
     * Apply config pushed from the app by value. The app attaches the full serialized
     * [dev.sonypods.config.AppConfig] to [SonyPodsAction.ACTION_CONFIG_CHANGED]; we apply
     * it directly to the shared [ConfigManager] cache so changes made in the app (e.g. ANC
     * cycle modes) take effect in the engine without relying on remote-preferences
     * propagation. Falls back to reading the remote prefs when no JSON payload is present
     * (older app builds).
     */
    private fun registerConfigReceiver(context: Context) {
        if (configReceiverRegistered) return
        configReceiverRegistered = true
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != SonyPodsAction.ACTION_CONFIG_CHANGED) return
                        val json = intent.getStringExtra(ConfigManager.PREF_KEY_CONFIG_JSON)
                        if (json != null) {
                            ConfigManager.applyConfigJson(json)
                        } else {
                            prefs?.let { ConfigManager.refreshFromPrefs(it) }
                        }
                    }
                },
                IntentFilter(SonyPodsAction.ACTION_CONFIG_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
            Log.d(TAG, "config push receiver registered")
        }.onFailure { Log.w(TAG, "config push receiver registration failed", it) }
    }

    private fun handleCommand(intent: Intent) {
        val repo = repository ?: return
        val command = intent.getStringExtra(SonyBridge.EXTRA_COMMAND) ?: return
        Log.d(TAG, "command=$command")
        when (command) {
            SonyBridge.CMD_SET_NOISE_CONTROL -> {
                val mode = intent.getStringExtra(SonyBridge.EXTRA_STRING)
                    ?.let { name -> NoiseControlMode.entries.firstOrNull { it.name == name } }
                    ?: return
                repo.setNoiseControlMode(mode)
            }

            SonyBridge.CMD_CYCLE_NOISE_CONTROL -> {
                val enabledNames = ConfigManager.ancCycleModes()
                val cycle = ConfigManager.ANC_CYCLE_MODE_ORDER
                    .filter { it in enabledNames }
                    .mapNotNull { name -> NoiseControlMode.entries.firstOrNull { it.name == name } }
                    .ifEmpty { listOf(NoiseControlMode.NOISE_CANCELLING, NoiseControlMode.AMBIENT_SOUND, NoiseControlMode.OFF) }
                val current = repo.state.value.noiseControlState.controlMode
                val index = cycle.indexOf(current)
                val next = if (index >= 0) cycle[(index + 1) % cycle.size] else cycle.first()
                if (next != current) repo.setNoiseControlMode(next)
            }

            SonyBridge.CMD_SET_AMBIENT_LEVEL ->
                repo.setAmbientLevel(intent.getIntExtra(SonyBridge.EXTRA_INT, 10))

            SonyBridge.CMD_SET_AMBIENT_VOICE ->
                repo.setAmbientVoiceMode(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))

            SonyBridge.CMD_SET_EQ_PRESET -> {
                val preset = intent.getStringExtra(SonyBridge.EXTRA_STRING)
                    ?.let { name -> EqPresetId.entries.firstOrNull { it.name == name } }
                    ?: return
                repo.setEqPreset(preset)
            }

            SonyBridge.CMD_SET_CLEAR_BASS ->
                repo.setClearBass(intent.getIntExtra(SonyBridge.EXTRA_INT, 0))

            SonyBridge.CMD_SET_EQ_BAND -> repo.setCustomEqBand(
                intent.getIntExtra(SonyBridge.EXTRA_INDEX, 0),
                intent.getIntExtra(SonyBridge.EXTRA_INT, 0),
            )

            SonyBridge.CMD_PLAYBACK_PREVIOUS -> repo.playbackPrevious()
            SonyBridge.CMD_PLAYBACK_PLAY_PAUSE -> repo.playbackPlayPause()
            SonyBridge.CMD_PLAYBACK_NEXT -> repo.playbackNext()

            SonyBridge.CMD_CONNECT -> {
                val address = intent.getStringExtra(SonyBridge.EXTRA_STRING) ?: return
                val name = intent.getStringExtra("device_name") ?: "Sony audio device"
                repo.connect(address, name)
            }

            SonyBridge.CMD_DISCONNECT -> repo.disconnect()

            SonyBridge.CMD_REFRESH -> if (repo.state.value.deviceInfo.protocolReady) {
                repo.refreshBasics()
            }

            SonyBridge.CMD_REPUBLISH -> appContext?.let { publish(it, snapshot()) }

            SonyBridge.CMD_SURFACES_READY -> appContext?.let {
                // Forget what we think is on screen so the island shows again.
                lastRenderedAddress = null
                lastRenderedBattery = null
                Log.d(TAG, "surfaces ready; re-rendering notification and island")
                publish(it, snapshot())
            }

            SonyBridge.CMD_DEBUG_RAW ->
                repo.runDebugAction("raw", intent.getStringExtra(SonyBridge.EXTRA_STRING))
        }
    }

    // ── State fan-out ──

    private fun publish(context: Context, snapshot: SonyStateSnapshot) {
        val bundle = snapshot.toBundle()
        SonyBridge.STATE_CONSUMERS.forEach { target ->
            runCatching {
                context.sendBroadcast(
                    Intent(SonyBridge.ACTION_STATE).apply {
                        putExtra(SonyStateSnapshot.EXTRA_SNAPSHOT, bundle)
                        setPackage(target)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    }
                )
            }.onFailure { Log.w(TAG, "state broadcast to $target failed", it) }
        }
        injectSystemBattery(context, snapshot)
        renderXiaomiSurfaces(context, snapshot)
    }

    /**
     * Drives the HyperOS notification and focus island, which are built by our hook
     * inside com.xiaomi.bluetooth. Previously the module app pushed these; doing it
     * here is what keeps them alive when the app is not running.
     */
    @SuppressLint("MissingPermission")
    private fun renderXiaomiSurfaces(context: Context, snapshot: SonyStateSnapshot) {
        val address = snapshot.deviceAddress
        if (address == null || !snapshot.connected) {
            val previous = lastRenderedAddress ?: return
            lastRenderedAddress = null
            lastRenderedBattery = null
            lastConnectAnimationKey = null
            remoteDevice(context, previous)?.let {
                runCatching { MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt(context, it) }
            }
            return
        }

        // Sony reports 0 for a bud that is not in place rather than omitting it, which
        // would otherwise be rendered as a real "0%".
        fun pod(level: Int?) = level?.takeIf { it > 0 }?.let { PodParams(battery = it, isConnected = true) }
        val battery = BatteryParams(
            left = pod(snapshot.batteryLeft ?: snapshot.batterySingle),
            right = pod(snapshot.batteryRight),
            case = pod(snapshot.batteryCradle),
        )
        if (battery == lastRenderedBattery && address == lastRenderedAddress) return

        // HyperOS replays the connect animation whenever the wear composition changes —
        // a bud going in or out — but not for plain battery drift. Keying on which sides
        // report reproduces that, and skips the empty state before any level arrived.
        val presence = "$address|${battery.left != null}|${battery.right != null}"
        val playAnimation = battery.left != null || battery.right != null
        val isNewDevice = playAnimation && presence != lastConnectAnimationKey
        if (isNewDevice) lastConnectAnimationKey = presence
        lastRenderedBattery = battery
        lastRenderedAddress = address

        val device = remoteDevice(context, address) ?: return
        runCatching {
            MiuiStrongToastUtil.showPodsNotificationByMiuiBt(
                context = context,
                batteryParams = battery,
                device = device,
                sourceColor = snapshot.modelImageSourceColor,
            )
            // The island is an arrival animation: only on a fresh connection, not on
            // every battery tick.
            if (isNewDevice) {
                MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(
                    context = context,
                    batteryParams = battery,
                    device = device,
                    // Headband models report one level; the connect animation has a
                    // dedicated single-battery variant for them.
                    singleBattery = snapshot.batterySingle != null && snapshot.batteryLeft == null,
                )
            }
            Log.d(TAG, "xiaomi surfaces updated address=$address newDevice=$isNewDevice")
        }.onFailure { Log.w(TAG, "xiaomi surface render failed", it) }
    }

    @SuppressLint("MissingPermission")
    private fun remoteDevice(context: Context, address: String) = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
    }.getOrNull()

    /** Feeds the system bluetooth stack so stock UI shows headphone battery. */
    @SuppressLint("MissingPermission")
    private fun injectSystemBattery(context: Context, snapshot: SonyStateSnapshot) {
        val address = snapshot.deviceAddress ?: return
        val level = snapshot.systemBatteryLevel ?: return
        val service = adapterService ?: return
        val device = remoteDevice(context, address) ?: return
        runCatching {
            callMethod(service, "setBatteryLevel", device, level, false)
            Log.d(TAG, "battery injected level=$level address=$address")
        }.onFailure { Log.w(TAG, "setBatteryLevel failed level=$level", it) }
    }
}
