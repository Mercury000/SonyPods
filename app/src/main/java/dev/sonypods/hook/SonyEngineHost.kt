package dev.sonypods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.data.SonyHeadphoneRepository
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.utils.miuiStrongToast.MiuiStrongToastUtil
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.PodParams
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var repository: SonyHeadphoneRepository? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var adapterService: Any? = null

    private var started = false
    private var lastSnapshot: SonyStateSnapshot? = null
    private var lastRenderedBattery: BatteryParams? = null
    private var lastRenderedAddress: String? = null

    fun start(context: Context, adapterService: Any?) {
        adapterService?.let { this.adapterService = it }
        if (started) return
        val ctx = context.applicationContext ?: context
        appContext = ctx
        started = true

        // The engine reads its model-image catalog from our own assets, which the
        // bluetooth app's context cannot see.
        val moduleContext = runCatching {
            ctx.createPackageContext("dev.sonypods", Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull()
        val repo = SonyHeadphoneRepository.getInstance(moduleContext ?: ctx, ctx)
        repository = repo

        registerCommandReceiver(ctx)

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
        Log.i(TAG, "engine started in ${ctx.packageName} moduleContext=${moduleContext != null}")
    }

    fun onAdapterService(service: Any?) {
        if (service != null) adapterService = service
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice) {
        val repo = repository ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        if (repo.state.value.connectedDevice?.address.equals(address, ignoreCase = true)) return
        val name = runCatching { device.name }.getOrNull() ?: "Sony audio device"
        Log.i(TAG, "connecting Tandem session to $name ($address)")
        repo.connect(address, name)
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice(device: BluetoothDevice) {
        val repo = repository ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        if (repo.state.value.connectedDevice?.address.equals(address, ignoreCase = true)) {
            Log.i(TAG, "disconnecting Tandem session from $address")
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
            Log.i(TAG, "command receiver registered")
        }.onFailure { Log.w(TAG, "command receiver registration failed", it) }
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
                val next = when (repo.state.value.noiseControlState.controlMode) {
                    NoiseControlMode.NOISE_CANCELLING -> NoiseControlMode.AMBIENT_SOUND
                    NoiseControlMode.AMBIENT_SOUND -> NoiseControlMode.OFF
                    else -> NoiseControlMode.NOISE_CANCELLING
                }
                repo.setNoiseControlMode(next)
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
            remoteDevice(context, previous)?.let {
                runCatching { MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt(context, it) }
            }
            return
        }

        val battery = BatteryParams(
            left = (snapshot.batteryLeft ?: snapshot.batterySingle)
                ?.let { PodParams(battery = it, isConnected = true) },
            right = snapshot.batteryRight?.let { PodParams(battery = it, isConnected = true) },
            case = snapshot.batteryCradle?.let { PodParams(battery = it, isConnected = true) },
        )
        if (battery == lastRenderedBattery && address == lastRenderedAddress) return
        val isNewDevice = address != lastRenderedAddress
        lastRenderedBattery = battery
        lastRenderedAddress = address

        val device = remoteDevice(context, address) ?: return
        runCatching {
            MiuiStrongToastUtil.showPodsNotificationByMiuiBt(context, battery, device)
            // The island is an arrival animation: only on a fresh connection, not on
            // every battery tick.
            if (isNewDevice) {
                MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(context, battery, device)
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
