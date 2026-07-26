package dev.sonypods.hook

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import com.xzakota.hyper.notification.focus.FocusNotification
import dev.sonypods.BuildConfig
import dev.sonypods.utils.FocusIslandUtil
import dev.sonypods.utils.PodImageLoader
import dev.sonypods.utils.SystemApisUtils
import dev.sonypods.utils.SystemApisUtils.cancelAsUser
import dev.sonypods.utils.SystemApisUtils.notifyAsUser
import dev.sonypods.config.ConfigManager
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import dev.sonypods.R

@SuppressLint("MissingPermission")
object MiBluetoothToastHook : HookContext() {

    override fun onHook() {

        fun deleteIntent(context: Context, bluetoothDevice: BluetoothDevice): PendingIntent? {
            val intent = Intent("com.android.bluetooth.headset.notification.cancle")
            intent.putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
            return PendingIntent.getBroadcast(context, 0, intent, 201326592)
        }

        @SuppressLint("WrongConstant")
        fun createPodsNotification(bluetoothDevice: BluetoothDevice?, context: Context, batteryParams: BatteryParams) {
            val miheadset_notification_Box = context.resources.getIdentifier("miheadset_notification_Box", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_LeftEar = context.resources.getIdentifier("miheadset_notification_LeftEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_RightEar = context.resources.getIdentifier("miheadset_notification_RightEar", "string", "com.xiaomi.bluetooth")
            val miheadset_notification_Disconnect = context.resources.getIdentifier("miheadset_notification_Disconnect", "string", "com.xiaomi.bluetooth")
            val system_notification_accent_color = context.resources.getIdentifier("system_notification_accent_color", "color", "android")
            if (bluetoothDevice == null) {
                Log.e("SonyPods", "createPodsNotification: btDevice null")
                return
            }
            try {
                val address: String = bluetoothDevice.address
                var alias: String? = bluetoothDevice.alias
                if (alias?.isEmpty() == true) {
                    alias = bluetoothDevice.name
                }

                val caseBattStr = if (batteryParams.case != null && batteryParams.case!!.isConnected)
                    "${context.resources.getString(miheadset_notification_Box)}${batteryParams.case!!.battery}%" +
                            "${if (batteryParams.case!!.isCharging) "⚡ " else " "}\n"
                else ""
                val leftEar = if (batteryParams.left != null && batteryParams.left!!.isConnected)
                    "${context.resources.getString(miheadset_notification_LeftEar)}${batteryParams.left!!.battery}%" +
                        (if (batteryParams.left!!.isCharging) "⚡" else "")
                else ""
                val leftToRight = if (batteryParams.left?.isConnected == true && batteryParams.right?.isConnected == true) " " else ""
                val rightEar = if (batteryParams.right != null && batteryParams.right!!.isConnected)
                    "$leftToRight${context.resources.getString(miheadset_notification_RightEar)}${batteryParams.right!!.battery}%" +
                        (if (batteryParams.right!!.isCharging) "⚡ " else " ")
                else ""

                val contentText: String = caseBattStr + leftEar + rightEar
                val notificationManager = context.getSystemService("notification") as NotificationManager
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        "BTHeadset$address",
                        alias,
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        setSound(null, null)
                        setAllowBubbles(true)
                    }
                )
                val bundle = Bundle()
                bundle.putParcelable("Device", bluetoothDevice)
                val intent = Intent("com.android.bluetooth.headset.notification")
                intent.putExtra("btData", bundle)
                intent.putExtra("disconnect", "1")
                intent.setIdentifier("BTHeadset$address")
                val disconnectAction = Notification.Action(
                    285737079,
                    context.resources.getString(miheadset_notification_Disconnect),
                    PendingIntent.getBroadcast(context, 0, intent, 201326592)
                )
                // 循环切换降噪模式：广播路由到模块 App 进程，由 Sony repository 执行三态循环
                val ancCycleIntent = Intent(SonyPodsAction.ACTION_CYCLE_ANC)
                ancCycleIntent.setPackage(BuildConfig.APPLICATION_ID)
                ancCycleIntent.setIdentifier("BTHeadset$address")
                ancCycleIntent.putExtra("device_name", alias ?: bluetoothDevice.name ?: "")
                val moduleContext = context.createPackageContext(
                    "dev.sonypods", Context.CONTEXT_IGNORE_SECURITY
                )
                val headsetBitmap = PodImageLoader.loadBoxBitmap(context, prefs, address)
                    ?: BitmapFactory.decodeResource(moduleContext.resources, R.drawable.img_box)
                if (headsetBitmap == null) {
                    Log.e("SonyPods", "createPodsNotification: headset bitmap null")
                    return
                }
                val headsetIcon = Icon.createWithBitmap(headsetBitmap)
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(SonyPodsAction.ACTION_SHOW_PODS_UI).apply {
                        setClassName("dev.sonypods", "dev.sonypods.PopupActivity")
                        putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
                        putExtra("bluetoothaddress", bluetoothDevice.address)
                        putExtra("device_name", alias)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val focusExtras = FocusNotification.buildV3 {
                    val logo = createPicture("key_headset", headsetIcon)
                    enableFloat = true
                    ticker = alias ?: ""
                    updatable = true
//                    tickerPic = logo

                    iconTextInfo {
                        animIconInfo{
                            type = 0
                            src = logo
                        }
                        title = alias ?: ""
                        content = contentText
                    }

                    island {
                        islandProperty = 1
                        bigIslandArea {
                            imageTextInfoLeft {
                                type = 1
                                picInfo {
                                    type = 1
                                    pic = logo
                                }
                            }
                            imageTextInfoRight {
                                type = 2
                                textInfo {
                                    title = alias ?: ""
                                    content = contentText
                                }
                            }
                        }
                    }


                    textButton {
                        addActionInfo {
                            val ancLabel = moduleContext.getString(R.string.cycle_anc)
                            val ancAction = Notification.Action.Builder(
                                Icon.createWithResource(context, android.R.drawable.ic_lock_silent_mode),
                                ancLabel,
                                PendingIntent.getBroadcast(context, 1, ancCycleIntent, 201326592)
                            ).build()
                            action = createAction("key_anc_cycle", ancAction)
                            actionTitle = ancLabel
                        }
                        addActionInfo {
                            val disconnectLabel = moduleContext.getString(R.string.notification_btn_disconnect)
                            val disconnectIntent = Intent("com.android.bluetooth.headset.notification").apply {
                                putExtra("btData", bundle)
                                putExtra("disconnect", "1")
                                setIdentifier("BTHeadset$address")
                            }
                            val disconnectAction = Notification.Action.Builder(
                                Icon.createWithResource(context, android.R.drawable.ic_delete),
                                disconnectLabel,
                                PendingIntent.getBroadcast(context, 2, disconnectIntent, 201326592)
                            ).build()
                            action = createAction("key_disconnect", disconnectAction)
                            actionTitle = disconnectLabel
                        }
                    }
                }
                // AOD 息屏显示：左右耳电量拼合后注入 aodTitle
                if (focusExtras != null) {
                    val aodParts = mutableListOf<String>()
                    if (batteryParams.left?.isConnected == true)
                        aodParts.add("L ${batteryParams.left!!.battery}%")
                    if (batteryParams.right?.isConnected == true)
                        aodParts.add("R ${batteryParams.right!!.battery}%")
                    val aodTitle = aodParts.joinToString(" | ")
                    try {
                        val json = org.json.JSONObject(focusExtras.getString("miui.focus.param") ?: "{}")
                        val pv2 = json.optJSONObject("param_v2") ?: org.json.JSONObject()
                        pv2.put("aodTitle", aodTitle)
                        pv2.put("aodPic", "key_headset")
                        json.put("param_v2", pv2)
                        focusExtras.putString("miui.focus.param", json.toString())
                    } catch (_: Exception) {}
                }
                notificationManager.notifyAsUser(
                    "BTHeadset$address",
                    10003,
                    Notification.Builder(context, "BTHeadset$address")
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setWhen(0L)
                        .setTicker(alias)
                        .setDefaults(-1)
                        .setContentTitle(alias)
                        .setContentText(contentText)
                        .setContentIntent(pendingIntent)
                        .setDeleteIntent(deleteIntent(context, bluetoothDevice))
                        .setColor(context.getColor(system_notification_accent_color))
                        .addAction(disconnectAction)
                        .apply { focusExtras?.let { addExtras(it) } }
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .build(),
                    SystemApisUtils.getUserAllUserHandle()
                )
            } catch (e: Exception) {
                Log.e("SonyPods", "Failed to create Pod Notification", e)
            }
        }

        fun cancelNotification(bluetoothDevice: BluetoothDevice, context: Context) {
            try {
                val address = bluetoothDevice.address
                if (address.isNotEmpty()) {
                    val notificationManager = context.getSystemService("notification") as NotificationManager
                    notificationManager.cancelAsUser("BTHeadset$address", 10003, SystemApisUtils.getUserAllUserHandle())
                }
            } catch (e: Exception) {
                Log.e("SonyPods", "Failed to cancel Pod Notification!", e)
            }
        }


        hookConstructorAfter(findConstructorByParamCount("com.android.bluetooth.ble.app.MiuiBluetoothNotification", 2)) {
            val context = getObjectField(instance, "mContext") as Context

                    val broadcastReceiver = object : BroadcastReceiver() {
                        override fun onReceive(p0: Context?, p1: Intent?) {
                            if (p1?.action == SonyPodsAction.ACTION_SEND_STRONG_TOAST) {
                                if (ConfigManager.islandMode() != ConfigManager.ISLAND_MODE_MODULE) {
                                    Log.d("SonyPods", "skip module island mode=${ConfigManager.islandMode()}")
                                    return
                                }
                                val batteryParams = p1.getParcelableExtra("batteryParams", BatteryParams::class.java)!!
                                // Use Focus Island (HyperOS 3+) for battery display
                                val address = p1.getStringExtra("address").orEmpty()
                                FocusIslandUtil.showBatteryIsland(context, prefs, batteryParams, address)
                            } else if (p1?.action == SonyPodsAction.ACTION_UPDATE_PODS_NOTIFICATION) {
                                val batteryParams = p1.getParcelableExtra<BatteryParams>("batteryParams", BatteryParams::class.java)
                                val device = p1.getParcelableExtra("device", BluetoothDevice::class.java)
                                createPodsNotification(device, context, batteryParams!!)
                            } else if (p1?.action == SonyPodsAction.ACTION_CANCEL_PODS_NOTIFICATION) {
                                val device = p1.getParcelableExtra("device", BluetoothDevice::class.java) as BluetoothDevice
                                cancelNotification(device, context)
                            }
                        }
                    }

                    val intentFilter = IntentFilter(SonyPodsAction.ACTION_SEND_STRONG_TOAST)
                    intentFilter.addAction(SonyPodsAction.ACTION_UPDATE_PODS_NOTIFICATION)
                    intentFilter.addAction(SonyPodsAction.ACTION_CANCEL_PODS_NOTIFICATION)
                    context.registerReceiver(broadcastReceiver, intentFilter,
                        Context.RECEIVER_EXPORTED)
        }
    }
}
