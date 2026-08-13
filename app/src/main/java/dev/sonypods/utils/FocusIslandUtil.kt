package dev.sonypods.utils
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import com.xzakota.hyper.notification.focus.FocusNotification
import dev.sonypods.config.ConfigManager
import dev.sonypods.hook.Log
import dev.sonypods.utils.PodImageLoader
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import com.mercury.sonypods.R

@SuppressLint("WrongConstant")
object FocusIslandUtil {
    private const val TAG = "SonyPods-FocusIsland"
    private const val CHANNEL_ID = "sonypods_focus_island"
    private const val CHANNEL_NAME = "SonyPods Battery"
    private const val NOTIFICATION_ID = 10086
    // Keep the island entry separate from notification/automatic-popup entries:
    // PendingIntent identity does not include extras, so sharing a request code
    // would let a later entry overwrite the source marker.
    private const val POD_DIALOG_ISLAND_PENDING_INTENT_REQUEST_CODE = 10088
    private const val MODULE_PACKAGE = "com.mercury.sonypods"
    private const val IOS_CHARGING_GREEN = "#34C759"

    private val mainHandler = Handler(Looper.getMainLooper())
    // Single pending dismissal: each (re-)post reschedules it, so an earlier
    // island's timer cannot kill a newer in-place update prematurely.
    private var dismissRunnable: Runnable? = null
    // A replay is intentionally delayed until HyperOS finishes removing the
    // consumed notification. Keep it cancellable so a disconnect cannot resurrect
    // the island after the device is gone.
    private var repostRunnable: Runnable? = null
    private var islandVisible = false
    private var islandExpiresAtMillis = 0L

