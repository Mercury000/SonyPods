package dev.sonypods.utils.miuiStrongToast

import StringToastBundle
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.serialization.json.Json
import dev.sonypods.BuildConfig
import dev.sonypods.hook.Log
import dev.sonypods.utils.SystemApisUtils.isHyperOS
import dev.sonypods.utils.miuiStrongToast.data.BatteryParams
import dev.sonypods.utils.miuiStrongToast.data.IconParams
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import dev.sonypods.utils.miuiStrongToast.data.Left
import dev.sonypods.utils.miuiStrongToast.data.Right
import dev.sonypods.utils.miuiStrongToast.data.StringToastBean
import dev.sonypods.utils.miuiStrongToast.data.TextParams

@SuppressLint("WrongConstant")
object MiuiStrongToastUtil {
    var lastPodsTimestamp = -1L

    /** HyperOS omits absent fields entirely (e.g. no textParams for an unworn bud). */
    private val toastJson = Json { encodeDefaults = false; explicitNulls = false }

    fun showStringToast(context: Context, text: String?, colorType: Int) {
        if (!isHyperOS) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            val textParams = TextParams(text, if (colorType == 1) Color.parseColor("#4CAF50") else Color.parseColor("#E53935"))
            val left = Left(textParams = textParams)
            val iconParams = IconParams(Category.DRAWABLE, FileType.SVG, "ic_launcher", 1)
            val right = Right(iconParams = iconParams)
            val stringToastBean = StringToastBean(left, right)
            val jsonStr = Json.encodeToString(StringToastBean.serializer(), stringToastBean)
            val bundle = StringToastBundle.Builder()
                .setPackageName(BuildConfig.APPLICATION_ID)
                .setStrongToastCategory(StrongToastCategory.TEXT_BITMAP_INTENT)
                .setTarget(null)
                .setParam(jsonStr)
                .onCreate()
            val service = context.getSystemService(Context.STATUS_BAR_SERVICE)
            service.javaClass.getMethod(
                "setStatus", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java
            ).invoke(service, 1, "strong_toast_action", bundle)
        } catch (e: Exception) {
            Log.e("SonyPods", "Failed to show HyperOS String Toast")
        }
    }

    fun showPodsBatteryToast(
        context: Context,
        leftVideoUri: Uri,
        rightVideoUri: Uri,
        lowBatteryThreshold: Int = 20,
        batteryParams: BatteryParams
    ) {
        if (!isHyperOS) return

        val leftConnected = batteryParams.left?.isConnected == true
        val rightConnected = batteryParams.right?.isConnected == true
        val left = batteryParams.left?.battery ?: 0
        val leftCharging = batteryParams.left?.isCharging == true
        val right = batteryParams.right?.battery ?: 0
        val rightCharging = batteryParams.right?.isCharging == true

        val leftText =
            TextParams(if (leftConnected) "$left %" else "", if (leftCharging) Color.GREEN else if (left <= lowBatteryThreshold) Color.RED else Color.WHITE)
        val leftVideo = IconParams(Category.RAW, FileType.MP4, leftVideoUri.toString(), 1)
        val rightText =
            TextParams(if (rightConnected) "$right %" else "", if (rightCharging) Color.GREEN else if (right <= lowBatteryThreshold) Color.RED else Color.WHITE)
        val rightVideo = IconParams(Category.RAW, FileType.MP4, rightVideoUri.toString(), 1)
        val l = Left(textParams = leftText, iconParams = leftVideo)
        val r = Right(textParams = rightText, iconParams = rightVideo)
        val stringToastBean = StringToastBean(l, r)
        val jsonStr = Json.encodeToString(StringToastBean.serializer(), stringToastBean)
        val bundle = StringToastBundle.Builder()
            .setPackageName("com.xiaomi.bluetooth")
            .setStrongToastCategory(StrongToastCategory.VIDEO_TEXT_TEXT_VIDEO)
            .setDuration(5000)
            .setTarget(null)
            .setParam(jsonStr)
            .onCreate()
        try {
            val service = context.getSystemService(Context.STATUS_BAR_SERVICE)
            service.javaClass.getMethod(
                "setStatus", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java
            ).invoke(service, 1, "strong_toast_action", bundle)
            lastPodsTimestamp = System.currentTimeMillis()
        } catch (_: Exception) {
            Log.e("SonyPods", "Failed to show Pods Battery Toast")
        }
    }

    fun showPodsBatteryToastByMiuiBt(
        context: Context,
        batteryParams: BatteryParams,
        device: BluetoothDevice? = null,
        singleBattery: Boolean = false,
    ) {
        val intent = Intent(SonyPodsAction.ACTION_SEND_STRONG_TOAST)
        intent.putExtra("batteryParams", batteryParams)
        intent.putExtra("address", device?.address.orEmpty())
        intent.putExtra(EXTRA_SINGLE_BATTERY, singleBattery)
        intent.`package` = "com.xiaomi.bluetooth"
        context.sendBroadcast(intent)
    }

    const val EXTRA_SINGLE_BATTERY = "single_battery"

    /**
     * The connect animation HyperOS plays for its own earbuds: a strong toast whose
     * left and right halves each pair one of the stock earphone clips with a battery
     * readout. Headband devices use the single-battery variant instead.
     *
     * The clips live in com.xiaomi.bluetooth's raw resources, which is the process this
     * runs in, so they are addressed as `android.resource://` URIs by name — nothing is
     * copied out of the system app.
     */
    fun showOfficialConnectToast(
        context: Context,
        batteryParams: BatteryParams,
        singleBattery: Boolean,
        lowBatteryThreshold: Int = 20,
    ) {
        if (!isHyperOS) return
        if (singleBattery) {
            val pod = batteryParams.left ?: return
            showToast(
                context = context,
                category = StrongToastCategory.VIDEO_TEXT,
                left = Left(
                    iconParams = IconParams(Category.RAW, FileType.MP4, clipUri("single_battery_head"), 1),
                    textParams = batteryText(pod.battery, pod.isCharging, lowBatteryThreshold),
                ),
                right = null,
            )
            return
        }

        showToast(
            context = context,
            category = StrongToastCategory.VIDEO_TEXT_TEXT_VIDEO,
            left = Left(
                iconParams = IconParams(Category.RAW, FileType.MP4, clipUri("earphone_left_inear"), 1),
                textParams = batteryParams.left?.let {
                    batteryText(it.battery, it.isCharging, lowBatteryThreshold)
                },
            ),
            right = Right(
                iconParams = IconParams(Category.RAW, FileType.MP4, clipUri("earphone_right_inear"), 1),
                textParams = batteryParams.right?.let {
                    batteryText(it.battery, it.isCharging, lowBatteryThreshold)
                },
            ),
        )
    }

    /** Official formatting: `100%`, no space, and a viewFlags field on the toast half. */
    private fun batteryText(level: Int, charging: Boolean, lowThreshold: Int): TextParams = TextParams(
        text = "${level.coerceIn(0, 100)}%",
        textColor = when {
            charging -> Color.GREEN
            level <= lowThreshold -> Color.RED
            else -> Color.WHITE
        },
        viewFlags = 0,
    )

    private fun Left.asIslandHalf(): Left = Left(
        iconParams = iconParams?.copy(iconType = 0),
        textParams = textParams?.copy(viewFlags = null, turnAnim = true),
    )

    private fun Right.asIslandHalf(): Right = Right(
        iconParams = iconParams?.copy(iconType = 0),
        textParams = textParams?.copy(viewFlags = null, turnAnim = true),
    )

    /**
     * HyperOS addresses these clips through its own FileProvider rather than as
     * `android.resource://` ids — SystemUI resolves nothing else.
     */
    private fun clipUri(name: String): String =
        "content://com.xiaomi.bluetooth.fileprovider/internal_files/$name.mp4"

    private fun showToast(context: Context, category: String, left: Left, right: Right?) {
        val toastPayload = toastJson.encodeToString(StringToastBean.serializer(), StringToastBean(left, right))
        // The island half repeats the payload with iconType 0 and turnAnim text; HyperOS
        // always sends both, bound to the headset focus notification by notifyId.
        val islandPayload = toastJson.encodeToString(
            StringToastBean.serializer(),
            StringToastBean(left.asIslandHalf(), right?.asIslandHalf()),
        )
        val bundle = StringToastBundle.Builder()
            .setPackageName("com.xiaomi.bluetooth")
            .setStrongToastCategory(category)
            .setDuration(5000)
            .setTarget(null)
            .setParam(toastPayload)
            .setIslandParam(islandPayload)
            .setNotifyId("headset_wear_notification")
            .onCreate()
        runCatching {
            val service = context.getSystemService(Context.STATUS_BAR_SERVICE)
            service.javaClass.getMethod(
                "setStatus", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java
            ).invoke(service, 1, "strong_toast_action", bundle)
            lastPodsTimestamp = System.currentTimeMillis()
            Log.i("SonyPods", "official strong toast shown category=$category")
        }.onFailure { Log.e("SonyPods", "Failed to show strong toast", it) }
    }

    fun showPodsNotificationByMiuiBt(
        context: Context,
        batteryParams: BatteryParams,
        device: BluetoothDevice,
    ) {
        val intent = Intent(SonyPodsAction.ACTION_UPDATE_PODS_NOTIFICATION)
        intent.putExtra("batteryParams", batteryParams)
        intent.putExtra("device", device)
        intent.`package` = "com.xiaomi.bluetooth"
        context.sendBroadcast(intent)
    }

    fun cancelPodsNotificationByMiuiBt(
        context: Context,
        device: BluetoothDevice,
    ) {
        val intent = Intent(SonyPodsAction.ACTION_CANCEL_PODS_NOTIFICATION)
        intent.putExtra("device", device)
        intent.`package` = "com.xiaomi.bluetooth"
        context.sendBroadcast(intent)
    }

    object Category {
        const val RAW = "raw"
        const val DRAWABLE = "drawable"
        const val FILE = "file"
        const val MIPMAP = "mipmap"
    }

    object FileType {
        const val MP4 = "mp4"
        const val PNG = "png"
        const val SVG = "svg"
    }

    object StrongToastCategory {
        const val VIDEO_TEXT = "video_text"
        const val VIDEO_BITMAP_INTENT = "video_bitmap_intent"
        const val TEXT_BITMAP = "text_bitmap"
        const val TEXT_BITMAP_INTENT = "text_bitmap_intent"
        const val VIDEO_TEXT_TEXT_VIDEO = "video_text_text_video"
    }
}
