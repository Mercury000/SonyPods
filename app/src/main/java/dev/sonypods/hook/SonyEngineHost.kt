package dev.sonypods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.SharedPreferences
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.SystemClock
import android.os.IBinder
import android.os.RemoteException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.config.ConfigManager
import dev.sonypods.data.SonyHeadphoneRepository
import dev.sonypods.device.SonyDeviceService
import dev.sonypods.device.UnifiedDeviceIdentityService
import java.io.File
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NoiseAdaptiveSensitivity
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.GestureNoiseControlMode
import dev.sonypods.utils.miuiStrongToast.MiuiStrongToastUtil
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.PodParams
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import dev.sonypods.headphones.HeadphoneFormFactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

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
    /** Probe-cache persistence file in the host process's own data directory. */
    private const val CAPABILITY_CACHE_FILE = "sonypods_capability_cache.json"
    /** Audio Stream Control Service: only an LE Audio identity carries it. */
    private val ASCS_SERVICE_UUID: java.util.UUID =
        java.util.UUID.fromString("0000184E-0000-1000-8000-00805F9B34FB")
    /**
     * `BluetoothProfile.HID_HOST`, which is `@SystemApi` and so absent from the compile SDK.
     *
     * HyperOS's `LeAudioProfile` trades this against LE Audio alongside A2DP and HFP — AOSP's
     * `Utils.setLeAudioEnabled` handles only the two audio profiles.
     */
    private const val PROFILE_HID_HOST = 4
    private const val STARTUP_ANNOUNCE_COUNT = 10
    private const val STARTUP_ANNOUNCE_INTERVAL_MS = 3_000L
    private const val RECONCILE_INTERVAL_MS = 15_000L
    private const val CONNECT_COOLDOWN_MS = 10_000L
    private const val CONNECT_IN_FLIGHT_TIMEOUT_MS = 15_000L
    /**
     * `BluetoothProfile.CONNECTION_POLICY_*`, which are `@SystemApi` and so absent from the
     * compile SDK. The values are the ones the stack stores and compares against.
     */
    private const val CONNECTION_POLICY_ALLOWED = 100
    private const val CONNECTION_POLICY_FORBIDDEN = 0
    /** How often a missing LE Audio identity is looked for in the bond list. */
    private const val LE_IDENTITY_RESCAN_MS = 5_000L
    /** `LeAudioService.LE_AUDIO_GROUP_ID_INVALID`: what `getActiveGroupId` answers with no route. */
    private const val LE_AUDIO_GROUP_ID_INVALID = -1
    /** HyperOS's own wait between forbidding LE Audio and restoring the classic profiles. */
    private const val CLASSIC_RESTORE_DELAY_MS = 500L
    /** The profile service's own key for this codec in its per-device user-preference store. */
    private const val LDAC_CODEC_NAME = "LDAC"
    /**
     * How long the LDAC row is held after a write.
     *
     * The codec is renegotiated asynchronously and the stack's own auto-off check runs 1.3 s after
     * the change, so the reading is not final before then; the system's own switch disables its
     * checkbox for the same 3 s.
     */
    private const val LDAC_SETTLE_MS = 3_000L

    // SupervisorJob only stops sibling cancellation; an exception escaping a launch still reaches
    // the thread's default handler, and in com.android.bluetooth that takes the stack down.
    private val coroutineCrashGuard = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "engine coroutine crashed", error)
    }

    private fun newGenerationScope() =
        CoroutineScope(SupervisorJob() + Dispatchers.Main + coroutineCrashGuard)
    private var scope = newGenerationScope()

    @Volatile
    private var repository: SonyHeadphoneRepository? = null

    @Volatile
    private var cloudFallback: HookCloudModelFallback? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var adapterService: Any? = null

    /** Throttle for the bond enumeration behind [leAudioIdentityAddress]. */
    private var lastLeIdentityRescanMs = 0L

    /** Last logged LE Audio policy reading, so the per-emission read logs only on change. */
    private var lastLeAudioPolicyLog: String? = null

    /** The LDAC position asked for, held until [ldacWriteSettlesAtMs] so the row cannot bounce. */
    private var ldacWriteTarget: Boolean? = null
    private var ldacWriteSettlesAtMs = 0L

    /** Whether the missing-AdapterService warning has already been emitted this generation. */
    private var adapterServiceWarned = false

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Source of the framework-backed remote-preference store. Re-invoking it always
     * returns a store reflecting the latest data the LSPosed framework has persisted,
     * so we prefer this over the single [prefs] captured at package-load (which can race
     * the remote-prefs bridge and come back empty). See [currentPrefs].
     */
    @Volatile
    private var prefsProvider: (() -> SharedPreferences)? = null

    private var started = false
    private var lastSnapshot: SonyStateSnapshot? = null
    private var lastRenderedBattery: BatteryParams? = null
    private var lastRenderedAddress: String? = null
    /** Last device reported connected by Tandem, including while its transport is down. */
    private var lastConnectedAddress: String? = null
    private var lastConnectAttemptMs = 0L
    /** Prevent startAfterReload and the A2DP proxy callback from opening two sessions. */
    private var connectInFlightAddress: String? = null
    /** A2DP has explicitly gone down; do not classify its trailing Tandem callback as recovery. */
    private var physicalDisconnectAddress: String? = null
    /** Snapshot retained for the rare path where a reload request is rejected after shutdown. */
    private var preparedReloadAddress: String? = null
    private var preparedReloadPhysicalDisconnectAddress: String? = null

    /**
     * Sound Connect holds this lease while it owns the headphone control session.
     * The lease is backed by a Binder token from the official app process, so a
     * process death also releases it without any polling.
     */
    @Volatile
    private var officialAppOwnsTandem = false
    private var officialAppLeaseId: String? = null
    private var officialAppLeaseToken: IBinder? = null
    private var officialAppDeathRecipient: IBinder.DeathRecipient? = null
    /** Address saved before Sound Connect disconnects the repository session. */
    private var officialAppLeaseAddress: String? = null

    /** Address + which sides report; the connect animation replays when this changes. */
    private var lastConnectAnimationKey: String? = null

    /**
     * Pending LE Audio enable waiting for classic profiles to actually disconnect.
     *
     * When [setLeAudioPolicy] is called with `allowed=true`, it first disables A2DP/HFP/HID and
     * stores the LE Audio enable continuation here.  [HeadsetStateDispatcher] invokes
     * [onClassicProfileDisconnected] when the A2DP state machine reports STATE_DISCONNECTED for a
     * Sony device, which fires this callback.  This ensures the stack has finished tearing down
     * classic GATT connections before the LE Audio GATT layer sets up — preventing a race where
     * a stale CLCB function pointer causes a SIGBUS in `bta_gattc_cfg_mtu_cmpl`.
     */
    @Volatile
    private var pendingLeAudioEnable: (() -> Unit)? = null
    /**
     * A Tandem transport recovery is not a user-visible new connection.  Keep this
     * separate from the screen lifecycle: GATT/SPP can fail for any reason while the
     * A2DP link remains alive.
     */
    private var transportRecoveryAddress: String? = null

    private var commandReceiver: BroadcastReceiver? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var remotePreferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var remotePreferenceStore: SharedPreferences? = null
    private var a2dpContext: Context? = null
    private var a2dpListener: BluetoothProfile.ServiceListener? = null

    @Volatile
    private var a2dpProxy: BluetoothProfile? = null

    @Synchronized
    fun start(
        context: Context,
        adapterService: Any?,
        prefsProvider: (() -> SharedPreferences)? = null,
        remoteModelInfoReader: (() -> String?)? = null,
        remoteFileReader: ((String) -> ByteArray?)? = null,
    ) {
        adapterService?.let { this.adapterService = it }
        prefsProvider?.let { this.prefsProvider = it }
        // Keep a snapshot of the store for the rare code paths that need a value without
        // re-fetching; the cycle command and the deferred re-read prefer currentPrefs().
        this.prefs = prefsProvider?.invoke()
        if (started) {
            // A receiver registration can fail transiently while the system
            // process is still starting. Retry only the idempotent bindings on
            // later AdapterService/A2DP callbacks.
            appContext?.let {
                registerCommandReceiver(it)
                registerUnlockReceiver(it)
            }
            registerRemoteConfigListener()
            if (a2dpListener == null) bindA2dpProxy(appContext ?: context)
            return
        }
        if (scope.coroutineContext[Job]?.isActive != true) scope = newGenerationScope()
        val ctx = context.applicationContext ?: context
        appContext = ctx
        started = true

        // The engine cannot read module-app SharedPreferences or private files. The
        // catalog reader is backed by Remote Files, with a host-local network fallback
        // when the module process has not yet been allowed to publish them.
        val fallback = remoteFileReader?.let { reader ->
            HookCloudModelFallback(
                context = ctx,
                remoteFileReader = reader,
                onCatalogReady = { repository?.refreshModelImageCatalog() },
                onImageReady = { address ->
                    SonyBridge.imageReady(ctx, address)
                },
            )
        }
        cloudFallback = fallback
        val catalogReader = fallback?.let { { it.catalogReader() } } ?: remoteModelInfoReader
        val repo = SonyHeadphoneRepository.getInstance(
            ctx,
            ctx,
            catalogReader,
            debugLogForwarder = { line ->
                if (ConfigManager.logLevel() == ConfigManager.LOG_LEVEL_DEBUG) {
                    runCatching {
                        ctx.sendBroadcast(
                            Intent(SonyBridge.ACTION_DEBUG_LOG).apply {
                                putExtra(SonyBridge.EXTRA_STRING, line)
                                setPackage("com.mercury.sonypods")
                                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                            }
                        )
                    }.onFailure { Log.w(TAG, "debug log broadcast to app failed", it) }
                }
            },
        )
        catalogReader?.let { reader -> repo.attachModelInfoReader(reader) }
        repo.attachModelCatalogFallback { modelName, _, _ ->
            fallback?.ensureCatalogFor(modelName)
        }
        repository = repo
        // The probe cache is consumed only by this process's repository, so it
        // persists into THIS process's data directory — no app-process detour, no
        // waking the module app for every probe write. A local file survives scope
        // restarts just as well as the former shared-store round trip did.
        val capabilityCacheFile = File(ctx.filesDir, CAPABILITY_CACHE_FILE)
        runCatching {
            if (capabilityCacheFile.isFile) repo.installCapabilityCache(capabilityCacheFile.readText())
        }.onFailure { Log.w(TAG, "capability cache restore failed", it) }
        repo.attachCapabilityCacheWriter { json ->
            runCatching { capabilityCacheFile.writeText(json) }
                .onFailure { Log.w(TAG, "capability cache persist failed", it) }
        }

        registerCommandReceiver(ctx)
        announceEngineReadyToOfficialApp(ctx)

        scope.launch {
            repo.state.collect { uiState ->
                val snapshot = withSystemFacts(SonyStateSnapshot.fromUiState(uiState))
                val pendingAlert = snapshot.multipoint.pendingAlertMessageType
                val lastPending = lastSnapshot?.multipoint?.pendingAlertMessageType
                val same = snapshot == lastSnapshot
                android.util.Log.i("OpenBuds", "engine collect pendingAlert=$pendingAlert lastPending=$lastPending same=$same")
                if (snapshot != lastSnapshot) {
                    lastSnapshot = snapshot
                    fallback?.onState(snapshot)
                    repo.ensureModelImageCatalogIfNeeded()
                    android.util.Log.i("OpenBuds", "engine publish pendingAlert=$pendingAlert")
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
        // Deferred config re-read. The LSPosed remote-prefs bridge may not be ready at
        // package-load time, so the init read (HookEntry -> ConfigManager.attachStore)
        // can come back empty and leave cachedConfig at its default (ANC cycle includes
        // OFF). Re-read a couple of times shortly after start so the persisted cycle
        // config is picked up even after a scope restart with the module app never
        // opened. Reads are harmless: the hook-side store is read-only, so this can
        // never clobber the user's config.
        scope.launch {
            for (delayMs in listOf(3_000L, 8_000L)) {
                delay(delayMs)
                runCatching {
                    currentPrefs()?.let { ConfigManager.refreshFromPrefs(it) }
                    Log.d(TAG, "deferred config re-read done; ancCycleModes=${ConfigManager.ancCycleModes()}")
                }.onFailure { Log.w(TAG, "deferred config re-read failed", it) }
            }
        }
        // Native remote-preference change listener (canonical libxposed pattern, see
        // libxposed/example ModuleMainKt). The framework notifies us whenever the app writes
        // to the shared remote-preference store, so we refresh cachedConfig from the live
        // store without relying on a custom broadcast. The hook-side store is read-only, but
        // registering a listener is a read operation and is explicitly supported. This keeps
        // the engine's config in sync with the app even while the module app is backgrounded.
        registerRemoteConfigListener()
        Log.d(TAG, "engine started in ${ctx.packageName} cloudModelInfoRemoteFile=${remoteModelInfoReader != null}")
    }

    /** Idempotent generation teardown. Bluetooth transports never cross a hot reload. */
    @Synchronized
    fun shutdown() {
        if (!started && repository == null && commandReceiver == null) return
        started = false
        pendingLeAudioEnable = null
        val oldScope = scope
        scope = newGenerationScope()
        oldScope.cancel()
        val ctx = appContext
        listOf(commandReceiver, unlockReceiver)
            .filterNotNull()
            .forEach { receiver -> ctx?.let { runCatching { it.unregisterReceiver(receiver) } } }
        commandReceiver = null
        unlockReceiver = null

        val prefsStore = remotePreferenceStore ?: currentPrefs()
        remotePreferenceListener?.let { listener ->
            prefsStore?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(listener) } }
        }
        remotePreferenceListener = null
        remotePreferenceStore = null
        remoteConfigListenerRegistered = false

        val repo = repository
        repository = null
        runCatching { repo?.close() }
        cloudFallback?.close()
        cloudFallback = null

        val proxy = a2dpProxy
        a2dpProxy = null
        val adapter = a2dpContext?.getSystemService(BluetoothManager::class.java)?.adapter
        if (proxy != null && adapter != null) runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
        a2dpContext = null
        a2dpListener = null

        officialAppDeathRecipient?.let { recipient -> officialAppLeaseToken?.let { token -> runCatching { token.unlinkToDeath(recipient, 0) } } }
        officialAppDeathRecipient = null
        officialAppLeaseToken = null
        officialAppLeaseId = null
        officialAppOwnsTandem = false
        officialAppLeaseAddress = null
        appContext = null
        adapterService = null
        adapterServiceWarned = false
        lastLeAudioPolicyLog = null
        ldacWriteTarget = null
        ldacWriteSettlesAtMs = 0L
        prefs = null
        prefsProvider = null
        // Keep connection identity across a rejected reload. A replacement
        // classloader receives the same information through GenerationRuntime's
        // Bundle; clearing it here makes the first Tandem=false snapshot look like
        // a new connection while A2DP is still alive.
        lastSnapshot = null
        connectInFlightAddress = null
        Log.d(TAG, "engine generation shut down")
    }

    @Volatile
    private var remoteConfigListenerRegistered = false

    private fun registerRemoteConfigListener() {
        if (remoteConfigListenerRegistered) return
        val p = currentPrefs() ?: return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            runCatching {
                currentPrefs()?.let { ConfigManager.refreshFromPrefs(it) }
                Log.d(TAG, "remote config changed; refreshed; ancCycleModes=${ConfigManager.ancCycleModes()}")
            }.onFailure { Log.w(TAG, "remote config change refresh failed", it) }
        }
        runCatching {
            p.registerOnSharedPreferenceChangeListener(listener)
            remotePreferenceListener = listener
            remotePreferenceStore = p
            remoteConfigListenerRegistered = true
            Log.d(TAG, "remote config change listener registered")
        }.onFailure { Log.w(TAG, "remote config change listener registration failed", it) }
    }

    fun onAdapterService(service: Any?) {
        if (service != null) adapterService = service
    }

    /**
     * The live `AdapterService`, recovered from the class when this generation has not been handed
     * one.
     *
     * `AdapterService.onCreate` is where the instance normally arrives, and it does not fire again
     * in a live bluetooth process — so a hot-reloaded generation, whose fields all start empty, has
     * to find the singleton itself or every call that needs the stack silently turns into a no-op.
     * This stack keeps it in the public static `sAdapterService` field; builds that predate that
     * expose a static `getAdapterService()` instead.
     */
    private fun adapterService(): Any? {
        adapterService?.let { return it }
        val loader = appContext?.classLoader ?: return null
        val resolved = runCatching {
            val clazz = loader.loadClass("com.android.bluetooth.btservice.AdapterService")
            runCatching {
                clazz.getDeclaredField("sAdapterService").apply { isAccessible = true }.get(null)
            }.getOrNull()
                ?: clazz.getDeclaredMethod("getAdapterService").apply { isAccessible = true }.invoke(null)
        }.getOrNull()
        if (resolved == null) {
            // Once: this sits on the state-emission path and a missing singleton does not fix
            // itself within a generation.
            if (!adapterServiceWarned) {
                adapterServiceWarned = true
                Log.w(TAG, "AdapterService singleton not resolvable")
            }
        } else {
            adapterService = resolved
            Log.i(TAG, "AdapterService singleton recovered from class")
        }
        return resolved
    }

    /** Audio routing changing usually means a bud joined or left; re-read state now. */
    fun refreshNow(reason: String) {
        if (officialAppOwnsTandem) {
            Log.d(TAG, "refresh skipped while Sound Connect owns Tandem reason=$reason")
            return
        }
        val repo = repository ?: return
        if (!repo.state.value.deviceInfo.protocolReady || !repo.hasLiveTransport()) {
            runCatching { reconcileConnection() }
                .onFailure { Log.w(TAG, "refresh reconnect failed reason=$reason", it) }
            return
        }
        Log.d(TAG, "refresh requested: $reason")
        runCatching { repo.refreshBasics() }
            .onFailure { Log.w(TAG, "refresh failed reason=$reason", it) }
    }

    /**
     * Re-reads the stack's own LE Audio facts and pushes them out.
     *
     * Publishing is driven by the repository's state changing, and none of what
     * [withLeAudioPolicy] adds lives there: the LE Audio profile connecting, or its group becoming
     * the audio route, moves nothing the collector watches. So the surfaces would keep showing the
     * reading taken at the last headset event — the switch summary in particular sticking on
     * "waiting for the system to establish LC3" long after it had. The LE Audio hooks call this on
     * every state and active-device change.
     */
    fun republishLeAudioState(reason: String) {
        val context = appContext ?: return
        val base = lastSnapshot ?: return
        val updated = withSystemFacts(base)
        if (updated == base) return
        lastSnapshot = updated
        cloudFallback?.onState(updated)
        publish(context, updated)
        Log.d(
            TAG,
            "LE Audio state republished reason=$reason connected=${updated.leAudioSystemConnected} " +
                "active=${updated.leAudioSystemActive}"
        )
    }

    @SuppressLint("MissingPermission")
    fun connectDevice(device: BluetoothDevice, force: Boolean = false) {
        if (officialAppOwnsTandem) {
            Log.d(TAG, "connect skipped while Sound Connect owns Tandem")
            return
        }
        val repo = repository ?: return
        val address = runCatching { device.address }.getOrNull() ?: return
        val current = repo.state.value
        val explicitPhysicalDisconnect = physicalDisconnectAddress
            ?.equals(address, ignoreCase = true) == true
        // Keep the terminal physical marker until the connected snapshot reaches
        // renderXiaomiSurfaces(). That renderer must see it to reset the old
        // animation identity for a genuine same-address A2DP reconnect.
        val sameKnownConnection = current.connectedDevice?.address.equals(address, ignoreCase = true) ||
            lastConnectedAddress?.equals(address, ignoreCase = true) == true ||
            lastRenderedAddress?.equals(address, ignoreCase = true) == true
        if (!explicitPhysicalDisconnect && sameKnownConnection &&
            !repo.hasLiveTransport()
        ) {
            // The A2DP device is still the same, but the Tandem session is gone.
            // Reconnecting this session must not replay the user-facing connection UI.
            // The repository clears connectedDevice as soon as GATT/SPP reports a
            // transport loss, so the host's last-known address is part of this test.
            markTransportRecovery(address)
        }
        val alreadyLive = current.connectedDevice?.address.equals(address, ignoreCase = true) &&
            current.deviceInfo.protocolReady &&
            repo.hasLiveTransport()
        val now = SystemClock.elapsedRealtime()
        if (alreadyLive) {
            connectInFlightAddress = null
            if (!force) return
        }
        val sameAttemptInFlight = !force &&
            connectInFlightAddress?.equals(address, ignoreCase = true) == true &&
            now - lastConnectAttemptMs < CONNECT_IN_FLIGHT_TIMEOUT_MS
        if (sameAttemptInFlight) return
        if (!force && now - lastConnectAttemptMs < CONNECT_COOLDOWN_MS) return
        lastConnectAttemptMs = now
        connectInFlightAddress = address
        val name = runCatching { device.name }.getOrNull() ?: "Sony audio device"
        Log.d(TAG, "connecting Tandem session to $name ($address)")
        runCatching { repo.connect(address, name) }
            .onFailure {
                if (connectInFlightAddress.equals(address, ignoreCase = true)) {
                    connectInFlightAddress = null
                }
                Log.w(TAG, "Tandem connect request failed address=$address", it)
            }
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
        if (officialAppOwnsTandem) {
            Log.d(TAG, "reconcile skipped while Sound Connect owns Tandem")
            return
        }
        val repo = repository ?: return
        if (repo.state.value.deviceInfo.protocolReady && repo.hasLiveTransport()) return
        val allConnected = runCatching { a2dpProxy?.connectedDevices }.getOrNull()
        if (!allConnected.isNullOrEmpty() && allConnected.none { HeadsetStateDispatcher.isSonyPod(it) }) {
            // A2DP is active, but only non-Sony devices are connected — do nothing.
            Log.d(TAG, "reconcile skipped: ${allConnected.size} non-Sony A2DP device(s) connected")
            return
        }
        val device = allConnected?.firstOrNull { HeadsetStateDispatcher.isSonyPod(it) }
            ?: gattConnectedSonyDevice()
            ?: return
        Log.d(TAG, "reconciling: ${device.address} is connected but has no Tandem session")
        connectDevice(device, force = true)
    }

    /**
     * LE Audio hand-over drops A2DP entirely, so right after the system toggles 低功耗音频
     * the A2DP lookup above finds nothing while the headset is still connected — over the
     * LE ACL. The Tandem session then rides that same ACL.
     *
     * The returned device must be the identity that carries Tandem: SonyBleClient.connect
     * deliberately keeps the requested address when it is LE-Audio-connected (mirroring
     * Sound Connect's pairing-record identifier), so handing it the pure-LE identity would
     * open a session that can never carry control. Prefer a connected non-LE identity,
     * fold an LE-only one through the bonded alias table, and skip when unresolvable.
     */
    private fun gattConnectedSonyDevice(): BluetoothDevice? {
        val context = appContext ?: return null
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        val connected = runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }
            .getOrNull()
            .orEmpty()
        val sony = connected.filter { HeadsetStateDispatcher.isSonyPod(it) }
        if (sony.isEmpty()) return null
        SonyDeviceService.linkLeAudioIdentities(adapter.bondedDevices.orEmpty())
        sony.firstOrNull { candidate ->
            !UnifiedDeviceIdentityService.isLeAudioIdentity(candidate.address) &&
                UnifiedDeviceIdentityService.resolveControlAddress(candidate.address)
                    .equals(candidate.address, ignoreCase = true)
        }?.let { return it }
        val leIdentity = sony.first()
        val control = UnifiedDeviceIdentityService.resolveControlAddress(leIdentity.address)
            .takeIf { !it.equals(leIdentity.address, ignoreCase = true) }
            ?: run {
                Log.d(TAG, "reconcile skip: only LE identity ${leIdentity.address} connected and no control alias")
                return null
            }
        return runCatching { adapter.getRemoteDevice(control) }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun connectedSonyDevice(): BluetoothDevice? =
        runCatching {
            a2dpProxy?.connectedDevices?.firstOrNull { HeadsetStateDispatcher.isSonyPod(it) }
        }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun knownSonyAddress(): String? =
        connectedSonyDevice()?.let { runCatching { it.address }.getOrNull() }
            ?: repository?.state?.value?.connectedDevice?.address
            ?: lastSnapshot?.deviceAddress
            ?: lastRenderedAddress

    /** Address to carry across a hot reload, including a Tandem-only loss. */
    fun reloadDeviceAddress(): String? = (knownSonyAddress() ?: preparedReloadAddress).also {
        preparedReloadAddress = it
    }

    /** Terminal A2DP disconnect marker to carry across a reload racing teardown. */
    fun reloadPhysicalDisconnectAddress(): String? =
        (physicalDisconnectAddress ?: preparedReloadPhysicalDisconnectAddress).also {
            preparedReloadPhysicalDisconnectAddress = it
        }

    /**
     * Seeds the replacement generation before its first default repository snapshot
     * is published. A2DP surviving reload is transport recovery; a terminal A2DP
     * disconnect must instead be allowed to clear all user-visible surfaces.
     */
    fun restoreHotReloadState(address: String?, physicalDisconnect: String?) {
        preparedReloadAddress = null
        preparedReloadPhysicalDisconnectAddress = null
        val physical = physicalDisconnect?.takeIf { it.isNotBlank() }
        if (physical != null) {
            physicalDisconnectAddress = physical
            transportRecoveryAddress = null
            lastConnectedAddress = physical
            lastRenderedAddress = physical
            lastRenderedBattery = null
            lastConnectAnimationKey = physical
            return
        }
        address?.takeIf { it.isNotBlank() }?.let(::markTransportRecovery)
    }

    private fun acquireOfficialAppLease(intent: Intent) {
        val leaseId = intent.getStringExtra(SonyBridge.EXTRA_OFFICIAL_LEASE_ID) ?: run {
            Log.w(TAG, "ignored Sound Connect acquire without lease id")
            return
        }
        val token = intent.extras?.getBinder(SonyBridge.EXTRA_OFFICIAL_LEASE_TOKEN) ?: run {
            Log.w(TAG, "ignored Sound Connect acquire without Binder token")
            return
        }
        if (officialAppLeaseId == leaseId && officialAppLeaseToken == token) return

        val leaseAddress = knownSonyAddress()

        // A newer ownership lease replaces an older one without briefly reconnecting.
        clearOfficialAppLease(reconnect = false, reason = "replaced")
        val deathRecipient = IBinder.DeathRecipient {
            scope.launch {
                releaseOfficialAppLease(leaseId, "Sound Connect process died")
            }
        }
        officialAppLeaseId = leaseId
        officialAppLeaseToken = token
        officialAppDeathRecipient = deathRecipient
        officialAppOwnsTandem = true
        officialAppLeaseAddress = leaseAddress
        try {
            token.linkToDeath(deathRecipient, 0)
        } catch (_: RemoteException) {
            clearOfficialAppLease(reconnect = true, reason = "token already dead")
            return
        }

        lastConnectAttemptMs = 0L
        connectInFlightAddress = null
        leaseAddress?.let(::markTransportRecovery)
        repository?.disconnect()
        Log.d(
            TAG,
            "Sound Connect acquired Tandem lease id=$leaseId; " +
                "SonyPods disconnected handoffAddress=$leaseAddress",
        )
    }

    private fun releaseOfficialAppLease(leaseId: String, reason: String, token: IBinder? = null) {
        if (officialAppLeaseId != leaseId) {
            Log.d(TAG, "ignored stale Sound Connect release id=$leaseId current=$officialAppLeaseId")
            return
        }
        if (token != null && officialAppLeaseToken != token) {
            Log.w(TAG, "ignored Sound Connect release with mismatched Binder token id=$leaseId")
            return
        }
        clearOfficialAppLease(reconnect = true, reason = reason)
    }

    private fun clearOfficialAppLease(reconnect: Boolean, reason: String) {
        // The A2DP profile can report an empty list for a short period while the
        // official app releases its session. Keep the address captured at acquire
        // time instead of trying to discover it only after the disconnect.
        val handoffAddress = officialAppLeaseAddress ?: knownSonyAddress()
        val token = officialAppLeaseToken
        val deathRecipient = officialAppDeathRecipient
        if (token != null && deathRecipient != null) {
            runCatching { token.unlinkToDeath(deathRecipient, 0) }
        }
        val previousLeaseId = officialAppLeaseId
        officialAppLeaseId = null
        officialAppLeaseToken = null
        officialAppDeathRecipient = null
        officialAppOwnsTandem = false
        officialAppLeaseAddress = null

        if (previousLeaseId == null) return
        Log.d(TAG, "Sound Connect lease cleared id=$previousLeaseId reason=$reason reconnect=$reconnect")
        if (!reconnect) return

        if (handoffAddress.isNullOrBlank()) {
            Log.d(TAG, "Sound Connect released Tandem but no known Sony address is available")
            return
        }

        // The returned session is the same A2DP device's Tandem recovery. Reuse the
        // generic recovery marker so handoff and unexpected transport loss follow one
        // surface policy: no popup, no first island animation, no expired-island restore.
        markTransportRecovery(handoffAddress)
        lastConnectAttemptMs = 0L
        connectInFlightAddress = null
        restoreSoundConnectConnection(handoffAddress)
    }

    /**
     * Reconnect once after Sound Connect hands the control session back. A2DP can
     * be temporarily empty at the release callback, so use the address captured
     * before Sound Connect disconnected the repository session.
     */
    @SuppressLint("MissingPermission")
    private fun restoreSoundConnectConnection(address: String) {
        if (officialAppOwnsTandem) {
            Log.d(TAG, "handoff reconnect skipped: official lease reacquired address=$address")
            return
        }
        val context = appContext ?: run {
            Log.w(TAG, "cannot restore Sound Connect handoff without context address=$address")
            return
        }
        val a2dpDevice = runCatching {
            a2dpProxy?.connectedDevices?.firstOrNull {
                it.address.equals(address, ignoreCase = true)
            }
        }.getOrNull()
        val device = a2dpDevice ?: remoteDevice(context, address)
        if (device == null) {
            Log.w(TAG, "handoff reconnect skipped: cannot resolve device address=$address")
            return
        }
        val source = if (a2dpDevice != null) "a2dp" else "saved-address"
        Log.d(TAG, "handoff reconnect request source=$source address=$address")
        connectDevice(device, force = true)
    }

    /**
     * Everything published while the device is still locked lands in consumers that
     * cannot render it yet — our provider and resources live in credential-encrypted
     * storage. Republish once the user unlocks so the panels and notification catch up.
     */
    private fun registerUnlockReceiver(context: Context) {
        if (unlockReceiver != null) return
        runCatching {
            val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        Log.d(TAG, "user unlocked (${intent?.action}); republishing state")
                        lastRenderedAddress = null
                        lastRenderedBattery = null
                        publish(context, snapshot())
                    }
                }
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_USER_UNLOCKED)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                Context.RECEIVER_EXPORTED,
            )
            unlockReceiver = receiver
        }.onFailure { Log.w(TAG, "unlock receiver registration failed", it) }
    }

    private fun bindA2dpProxy(context: Context) {
        runCatching {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.A2DP) {
                            if (!started || a2dpListener !== this) {
                                runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
                                Log.d(TAG, "ignoring late A2DP proxy after engine generation ended")
                                return
                            }
                            a2dpProxy = proxy
                            Log.d(TAG, "A2DP proxy bound")
                            reconcileConnection()
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.A2DP && a2dpListener === this) a2dpProxy = null
                    }
                }
            a2dpContext = context
            a2dpListener = listener
            val requested = adapter.getProfileProxy(
                context,
                listener,
                BluetoothProfile.A2DP,
            )
            if (!requested && a2dpListener === listener) {
                a2dpListener = null
                a2dpContext = null
                Log.w(TAG, "A2DP proxy request was rejected; will retry on next engine callback")
            }
        }.onFailure { Log.w(TAG, "A2DP proxy bind failed", it) }
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice(device: BluetoothDevice, forceTeardown: Boolean = false) {
        val address = runCatching { device.address }.getOrNull() ?: return
        // A2DP dropping is expected, not terminal, once the headset moves audio to LE Audio:
        // the classic link goes away while Tandem keeps working over it on demand. Treating it
        // as a physical disconnect tore down a session that was still exchanging frames, which
        // is why the headset became uncontrollable exactly when LC3 started working.
        // The LE Audio path passes forceTeardown: its own DISCONNECTED callback is the
        // authoritative physical-off signal, but the liveness probe below can lag behind the
        // profile state machine and would wrongly report the link as still up.
        if (!forceTeardown && isLeAudioStillConnected(device)) {
            Log.d(TAG, "A2DP disconnect for $address ignored: LE Audio is still connected")
            return
        }
        // This is an explicit A2DP disconnect, not a recoverable Tandem transport loss.
        transportRecoveryAddress = null
        physicalDisconnectAddress = address
        // The connect episode ends here: a cooldown recorded by an earlier attempt must not
        // swallow the next genuine connect signal after a fresh power-on. The LE Audio path
        // only fires on profile transitions, so a swallowed dial means nobody retries until
        // an unrelated reconcile happens to run many seconds later.
        connectInFlightAddress = null
        lastConnectAttemptMs = 0L
        // GATT/SPP usually reports false before the terminal A2DP callback. In that
        // ordering the repository already looks disconnected and no second state
        // emission is produced, leaving the notification/island alive forever.
        // Clear the Xiaomi surfaces at the authoritative A2DP boundary instead of
        // waiting for a repository callback that may already have happened.
        appContext?.let { clearXiaomiSurfaces(it, address, device) }

        val repo = repository
        if (repo?.state?.value?.connectedDevice?.address.equals(address, ignoreCase = true)) {
            Log.d(TAG, "disconnecting Tandem session from $address")
            repo?.disconnect()
        }
    }

    /**
     * Whether this headset still has an LE Audio connection.
     *
     * Both of a TWS headset's identities are checked: LE Audio runs on the LE identity while
     * A2DP runs on the classic one, and either address may be the one whose A2DP just dropped.
     * Reads the live profile rather than cached state so a stale record cannot keep a session
     * alive after the headset is really gone.
     */
    @SuppressLint("MissingPermission")
    private fun isLeAudioStillConnected(device: BluetoothDevice): Boolean {
        val adapter = appContext?.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return false
        val address = runCatching { device.address }.getOrNull() ?: return false
        val related = buildSet {
            add(address.uppercase())
            SonyDeviceService.resolveControlAddress(address)?.let { add(it.uppercase()) }
            SonyDeviceService.leAudioAliasSnapshot().forEach { (le, control) ->
                if (control.equals(address, ignoreCase = true)) add(le.uppercase())
            }
        }
        // Checked per identity rather than on the device that just dropped: that one reports
        // disconnected the moment A2DP goes, which says nothing about the LE side. Requiring an
        // ASCS service is what makes an ACL here mean "LE Audio", since under LC3 the classic
        // link is the one that went away.
        return related.any { candidate ->
            runCatching {
                val remote = adapter.getRemoteDevice(candidate)
                if (remote.uuids.orEmpty().none { it.uuid == ASCS_SERVICE_UUID }) return@runCatching false
                BluetoothDevice::class.java.getMethod("isConnected").invoke(remote) as? Boolean == true
            }.getOrDefault(false)
        }
    }


    // ── System per-device LE Audio permission ("低功耗音频") ──

    /**
     * Every bonded identity of this headset that the LE Audio profile applies to, control first.
     *
     * The stack decides that profile applies to a device from one thing: the device advertises
     * ASCS. `RemoteDevices` reports a bond's BR/EDR and LE service sets merged, so a dual-mode
     * headset qualifies under its classic address too — and on a live LC3 link that classic
     * address is the very one `LeAudioService` holds the connection and the active group on.
     * Reading the permission therefore does not hang on the module having created the LE-only bond
     * itself, which is what lets the switch show the true position for a headset that was switched
     * over from Sound Connect. The LE-only identity is still included, for the models whose
     * control identity carries no ASCS of its own.
     */
    @SuppressLint("MissingPermission")
    private fun leAudioPolicyDevices(controlAddress: String): List<BluetoothDevice> {
        val context = appContext ?: return emptyList()
        val addresses = buildList {
            add(controlAddress)
            leAudioIdentityAddress(controlAddress)
                ?.takeIf { !it.equals(controlAddress, ignoreCase = true) }
                ?.let { add(it) }
        }
        return addresses.mapNotNull { remoteDevice(context, it) }.filter { leAudioApplies(it) }
    }

    /** Bonded and advertising ASCS: the stack's own test for "the LE Audio profile applies here". */
    @SuppressLint("MissingPermission")
    private fun leAudioApplies(device: BluetoothDevice): Boolean = runCatching {
        device.bondState == BluetoothDevice.BOND_BONDED &&
            device.uuids.orEmpty().any { it.uuid == ASCS_SERVICE_UUID }
    }.getOrDefault(false)

    /**
     * The headset's separate LE-only bond, when the phone holds one.
     *
     * A hit in the alias map answers this without touching the stack. Only a miss enumerates the
     * bonds to rebuild the map, and at most every [LE_IDENTITY_RESCAN_MS], because this sits on
     * the path of every state emission while the map is populated for free by the BLE client, the
     * dispatcher, and the pairer.
     */
    @SuppressLint("MissingPermission")
    private fun leAudioIdentityAddress(controlAddress: String): String? {
        leAudioAliasFor(controlAddress)?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (now - lastLeIdentityRescanMs < LE_IDENTITY_RESCAN_MS) return null
        lastLeIdentityRescanMs = now
        val adapter = appContext?.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        SonyDeviceService.linkLeAudioIdentities(adapter.bondedDevices.orEmpty())
        return leAudioAliasFor(controlAddress)
    }

    /** The alias map is keyed LE -> control, so the control address is looked up by value. */
    private fun leAudioAliasFor(controlAddress: String): String? =
        SonyDeviceService.leAudioAliasSnapshot()
            .entries.firstOrNull { it.value.equals(controlAddress, ignoreCase = true) }
            ?.key

    /**
     * The stored LE Audio connection policy of [device], or null when the read itself failed.
     *
     * `ConnectableProfile.getConnectionPolicy` is
     * `AdapterService.getProfileConnectionPolicy(device, BluetoothProfile.LE_AUDIO)`, so this is
     * the same stored value the system switch is drawn from. A device with no record answers
     * CONNECTION_POLICY_UNKNOWN, which is an answer and not a failure — the switch draws it off,
     * following the stricter of the stack's two tests (`policy > CONNECTION_POLICY_FORBIDDEN`, as
     * settings does; connecting uses `okToConnect`, which accepts UNKNOWN too).
     */
    private fun rawLeAudioPolicy(device: BluetoothDevice): Int? =
        rawProfilePolicy(device, BluetoothProfile.LE_AUDIO)

    /** [rawLeAudioPolicy] for any profile: the same stored value each profile's switch is drawn from. */
    private fun rawProfilePolicy(device: BluetoothDevice, profile: Int): Int? {
        val service = adapterService() ?: return null
        return runCatching {
            service.javaClass
                .getMethod("getProfileConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(service, device, profile) as? Int
        }.getOrElse {
            Log.w(TAG, "profile $profile policy read failed", it)
            null
        }
    }

    /**
     * Flips that permission through the profile service, which is what the system switch does.
     *
     * `LeAudioService.setConnectionPolicy` is far more than a stored flag: it persists the
     * policy, pushes the native enable state, mirrors the decision into BASS and Xiaomi's
     * BatteryService, re-authorizes or de-authorizes the related GATT profiles, and finally
     * connects or disconnects — including handing audio back to A2DP/HFP when forbidding under
     * dual mode. Writing the stored value alone would leave every one of those undone. The bond
     * is untouched in both directions, which is why re-enabling needs no re-pairing.
     */
    private fun applyLeAudioPolicy(device: BluetoothDevice, allowed: Boolean): Boolean {
        val leAudio = leAudioService() ?: return false
        val policy = if (allowed) CONNECTION_POLICY_ALLOWED else CONNECTION_POLICY_FORBIDDEN
        return runCatching {
            leAudio.javaClass
                .getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(leAudio, device, policy)
            true
        }.getOrElse {
            Log.w(TAG, "LE Audio policy write failed allowed=$allowed", it)
            false
        }
    }

    /**
     * `persist.bluetooth.enable_dual_mode_audio`, the property that decides who owns the classic
     * audio profiles while LE Audio is permitted.
     *
     * Default false. On that default the stack keeps A2DP and HFP out of the way by itself only
     * *while* LE Audio is allowed; putting them back when it is forbidden is the caller's job —
     * `Utils.setLeAudioEnabled` in system settings does exactly that, and not doing it is why the
     * headset went silent after the switch was turned off.
     */
    private fun dualModeAudioEnabled(): Boolean = runCatching {
        val clazz = appContext?.classLoader?.loadClass("android.os.SystemProperties")
            ?: return false
        clazz.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(null, "persist.bluetooth.enable_dual_mode_audio", false) as? Boolean == true
    }.getOrElse {
        Log.w(TAG, "dual mode audio property read failed", it)
        false
    }

    /**
     * Every bonded device the switch applies to: [seed] plus the rest of its coordinated set.
     *
     * `Utils.setLeAudioEnabled` is handed `findAllCachedBluetoothDevicesByGroupId`, i.e. the main
     * device and its members — a two-bond earbud pair has to be flipped together or one bud keeps
     * the profile the other just gave up. `LeAudioService.getGroupDevices` is the stack-side answer
     * to the same question; an ungrouped device answers nothing and stands alone.
     */
    private fun leAudioSwitchGroup(seed: List<BluetoothDevice>): List<BluetoothDevice> {
        val service = leAudioService() ?: return seed
        val members = seed.flatMap { device ->
            val groupId = leAudioIntCall(service, "getGroupId", device)
            if (groupId == null || groupId == LE_AUDIO_GROUP_ID_INVALID) {
                emptyList()
            } else {
                leAudioGroupDevices(service, groupId)
            }
        }
        return (seed + members).distinctBy { runCatching { it.address }.getOrNull() ?: it }
    }

    /** `LeAudioService.getGroupDevices(groupId)`: the bonds the stack counts in one set. */
    private fun leAudioGroupDevices(service: Any, groupId: Int): List<BluetoothDevice> = runCatching {
        @Suppress("UNCHECKED_CAST")
        (service.javaClass
            .getMethod("getGroupDevices", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(service, groupId) as? List<BluetoothDevice>)
            .orEmpty()
    }.getOrElse {
        Log.w(TAG, "LE Audio getGroupDevices failed", it)
        emptyList()
    }

    /**
     * The profiles the LE Audio switch trades against, in the order HyperOS writes them.
     *
     * `LeAudioProfile.disableProfileBeforeUserEnablesLeAudio` /
     * `enableProfileAfterUserDisablesLeAudio` walk A2DP, HFP and HID_HOST — the input profile is
     * HyperOS's own addition on top of AOSP, and it is restored on the same path as the audio
     * ones.
     */
    private val CLASSIC_SWITCH_PROFILES =
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, PROFILE_HID_HOST)

    private fun setClassicAudioEnabled(devices: List<BluetoothDevice>, enabled: Boolean) {
        CLASSIC_SWITCH_PROFILES.forEach { setProfileEnabledWhenChangingLeAudio(devices, it, enabled) }
    }

    /**
     * One classic profile across [devices], with HyperOS's guards kept intact.
     *
     * Each direction has its own test, and neither is `!= wanted`: a profile is only turned back
     * on when its stored policy is exactly FORBIDDEN, and only turned off when it is above
     * FORBIDDEN. A device that never had the profile answers UNKNOWN and so fails both — which is
     * what keeps this off the LE-only identity without asking for a UUID list, and is why no
     * needless disconnect/connect is provoked.
     */
    private fun setProfileEnabledWhenChangingLeAudio(
        devices: List<BluetoothDevice>,
        profile: Int,
        enabled: Boolean,
    ) {
        val service = classicProfileService(profile)
        if (service == null) {
            Log.w(TAG, "profile $profile service is not up; enabled=$enabled not written")
            return
        }
        devices.forEach { device ->
            val current = rawProfilePolicy(device, profile) ?: return@forEach
            val write = if (enabled) {
                current == CONNECTION_POLICY_FORBIDDEN
            } else {
                current > CONNECTION_POLICY_FORBIDDEN
            }
            if (!write) return@forEach
            val policy = if (enabled) CONNECTION_POLICY_ALLOWED else CONNECTION_POLICY_FORBIDDEN
            val written = runCatching {
                service.javaClass
                    .getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(service, device, policy)
                true
            }.getOrElse {
                Log.w(TAG, "profile $profile write failed enabled=$enabled", it)
                false
            }
            Log.i(
                TAG,
                "profile $profile ${runCatching { device.address }.getOrNull()} " +
                    "$current -> $policy written=$written",
            )
        }
    }

    /** The profile service that owns [profile]'s connection policy, or null while it is down. */
    private fun classicProfileService(profile: Int): Any? = when (profile) {
        BluetoothProfile.A2DP -> profileService("getA2dpService", "com.android.bluetooth.a2dp.A2dpService")
        BluetoothProfile.HEADSET -> profileService("getHeadsetService", "com.android.bluetooth.hfp.HeadsetService")
        PROFILE_HID_HOST -> profileService("getHidHostService", "com.android.bluetooth.hid.HidHostService")
        else -> null
    }

    /**
     * The running instance of one profile service.
     *
     * This stack hands them out from `AdapterService` as an `Optional`; builds that predate that
     * keep the singleton behind the service class's own static getter of the same name.
     */
    private fun profileService(getter: String, className: String): Any? {
        adapterService()?.let { adapter ->
            runCatching {
                val result = adapter.javaClass.getMethod(getter)
                    .apply { isAccessible = true }
                    .invoke(adapter)
                (result as? java.util.Optional<*>)?.orElse(null) ?: result
            }.getOrNull()?.let { return it }
        }
        val loader = appContext?.classLoader ?: return null
        return runCatching {
            loader.loadClass(className).getMethod(getter).apply { isAccessible = true }.invoke(null)
        }.getOrElse {
            Log.w(TAG, "$getter lookup failed", it)
            null
        }
    }

    /**
     * The running `LeAudioService`, or null while the profile is not up.
     *
     * `AdapterService.getLeAudioService` answers an `Optional`, empty until the profile starts and
     * again once it stops — so a null here is "no LE Audio profile right now", not an error.
     */
    private fun leAudioService(): Any? =
        profileService("getLeAudioService", "com.android.bluetooth.le_audio.LeAudioService")

    /** What the stack currently holds for one of the headset's identities. Nulls mean unreadable. */
    private data class LeAudioSystemState(val connected: Boolean?, val active: Boolean?)

    /**
     * Whether the stack has LE Audio connected to this headset, and whether it is the audio route.
     *
     * "Active" is decided by group, not by device: `getActiveGroupId` names the group the stack is
     * routing to, and a coordinated set answers with one group id for either bud and for either
     * identity of a dual-mode headset. Comparing group ids therefore holds however the headset is
     * bonded and whichever bud the stack happens to have made active — where comparing addresses
     * against `getActiveDevices` would miss the other identity.
     */
    private fun leAudioSystemState(devices: List<BluetoothDevice>): LeAudioSystemState {
        if (devices.isEmpty()) return LeAudioSystemState(null, null)
        val service = leAudioService() ?: return LeAudioSystemState(null, null)
        val states = devices.map { leAudioIntCall(service, "getConnectionState", it) }
        val connected = if (states.all { it == null }) null
        else states.any { it == BluetoothProfile.STATE_CONNECTED }
        val activeGroup = leAudioIntCall(service, "getActiveGroupId")
        val active = when {
            activeGroup == null -> null
            activeGroup == LE_AUDIO_GROUP_ID_INVALID -> false
            else -> {
                val groups = devices.map { leAudioIntCall(service, "getGroupId", it) }
                if (groups.all { it == null }) null else groups.any { it == activeGroup }
            }
        }
        return LeAudioSystemState(connected, active)
    }

    /** One reflective int read off the profile service; null when it is not available to us. */
    private fun leAudioIntCall(service: Any, name: String, device: BluetoothDevice? = null): Int? =
        runCatching {
            val method = if (device == null) {
                service.javaClass.getMethod(name)
            } else {
                service.javaClass.getMethod(name, BluetoothDevice::class.java)
            }
            method.isAccessible = true
            val result = if (device == null) method.invoke(service) else method.invoke(service, device)
            (result as? Int) ?: (result as? Number)?.toInt()
        }.getOrElse {
            Log.w(TAG, "LE Audio $name read failed", it)
            null
        }


    /**
     * Adds every fact only this process can see to a repository-built snapshot: the system's LE
     * Audio permission and its LDAC switch, both of which live in the profile services rather than
     * in the headset.
     */
    private fun withSystemFacts(base: SonyStateSnapshot): SonyStateSnapshot =
        withControlIdentity(withLdac(withLeAudioPolicy(base)))

    /**
     * Publishes the control (classic) identity as the device address.
     *
     * After a CTKD re-pair or an LE-only reconnect the only live link can be the LE identity,
     * and the raw session address is then that one — which no saved record is keyed on, so
     * every saved-device surface blanks out. Resolve through the alias map (authoritative:
     * fed by the pairer and the bond scans) and publish its control side instead. Guards keep
     * this inert when the map has no entry, when it maps the address to itself, or when the
     * control bond no longer exists.
     */
    private fun withControlIdentity(base: SonyStateSnapshot): SonyStateSnapshot {
        val address = base.deviceAddress ?: return base
        val control = SonyDeviceService.resolveControlAddress(address)
            ?.takeIf { !it.equals(address, ignoreCase = true) }
            ?: return base
        val bondedNow = appContext?.getSystemService(BluetoothManager::class.java)?.adapter
            ?.bondedDevices.orEmpty()
            .any { it.address.equals(control, ignoreCase = true) }
        if (!bondedNow) return base
        Log.i(TAG, "publishing control identity $control for live session on $address")
        return base.copy(
            deviceAddress = control,
            // An identity field equal to the rewritten address would be an inverted pairing;
            // drop it rather than feed the poison back to consumers.
            leAudioIdentityAddress = base.leAudioIdentityAddress
                ?.takeIf { !it.equals(control, ignoreCase = true) },
        )
    }

    /**
     * Adds the facts only this process can see to a repository-built snapshot.
     *
     * `leAudioIdentityAddress` is the identity the permission would be written to, and doubles as
     * "the system has a device to permit LE Audio on at all"; `leAudioPolicyAllowed` is the
     * position of its switch, null only when no read succeeded. `leAudioSystemConnected` and
     * `leAudioSystemActive` are what the stack has actually done with that permission. All are
     * always written, so a bond that has gone away clears them instead of leaving the last reading
     * in place.
     *
     * A headset can hold two records — one per identity — and the two need not agree. Permitted on
     * either means the system may route LC3, so that is what the switch shows.
     */
    private fun withLeAudioPolicy(base: SonyStateSnapshot): SonyStateSnapshot {
        val address = base.deviceAddress ?: return base
        val devices = leAudioPolicyDevices(address)
        val readings = devices.map { it to rawLeAudioPolicy(it) }
        val system = leAudioSystemState(devices)
        logLeAudioPolicy(address, readings, system)
        val known = readings.mapNotNull { it.second }
        val target = readings.firstOrNull { (_, policy) ->
            policy != null && policy > CONNECTION_POLICY_FORBIDDEN
        }?.first ?: readings.firstOrNull()?.first
        return base.copy(
            leAudioIdentityAddress = target?.let { runCatching { it.address }.getOrNull() },
            leAudioPolicyAllowed = if (known.isEmpty()) null
            else known.any { it > CONNECTION_POLICY_FORBIDDEN },
            leAudioSystemConnected = system.connected,
            leAudioSystemActive = system.active,
        )
    }

    /** One line per change in what the stack reports, so a wrong switch position is diagnosable. */
    private fun logLeAudioPolicy(
        control: String,
        readings: List<Pair<BluetoothDevice, Int?>>,
        system: LeAudioSystemState,
    ) {
        val line = readings.joinToString(",") { (device, policy) ->
            "${runCatching { device.address }.getOrNull()}=$policy"
        }.ifEmpty { "no le audio capable bond" }
        val summary = "$control -> $line connected=${system.connected} active=${system.active}"
        if (summary == lastLeAudioPolicyLog) return
        lastLeAudioPolicyLog = summary
        Log.i(TAG, "LE Audio policy $summary")
    }

    // ── System per-device LDAC switch ──

    /**
     * Everything the stack knows about this device's LDAC, or nothing when A2DP is not up.
     *
     * [supported] is the stack's own test: LDAC among the selectable capabilities is what
     * `A2dpCodecConfig.isMiuiCodecConfigSelectable` requires before it will accept a preference for
     * it. [enabled] is the codec actually carrying media, which is what the system's own checkbox
     * shows — it recomputes its position from the current codec on every CODEC_CONFIG_CHANGED
     * rather than from the stored preference.
     */
    /**
     * What the A2DP service currently says about [address]'s codec situation.
     *
     * [supported] is tri-state on purpose: `null` means the profile had no answer — which is
     * the steady state while LE Audio carries the audio and A2DP is down. It must not be read
     * as "unsupported": the stock switch keys on bond-level capability bits that stay set
     * across transports, and so do we ([withLdac] falls back to that static answer).
     */
    private data class LdacState(val supported: Boolean?, val enabled: Boolean?)

    private fun a2dpService(): Any? =
        profileService("getA2dpService", "com.android.bluetooth.a2dp.A2dpService")

    // The int codec types are deprecated in the SDK in favour of BluetoothCodecType, but they are
    // what this stack itself stores and compares: its own codec-preference path builds a config from
    // the int type and picks the winner by comparing those ints. Reading anything else would not be
    // reading the same fact the switch writes.
    @Suppress("DEPRECATION")
    private fun ldacState(address: String): LdacState {
        val context = appContext ?: return LdacState(null, null)
        val service = a2dpService() ?: return LdacState(null, null)
        val device = remoteDevice(context, address) ?: return LdacState(null, null)
        val status = runCatching {
            service.javaClass
                .getMethod("getCodecStatus", BluetoothDevice::class.java)
                .apply { isAccessible = true }
                .invoke(service, device) as? BluetoothCodecStatus
        }.getOrElse {
            Log.w(TAG, "getCodecStatus failed for $address", it)
            null
        } ?: return LdacState(null, null)
        val supported = status.codecsSelectableCapabilities.any {
            it.codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC
        }
        // No current config is "unknown", not "off": a link that has not settled on a codec yet
        // must not be reported as LDAC being off.
        val enabled = status.codecConfig?.let {
            it.codecType == BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC
        }
        return LdacState(supported, enabled)
    }

    /**
     * Adds the LDAC switch's position to a snapshot.
     *
     * While a write is settling the target is reported instead of the reading: the stack needs about
     * a second to renegotiate, and reporting the old codec in the meantime would spring the row
     * back before it moved again. The system's own switch solves this the same way, by holding its
     * checkbox for [LDAC_SETTLE_MS] after a write.
     */
    private fun withLdac(base: SonyStateSnapshot): SonyStateSnapshot {
        val address = base.deviceAddress ?: return base
        val settling = ldacWriteSettlesAtMs > SystemClock.elapsedRealtime()
        if (!settling) ldacWriteTarget = null
        val state = ldacState(address)
        val target = ldacWriteTarget.takeIf { settling }
        return base.copy(
            // "Supported" is a bond-level fact and must not depend on which transport is
            // live: the miui headset capability bits the stock switch reads stay set while
            // LE Audio carries the audio, when A2DP has no codec status to offer. An
            // unreadable A2DP is therefore "unknown" and falls back to that static answer;
            // only an actual read of the codec list may say false.
            ldacSupported = state.supported ?: (target != null || base.connectedViaLeAudio),
            ldacEnabled = target ?: state.enabled,
            ldacSwitching = settling,
        )
    }

    /**
     * Handles [SonyBridge.CMD_SET_LDAC_ENABLED] exactly as the stack's own codec switch does.
     *
     * Two writes, and both are needed. `defaultSetCodec` is what moves the codec: it builds the
     * one-codec preference (priority 1000000 on, -1 off, every other field left as a wildcard) and
     * hands it to `setCodecConfigPreference`, which validates it against the device's selectable
     * capabilities and dispatches it to the native stack, where it persists per device.
     * `setUserCodecStatus` records the user's choice in the profile service's own store, and that
     * record is not decoration: on every codec change the service checks it, and an LDAC link whose
     * stored status is 0 is force-switched away 1.3 s later. Writing only the codec would therefore
     * be undone, and writing only the status would do nothing until the next reconnect.
     *
     * Both are the profile service's own methods, called in the process that owns it — no settings
     * app, no system properties, no reimplementation of the codec-config construction.
     */
    private fun setLdacEnabled(enabled: Boolean) {
        val context = appContext ?: return
        val base = lastSnapshot ?: return
        val address = base.deviceAddress ?: return
        val service = a2dpService()
        if (service == null) {
            Log.w(TAG, "A2DP service is not up; LDAC enabled=$enabled not written")
            return
        }
        val device = remoteDevice(context, address) ?: return
        val wrote = runCatching {
            service.javaClass
                .getMethod(
                    "setUserCodecStatus",
                    BluetoothDevice::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
                .apply { isAccessible = true }
                .invoke(service, device, LDAC_CODEC_NAME, if (enabled) 1 else 0)
            service.javaClass
                .getMethod(
                    "defaultSetCodec",
                    String::class.java,
                    BluetoothDevice::class.java,
                    Boolean::class.javaPrimitiveType,
                )
                .apply { isAccessible = true }
                .invoke(service, LDAC_CODEC_NAME, device, enabled)
            true
        }.getOrElse {
            Log.w(TAG, "LDAC write enabled=$enabled failed for $address", it)
            false
        }
        if (!wrote) return
        Log.i(TAG, "LDAC enabled=$enabled written for $address")
        ldacWriteTarget = enabled
        ldacWriteSettlesAtMs = SystemClock.elapsedRealtime() + LDAC_SETTLE_MS
        publishLdac(context)
        // The codec change lands asynchronously; re-read once the hold expires so the row shows
        // what the stack settled on rather than what was asked for.
        scope.launch {
            delay(LDAC_SETTLE_MS)
            publishLdac(context)
        }
    }

    /** Re-read the LDAC facts and push them out; nothing in the repository carries them. */
    private fun publishLdac(context: Context) {
        val base = lastSnapshot ?: return
        val updated = withLdac(base)
        if (updated == base) return
        lastSnapshot = updated
        cloudFallback?.onState(updated)
        publish(context, updated)
    }

    /**
     * [snapshot] with the system-side facts re-read.
     *
     * Nothing notifies us when the LE Audio permission or the codec preference changes — system
     * settings writes both straight into the stack — so a replay request is the moment to look
     * again.
     */
    private fun refreshedSnapshot(): SonyStateSnapshot {
        val current = lastSnapshot ?: return SonyStateSnapshot()
        val updated = withSystemFacts(current)
        if (updated != current) lastSnapshot = updated
        return updated
    }

    /**
     * Handles [SonyBridge.CMD_SET_LE_AUDIO_POLICY]: flip the switch the way HyperOS does, then
     * republish.
     *
     * The switch is not one write. Unless `persist.bluetooth.enable_dual_mode_audio` is set — it is
     * not, by default — A2DP, HFP and HID_HOST are its other half: turned off before LE Audio is
     * permitted, and turned back on after it is forbidden. HyperOS keeps that in its own
     * `LeAudioProfile.setEnabled` override (AOSP's `Utils.setLeAudioEnabled` does the two audio
     * profiles only; the input profile is Xiaomi's addition). Writing the LE Audio policy alone
     * leaves all three at FORBIDDEN, which is a headset that is connected, controllable, and
     * completely silent.
     *
     * The LE Audio policy is written to every identity it applies to, because each identity
     * carries its own stored policy and forbidding one alone would leave the other free to bring
     * LE Audio back. The classic write instead covers the whole coordinated set, as HyperOS does.
     *
     * The republish is not redundant. Publishing is gated on the snapshot differing from the
     * previous one, and this permission lives in the stack rather than in the headset, so no
     * repository state changes to carry it out — without pushing the re-read the switch would
     * spring back to its old position.
     */
    private fun setLeAudioPolicy(allowed: Boolean) {
        val context = appContext ?: return
        val base = lastSnapshot ?: return
        val address = base.deviceAddress ?: return
        val devices = leAudioPolicyDevices(address)
        if (devices.isEmpty()) {
            Log.w(TAG, "no LE Audio capable bond for $address; policy write skipped")
            return
        }
        val dualMode = dualModeAudioEnabled()
        val group = leAudioSwitchGroup(devices)
        Log.i(
            TAG,
            "LE Audio switch allowed=$allowed dualMode=$dualMode group=" +
                group.joinToString { runCatching { it.address }.getOrNull().orEmpty() },
        )
        if (allowed && !dualMode) {
            // Disable classic profiles first, then wait for the A2DP state machine to report
            // STATE_DISCONNECTED before enabling LE Audio.  Writing the LE Audio policy while
            // classic GATT connections are still tearing down causes the stack to race on CLCB
            // allocation and SIGBUSes in bta_gattc_cfg_mtu_cmpl with a stale function pointer.
            setClassicAudioEnabled(group, enabled = false)
            val enableLeAudio = {
                val applied = devices.map { applyLeAudioPolicy(it, true) }.any()
                if (!applied) {
                    // Nothing took the permission, so the profiles just given up are the only
                    // audio path this headset has left.
                    setClassicAudioEnabled(group, enabled = true)
                } else {
                    val updated = withSystemFacts(base)
                    if (updated != base) {
                        lastSnapshot = updated
                        cloudFallback?.onState(updated)
                        publish(context, updated)
                    }
                }
                Unit
            }
            // Check whether all targeted identities are already disconnected — if so, proceed
            // immediately without waiting for a callback that may never arrive.
            if (group.all { isClassicProfileDisconnected(it) }) {
                Log.i(TAG, "classic profiles already disconnected; enabling LE Audio now")
                enableLeAudio()
            } else {
                Log.i(TAG, "waiting for classic profiles to disconnect before enabling LE Audio")
                pendingLeAudioEnable = enableLeAudio
            }
        } else {
            // Cancel any pending classic-disconnect → LE Audio enable from a previous toggle.
            if (!allowed) pendingLeAudioEnable = null
            if (devices.map { applyLeAudioPolicy(it, allowed) }.none { it }) {
                if (!allowed && !dualMode) setClassicAudioEnabled(group, enabled = true)
                return
            }
            if (!allowed && !dualMode) {
                // HyperOS waits out CLASSIC_RESTORE_DELAY_MS on a thread of its own before
                // restoring: LeAudioService is still tearing the LE Audio link down, and a
                // classic policy written into that window is what the stack undoes on its way out.
                scope.launch {
                    delay(CLASSIC_RESTORE_DELAY_MS)
                    setClassicAudioEnabled(group, enabled = true)
                }
            }
            val updated = withSystemFacts(base)
            if (updated == base) return
            lastSnapshot = updated
            cloudFallback?.onState(updated)
            publish(context, updated)
        }
    }

    /**
     * Checks whether A2DP, HFP and HID are all disconnected for [device].
     *
     * Used by [setLeAudioPolicy] to decide whether to proceed immediately with the LE Audio
     * policy write or to defer it until [onClassicProfileDisconnected] fires.
     */
    private fun isClassicProfileDisconnected(device: BluetoothDevice): Boolean {
        val context = appContext ?: return false
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return false
        for (profileId in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, PROFILE_HID_HOST)) {
            val connected = runCatching {
                manager.getConnectedDevices(profileId)
            }.getOrNull().orEmpty()
            if (connected.any { it.address.equals(device.address, ignoreCase = true) }) return false
        }
        return true
    }

    /**
     * Called by [HeadsetStateDispatcher] when A2DP reports STATE_DISCONNECTED for a Sony device.
     *
     * If a [pendingLeAudioEnable] callback is waiting, this fires it — the classic profiles have
     * now torn down and it is safe to write the LE Audio policy without racing the GATT client
     * layer's CLCB allocation.
     */
    @Synchronized
    fun onClassicProfileDisconnected(device: BluetoothDevice) {
        val pending = pendingLeAudioEnable ?: return
        if (!HeadsetStateDispatcher.isSonyPod(device)) return
        pendingLeAudioEnable = null
        Log.i(TAG, "classic profiles disconnected for ${device.address}; enabling LE Audio")
        pending()
    }

    /** Cancel both Xiaomi battery surfaces for a terminal Bluetooth disconnect. */
    private fun clearXiaomiSurfaces(
        context: Context,
        address: String,
        device: BluetoothDevice? = null,
    ) {
        lastRenderedAddress = null
        lastRenderedBattery = null
        lastConnectAnimationKey = null
        (device ?: remoteDevice(context, address))?.let {
            runCatching { MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt(context, it) }
        }
        runCatching { MiuiStrongToastUtil.cancelBatteryIslandByMiuiBt(context) }
        Log.d(TAG, "xiaomi surfaces cancelled for physical disconnect address=$address")
    }

    /** Latest known state; hooks render system surfaces from this. */
    fun snapshot(): SonyStateSnapshot = lastSnapshot ?: SonyStateSnapshot()

    /**
     * The live framework-backed remote-preference store. Re-invoking [prefsProvider]
     * returns a store reflecting the latest persisted data, correcting any startup read
     * that raced the LSPosed remote-prefs bridge and came back empty (leaving the cached
     * config at its default, which includes OFF in the ANC cycle). Falls back to the
     * package-load snapshot when no provider is wired.
     */
    private fun currentPrefs(): SharedPreferences? =
        runCatching { prefsProvider?.invoke() }.getOrNull() ?: prefs

    // ── Commands ──

    private fun registerCommandReceiver(context: Context) {
        if (commandReceiver != null) return
        runCatching {
            val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        handleCommand(intent ?: return)
                    }
                }
            context.registerReceiver(
                receiver,
                IntentFilter(SonyBridge.ACTION_COMMAND),
                Context.RECEIVER_EXPORTED,
            )
            commandReceiver = receiver
            Log.d(TAG, "command receiver registered")
        }.onFailure { Log.w(TAG, "command receiver registration failed", it) }
    }

    private fun announceEngineReadyToOfficialApp(context: Context) {
        runCatching {
            context.sendBroadcast(
                Intent(SonyBridge.ACTION_ENGINE_READY).apply {
                    setPackage(SonyBridge.OFFICIAL_APP_PACKAGE)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            )
        }.onFailure { Log.w(TAG, "engine-ready broadcast to Sound Connect failed", it) }
    }

    private fun handleCommand(intent: Intent) {
        val command = intent.getStringExtra(SonyBridge.EXTRA_COMMAND) ?: return
        if (command == SonyBridge.CMD_OFFICIAL_APP_ACQUIRE || command == SonyBridge.CMD_OFFICIAL_APP_RELEASE) {
            if (!isValidOfficialLeaseIntent(intent)) {
                Log.w(TAG, "ignored invalid Sound Connect lease command")
                return
            }
            when (command) {
                SonyBridge.CMD_OFFICIAL_APP_ACQUIRE -> acquireOfficialAppLease(intent)
                SonyBridge.CMD_OFFICIAL_APP_RELEASE -> {
                    val leaseId = intent.getStringExtra(SonyBridge.EXTRA_OFFICIAL_LEASE_ID) ?: return
                    val token = intent.extras?.getBinder(SonyBridge.EXTRA_OFFICIAL_LEASE_TOKEN) ?: return
                    releaseOfficialAppLease(leaseId, "official app released Tandem lease", token)
                }
            }
            return
        }

        // Catalog publication is independent of the Tandem lease and of whether a
        // headphone is currently connected. Refresh it before the repository guard so
        // a newly published Remote File is picked up even while Sound Connect owns the
        // live session (or while the engine is between generations).
        if (command == SonyBridge.CMD_CLOUD_MODEL_INFO_READY) {
            repository?.refreshModelImageCatalog()
            Log.d(TAG, "cloud model catalog ready command handled")
            return
        }

        if (officialAppOwnsTandem && command !in setOf(
                SonyBridge.CMD_REPUBLISH,
                SonyBridge.CMD_SURFACES_READY,
                SonyBridge.CMD_IMAGE_READY,
            )) {
            Log.d(TAG, "command=$command skipped while Sound Connect owns Tandem")
            return
        }

        val repo = repository ?: return
        Log.d(TAG, "command=$command")
        when (command) {
            SonyBridge.CMD_SET_NOISE_CONTROL -> {
                val mode = intent.getStringExtra(SonyBridge.EXTRA_STRING)
                    ?.let { name -> NoiseControlMode.entries.firstOrNull { it.name == name } }
                    ?: return
                repo.setNoiseControlMode(mode)
            }

            SonyBridge.CMD_CYCLE_NOISE_CONTROL -> {
                // cachedConfig is authoritative here: it is seeded from the remote-pref
                // store at engine start (HookEntry -> ConfigManager.attachStore), kept
                // live by the native OnSharedPreferenceChangeListener and the deferred
                // re-reads above, so no re-read is needed on this path. Do NOT call
                // currentPrefs()?.let { refreshFromPrefs(it) } speculatively — before the
                // framework bridge is ready a fresh fetch returns an empty snapshot and
                // would clobber the live config with defaults.
                val enabledNames = ConfigManager.ancCycleModes()
                // Build the ordered cycle from the user's chosen subset.
                // .ifEmpty fallback only fires when cachedConfig itself has no valid mode names
                // (genuine corruption / first boot before any config broadcast), never for a
                // normal two-mode subset like [NC, ASM].
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

            SonyBridge.CMD_SET_NOISE_ADAPTIVE ->
                repo.setNoiseAdaptive(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))

            SonyBridge.CMD_SET_NOISE_ADAPTIVE_SENSITIVITY -> {
                val sensitivity = intent.getStringExtra(SonyBridge.EXTRA_STRING)
                    ?.let { name -> NoiseAdaptiveSensitivity.entries.firstOrNull { it.name == name } }
                    ?: return
                repo.setNoiseAdaptiveSensitivity(sensitivity)
            }

            SonyBridge.CMD_SET_EQ_PRESET -> {
                val preset = intent.getStringExtra(SonyBridge.EXTRA_STRING)
                    ?.let { name -> EqPresetId.entries.firstOrNull { it.name == name } }
                    ?: return
                repo.setEqPreset(preset)
            }

            SonyBridge.CMD_SET_CLEAR_BASS ->
                repo.setClearBass(intent.getIntExtra(SonyBridge.EXTRA_INT, 0))

            SonyBridge.CMD_POWER_OFF -> repo.powerOff()

            SonyBridge.CMD_SET_EQ_BAND -> repo.setCustomEqBand(
                intent.getIntExtra(SonyBridge.EXTRA_INDEX, 0),
                intent.getIntExtra(SonyBridge.EXTRA_INT, 0),
            )

            SonyBridge.CMD_SET_GESTURE_PRESET -> repo.setGesturePreset(
                intent.getIntExtra(SonyBridge.EXTRA_KEY_CODE, -1),
                intent.getIntExtra(SonyBridge.EXTRA_PRESET_CODE, -1),
            )

            SonyBridge.CMD_SET_GESTURE_FUNCTION -> repo.setGestureFunction(
                intent.getIntExtra(SonyBridge.EXTRA_KEY_CODE, -1),
                intent.getIntExtra(SonyBridge.EXTRA_ACTION_CODE, -1),
                intent.getIntExtra(SonyBridge.EXTRA_FUNCTION_CODE, -1),
            )

            SonyBridge.CMD_SET_QUICK_ACCESS_FUNCTION -> repo.setQuickAccessFunction(
                intent.getIntExtra(SonyBridge.EXTRA_QUICK_ACCESS_ACTION_INDEX, -1),
                intent.getIntExtra(SonyBridge.EXTRA_QUICK_ACCESS_FUNCTION_CODE, -1),
            )

            SonyBridge.CMD_SET_GESTURE_AMBIENT_MODES -> {
                val modes = intent.getIntArrayExtra(SonyBridge.EXTRA_FUNCTION_CODE)
                    ?.asSequence()
                    ?.mapNotNull { code -> GestureNoiseControlMode.entries.getOrNull(code) }
                    ?.toSet()
                    ?: emptySet()
                repo.setGestureAmbientModes(modes)
            }

            SonyBridge.CMD_SET_MULTIPOINT_PAIRING_MODE ->
                repo.setMultipointPairingMode(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))

            SonyBridge.CMD_CONNECT_MULTIPOINT_DEVICE ->
                intent.getStringExtra(SonyBridge.EXTRA_STRING)?.let(repo::connectMultipointDevice)

            SonyBridge.CMD_DISCONNECT_MULTIPOINT_DEVICE ->
                intent.getStringExtra(SonyBridge.EXTRA_STRING)?.let(repo::disconnectMultipointDevice)

            SonyBridge.CMD_UNPAIR_MULTIPOINT_DEVICE ->
                intent.getStringExtra(SonyBridge.EXTRA_STRING)?.let(repo::unpairMultipointDevice)
            SonyBridge.CMD_SET_SOURCE_SWITCH_ENABLED ->
                repo.setSourceSwitchEnabled(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_SET_MULTIPOINT_ENABLED ->
                repo.setMultipointEnabled(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_SET_LE_AUDIO_ENABLED ->
                repo.setLeAudioEnabled(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_SET_UPSCALING_ENABLED ->
                repo.setUpscalingEnabled(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_SET_CONNECTION_QUALITY -> {
                val modeName = intent.getStringExtra(SonyBridge.EXTRA_STRING)
                val mode = runCatching {
                    dev.sonypods.protocol.ConnectionQualityMode.valueOf(modeName.orEmpty())
                }.getOrNull()
                if (mode == null) {
                    Log.w(TAG, "connection quality command ignored: unknown mode $modeName")
                } else {
                    repo.setConnectionQuality(mode)
                }
            }
            SonyBridge.CMD_REPLY_MULTIPOINT_ALERT ->
                repo.replyMultipointAlert(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_REPLY_LE_AUDIO_ALERT ->
                repo.replyLeAudioAlert(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_LE_AUDIO_DEVICE_PAIR -> repo.startLeAudioDevicePairing()
            SonyBridge.CMD_LE_AUDIO_PAIRING_GUIDE -> repo.showLeAudioPairingGuide()
            SonyBridge.CMD_LE_AUDIO_DEVICE_UNPAIR ->
                repo.unpairLeAudioDevice(intent.getStringExtra(SonyBridge.EXTRA_STRING))
            SonyBridge.CMD_SET_LE_AUDIO_POLICY ->
                setLeAudioPolicy(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_SET_LDAC_ENABLED ->
                setLdacEnabled(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_SET_FIXED_SOURCE ->
                intent.getStringExtra(SonyBridge.EXTRA_STRING)?.let(repo::setFixedSource)
            SonyBridge.CMD_SET_MUSIC_HAND_OVER ->
                repo.setMusicHandOverEnabled(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))

            SonyBridge.CMD_PLAYBACK_PREVIOUS -> repo.playbackPrevious()
            SonyBridge.CMD_PLAYBACK_PLAY_PAUSE -> repo.playbackPlayPause()
            SonyBridge.CMD_PLAYBACK_NEXT -> repo.playbackNext()
            SonyBridge.CMD_SET_PLAYBACK_VOLUME ->
                repo.setPlaybackVolume(intent.getIntExtra(SonyBridge.EXTRA_INT, -1))

            SonyBridge.CMD_CONNECT -> {
                val address = intent.getStringExtra(SonyBridge.EXTRA_STRING) ?: return
                val name = intent.getStringExtra("device_name") ?: "Sony audio device"
                repo.connect(address, name)
            }

            SonyBridge.CMD_DISCONNECT -> {
                val address = knownSonyAddress()
                connectInFlightAddress = null
                transportRecoveryAddress = null
                // A user-requested disconnect is physical from the surface's point
                // of view. Keep its address long enough for the terminal false state
                // to cancel the old notification/island instead of preserving it as
                // an apparent Tandem recovery.
                physicalDisconnectAddress = address
                repo.disconnect()
            }

            SonyBridge.CMD_REFRESH -> if (
                repo.state.value.deviceInfo.protocolReady && repo.hasLiveTransport()
            ) {
                repo.refreshBasics()
            } else {
                runCatching { reconcileConnection() }
                    .onFailure { Log.w(TAG, "manual refresh reconnect failed", it) }
            }

            SonyBridge.CMD_IMAGE_READY -> {
                val imageAddress = intent.getStringExtra(SonyBridge.EXTRA_STRING)
                val current = snapshot()
                if (imageAddress.isNullOrBlank() ||
                    current.deviceAddress?.equals(imageAddress, ignoreCase = true) != true
                ) return
                appContext?.let {
                    // Both the notification and the island carry embedded bitmaps;
                    // invalidate the render guard and refresh the current surfaces.
                    // This is an image replacement during the same connection, not
                    // a new connection: preserving lastConnectAnimationKey keeps
                    // HyperOS on the in-place update path instead of remove -> add.
                    lastRenderedAddress = null
                    lastRenderedBattery = null
                    cloudFallback?.onState(current)
                    Log.d(TAG, "model image ready; re-rendering surfaces address=$imageAddress")
                    publish(it, current)
                }
            }

            // State consumers request a replay when their process starts. Do not
            // re-submit the notification/island for that request: surface owners
            // have their own CMD_SURFACES_READY handshake, and re-rendering here
            // races Remote File publication when the module is opened.
            SonyBridge.CMD_REPUBLISH -> appContext?.let { publishState(it, refreshedSnapshot()) }

            SonyBridge.CMD_SURFACES_READY -> appContext?.let {
                // Forget what we think is on screen so the island shows again.
                lastRenderedAddress = null
                lastRenderedBattery = null
                val islandFirstFloat = if (intent.hasExtra(SonyBridge.EXTRA_ISLAND_FIRST_FLOAT)) {
                    intent.getBooleanExtra(SonyBridge.EXTRA_ISLAND_FIRST_FLOAT, true)
                } else {
                    null
                }
                Log.d(TAG, "surfaces ready; re-rendering notification and island")
                publish(it, snapshot(), islandFirstFloat)
            }

            SonyBridge.CMD_DEBUG_RAW ->
                repo.runDebugAction("raw", intent.getStringExtra(SonyBridge.EXTRA_STRING))
        }
    }

    private fun isValidOfficialLeaseIntent(intent: Intent): Boolean {
        // Android 15/HyperOS delivers this explicit, dynamically registered
        // cross-process broadcast without usable system sender metadata. The
        // lease therefore carries the official process declaration; verify it
        // against PackageManager and require the process-bound Binder token.
        if (intent.`package` != SonyBridge.ENGINE_PACKAGE) return false
        val declaredPackage = intent.getStringExtra(SonyBridge.EXTRA_OFFICIAL_SENDER_PACKAGE)
        val declaredUid = intent.getIntExtra(SonyBridge.EXTRA_OFFICIAL_SENDER_UID, -1)
        val packageOwnsUid = runCatching {
            appContext?.packageManager?.getPackagesForUid(declaredUid)
                ?.contains(SonyBridge.OFFICIAL_APP_PACKAGE) == true
        }.getOrDefault(false)
        val leaseId = intent.getStringExtra(SonyBridge.EXTRA_OFFICIAL_LEASE_ID)
        val token = intent.extras?.getBinder(SonyBridge.EXTRA_OFFICIAL_LEASE_TOKEN)
        return declaredPackage == SonyBridge.OFFICIAL_APP_PACKAGE &&
            declaredUid >= 0 &&
            packageOwnsUid &&
            !leaseId.isNullOrBlank() &&
            token?.pingBinder() == true
    }

    // ── State fan-out ──

    private fun publish(
        context: Context,
        snapshot: SonyStateSnapshot,
        islandFirstFloat: Boolean? = null,
    ) {
        publishState(context, snapshot)
        injectSystemBattery(context, snapshot)
        renderXiaomiSurfaces(context, snapshot, islandFirstFloat)
    }

    /** Broadcast state without touching the notification or Dynamic Island. */
    private fun publishState(
        context: Context,
        snapshot: SonyStateSnapshot,
        suppressConnectPopup: Boolean = shouldSuppressConnectPopup(snapshot),
    ) {
        Log.d(
            TAG,
            "publish state connected=${snapshot.connected} address=${snapshot.deviceAddress} " +
                "suppressPopup=$suppressConnectPopup recovery=$transportRecoveryAddress " +
                "physicalDisconnect=$physicalDisconnectAddress lastConnected=$lastConnectedAddress",
        )
        val bundle = snapshot.toBundle()
        SonyBridge.STATE_CONSUMERS.forEach { target ->
            runCatching {
                context.sendBroadcast(
                    Intent(SonyBridge.ACTION_STATE).apply {
                        putExtra(SonyStateSnapshot.EXTRA_SNAPSHOT, bundle)
                        putExtra(SonyBridge.EXTRA_SUPPRESS_CONNECT_POPUP, suppressConnectPopup)
                        physicalDisconnectAddress?.let {
                            putExtra(SonyBridge.EXTRA_PHYSICAL_DISCONNECT_ADDRESS, it)
                        }
                        setPackage(target)
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    }
                )
            }.onFailure { Log.w(TAG, "state broadcast to $target failed", it) }
        }
    }

    /**
     * Drives the HyperOS notification and focus island, which are built by our hook
     * inside com.xiaomi.bluetooth. Previously the module app pushed these; doing it
     * here is what keeps them alive when the app is not running.
     */
    @SuppressLint("MissingPermission")
    private fun renderXiaomiSurfaces(
        context: Context,
        snapshot: SonyStateSnapshot,
        islandFirstFloat: Boolean? = null,
    ) {
        val address = snapshot.deviceAddress
        if (address == null || !snapshot.connected) {
            val previous = transportRecoveryAddress ?: lastRenderedAddress ?: lastConnectedAddress
            Log.d(
                TAG,
                "surface state unavailable connected=${snapshot.connected} address=$address " +
                    "previous=$previous recovery=$transportRecoveryAddress " +
                    "physicalDisconnect=$physicalDisconnectAddress",
            )
            // The A2DP proxy can briefly return an empty list while the Tandem
            // session is recovering. The explicit A2DP disconnect callback is the
            // authoritative physical-disconnect signal; requiring a fresh proxy
            // query here made the surface get cancelled during that gap.
            val a2dpStillHasPreviousDevice = previous != null &&
                physicalDisconnectAddress?.equals(previous, ignoreCase = true) != true
            if (a2dpStillHasPreviousDevice) {
                // Keep the surface records alive while only the Tandem transport is
                // recovering.  The next usable battery state will update them without
                // treating the device as a new connection.
                markTransportRecovery(previous)
                Log.d(TAG, "Tandem transport lost; preserving surfaces for recovery address=$previous")
                return
            }
            transportRecoveryAddress = null
            physicalDisconnectAddress = null
            lastConnectedAddress = null
            if (previous == null) return
            clearXiaomiSurfaces(context, previous)
            return
        }

        val physicalReconnect = physicalDisconnectAddress
            ?.equals(address, ignoreCase = true) == true
        lastConnectedAddress = address
        if (physicalReconnect) {
            // A real A2DP disconnect followed by a same-address reconnect is a new
            // user connection. Clear the old render identity so it can legitimately
            // show its first island/popup; this path is never used for a Tandem-only
            // loss because physicalDisconnectAddress is set only by the terminal
            // A2DP DISCONNECTED event or an explicit disconnect command.
            physicalDisconnectAddress = null
            transportRecoveryAddress = null
            lastRenderedAddress = null
            lastRenderedBattery = null
            lastConnectAnimationKey = null
        }
        if (transportRecoveryAddress?.equals(address, ignoreCase = true) != true) {
            // A different device cannot consume the previous device's recovery marker.
            transportRecoveryAddress = null
        }

        // Form factor (headband vs TWS) comes from the device's capability table, and
        // nothing here may render before that table is in: the neutral fallback profile
        // reports UNKNOWN form factor and asks a single battery question, which renders a
        // pair of buds as a single-battery headband. Cached devices restore the table
        // instantly, a first-time connection has to wait for the probe replies.
        //
        // essentialValuesReady adds the other half: the table says which features exist,
        // not what they read. The island and the notification show battery and the
        // noise-control mode and let the user change the latter, so they wait until those
        // two domains have actually answered — over LE that is seconds after the table, and
        // until then nothing here can be controlled.
        if (!snapshot.capabilitiesKnown || !snapshot.essentialValuesReady) return

        val singleBattery = when (snapshot.formFactor) {
            HeadphoneFormFactor.HEADSET.name -> true
            HeadphoneFormFactor.TRUE_WIRELESS.name -> false
            else -> {
                // Unreachable via the gate above: a capability table always resolves the
                // form factor to one of the two. Guessing from the battery shape is what
                // produced the single-battery headband island, so refuse instead.
                Log.d(TAG, "skip surface render: form factor unresolved address=$address")
                return
            }
        }

        // Battery values are normalized once in SonyHeadphoneRepository: disconnected
        // buds are null, while a cradle at 0% remains a valid non-null level.
        fun pod(level: Int?) = level?.let { PodParams(battery = it, isConnected = true) }
        val battery = BatteryParams(
            // Fold the single (headband) level into the left slot only for actual
            // single-battery devices; a stale pre-probe BATTERY reading must not
            // masquerade as a TWS left bud.
            left = pod(snapshot.batteryLeft ?: snapshot.batterySingle.takeIf { singleBattery }),
            right = pod(snapshot.batteryRight),
            case = pod(snapshot.batteryCradle),
        )
        val isTransportRecovery = transportRecoveryAddress
            ?.equals(address, ignoreCase = true) == true
        val hasBatteryData = battery.left != null || battery.right != null || battery.case != null
        if (!hasBatteryData) {
            // A Tandem/Sound Connect handoff can produce one probe-complete
            // snapshot before the first real BATTERY reply. Do not submit an
            // empty BatteryParams: Xiaomi renders that as a transient "-" and
            // the next real reply then appears as a second refresh. Keeping the
            // previous surface untouched is the correct recovery behavior.
            Log.d(TAG, "skip surface render: no battery data yet address=$address recovery=$isTransportRecovery")
            return
        }
        if (battery == lastRenderedBattery && address == lastRenderedAddress &&
            !isTransportRecovery
        ) return

        // Pop the island once per connection. Subsequent battery replies still go
        // through the same bridge so the visible island can update in place, but
        // they must not retrigger the connection animation.
        val isTransportRecoveryReplay = isTransportRecovery && hasBatteryData
        val isNewDevice = !isTransportRecovery &&
            address != lastConnectAnimationKey && hasBatteryData
        // A surface replay explicitly requested by PopupActivity is a new
        // notification submission, not a battery update.  It must recreate the
        // island even though the connection animation has already been shown.
        val isIslandReplay = islandFirstFloat != null
        // A Tandem recovery only updates the existing notification/island. It must
        // never submit a new island: if the old island expired, it should stay gone.
        val showIsland = isNewDevice || isIslandReplay
        if (isNewDevice) lastConnectAnimationKey = address
        lastRenderedBattery = battery
        lastRenderedAddress = address

        val device = remoteDevice(context, address) ?: return
        runCatching {
            MiuiStrongToastUtil.showPodsNotificationByMiuiBt(
                context = context,
                batteryParams = battery,
                device = device,
                sourceColor = snapshot.modelImageSourceColor,
                // Headband models report one level; tag the notification so the label
                // reads "电量" (battery) instead of "左"+"%" for the single over-ear value.
                singleBattery = singleBattery,
            )
            // Send every battery tick. The hook shows the island only for the first
            // usable state of a connection and updates the existing island thereafter.
            MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(
                context = context,
                batteryParams = battery,
                device = device,
                // Headband models report one level; the connect animation has a
                // dedicated single-battery variant for them.
                singleBattery = singleBattery,
                showIsland = showIsland,
                // Transport recovery updates an existing island in place and never
                // uses the first-float animation.
                islandFirstFloat = when {
                    isTransportRecoveryReplay -> false
                    else -> islandFirstFloat?.takeIf { showIsland }
                },
                transportRecovery = isTransportRecoveryReplay,
            )
            if (isTransportRecoveryReplay) transportRecoveryAddress = null
            Log.d(
                TAG,
                "xiaomi surfaces updated address=$address " +
                    "transportRecovery=$isTransportRecoveryReplay " +
                    "islandReplay=$isIslandReplay showIsland=$showIsland single=$singleBattery",
            )
        }.onFailure { Log.w(TAG, "xiaomi surface render failed", it) }
    }

    /** Mark an existing A2DP device's Tandem session as recoverable, not new. */
    private fun markTransportRecovery(address: String) {
        transportRecoveryAddress = address
        lastConnectedAddress = address
        // Force the next usable state through the renderer, while keeping the
        // connection animation key so it cannot become isNewDevice=true.
        lastConnectAnimationKey = address
        lastRenderedAddress = null
        lastRenderedBattery = null
    }

    /**
     * Preserves popup suppression for all transitions within the same active physical session.
     * Only genuine physical reconnects (after an authoritative A2DP / LE Audio disconnect)
     * are allowed to trigger the connect popup.
     */
    private fun shouldSuppressConnectPopup(snapshot: SonyStateSnapshot): Boolean {
        if (transportRecoveryAddress?.equals(snapshot.deviceAddress, ignoreCase = true) == true) {
            return true
        }
        val address = snapshot.deviceAddress ?: lastConnectedAddress
        val isPhysicalReconnect = address != null &&
            physicalDisconnectAddress?.equals(address, ignoreCase = true) == true
        if (!isPhysicalReconnect && address != null &&
            lastConnectedAddress?.equals(address, ignoreCase = true) == true
        ) {
            return true
        }
        return !snapshot.connected &&
            physicalDisconnectAddress == null &&
            lastConnectedAddress != null
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
        val service = adapterService() ?: return
        // The stack keeps a battery cache per identity, and stock UI (the bluetooth landing
        // page) may read the LE Audio identity's entry — which the headset also updates
        // natively with its own, differently-sourced reading. Inject into both identities so
        // every reader sees the same Tandem-derived value.
        val targets = buildList {
            add(address)
            SonyDeviceService.leAudioAliasSnapshot().entries
                .firstOrNull { it.value.equals(address, ignoreCase = true) }
                ?.key?.let(::add)
        }
        targets.forEach { target ->
            val device = remoteDevice(context, target) ?: return@forEach
            runCatching {
                callMethod(service, "setBatteryLevel", device, level, false)
                val readBack = runCatching {
                    device.javaClass.getMethod("getBatteryLevel").invoke(device) as? Int
                }.getOrNull()
                Log.d(TAG, "battery injected level=$level readBack=$readBack address=$target")
            }.onFailure { Log.w(TAG, "setBatteryLevel failed level=$level address=$target", it) }
        }
    }
}
