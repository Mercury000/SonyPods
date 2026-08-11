package dev.sonypods.hook

import android.annotation.SuppressLint
import android.app.ActivityOptions
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
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.xzakota.hyper.notification.focus.FocusNotification
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.HookStateMirror
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.utils.FocusIslandUtil
import dev.sonypods.utils.miuiStrongToast.MiuiStrongToastUtil
import dev.sonypods.utils.PodImageLoader
import dev.sonypods.utils.SystemApisUtils
import dev.sonypods.utils.SystemApisUtils.cancelAsUser
import dev.sonypods.utils.SystemApisUtils.notifyAsUser
import dev.sonypods.config.ConfigManager
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import com.mercury.sonypods.R

@SuppressLint("MissingPermission")
object MiBluetoothToastHook : HookContext() {
    private const val POD_DIALOG_PENDING_INTENT_REQUEST_CODE = 10087
    private var receiverContext: Context? = null
    private var notificationReceiver: BroadcastReceiver? = null
    private var unlockReceiver: BroadcastReceiver? = null
    private var stateMirror: HookStateMirror? = null
    private var cloudFallback: HookCloudModelFallback? = null
    private var notificationRenderer: ((Context, BluetoothDevice?, BatteryParams, String?, Boolean) -> Unit)? = null
    private var notificationCanceller: ((Context, BluetoothDevice) -> Unit)? = null
    private val surfaceHandler = Handler(Looper.getMainLooper())

    override fun onBeforeReload() {
        surfaceHandler.removeCallbacksAndMessages(null)
        listOf(notificationReceiver, unlockReceiver).filterNotNull().forEach { receiver ->
            unregisterReceiverForReload(receiverContext, receiver)
        }
        notificationReceiver = null
        unlockReceiver = null
        stateMirror?.close()
        stateMirror = null
        cloudFallback?.close()
        cloudFallback = null
        PodImageLoader.remoteImageReader = null
        PodImageLoader.temporaryImageReader = null
    }

    override fun onReloadRejected(snapshot: SonyStateSnapshot) {
        receiverContext?.let { startAfterReload(it) }
    }

