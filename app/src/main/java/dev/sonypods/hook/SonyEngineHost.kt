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
    private const val STARTUP_ANNOUNCE_COUNT = 10
    private const val STARTUP_ANNOUNCE_INTERVAL_MS = 3_000L
    private const val RECONCILE_INTERVAL_MS = 15_000L
    private const val CONNECT_COOLDOWN_MS = 10_000L

    private fun newGenerationScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scope = newGenerationScope()

    @Volatile
    private var repository: SonyHeadphoneRepository? = null

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
    private var lastConnectAttemptMs = 0L

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

    /** Address + which sides report; the connect animation replays when this changes. */
    private var lastConnectAnimationKey: String? = null

    private var commandReceiver: BroadcastReceiver? = null
    private var configReceiver: BroadcastReceiver? = null
    private var capabilityCacheReceiver: BroadcastReceiver? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var remotePreferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var a2dpContext: Context? = null
    private var a2dpListener: BluetoothProfile.ServiceListener? = null

    @Volatile
    private var a2dpProxy: BluetoothProfile? = null

    @Synchronized
    fun start(context: Context, adapterService: Any?, prefsProvider: (() -> SharedPreferences)? = null) {
        adapterService?.let { this.adapterService = it }
        prefsProvider?.let { this.prefsProvider = it }
        // Keep a snapshot of the store for the rare code paths that need a value without
        // re-fetching; the cycle command and the deferred re-read prefer currentPrefs().
        this.prefs = prefsProvider?.invoke()
        if (started) return
        if (scope.coroutineContext[Job]?.isActive != true) scope = newGenerationScope()
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
        Log.d(TAG, "engine started in ${ctx.packageName} moduleContext=${moduleContext != null}")
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

        val prefsStore = currentPrefs()
        remotePreferenceListener?.let { listener ->
            prefsStore?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(listener) } }
        }
        remotePreferenceListener = null
        remoteConfigListenerRegistered = false
        configReceiverRegistered = false
        capabilityCacheReceiverRegistered = false

        val repo = repository
        repository = null
        runCatching { repo?.close() }

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
        appContext = null
        adapterService = null
        prefs = null
        prefsProvider = null
        lastSnapshot = null
        lastRenderedBattery = null
        lastRenderedAddress = null
        lastConnectAnimationKey = null
        Log.d(TAG, "engine generation shut down")
    }

    @Volatile
    private var remoteConfigListenerRegistered = false

    private fun registerRemoteConfigListener() {
        if (remoteConfigListenerRegistered) return
        val p = currentPrefs() ?: return
        remoteConfigListenerRegistered = true
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            runCatching {
                currentPrefs()?.let { ConfigManager.refreshFromPrefs(it) }
                repository?.refreshCapabilityCacheFromPrefs()
                Log.d(TAG, "remote config changed; refreshed; ancCycleModes=${ConfigManager.ancCycleModes()}")
            }.onFailure { Log.w(TAG, "remote config change refresh failed", it) }
        }
        remotePreferenceListener = listener
        p.registerOnSharedPreferenceChangeListener(listener)
        Log.d(TAG, "remote config change listener registered")
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
        if (!repo.state.value.deviceInfo.protocolReady) return
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
        val alreadyLive = current.connectedDevice?.address.equals(address, ignoreCase = true) &&
            current.deviceInfo.protocolReady
        if (alreadyLive && !force) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastConnectAttemptMs < CONNECT_COOLDOWN_MS) return
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
        if (officialAppOwnsTandem) {
            Log.d(TAG, "reconcile skipped while Sound Connect owns Tandem")
            return
        }
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
        try {
            token.linkToDeath(deathRecipient, 0)
        } catch (_: RemoteException) {
            clearOfficialAppLease(reconnect = true, reason = "token already dead")
            return
        }

        lastConnectAttemptMs = 0L
        repository?.disconnect()
        Log.d(TAG, "Sound Connect acquired Tandem lease id=$leaseId; SonyPods disconnected")
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

        if (previousLeaseId == null) return
        Log.d(TAG, "Sound Connect lease cleared id=$previousLeaseId reason=$reason reconnect=$reconnect")
        if (!reconnect) return

        // This is an event-driven hand-back. Force intentionally bypasses the normal
        // reconnect cooldown so the Tandem session is re-established immediately.
        lastConnectAttemptMs = 0L
        val device = connectedSonyDevice()
        if (device == null) {
            Log.d(TAG, "Sound Connect released Tandem but no Sony A2DP device is connected")
            return
        }
        connectDevice(device, force = true)
    }

    /**
     * Everything published while the device is still locked lands in consumers that
     * cannot render it yet — our provider and resources live in credential-encrypted
     * storage. Republish once the user unlocks so the panels and notification catch up.
     */
    private fun registerUnlockReceiver(context: Context) {
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
                            a2dpProxy = proxy
                            Log.d(TAG, "A2DP proxy bound")
                            reconcileConnection()
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.A2DP) a2dpProxy = null
                    }
                }
            a2dpContext = context
            a2dpListener = listener
            adapter.getProfileProxy(
                context,
                listener,
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
        configReceiverRegistered = true
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
        capabilityCacheReceiverRegistered = true
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

            SonyBridge.CMD_DISCONNECT -> repo.disconnect()

            SonyBridge.CMD_REFRESH -> if (repo.state.value.deviceInfo.protocolReady) {
                repo.refreshBasics()
            }

            SonyBridge.CMD_IMAGE_READY -> {
                val imageAddress = intent.getStringExtra(SonyBridge.EXTRA_STRING)
                val current = snapshot()
                if (imageAddress.isNullOrBlank() ||
                    current.deviceAddress?.equals(imageAddress, ignoreCase = true) != true
                ) return
                appContext?.let {
                    // Both the notification and the island carry embedded bitmaps;
                    // invalidate the render guard and replay the current state.
                    lastRenderedAddress = null
                    lastRenderedBattery = null
                    lastConnectAnimationKey = null
                    Log.d(TAG, "model image ready; re-rendering surfaces address=$imageAddress")
                    publish(it, current)
                }
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

        // Sony reports 0 for a bud that is not in place rather than omitting it, which
        // would otherwise be rendered as a real "0%".
        fun pod(level: Int?) = level?.takeIf { it > 0 }?.let { PodParams(battery = it, isConnected = true) }
        val battery = BatteryParams(
            // Fold the single (headband) level into the left slot only for actual
            // single-battery devices; a stale pre-probe BATTERY reading must not
            // masquerade as a TWS left bud.
            left = pod(snapshot.batteryLeft ?: snapshot.batterySingle.takeIf { singleBattery }),
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
                // Headband models report one level; tag the notification so the label
                // reads "电量" (battery) instead of "左"+"%" for the single over-ear value.
                singleBattery = singleBattery,
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
                    singleBattery = singleBattery,
                )
            }
            Log.d(TAG, "xiaomi surfaces updated address=$address newDevice=$isNewDevice single=$singleBattery")
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
