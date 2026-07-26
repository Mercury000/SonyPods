package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction

/**
 * Bluetooth-process bridge (方案 A):
 *  - Watches A2DP connection state; when a Sony device connects, notifies the
 *    module app so it can attach the Sony Tandem transport (GATT/SPP).
 *  - Receives battery updates from the app and injects them into the system
 *    bluetooth stack via AdapterService.setBatteryLevel.
 *  - Answers UI liveness pings.
 */
object HeadsetStateDispatcher : HookContext() {
    private var appRequestReceiverRegistered = false
    private var adapterService: Any? = null

    override fun onHook() {
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                adapterService = instance
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

                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", true)
                    notifyApp(context, OppoPodsAction.ACTION_HOOK_DEVICE_CONNECTED, device)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", false)
                    notifyApp(context, OppoPodsAction.ACTION_HOOK_DEVICE_DISCONNECTED, device)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyApp(context: Context, action: String, device: BluetoothDevice) {
        runCatching {
            context.sendBroadcast(Intent(action).apply {
                setPackage(BuildConfig.APPLICATION_ID)
                putExtra("address", device.address)
                putExtra("device_name", device.name ?: "")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
            Log.d("OppoPods", "notify app $action device=${device.address}")
        }.onFailure {
            Log.w("OppoPods", "notify app failed action=$action", it)
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
                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        injectBatteryLevel(context, intent)
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
            addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
        }, Context.RECEIVER_EXPORTED)
        appRequestReceiverRegistered = true
    }

    /**
     * Injects the aggregated battery level from the app-process Sony repository
     * into the system bluetooth stack so system UI shows headset battery.
     */
    @SuppressLint("MissingPermission")
    private fun injectBatteryLevel(context: Context, intent: Intent) {
        val address = intent.getStringExtra("address") ?: return
        val level = intent.getIntExtra("system_battery_level", -1)
        if (level < 0) return
        runCatching {
            val service = adapterService ?: return
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            val device = adapter.getRemoteDevice(address)
            callMethod(service, "setBatteryLevel", device, level, false)
            Log.d("OppoPods", "injected battery level=$level address=$address")
        }.onFailure {
            Log.w("OppoPods", "setBatteryLevel injection failed address=$address level=$level", it)
        }
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
