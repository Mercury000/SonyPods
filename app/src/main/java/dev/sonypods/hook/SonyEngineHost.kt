package dev.sonypods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.SharedPreferences
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
    /** Audio Stream Control Service: only an LE Audio identity carries it. */
    private val ASCS_SERVICE_UUID: java.util.UUID =
        java.util.UUID.fromString("0000184E-0000-1000-8000-00805F9B34FB")
    private const val STARTUP_ANNOUNCE_COUNT = 10
    private const val STARTUP_ANNOUNCE_INTERVAL_MS = 3_000L
    private const val RECONCILE_INTERVAL_MS = 15_000L
    private const val CONNECT_COOLDOWN_MS = 10_000L
    private const val CONNECT_IN_FLIGHT_TIMEOUT_MS = 15_000L

    private fun newGenerationScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scope = newGenerationScope()

    @Volatile
    private var repository: SonyHeadphoneRepository? = null

    @Volatile
    private var cloudFallback: HookCloudModelFallback? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var adapterService: Any? = null

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
     * A Tandem transport recovery is not a user-visible new connection.  Keep this
     * separate from the screen lifecycle: GATT/SPP can fail for any reason while the
     * A2DP link remains alive.
     */
    private var transportRecoveryAddress: String? = null

    private var commandReceiver: BroadcastReceiver? = null
    private var configReceiver: BroadcastReceiver? = null
    private var capabilityCacheReceiver: BroadcastReceiver? = null
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
                registerConfigReceiver(it)
                registerCapabilityCacheReceiver(it)
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
        // Wire the remote-prefs store (read-only side) for the capability-probe
        // cache, and the sink that carries cache writes to the app process, which is
        // the only side allowed to persist into the shared store.
        repo.attachPrefsProvider { currentPrefs() }
        repo.attachCapabilityCacheSink { json ->
            runCatching {
                ctx.sendBroadcast(
                    Intent(SonyBridge.ACTION_CAPABILITY_CACHE).apply {
                        putExtra(SonyBridge.EXTRA_CAPABILITY_JSON, json)
                        setPackage("com.mercury.sonypods")
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    }
                )
            }.onFailure { Log.w(TAG, "capability cache broadcast to app failed", it) }
        }

        registerCommandReceiver(ctx)
        registerConfigReceiver(ctx)
        registerCapabilityCacheReceiver(ctx)
        announceEngineReadyToOfficialApp(ctx)

        scope.launch {
            repo.state.collect { uiState ->
                val snapshot = SonyStateSnapshot.fromUiState(uiState)
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
        // package-load time, so the init read (HookEntry -> ConfigManager.init) can come
        // back empty and leave cachedConfig at its default (ANC cycle includes OFF). Re-read
        // a couple of times shortly after start so the persisted cycle config is picked up
        // even after a scope restart with the module app never opened. Reads are harmless:
        // the hook-side store is read-only, so this can never clobber the user's config.
        scope.launch {
            for (delayMs in listOf(3_000L, 8_000L)) {
                delay(delayMs)
                runCatching {
                    currentPrefs()?.let { ConfigManager.refreshFromPrefs(it) }
                    repository?.refreshCapabilityCacheFromPrefs()
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
        val oldScope = scope
        scope = newGenerationScope()
        oldScope.cancel()
        val ctx = appContext
        listOf(commandReceiver, configReceiver, capabilityCacheReceiver, unlockReceiver)
            .filterNotNull()
            .forEach { receiver -> ctx?.let { runCatching { it.unregisterReceiver(receiver) } } }
        commandReceiver = null
        configReceiver = null
        capabilityCacheReceiver = null
        unlockReceiver = null

        val prefsStore = remotePreferenceStore ?: currentPrefs()
        remotePreferenceListener?.let { listener ->
            prefsStore?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(listener) } }
        }
        remotePreferenceListener = null
        remotePreferenceStore = null
        remoteConfigListenerRegistered = false
        configReceiverRegistered = false
        capabilityCacheReceiverRegistered = false

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
                repository?.refreshCapabilityCacheFromPrefs()
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
        val sameAttemptInFlight = connectInFlightAddress?.equals(address, ignoreCase = true) == true &&
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
        val device = allConnected?.firstOrNull { HeadsetStateDispatcher.isSonyPod(it) } ?: return
        Log.d(TAG, "reconciling: ${device.address} is connected but has no Tandem session")
        connectDevice(device, force = true)
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

    /**
     * Link-layer authority for a terminal disconnect, for the topologies no profile covers.
     *
     * `LeAudioService` only announces devices that hold a `LeAudioDeviceDescriptor`, so when a
     * headset's control identity is not itself an LE Audio member, nothing ever reports its
     * power-off: A2DP is gone under LC3 and the LE Audio transition belongs to the other
     * identity. The surfaces then stay preserved for a recovery that never arrives.
     *
     * The stack does mark it, and this mirrors that rather than inventing a rule:
     * `RemoteDevices.aclStateChangeCallback` broadcasts ACTION_ACL_DISCONNECTED and, at that same
     * point, treats `AdapterService.getConnectionState(device) == 0` as the device being gone —
     * that is where it resets the battery level and disconnects Xiaomi's BatteryService. The one
     * widening is from the dropped address to every identity of the headset, because a
     * coordinated set's members are separate `BluetoothDevice`s and one earbud returning to the
     * case is not the headset leaving.
     */
    @SuppressLint("MissingPermission")
    fun onAclDisconnected(device: BluetoothDevice) {
        val address = runCatching { device.address }.getOrNull() ?: return
        val context = appContext ?: return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        // The alias map is what folds a set member onto its control identity below; refresh it
        // from the current bonds first, exactly as the LE Audio hook does before deciding.
        SonyDeviceService.linkLeAudioIdentities(adapter.bondedDevices.orEmpty())
        if (isLeAudioStillConnected(device)) {
            Log.d(TAG, "ACL drop of $address not terminal: LE Audio is still connected")
            return
        }
        val controlAddress = SonyDeviceService.resolveControlAddress(address) ?: address
        val control = remoteDevice(context, controlAddress) ?: return
        if (hasAnyLink(control)) {
            Log.d(TAG, "ACL drop of $address not terminal: control identity $controlAddress is linked")
            return
        }
        Log.d(TAG, "ACL drop of $address is terminal for control identity $controlAddress")
        disconnectDevice(control, forceTeardown = true)
    }

    /**
     * Whether any ACL to [device] remains, on either transport.
     *
     * `BluetoothDevice.isConnected()` is `getConnectionState(device) != STATE_DISCONNECTED`, so
     * this is the same aggregate the stack itself consults — not a per-transport guess. An
     * unreadable answer reports "still linked": that keeps the pre-existing behaviour instead of
     * letting a reflection failure tear a session down.
     */
    private fun hasAnyLink(device: BluetoothDevice): Boolean = runCatching {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as? Boolean == true
    }.getOrDefault(true)

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

    private var configReceiverRegistered = false

    /**
     * Apply config pushed from the app by value. The app attaches the full serialized
     * [dev.sonypods.config.AppConfig] to [SonyPodsAction.ACTION_CONFIG_CHANGED]; we apply
     * it directly to the shared [ConfigManager] cache so changes made in the app (e.g. ANC
     * cycle modes) take effect in the engine immediately. The persisted authority at startup
     * is the remote-preference store (HookEntry -> ConfigManager.init); live updates arrive
     * here by value.
     *
     * NOTE: this receiver must NOT write back to remote prefs. In the hooked process
     * `XposedModule.getRemotePreferences(...)` is **read-only** — calling `.edit()` on it
     * throws `UnsupportedOperationException: Read only implementation`, which (uncaught in
     * onReceive) kills the `com.android.bluetooth` main thread and drops the Bluetooth link.
     * Durability across a scope restart comes solely from the app process writing the config
     * via `XposedService.getRemotePreferences` (which is writable); the engine only reads it.
     */
    private fun registerConfigReceiver(context: Context) {
        if (configReceiverRegistered) return
        runCatching {
            val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != SonyPodsAction.ACTION_CONFIG_CHANGED) return
                        val json = intent.getStringExtra(ConfigManager.PREF_KEY_CONFIG_JSON) ?: return
                        ConfigManager.applyConfigJson(json)
                    }
                }
            context.registerReceiver(
                receiver,
                IntentFilter(SonyPodsAction.ACTION_CONFIG_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
            configReceiver = receiver
            configReceiverRegistered = true
            Log.d(TAG, "config push receiver registered")
        }.onFailure { Log.w(TAG, "config push receiver registration failed", it) }
    }

    private var capabilityCacheReceiverRegistered = false

    /**
     * App -> engine value push of the capability-probe cache (the app echoes the
     * engine's own write after persisting it, so the in-process overlay stays
     * current even when the hook-side remote-prefs read is not yet reliable).
     */
    private fun registerCapabilityCacheReceiver(context: Context) {
        if (capabilityCacheReceiverRegistered) return
        runCatching {
            val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != SonyBridge.ACTION_CAPABILITY_CACHE) return
                        val json = intent.getStringExtra(SonyBridge.EXTRA_CAPABILITY_JSON) ?: return
                        repository?.installCapabilityCache(json)
                    }
                }
            context.registerReceiver(
                receiver,
                IntentFilter(SonyBridge.ACTION_CAPABILITY_CACHE),
                Context.RECEIVER_EXPORTED,
            )
            capabilityCacheReceiver = receiver
            capabilityCacheReceiverRegistered = true
            Log.d(TAG, "capability cache push receiver registered")
        }.onFailure { Log.w(TAG, "capability cache push receiver registration failed", it) }
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
                // IMPORTANT: do NOT call currentPrefs()?.let { refreshFromPrefs(it) } here.
                // XposedModule.getRemotePreferences() in the hooked process (com.android.bluetooth)
                // returns a SharedPreferences backed by *that process's own* data directory —
                // it is NOT the module app's store. In practice it always returns an empty object
                // (class=a1, keys=[]), so reading it clobbers the config that applyConfigJson
                // already delivered correctly via broadcast. The live config in cachedConfig is
                // authoritative; trust it without re-reading.
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
            SonyBridge.CMD_REPLY_MULTIPOINT_ALERT ->
                repo.replyMultipointAlert(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_REPLY_LE_AUDIO_ALERT ->
                repo.replyLeAudioAlert(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_LE_AUDIO_DEVICE_PAIR -> repo.startLeAudioDevicePairing()
            SonyBridge.CMD_LE_AUDIO_PAIRING_GUIDE -> repo.showLeAudioPairingGuide()
            SonyBridge.CMD_LE_AUDIO_DEVICE_UNPAIR ->
                repo.unpairLeAudioDevice(intent.getStringExtra(SonyBridge.EXTRA_STRING))
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
            SonyBridge.CMD_REPUBLISH -> appContext?.let { publishState(it, snapshot()) }

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

        // Form factor (headband vs TWS) is only known once the capability probe
        // finished — cached devices restore it instantly, a first-time connection
        // must wait. Rendering earlier races the neutral profile's single-battery
        // query and flashes the headband notification variant for TWS buds.
        if (!snapshot.probeComplete) return

        val singleBattery = when (snapshot.formFactor) {
            HeadphoneFormFactor.HEADSET.name -> true
            HeadphoneFormFactor.TRUE_WIRELESS.name -> false
            else -> snapshot.batterySingle != null && snapshot.batteryLeft == null
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
     * The false Tandem snapshot is emitted before renderXiaomiSurfaces() gets a
     * chance to establish transportRecoveryAddress. Preserve the popup decision for
     * that one transition as long as there was no terminal physical A2DP disconnect.
     */
    private fun shouldSuppressConnectPopup(snapshot: SonyStateSnapshot): Boolean {
        if (transportRecoveryAddress?.equals(snapshot.deviceAddress, ignoreCase = true) == true) {
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
        val service = adapterService ?: return
        val device = remoteDevice(context, address) ?: return
        runCatching {
            callMethod(service, "setBatteryLevel", device, level, false)
            Log.d(TAG, "battery injected level=$level address=$address")
        }.onFailure { Log.w(TAG, "setBatteryLevel failed level=$level", it) }
    }
}
