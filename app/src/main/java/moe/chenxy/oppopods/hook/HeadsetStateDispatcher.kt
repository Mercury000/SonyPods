package moe.chenxy.oppopods.hook

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
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction

object HeadsetStateDispatcher : HookContext() {
    private var appRequestReceiverRegistered = false

    override fun onHook() {
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                registerAppRequestReceiver(instance as? Context)
            }
        }.onFailure {
            Log.w("OppoPods", "AdapterService.onCreate hook skipped", it)
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
                Log.d("OppoPods", "A2DP Connection State: $currState, isSonyPod ${isSonyPod(device)}")
                val context = instance as ContextWrapper
                registerAppRequestReceiver(context)
                if (!isSonyPod(device)) return@post

                // Phase 3 will notify the app process here so it can attach the Sony
                // Tandem transport; for now only the status bar icon is handled.
                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", true)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", false)
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
                    OppoPodsAction.ACTION_PODS_UI_INIT,
                    OppoPodsAction.ACTION_REFRESH_STATUS -> {
                        context.sendBroadcast(Intent(OppoPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        })
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
            addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
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
