package dev.sonypods.hook
import com.mercury.sonypods.R

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import com.mercury.sonypods.BuildConfig
import dev.sonypods.config.CloudModelInfoStore
import dev.sonypods.utils.SystemApisUtils.setIconVisibility
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.device.SonyDeviceService

/**
 * Bluetooth-process entry point. Boots [SonyEngineHost] — which owns the Sony Tandem
 * session for the whole system — and drives it from A2DP connection state.
 */
object HeadsetStateDispatcher : HookContext() {
    private var appRequestReceiverRegistered = false
    private var appRequestReceiver: BroadcastReceiver? = null
    private var aclReceiver: BroadcastReceiver? = null
    private var receiverContext: Context? = null

    override fun onBeforeReload() {
        SonyEngineHost.shutdown()
        listOf(appRequestReceiver, aclReceiver).filterNotNull().forEach { receiver ->
            unregisterReceiverForReload(receiverContext, receiver)
        }
        appRequestReceiver = null
        aclReceiver = null
        appRequestReceiverRegistered = false
        aclReceiverRegistered = false
    }

    override fun onReloadRejected(snapshot: SonyStateSnapshot) {
        receiverContext?.let {
            startAfterReload(
                context = it,
                address = SonyEngineHost.reloadDeviceAddress() ?: snapshot.deviceAddress,
                name = snapshot.deviceName,
            )
        }
    }

    @SuppressLint("MissingPermission")
    internal fun startAfterReload(
        context: Context,
        address: String?,
        name: String?,
    ) {
        SonyDeviceService.rememberAddress(address)
        // Must happen before start() launches the repository collector and its
        // startup announce. The reload's own reconnect is always a recovery,
        // never a fresh connection, so the tracker is restored accordingly.
        SonyEngineHost.restoreHotReloadState(address)
        // No AdapterService instance to hand over: onCreate will not fire again in a live
        // bluetooth process, so the host recovers the running singleton from the class on demand.
        SonyEngineHost.start(
            context,
            null,
            prefsProvider,
            remoteModelInfoReader = cloudModelInfoReader(),
            remoteFileReader = remoteFileReader,
        )
        registerAppRequestReceiver(context)
        registerAclReceiver(context)
        if (!address.isNullOrBlank()) {
            runCatching {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                    ?.adapter?.getRemoteDevice(address)
                    ?.let { SonyEngineHost.connectDevice(it, force = true) }
            }.onFailure { Log.w("SonyPods-Engine", "saved-address reconnect failed address=$address", it) }
        }
    }

