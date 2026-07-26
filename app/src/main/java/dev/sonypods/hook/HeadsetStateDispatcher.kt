package dev.sonypods.hook

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
import dev.sonypods.BuildConfig
import dev.sonypods.utils.SystemApisUtils.setIconVisibility
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction

/**
 * Bluetooth-process entry point. Boots [SonyEngineHost] — which owns the Sony Tandem
 * session for the whole system — and drives it from A2DP connection state.
 */
object HeadsetStateDispatcher : HookContext() {
    private var appRequestReceiverRegistered = false

    override fun onHook() {
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                val context = instance as? Context
                SonyEngineHost.onAdapterService(instance)
                if (context != null) SonyEngineHost.start(context, instance)
                registerAppRequestReceiver(context)
            }
        }.onFailure {
            Log.w("SonyPods", "AdapterService.onCreate hook skipped", it)
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
                SonyEngineHost.start(context, null)
                registerAppRequestReceiver(context)
                if (!isSonyPod(device)) return@post

                Log.i("SonyPods", "A2DP state=$currState for Sony device ${device.address}")
                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", true)
                    SonyEngineHost.connectDevice(device)
                    // Already-live session: a second bud joining shows up here as a state
                    // change, so re-read levels instead of waiting for the next poll.
                    SonyEngineHost.refreshNow("a2dp-connected")
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", false)
                    SonyEngineHost.disconnectDevice(device)
                }
            }
        }
    }

    private fun registerAppRequestReceiver(context: Context?) {
        if (context == null || appRequestReceiverRegistered) return
        context.registerReceiver(object : BroadcastReceiver() {
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
        }, IntentFilter().apply {
            addAction(SonyPodsAction.ACTION_PODS_UI_INIT)
        }, Context.RECEIVER_EXPORTED)
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
