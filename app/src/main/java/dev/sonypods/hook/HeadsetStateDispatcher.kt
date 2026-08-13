package dev.sonypods.hook
import com.mercury.sonypods.R

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
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
        receiverContext?.let { startAfterReload(it, snapshot.deviceAddress, snapshot.deviceName) }
    }

    @SuppressLint("MissingPermission")
    internal fun startAfterReload(
        context: Context,
        address: String?,
        name: String?,
        physicalDisconnectAddress: String? = null,
    ) {
        // AdapterService.onCreate will not fire again in a live bluetooth process,
        // so the reloaded generation must re-resolve the running instance itself.
        val adapterService = runCatching {
            context.classLoader.loadClass("com.android.bluetooth.btservice.AdapterService")
                .getDeclaredMethod("getAdapterService")
                .apply { isAccessible = true }
                .invoke(null)
        }.getOrNull()
        // Must happen before start() launches the repository collector and its
        // startup announce. Otherwise the initial Tandem=false snapshot can clear
        // surfaces or look like a new connection during a hot reload.
        SonyEngineHost.restoreHotReloadState(address, physicalDisconnectAddress)
        SonyEngineHost.start(
            context,
            adapterService,
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
            }.onFailure { Log.w("SonyPods", "saved-address reconnect failed address=$address", it) }
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
            Log.d("SonyPods", "AdapterService.onCreate hook skipped", it)
        }

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

                Log.d("SonyPods", "A2DP state=$currState for Sony device ${device.address}")
                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", true)
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
                        SonyEngineHost.disconnectDevice(device)
                    } else {
                        Log.d("SonyPods", "A2DP disconnecting; deferring Tandem teardown for ${device.address}")
                    }
                }
            }
        }
    }

    private var aclReceiverRegistered = false
    private var lastAclRefreshMs = 0L

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
                val now = System.currentTimeMillis()
                if (now - lastAclRefreshMs < 2000L) return
                lastAclRefreshMs = now
                Log.d("SonyPods", "ACL ${if (action == BluetoothDevice.ACTION_ACL_CONNECTED) "connected" else "disconnected"} for Sony device ${device.address}; refreshing state")
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

    /**
     * Detect Sony audio devices by name (mirrors SonyBleClient.isSonyCandidate).
     */
    @SuppressLint("MissingPermission")
    fun isSonyPod(device: BluetoothDevice): Boolean {
        val name = device.name?.trim()?.lowercase() ?: return false
        return name.contains("sony") ||
            name.contains("linkbuds") ||
            name.startsWith("wf-") ||
            name.startsWith("wh-") ||
            name.startsWith("wi-") ||
            name.startsWith("xba-") ||
            name.startsWith("mdr-")
    }
}