    override fun onHook() {

        fun deleteIntent(context: Context, bluetoothDevice: BluetoothDevice): PendingIntent? {
            val intent = Intent("com.android.bluetooth.headset.notification.cancle")
            intent.putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
            return PendingIntent.getBroadcast(context, 0, intent, 201326592)
        }

        @SuppressLint("WrongConstant")
        fun createPodsNotification(bluetoothDevice: BluetoothDevice?, context: Context, batteryParams: BatteryParams, sourceColor: String? = null, singleBattery: Boolean = false) {
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
                // Over-ear headphones report a single level; label it "电量" rather than "左".
                val leftLabel = if (singleBattery) "电量" else context.resources.getString(miheadset_notification_LeftEar)
                val leftEar = if (batteryParams.left != null && batteryParams.left!!.isConnected)
                    "$leftLabel${batteryParams.left!!.battery}%" +
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
                val disconnectAction = Notification.Action.Builder(
                    Icon.createWithResource(context, 285737079),
                    context.resources.getString(miheadset_notification_Disconnect),
                    PendingIntent.getBroadcast(context, 0, intent, 201326592),
                ).build()
                // 循环切换降噪：命令直接送到蓝牙进程的引擎，不经过模块 App，
                // 这样 App 未运行时通知栏按钮依然有效。
                val ancCycleIntent = Intent(SonyBridge.ACTION_COMMAND)
                    .putExtra(SonyBridge.EXTRA_COMMAND, SonyBridge.CMD_CYCLE_NOISE_CONTROL)
                ancCycleIntent.setPackage(SonyBridge.ENGINE_PACKAGE)
                ancCycleIntent.setIdentifier("BTHeadset$address")
                ancCycleIntent.putExtra("device_name", alias ?: bluetoothDevice.name ?: "")
                val moduleContext = context.createPackageContext(
                    "com.mercury.sonypods", Context.CONTEXT_IGNORE_SECURITY
                )
                // The remote-preferences object captured when this hook process
                // started can be a stale snapshot. Re-fetch it after the app has
                // downloaded an image so the new image path is visible here.
                val imagePrefs = runCatching { prefsProvider() }.getOrElse { prefs }
                // Before the user unlocks, our ContentProvider and resources are not
                // reachable ("user not unlocked"). Post the notification anyway with a
                // system icon rather than dropping it for the whole session.
                val headsetBitmap = runCatching { PodImageLoader.loadBoxBitmap(context, imagePrefs, address) }.getOrNull()
                    ?: runCatching { BitmapFactory.decodeResource(moduleContext.resources, R.drawable.img_box) }.getOrNull()
                if (headsetBitmap == null) {
                    Log.d("SonyPods", "createPodsNotification: no headset bitmap yet, using system icon")
                }
                val headsetIcon = headsetBitmap?.let { Icon.createWithBitmap(it) }
                    ?: Icon.createWithResource(context, android.R.drawable.stat_sys_data_bluetooth)
                val activityOptions = ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
                    )
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    POD_DIALOG_PENDING_INTENT_REQUEST_CODE,
                    Intent(SonyPodsAction.ACTION_SHOW_PODS_UI).apply {
                        setClassName("com.mercury.sonypods", "dev.sonypods.PopupActivity")
                        putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
                        putExtra("bluetoothaddress", bluetoothDevice.address)
                        putExtra("device_name", alias)
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    activityOptions.toBundle(),
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
                val aodParts = mutableListOf<String>()
                if (batteryParams.left?.isConnected == true)
                    aodParts.add(if (singleBattery) "电量${batteryParams.left!!.battery}%" else "L ${batteryParams.left!!.battery}%")
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
                notificationManager.notifyAsUser(
                    "BTHeadset$address",
                    10003,
                    Notification.Builder(context, "BTHeadset$address")
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setWhen(0L)
                        .setTicker(alias)
                        .setContentTitle(alias)
                        .setContentText(contentText)
                        .setContentIntent(pendingIntent)
                        .setDeleteIntent(deleteIntent(context, bluetoothDevice))
                        .setColor(
                            sourceColor?.let { runCatching { Color.parseColor("#$it") }.getOrNull() }
                                ?: context.getColor(system_notification_accent_color)
                        )
                        .addAction(disconnectAction)
                        .addExtras(focusExtras)
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

        notificationRenderer = { context, device, battery, sourceColor, singleBattery ->
            createPodsNotification(device, context, battery, sourceColor, singleBattery)
        }
        notificationCanceller = { context, device ->
            cancelNotification(device, context)
        }

        hookConstructorAfter(findConstructorByParamCount("com.android.bluetooth.ble.app.MiuiBluetoothNotification", 2)) {
            val context = getObjectField(instance, "mContext") as Context
            registerNotificationReceiver(context)
            // This class is constructed well after boot, so anything the engine
            // rendered before now was lost; ask it to render again.
            announceSurfacesReady(context)
            registerUnlockReceiver(context)
        }
    }

    /** Rebind receivers and request a surface replay when package callbacks are not replayed. */
    internal fun startAfterReload(context: Context) {
        registerNotificationReceiver(context)
        announceSurfacesReady(context)
        registerUnlockReceiver(context)
    }

    fun launchConnectPopup(context: Context, device: BluetoothDevice?, address: String, deviceName: String?) {
        // Start from the bluetooth process: mirror the notification's PendingIntent
        // (same request code / FLAG_UPDATE_CURRENT) so the popup behaves exactly like
        // tapping the island, including the background-activity-start allowance.
        runCatching {
            val activityOptions = ActivityOptions.makeBasic().apply {
                setPendingIntentCreatorBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
                )
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                POD_DIALOG_PENDING_INTENT_REQUEST_CODE,
                Intent(SonyPodsAction.ACTION_SHOW_PODS_UI).apply {
                    setClassName("com.mercury.sonypods", "dev.sonypods.PopupActivity")
                    device?.let { putExtra("android.bluetooth.device.extra.DEVICE", it) }
                    putExtra("bluetoothaddress", address)
                    deviceName?.let { putExtra("device_name", it) }
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                activityOptions.toBundle(),
            )
            pendingIntent.send()
            Log.d("SonyPods", "popup on connect launched $address")
        }.onFailure { Log.e("SonyPods", "popup on connect failed", it) }
    }

    private fun registerNotificationReceiver(context: Context) {
        if (notificationReceiver != null) return
        val render = notificationRenderer
            ?: throw IllegalStateException("notification renderer is not initialized")
        val cancel = notificationCanceller
            ?: throw IllegalStateException("notification canceller is not initialized")
        val broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                when (intent?.action) {
                    SonyPodsAction.ACTION_SEND_STRONG_TOAST -> {
                        val batteryParams = intent.getParcelableExtra("batteryParams", BatteryParams::class.java)
                            ?: return
                        val address = intent.getStringExtra("address").orEmpty()
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java)
                        val single = intent.getBooleanExtra(MiuiStrongToastUtil.EXTRA_SINGLE_BATTERY, false)
                        val showIsland = intent.getBooleanExtra(MiuiStrongToastUtil.EXTRA_SHOW_ISLAND, true)
                        val islandFirstFloat = if (intent.hasExtra(MiuiStrongToastUtil.EXTRA_ISLAND_FIRST_FLOAT)) {
                            intent.getBooleanExtra(MiuiStrongToastUtil.EXTRA_ISLAND_FIRST_FLOAT, true)
                        } else {
                            null
                        }
                        val deviceName = intent.getStringExtra("deviceName")
                        // Pull the freshest config at render time. The app persists the
                        // full config into remote prefs on every change; re-reading here
                        // keeps island mode/duration current even if a config push
                        // broadcast was missed (e.g. receiver lost across a hot reload).
                        val livePrefs = runCatching { prefsProvider() }.getOrElse { prefs }
                        runCatching { ConfigManager.refreshFromPrefs(livePrefs) }
                        if (showIsland) {
                            // PopupActivity re-submits the island (CMD_SURFACES_READY with
                            // EXTRA_ISLAND_FIRST_FLOAT=false) after the popup closes so the
                            // island reappears. That replay is not a new connection and must
                            // not relaunch the popup; only a genuine first render (no extra)
                            // may trigger it.
                            if (ConfigManager.popupOnConnect() && islandFirstFloat == null) {
                                launchConnectPopup(context, device, address, deviceName)
                            }
                            when (ConfigManager.islandMode()) {
                                ConfigManager.ISLAND_MODE_MODULE ->
                                    FocusIslandUtil.showBatteryIsland(
                                        context,
                                        livePrefs,
                                        batteryParams,
                                        address,
                                        singleBattery = single,
                                        deviceName = deviceName,
                                        device = device,
                                        durationSeconds = ConfigManager.islandDurationSeconds(),
                                        islandFirstFloat = islandFirstFloat ?: true,
                                    )
                                ConfigManager.ISLAND_MODE_OFFICIAL ->
                                    MiuiStrongToastUtil.showOfficialConnectToast(context, batteryParams, single)
                                else ->
                                    Log.d("SonyPods", "island disabled mode=${ConfigManager.islandMode()}")
                            }
                        } else if (ConfigManager.islandMode() == ConfigManager.ISLAND_MODE_MODULE) {
                            FocusIslandUtil.updateBatteryIsland(
                                context,
                                livePrefs,
                                batteryParams,
                                address,
                                singleBattery = single,
                                deviceName = deviceName,
                                device = device,
                            )
                        }
                    }
                    SonyPodsAction.ACTION_UPDATE_PODS_NOTIFICATION -> {
                        val batteryParams = intent.getParcelableExtra<BatteryParams>(
                            "batteryParams",
                            BatteryParams::class.java,
                        ) ?: return
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java)
                        val sourceColor = intent.getStringExtra(MiuiStrongToastUtil.EXTRA_SOURCE_COLOR)
                        val singleBattery = intent.getBooleanExtra(MiuiStrongToastUtil.EXTRA_SINGLE_BATTERY, false)
                        render(context, device, batteryParams, sourceColor, singleBattery)
                    }
                    SonyPodsAction.ACTION_CANCEL_BATTERY_ISLAND ->
                        FocusIslandUtil.cancelBatteryIsland(context)
                    SonyPodsAction.ACTION_CANCEL_PODS_NOTIFICATION -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        cancel(context, device)
                    }
                }
            }
        }
        val intentFilter = IntentFilter(SonyPodsAction.ACTION_SEND_STRONG_TOAST).apply {
            addAction(SonyPodsAction.ACTION_UPDATE_PODS_NOTIFICATION)
            addAction(SonyPodsAction.ACTION_CANCEL_PODS_NOTIFICATION)
            addAction(SonyPodsAction.ACTION_CANCEL_BATTERY_ISLAND)
        }
        context.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        notificationReceiver = broadcastReceiver
        receiverContext = context
        registerCloudFallback(context)
        Log.d("SonyPods", "notification/island receiver registered")
    }

    private fun registerCloudFallback(context: Context) {
        if (cloudFallback != null) return
        val fallback = HookCloudModelFallback(
            context = context,
            remoteFileReader = remoteFileReader,
            onCatalogReady = {},
            onImageReady = { address -> SonyBridge.imageReady(context, address) },
        )
        cloudFallback = fallback
        PodImageLoader.temporaryImageReader = { address -> fallback.temporaryBitmap(address) }
        val mirror = HookStateMirror { snapshot -> fallback.onState(snapshot) }
        stateMirror = mirror
        mirror.register(context)
    }

    /**
     * Until the user unlocks, our ContentProvider and resources are unavailable, so a
     * notification rendered during that window has no headphone image and the island
     * cannot be built. Nothing re-renders on its own afterwards — state is only pushed
     * on change — so re-run the handshake once the device becomes usable.
     */
    private fun registerUnlockReceiver(context: Context) {
        if (unlockReceiver != null) return
        runCatching {
            val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        Log.d("SonyPods", "user unlocked (${intent?.action}); re-rendering surfaces")
                        announceSurfacesReady(context)
                    }
                }
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_USER_UNLOCKED)
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_BOOT_COMPLETED)
                },
                Context.RECEIVER_EXPORTED,
            )
            unlockReceiver = receiver
            receiverContext = context
        }.onFailure { Log.w("SonyPods", "unlock receiver registration failed", it) }
    }

    private fun announceSurfacesReady(context: Context) {
        surfaceHandler.removeCallbacksAndMessages(null)
        repeat(SURFACES_READY_ATTEMPTS) { attempt ->
            surfaceHandler.postDelayed(
                { SonyBridge.sendCommand(context, SonyBridge.CMD_SURFACES_READY) },
                attempt * SURFACES_READY_INTERVAL_MS,
            )
        }
    }

    private const val SURFACES_READY_ATTEMPTS = 4
    private const val SURFACES_READY_INTERVAL_MS = 4_000L
}