    override fun onHook() {
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                val context = instance as? Context
                SonyEngineHost.onAdapterService(instance)
                if (context != null) SonyEngineHost.start(
                    context,
                    instance,
                    prefsProvider,
                    remoteModelInfoReader = cloudModelInfoReader(),
                    remoteFileReader = remoteFileReader,
                )
                registerAppRequestReceiver(context)
                registerAclReceiver(context)
            }
        }.onFailure {
            Log.d("SonyPods-Engine", "AdapterService.onCreate hook skipped", it)
        }

        hookLeAudioConnectionState()
        hookLeAudioActiveDevice()
        hookDeviceLevelDisconnect()

        hookAfter(findMethodByParamCount("com.android.bluetooth.a2dp.A2dpService", "handleConnectionStateChanged", 3)) {
            val currState = args[2] as Int
            val fromState = args[1] as Int
            val device = args[0] as BluetoothDevice?
            val handler = getObjectField(instance, "mHandler") as Handler
            if (device == null || currState == fromState) {
                return@hookAfter
            }
            handler.post {
                val context = instance as ContextWrapper
                SonyEngineHost.start(
                    context,
                    null,
                    prefsProvider,
                    remoteModelInfoReader = cloudModelInfoReader(),
                    remoteFileReader = remoteFileReader,
                )
                registerAppRequestReceiver(context)
                if (!isSonyPod(device)) return@post

                Log.d("SonyPods-Engine", "A2DP state=$currState for Sony device ${device.address}")
                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", true)
                    SonyEngineHost.onLinkConnected(device.address)
                    SonyEngineHost.connectDevice(device)
                    // Already-live session: a second bud joining shows up here as a state
                    // change, so re-read levels instead of waiting for the next poll.
                    SonyEngineHost.refreshNow("a2dp-connected")
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", false)
                    // DISCONNECTING is only an intermediate A2DP state.  Tandem/GATT
                    // may be rebuilding at the same time, so treating it as a
                    // physical disconnect clears the recovery identity too early and
                    // the following same-address transport reconnect looks new.  Only
                    // the terminal profile state is authoritative here.
                    if (currState == BluetoothHeadset.STATE_DISCONNECTED) {
                        // Notify the engine before teardown so a pending LE Audio enable
                        // can proceed now that classic profiles are down.
                        SonyEngineHost.onClassicProfileDisconnected(device)
                        SonyEngineHost.disconnectDevice(device)
                    } else {
                        Log.d("SonyPods-Engine", "A2DP disconnecting; deferring Tandem teardown for ${device.address}")
                    }
                }
            }
        }
    }

    private var aclReceiverRegistered = false
    private var lastAclRefreshMs = 0L
    private var lastLeAudioRefreshMs = 0L

    /**
     * A route change lands as a burst — the group is confirmed active, volume control follows — and
     * one re-read covers the burst. Shorter than the ACL debounce because this fires far less often.
     */
    private const val LE_AUDIO_REFRESH_DEBOUNCE_MS = 1_500L

    /** Hook-side catalog access; the module app's ordinary files/prefs are not visible here. */
    private fun cloudModelInfoReader(): () -> String? = {
        readRemoteFileText(CloudModelInfoStore.REMOTE_FILE_NAME)
    }

    /**
     * Refresh headphone state when a single bud connects or disconnects. The headset
     * may not push a NTFY for a per-bud link change, and since periodic status polling
     * was removed there is no other trigger to re-fetch — so we listen for the Android
     * ACL connect/disconnect of a Sony device and trigger one event-driven refresh.
     * This is not polling: it only fires on an actual link change.
     *
     * The disconnect side additionally carries the terminal-power-off signal for headsets whose
     * control identity is not an LE Audio member — see [SonyEngineHost.onAclDisconnected].
     */
    private fun registerAclReceiver(context: Context?) {
        if (context == null || aclReceiverRegistered) return
        aclReceiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
                val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                if (!isSonyPod(device)) return
                val connected = action == BluetoothDevice.ACTION_ACL_CONNECTED
                // ACL link changes (e.g. single bud in/out of case or SPP socket reset) only
                // trigger an event-driven battery/status refresh. Genuine physical disconnects
                // are solely owned by A2DP and LE Audio profile state machine transitions.
                val now = System.currentTimeMillis()
                if (now - lastAclRefreshMs < 2000L) return
                lastAclRefreshMs = now
                Log.d("SonyPods-Engine", "ACL ${if (connected) "connected" else "disconnected"} for Sony device ${device.address}; refreshing state")
                SonyEngineHost.refreshNow("bud-acl")
            }
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }, Context.RECEIVER_EXPORTED)
        aclReceiver = receiver
        receiverContext = context
    }

    /**
     * Drives session start from the LE Audio profile state machine, mirroring the A2DP hook.
     *
     * With LE Audio active neither A2DP nor a classic ACL exists: the audio link is LE, so
     * both existing triggers stay silent and the module would sit idle until something else
     * forced a connect. We live in this process, so instead of subscribing to the framework
     * broadcast (the app-level pattern Sound Connect uses) we hook the state machine's own
     * transition point — synchronous, ordered before any broadcast marshalling, and immune
     * to receiver scheduling.
     *
     * Signature verified against a HyperOS bluetooth.apk: LeAudioStateMachine
     * .broadcastConnectionState reaches LeAudioService.notifyConnectionStateChanged(device,
     * current, previous), whose 4-arg overload merely forwards into the 3-arg one doing the
     * real work. Mind the state order — (current, previous), the reverse of the A2DP hook.
     */
    private fun hookLeAudioConnectionState() {
        runCatching {
            hookAfter(findMethodByParamCount(
                "com.android.bluetooth.le_audio.LeAudioService",
                "notifyConnectionStateChanged",
                3,
            )) {
                val device = args.getOrNull(0) as? BluetoothDevice
                val currState = args.getOrNull(1) as? Int
                val prevState = args.getOrNull(2) as? Int
                if (device == null || currState == null || prevState == null) return@hookAfter
                onLeAudioStateChanged(instance, device, currState, prevState)
            }
        }.onFailure {
            Log.d("SonyPods-Engine", "LeAudioService.notifyConnectionStateChanged hook skipped", it)
        }
    }

    private fun onLeAudioStateChanged(
        serviceInstance: Any?,
        device: BluetoothDevice,
        currState: Int,
        prevState: Int,
    ) {
        if (!isSonyPod(device)) return
        Log.d("SonyPods-Engine", "LE Audio state=$currState for ${device.address}")
        if (currState == prevState) return
        // Before the identity folding below, because what the stack holds is group-wide: either
        // identity connecting or dropping changes the answer, and the surfaces have no other way
        // to learn it.
        onLeAudioSystemStateMoved(serviceInstance, "le-audio-state-$currState")

        // Both coordinated-set members reach CONNECTED, but only the classic identity
        // carries Sony's services. Acting on the lead earbud's resolvable LE identity is
        // wrong twice over: the session would never handshake on an identity with no Sony
        // GATT server, and dialing the control counterpart while its own CIS is still
        // forming collides with link establishment and stalls both for tens of seconds.
        // So fold identities first and only answer for the control identity — mirroring
        // SonyBleClient's resolveControlTarget.
        val controlAddress = resolveControlAddress(serviceInstance, device)
        val fromControlIdentity =
            controlAddress == null || controlAddress.equals(device.address, ignoreCase = true)
        when (currState) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (!fromControlIdentity) {
                    // Deferring is only right when the control identity is itself an LE Audio
                    // device — otherwise nothing ever announces it and the session never
                    // starts, since under LC3 there is no A2DP transition either. The profile
                    // answers this directly: a device reaches notifyConnectionStateChanged only
                    // if it holds a LeAudioDeviceDescriptor, and getGroupDevices lists exactly
                    // the descriptors in that group.
                    val group = leAudioGroupAddresses(serviceInstance, device)
                    if (group == null || controlAddress.uppercase() in group) {
                        Log.d(
                            "SonyPods-Engine",
                            "Deferring ${device.address}; control identity $controlAddress " +
                                "will announce separately",
                        )
                        return
                    }
                    val control = remoteControlDevice(serviceInstance, controlAddress) ?: return
                    Log.d(
                        "SonyPods-Engine",
                        "Control identity $controlAddress is outside the LE Audio group " +
                            "$group; connecting it from ${device.address}",
                    )
                    postToProfileHandler(serviceInstance) {
                        SonyEngineHost.onLinkConnected(control.address)
                    SonyEngineHost.connectDevice(control)
                    }
                    return
                }
                postToProfileHandler(serviceInstance) {
                    SonyEngineHost.onLinkConnected(device.address)
                    SonyEngineHost.connectDevice(device)
                }
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                // The classic path learns physical power-off from A2DP; under LE Audio no
                // other signal marks it, and without terminal teardown the surfaces stay
                // preserved "for recovery" forever. But a coordinated-set member leaving is
                // routine, not terminal — one earbud back in the case drops its own CIS while
                // the other keeps playing, and the control link is untouched. Folding that
                // member's drop onto the control identity would tear down a session that is
                // still exchanging frames, the same mistake the A2DP path made under LC3.
                // Only the control identity's own transition ends the session; when the
                // headset really powers off, that transition follows.
                if (!fromControlIdentity) {
                    Log.d(
                        "SonyPods-Engine",
                        "LE Audio drop of ${device.address} is a set member; " +
                            "control identity $controlAddress still owns the session",
                    )
                    return
                }
                postToProfileHandler(serviceInstance) {
                    SonyEngineHost.disconnectDevice(device, forceTeardown = true)
                }
            }
        }
    }

    /**
     * Hooks the moment the stack changes which device owns the LE Audio audio route.
     *
     * `LeAudioService.notifyActiveDeviceChanged` is where the profile has settled on a new active
     * group — it drives the volume-control service, the framework broadcast and `groupConfirmActive`
     * from there — and it is also called with a null device when the route is given up. That
     * transition is the system establishing or dropping LC3, which is exactly what the "低功耗音频"
     * summary and the LE-Audio-only feature restrictions have to follow. Nothing else announces it
     * to us: it moves no headset state, so no Tandem notification arrives and the repository emits
     * nothing.
     */
    private fun hookLeAudioActiveDevice() {
        runCatching {
            hookAfter(findMethodByParamCount(
                "com.android.bluetooth.le_audio.LeAudioService",
                "notifyActiveDeviceChanged",
                1,
            )) {
                val device = args.getOrNull(0) as? BluetoothDevice
                // A null device means "no LE Audio route any more", which concerns us whatever
                // it was before; a named one is only ours to act on if it is a Sony.
                if (device != null && !isSonyPod(device)) return@hookAfter
                onLeAudioSystemStateMoved(instance, "le-audio-active-${device?.address ?: "none"}")
            }
        }.onFailure {
            Log.d("SonyPods-Engine", "LeAudioService.notifyActiveDeviceChanged hook skipped", it)
        }
    }

    /**
     * The engine half of the dual-identity circulate race fix.
     *
     * A device-level disconnect — the Settings "断开连接" button or a circulate release —
     * enters the stack through `AdapterService.disconnectAllEnabledProfiles` (verified in
     * bluetooth.apk jadx: it is the only device-level disconnect entry). Under LC3 the
     * Tandem session rides a raw GATT client on the LE identity, and `disconnectAllEnabledProfiles`
     * only tears down *profiles* — never that client — so the LE ACL survives the release by
     * ~1.7 s (measured: our GATT holds it ~700 ms, then the stack's 1 s link idle timer).
     * The target host's connect command leaves the source 3–8 ms after the release and lands
     * inside that window, which is the probabilistic transfer drop. Pure classic has no such
     * GATT holder, tears down immediately, and never drops.
     *
     * Closing the Tandem session at the release entry makes the LE ACL fall on its own,
     * exactly like a pure-classic teardown — the race disappears without delaying the
     * target's connect at all. This replaces the former milink-side settle gate, whose
     * ACL-down broadcast never reaches the milink process.
     */
    private fun hookDeviceLevelDisconnect() {
        // The binder entry may live on AdapterService or on its IBluetooth binder inner class
        // (AOSP: AdapterService$BluetoothServiceBinder) depending on the ROM, so search the
        // class AND every declared inner class for the method name rather than betting on one
        // declaration site. Non-device overloads no-op on the args guard below.
        val candidateClasses = buildList {
            val adapter = "com.android.bluetooth.btservice.AdapterService"
            add(adapter)
            runCatching { findClass(adapter).declaredClasses.map { it.name } }
                .getOrElse { emptyList() }
                .forEach { add(it) }
        }
        var hooked = 0
        candidateClasses.forEach { className ->
            val methods = runCatching {
                findClass(className).declaredMethods
                    .filter { it.name == "disconnectAllEnabledProfiles" }
            }.getOrElse { emptyList() }
            methods.forEach { method ->
                runCatching {
                    hookBefore(
                        method,
                        logicalRole = "adapter-device-level-disconnect:$className:" +
                            method.parameterTypes.joinToString(",") { it.simpleName },
                    ) {
                        armDeviceLevelDisconnect(args.getOrNull(0) as? BluetoothDevice)
                    }
                    hooked++
                }.onFailure {
                    Log.w("SonyPods-Engine", "hook $className.disconnectAllEnabledProfiles failed", it)
                }
            }
        }
        if (hooked == 0) {
            Log.w(
                "SonyPods-Engine",
                "hook AdapterService.disconnectAllEnabledProfiles skipped: no overload found " +
                    "in $candidateClasses",
            )
        } else {
            Log.d("SonyPods-Engine", "hooked $hooked disconnectAllEnabledProfiles overload(s) for device-level release")
        }
    }

    private fun armDeviceLevelDisconnect(device: BluetoothDevice?) {
        val target = device ?: return
        if (!isSonyPod(target)) return
        val address = runCatching { target.address }.getOrNull() ?: return
        Handler(android.os.Looper.getMainLooper()).post {
            SonyEngineHost.onDeviceLevelDisconnect(address)
        }
    }

    /**
     * The stack's LE Audio position moved: republish it, and re-read the headset.
     *
     * Two separate jobs. The republish carries the system-side facts — connected, and routing LC3 —
     * that only this process can read and that no repository state change would ever push out. The
     * refresh is for the headset's own view: its LEA streaming status and, in particular, its
     * playback availability are reported once and then latched, so a DISABLE captured while the
     * link was being handed over leaves the playback controls greyed out for the rest of the
     * session. Re-reading on the route change is the same event-driven pattern the A2DP hook
     * already uses for a second bud joining.
     *
     * Posted onto the profile's own handler: the hooked methods run under the profile's group lock
     * and both halves of this read it back.
     */
    private fun onLeAudioSystemStateMoved(serviceInstance: Any?, reason: String) {
        postToProfileHandler(serviceInstance) {
            SonyEngineHost.republishLeAudioState(reason)
            val now = System.currentTimeMillis()
            if (now - lastLeAudioRefreshMs < LE_AUDIO_REFRESH_DEBOUNCE_MS) return@postToProfileHandler
            lastLeAudioRefreshMs = now
            SonyEngineHost.refreshNow(reason)
        }
    }

    /**
     * Addresses the profile itself counts as members of this device's LE Audio group, or null
     * when that could not be read.
     *
     * `LeAudioService.getGroupDevices(BluetoothDevice)` is `getGroupDevices(getGroupId(device))`,

     * which walks `mDeviceDescriptors` for the matching group id — so membership here is the
     * same thing as "that address will announce on its own". Descriptors are created by
     * `connect()`, which rejects a remote without the LE_AUDIO UUID, and by
     * `handleGroupNodeAdded`, which the native stack raises for every stored set member at
     * adapter start; both precede any member's CONNECTED. An empty set is a real answer: the
     * device has no coordinated set, so no counterpart will announce. Taking the group read
     * lock here is safe — the hooked method calls `getConnectedDevices()` on that same lock.
     */
    private fun leAudioGroupAddresses(serviceInstance: Any?, device: BluetoothDevice): Set<String>? =
        runCatching {
            val service = serviceInstance ?: return null
            // Resolved by parameter type: getGroupDevices is overloaded on BluetoothDevice
            // and on the raw group id, and both take one argument.
            val members = service.javaClass
                .getMethod("getGroupDevices", BluetoothDevice::class.java)
                .invoke(service, device) as? List<*>
                ?: return null
            members.filterIsInstance<BluetoothDevice>()
                .mapNotNull { runCatching { it.address?.uppercase() }.getOrNull() }
                .toSet()
        }.getOrNull()

    private fun postToProfileHandler(serviceInstance: Any?, block: () -> Unit) {
        // Serialized with profile internals like the A2DP path; connectDevice itself dedupes
        // already-live sessions and in-flight attempts.
        val handler = runCatching { getObjectField(serviceInstance, "mHandler") as Handler }.getOrNull()
            ?: Handler(android.os.Looper.getMainLooper())
        handler.post(block)
    }

    /** Refreshes the LE-Audio-identity -> control-address map from current bonds. */
    @SuppressLint("MissingPermission")
    private fun resolveControlAddress(serviceInstance: Any?, device: BluetoothDevice): String? {
        val adapter = (serviceInstance as? Context)
            ?.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            ?: return device.address
        val bonded = adapter.bondedDevices.orEmpty()
        SonyDeviceService.linkLeAudioIdentities(bonded)
        return SonyDeviceService.resolveControlAddress(device.address) ?: device.address
    }

    @SuppressLint("MissingPermission")
    private fun remoteControlDevice(serviceInstance: Any?, address: String): BluetoothDevice? =
        (serviceInstance as? Context)
            ?.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            ?.runCatching { getRemoteDevice(address) }
            ?.getOrNull()

    private fun registerAppRequestReceiver(context: Context?) {
        if (context == null || appRequestReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null) return
                when (intent?.action) {
                    SonyPodsAction.ACTION_PODS_UI_INIT -> {
                        context.sendBroadcast(Intent(SonyPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        })
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(SonyPodsAction.ACTION_PODS_UI_INIT)
        }, Context.RECEIVER_EXPORTED)
        appRequestReceiver = receiver
        receiverContext = context
        appRequestReceiverRegistered = true
    }

    /** Detect Sony devices through the shared identity service. */
    @SuppressLint("MissingPermission")
    fun isSonyPod(device: BluetoothDevice): Boolean {
        return SonyDeviceService.isSony(device)
    }
}
