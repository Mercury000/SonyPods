import android.app.PendingIntent
import android.os.Bundle

class StringToastBundle private constructor() {

    companion object {
        private var mBundle: Bundle = Bundle()
    }

    class Builder {
        private var packageName: String? = null
        private var stringToastCategory: String? = null
        private var target: PendingIntent? = null
        private var param: String? = null
        private var duration: Long = 2500L
        private var level: Float? = null
        private var rapidRate: Float? = null
        private var charge: String? = null
        private var stringToastChargeFlag: Int? = null
        private var statusBarStrongToast: String? = "show_custom_strong_toast"
        private var islandParam: String? = null
        private var notifyId: String? = null

        fun setPackageName(packageName: String?) = apply { this.packageName = packageName }
        fun setStrongToastCategory(category: String) = apply { stringToastCategory = category }
        fun setTarget(target: PendingIntent?) = apply { this.target = target }
        fun setParam(param: String?) = apply { this.param = param }
        fun setDuration(duration: Long) = apply { this.duration = duration }
        fun setLevel(level: Float) = apply { this.level = level }
        fun setRapidRate(rapidRate: Float) = apply { this.rapidRate = rapidRate }
        fun setCharge(charge: String?) = apply { this.charge = charge }
        fun setStringToastChargeFlag(stringToastChargeFlag: Int) = apply { this.stringToastChargeFlag = stringToastChargeFlag }
        fun setStatusBarStrongToast(statusBarStrongToast: String?) = apply { this.statusBarStrongToast = statusBarStrongToast }

        /** Island half of the payload; HyperOS sends it alongside the strong toast. */
        fun setIslandParam(islandParam: String?) = apply { this.islandParam = islandParam }

        /** Ties the toast to a focus notification, e.g. `headset_wear_notification`. */
        fun setNotifyId(notifyId: String?) = apply { this.notifyId = notifyId }

        fun onCreate(): Bundle {
            mBundle = Bundle()
            mBundle.putString("package_name", packageName)
            islandParam?.let { mBundle.putString("island_param", it) }
            notifyId?.let { mBundle.putString("notifyId", it) }
            mBundle.putString("strong_toast_category", stringToastCategory)
            mBundle.putParcelable("target", target)
            mBundle.putString("param", param)
            mBundle.putLong("duration", duration)
            // Only present when actually used: HyperOS sends no charge/level keys for a
            // headset toast, and their presence routes it into the charging variant.
            level?.let { mBundle.putFloat("level", it) }
            rapidRate?.let { mBundle.putFloat("rapid_rate", it) }
            charge?.let { mBundle.putString("charge", it) }
            stringToastChargeFlag?.let { mBundle.putInt("string_toast_charge_flag", it) }
            mBundle.putString("status_bar_strong_toast", statusBarStrongToast)
            return mBundle
        }
    }
}