    /** Immediately remove the island notification (device disconnected). */
    fun cancelBatteryIsland(context: Context) {
        dismissRunnable?.let(mainHandler::removeCallbacks)
        dismissRunnable = null
        repostRunnable?.let(mainHandler::removeCallbacks)
        repostRunnable = null
        islandVisible = false
        islandExpiresAtMillis = 0L
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        }
        Log.d(TAG, "Focus Island cancelled")
    }

    fun showBatteryIsland(
        context: Context,
        prefs: SharedPreferences,
        batteryParams: BatteryParams,
        address: String,
        singleBattery: Boolean = false,
        deviceName: String? = null,
        device: BluetoothDevice? = null,
        durationSeconds: Int = ConfigManager.DEFAULT_ISLAND_DURATION_SECONDS,
        islandFirstFloat: Boolean = true,
    ): Boolean = renderBatteryIsland(
        context = context,
        prefs = prefs,
        batteryParams = batteryParams,
        address = address,
        singleBattery = singleBattery,
        deviceName = deviceName,
        device = device,
        durationSeconds = durationSeconds,
        timeoutAtMillis = null,
        firstFloatOverride = islandFirstFloat,
    )

    /** Update a currently visible island without making a new connection popup. */
    fun updateBatteryIsland(
        context: Context,
        prefs: SharedPreferences,
        batteryParams: BatteryParams,
        address: String,
        singleBattery: Boolean = false,
        deviceName: String? = null,
        device: BluetoothDevice? = null,
    ): Boolean {
        val now = System.currentTimeMillis()
        var deadline = islandExpiresAtMillis
        if (!islandVisible || deadline <= now) {
            // The island lifecycle fields belong to this classloader. During a
            // hot reload they reset even though HyperOS still owns the existing
            // notification record, so a normal recovery update would be rejected
            // as "not visible" and the old island would stop receiving battery
            // updates. Rehydrate the short-lived state from our own notification
            // record before deciding that the island has really disappeared.
            val notificationStillExists = runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
                manager.activeNotifications.any { it.id == NOTIFICATION_ID }
            }.getOrDefault(false)
            if (!notificationStillExists) {
                islandVisible = false
                islandExpiresAtMillis = 0L
                return false
            }
            islandVisible = true
            islandExpiresAtMillis = now +
                ConfigManager.islandDurationSeconds().coerceIn(
                    1,
                    ConfigManager.MAX_ISLAND_DURATION_SECONDS,
                ) * 1000L
            deadline = islandExpiresAtMillis
            Log.d(TAG, "rehydrated existing Focus Island after classloader reload")
        }
        return renderBatteryIsland(
            context = context,
            prefs = prefs,
            batteryParams = batteryParams,
            address = address,
            singleBattery = singleBattery,
            deviceName = deviceName,
            device = device,
            durationSeconds = ConfigManager.DEFAULT_ISLAND_DURATION_SECONDS,
            timeoutAtMillis = deadline,
            firstFloatOverride = null,
        )
    }

    private fun renderBatteryIsland(
        context: Context,
        prefs: SharedPreferences,
        batteryParams: BatteryParams,
        address: String,
        singleBattery: Boolean = false,
        deviceName: String? = null,
        device: BluetoothDevice? = null,
        durationSeconds: Int = ConfigManager.DEFAULT_ISLAND_DURATION_SECONDS,
        timeoutAtMillis: Long?,
        firstFloatOverride: Boolean?,
    ): Boolean {
        try {
            val now = System.currentTimeMillis()
            val requestedDurationSeconds =
                durationSeconds.coerceIn(1, ConfigManager.MAX_ISLAND_DURATION_SECONDS)
            val deadline = timeoutAtMillis ?: (now + requestedDurationSeconds * 1000L)
            if (deadline <= now) return false
            val islandDurationSeconds = ((deadline - now + 999L) / 1000L)
                .coerceIn(1L, ConfigManager.MAX_ISLAND_DURATION_SECONDS.toLong())
                .toInt()
            val leftConnected = batteryParams.left?.isConnected == true
            val rightConnected = batteryParams.right?.isConnected == true

            val leftText = if (leftConnected) "${batteryParams.left!!.battery}" else "-"
            val rightText = if (rightConnected) "${batteryParams.right!!.battery}" else "-"

            // Expanded-state avatar + ticker: the model box image (headbands resolve
            // their device render through the same BOX slot).
            val caseBitmap = PodImageLoader.loadBoxBitmap(context, prefs, address)
            if (caseBitmap == null) {
                Log.e(TAG, "Failed to decode Focus Island icon bitmaps")
                return false
            }

            // 使用 createWithBitmap 直接嵌入图片数据，SystemUI 无需再访问模块资源；
            // 摘要态左右图为模块内置静态 WebP（连接视频末帧），走编译期资源 ID。
            val caseIcon = Icon.createWithBitmap(caseBitmap)
            val moduleContext = context.createPackageContext(
                MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY
            )
            // appIconPkg is serialized as a String reference. Register a fully
            // transparent Icon under a picture key so this test targets appIconPkg
            // itself, while the IM avatar remains the normal device picture.
            val transparentAppIconBitmap = Bitmap.createBitmap(
                1,
                1,
                Bitmap.Config.ARGB_8888,
            ).apply { setPixel(0, 0, Color.TRANSPARENT) }
            val transparentAppIcon = Icon.createWithBitmap(transparentAppIconBitmap)
            val leftIcon = if (singleBattery) {
                Icon.createWithResource(moduleContext, R.drawable.earphone_single_head)
            } else {
                Icon.createWithResource(moduleContext, R.drawable.earphone_left_inear)
            }
            val rightIcon = if (singleBattery) {
                null
            } else {
                Icon.createWithResource(moduleContext, R.drawable.earphone_right_inear)
            }
            // 展开态进度按钮：头戴用 AirPods Max 头戴符号，TWS 用左/右耳符号。
            val leftSvgLightIcon = Icon.createWithResource(
                moduleContext,
                if (singleBattery) R.drawable.ic_airpods_max_light else R.drawable.ic_airpods_left_light,
            )
            val leftSvgDarkIcon = Icon.createWithResource(
                moduleContext,
                if (singleBattery) R.drawable.ic_airpods_max_dark else R.drawable.ic_airpods_left_dark,
            )
            val rightSvgLightIcon = Icon.createWithResource(moduleContext, R.drawable.ic_airpods_right_light)
            val rightSvgDarkIcon = Icon.createWithResource(moduleContext, R.drawable.ic_airpods_right_dark)
            val caseSvgLightIcon = Icon.createWithResource(moduleContext, R.drawable.ic_airpods_case_light)
            val caseSvgDarkIcon = Icon.createWithResource(moduleContext, R.drawable.ic_airpods_case_dark)

            fun progressOf(pod: dev.sonypods.utils.miuiStrongToast.data.PodParams?): Int =
                pod?.takeIf { it.isConnected }?.battery?.coerceIn(0, 100) ?: 0

            val leftProgress = progressOf(batteryParams.left)
            val rightProgress = progressOf(batteryParams.right)
            val caseProgress = progressOf(batteryParams.case)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setAllowBubbles(true)
                }
            )

            val contentParts = mutableListOf<String>()
            if (leftConnected) contentParts.add("L: ${batteryParams.left!!.battery}%")
            if (rightConnected) contentParts.add("R: ${batteryParams.right!!.battery}%")
            val contentText = contentParts.joinToString("  ")

            val extras = FocusNotification.buildV3 {
                val picLeft = createPicture("key_pic_left", leftIcon)
                val picRight = rightIcon?.let { createPicture("key_pic_right", it) }
                val picCase = createPicture("key_pic_case", caseIcon)
                val picLeftActionLight = createPicture("key_pic_left_action_light", leftSvgLightIcon)
                val picLeftActionDark = createPicture("key_pic_left_action_dark", leftSvgDarkIcon)
                val picRightActionLight = createPicture("key_pic_right_action_light", rightSvgLightIcon)
                val picRightActionDark = createPicture("key_pic_right_action_dark", rightSvgDarkIcon)
                val picCaseActionLight = createPicture("key_pic_case_action_light", caseSvgLightIcon)
                val picCaseActionDark = createPicture("key_pic_case_action_dark", caseSvgDarkIcon)
                val appIconTransparent = createPicture(
                    "key_app_icon_transparent_test",
                    transparentAppIcon,
                )

                enableFloat = false
                ticker = "SonyPods"
                tickerPic = picCase

                isShowNotification = false
                // This is only supplied when a new island notification is being
                // re-sent (for example after the action activity consumed it).
                // Ordinary battery updates intentionally leave islandFirstFloat
                // untouched; enableFloat below is the update-notification switch.
                firstFloatOverride?.let { islandFirstFloat = it }
                // 展开态使用焦点通知组件；bigIslandArea 只负责摘要态。
                // 盒/设备图是头像，应使用 chatInfo.picProfile，而不是 baseInfo.picFunction。
                chatInfo {
                    appIconPkg = appIconTransparent
                    picProfile = picCase
                    picProfileDark = picCase
                    title = "已连接"
                    content = deviceName ?: "SonyPods"
                }
                island {
                    islandProperty = 1
                    islandTimeout = islandDurationSeconds
                    // TWS：左右动态图标 + 电量文字；头戴：左侧仅设备图、右侧仅电量。
                    bigIslandArea {
                        imageTextInfoLeft {
                            type = 1
                            picInfo {
                                type = 1
                                pic = picLeft
                            }
                            if (!singleBattery) {
                                textInfo {
                                    title = leftText
                                    content = "%"
                                }
                            }
                        }
                        imageTextInfoRight {
                            type = 2
                            if (singleBattery) {
                                // 头戴单电量骑乘在 left 槽位的数值上。
                                textInfo {
                                    title = leftText
                                    content = "%"
                                }
                            } else {
                                picInfo {
                                    type = 1
                                    pic = picRight!!
                                }
                                textInfo {
                                    title = rightText
                                    content = "%"
                                }
                            }
                        }
                    }
                    shareData {
                        title = "SonyPods"
                        content = contentText
                        shareContent = contentText
                    }
                }

                // 进度按钮属于焦点通知的 actions 数组，不能序列化为 textButton。
                // 头戴式（单电量）上游未适配：只出一个按钮，图标沿用左耳 SVG。
                actions {
                    if (!singleBattery) {
                        addActionInfo {
                            type = 1
                            actionIcon = picCaseActionLight
                            actionIconDark = picCaseActionDark
                            progressInfo {
                                progress = caseProgress
                                colorProgress = IOS_CHARGING_GREEN
                            }
                        }
                    }
                    addActionInfo {
                        type = 1
                        actionIcon = picLeftActionLight
                        actionIconDark = picLeftActionDark
                        progressInfo {
                            progress = leftProgress
                            colorProgress = IOS_CHARGING_GREEN
                        }
                    }
                    if (!singleBattery) {
                        addActionInfo {
                            type = 1
                            actionIcon = picRightActionLight
                            actionIconDark = picRightActionDark
                            progressInfo {
                                progress = rightProgress
                                colorProgress = IOS_CHARGING_GREEN
                            }
                        }
                    }
                }
            }
            // focus-api 1.4 未暴露文档中的两个进度按钮字段，补到最终 JSON，
            // 避免系统按默认动画/颜色渲染成红色。
            setStaticProgressOptions(extras)

            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("SonyPods")
                .setContentText(contentText)
                .setTicker("SonyPods")
                .setContentIntent(podDialogPendingIntent(context, address, deviceName, device))
                .addExtras(extras)
                .build()

            // HyperOS removes the island from DynamicIsland's visible-state list
            // when its content action opens the mini window, but keeps the 10086
            // NotificationRecord alive. A direct notify() then only updates that
            // hidden record and the island never returns. For a replay, make it a
            // real remove -> add cycle; ordinary battery updates must stay in-place.
            repostRunnable?.let(mainHandler::removeCallbacks)
            repostRunnable = null
            if (firstFloatOverride != null) {
                nm.cancel(NOTIFICATION_ID)
                val repost = Runnable {
                    repostRunnable = null
                    runCatching { nm.notify(NOTIFICATION_ID, notification) }
                        .onFailure { Log.e(TAG, "Failed to repost Focus Island", it) }
                }
                repostRunnable = repost
                mainHandler.postDelayed(repost, 150L)
            } else {
                nm.notify(NOTIFICATION_ID, notification)
            }

            dismissRunnable?.let(mainHandler::removeCallbacks)
            val dismiss = Runnable {
                try { nm.cancel(NOTIFICATION_ID) } catch (_: Exception) {}
                if (islandExpiresAtMillis == deadline) {
                    islandVisible = false
                    islandExpiresAtMillis = 0L
                }
            }
            dismissRunnable = dismiss
            islandVisible = true
            islandExpiresAtMillis = deadline
            mainHandler.postDelayed(dismiss, (deadline - System.currentTimeMillis()).coerceAtLeast(1L))

            val verb = if (timeoutAtMillis == null) "shown" else "updated"
            Log.d(TAG, "Focus Island $verb: L=$leftText% R=$rightText% single=$singleBattery duration=${islandDurationSeconds}s")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show Focus Island", e)
            return false
        }
    }

    private fun podDialogPendingIntent(
        context: Context,
        address: String,
        deviceName: String?,
        device: BluetoothDevice?,
    ): PendingIntent {
        val intent = Intent(SonyPodsAction.ACTION_SHOW_PODS_UI).apply {
            setClassName(MODULE_PACKAGE, "dev.sonypods.PopupActivity")
            putExtra(SonyPodsAction.EXTRA_POPUP_FROM_ISLAND, true)
            putExtra("bluetoothaddress", address)
            deviceName?.let { putExtra("device_name", it) }
            device?.let { putExtra("android.bluetooth.device.extra.DEVICE", it) }
        }
        // Android 15/HyperOS requires the PendingIntent creator to opt in to
        // background activity starts. Without this, pulling the island down
        // enters the mini-window animation but PopupActivity is BAL-blocked,
        // leaving DynamicIsland waiting until open_app_timeout.
        val activityOptions = ActivityOptions.makeBasic().apply {
            setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS,
            )
        }
        return PendingIntent.getActivity(
            context,
            POD_DIALOG_ISLAND_PENDING_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            activityOptions.toBundle(),
        )
    }

    private fun setStaticProgressOptions(extras: android.os.Bundle) {
        runCatching {
            // 根据文档和系统设计，actions 数组独立序列化在 Bundle 的 miui.focus.actions 中
            val actionsStr = extras.getString("miui.focus.actions")
            if (actionsStr != null) {
                val actions = org.json.JSONArray(actionsStr)
                for (index in 0 until actions.length()) {
                    val action = actions.optJSONObject(index) ?: continue
                    val progressInfo = action.optJSONObject("progressInfo") ?: continue
                    // 参考官方格式: colorProgress是进度条颜色，colorProgressEnd是进度条底色(圆环底色)
                    progressInfo.put("colorProgress", IOS_CHARGING_GREEN)
                    progressInfo.put("colorProgressDark", IOS_CHARGING_GREEN)
                    progressInfo.put("colorProgressEnd", "#1A000000")       // 浅色模式底色
                    progressInfo.put("colorProgressEndDark", "#29FFFFFF")   // 深色模式底色
                    progressInfo.put("isCCW", true)
                    progressInfo.put("isAutoProgress", false)
                }
                extras.putString("miui.focus.actions", actions.toString())
                return
            }

            val root = org.json.JSONObject(extras.getString("miui.focus.param") ?: return)
            val param = root.optJSONObject("param_v2") ?: return
            val actions = param.optJSONArray("actions") ?: return
            for (index in 0 until actions.length()) {
                val action = actions.optJSONObject(index) ?: continue
                val progressInfo = action.optJSONObject("progressInfo") ?: continue
                progressInfo.put("colorProgress", IOS_CHARGING_GREEN)
                progressInfo.put("colorProgressDark", IOS_CHARGING_GREEN)
                progressInfo.put("colorProgressEnd", "#1A000000")
                progressInfo.put("colorProgressEndDark", "#29FFFFFF")
                progressInfo.put("isCCW", true)
                progressInfo.put("isAutoProgress", false)
            }
            extras.putString("miui.focus.param", root.toString())
        }.onFailure { Log.w(TAG, "Failed to configure progress button JSON", it) }
    }
}
