package dev.sonypods.utils

import android.app.Notification
import android.app.NotificationManager
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.os.UserHandle
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader


object SystemApisUtils {

    val BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET: String
        get() = "Untethered Headset"

    val BluetoothDevice.METADATA_MANUFACTURER_NAME: Int get() = 0
    val BluetoothDevice.METADATA_MODEL_NAME: Int get() = 1
    val BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET: Int get() = 6
    val BluetoothDevice.METADATA_UNTETHERED_LEFT_ICON: Int get() = 7
    val BluetoothDevice.METADATA_UNTETHERED_RIGHT_ICON: Int get() = 8
    val BluetoothDevice.METADATA_UNTETHERED_CASE_ICON: Int get() = 9
    val BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY: Int get() = 10
    val BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY: Int get() = 11
    val BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY: Int get() = 12
    val BluetoothDevice.METADATA_UNTETHERED_LEFT_CHARGING: Int get() = 13
    val BluetoothDevice.METADATA_UNTETHERED_RIGHT_CHARGING: Int get() = 14
    val BluetoothDevice.METADATA_UNTETHERED_CASE_CHARGING: Int get() = 15
    val BluetoothDevice.METADATA_DEVICE_TYPE: Int get() = 17
    val BluetoothDevice.METADATA_MAIN_BATTERY: Int get() = 18
    val BluetoothDevice.METADATA_MAIN_CHARGING: Int get() = 19

    const val BATTERY_LEVEL_UNKNOWN: Int = -1

    fun getUserAllUserHandle(): UserHandle {
        return UserHandle::class.java.getDeclaredField("ALL").apply { isAccessible = true }.get(null) as UserHandle
    }

    fun BluetoothDevice.getMetadata(key: Int): ByteArray? {
        return callMethod(this, "getMetadata", key) as ByteArray?
    }

    fun BluetoothDevice.setMetadata(key: Int, value: ByteArray): Boolean {
        return callMethod(this, "setMetadata", key, value) as Boolean
    }

    fun NotificationManager.notifyAsUser(tag: String, id: Int, notification: Notification, userHandle: UserHandle) {
        callMethod(this, "notifyAsUser", tag, id, notification, userHandle)
    }

    fun NotificationManager.cancelAsUser(tag: String, id: Int, userHandle: UserHandle) {
        callMethod(this, "cancelAsUser", tag, id, userHandle)
    }

    fun StatusBarManager.setIconVisibility(iconName: String, show: Boolean) {
        callMethod(this, "setIconVisibility", iconName, show)
    }

    private fun callMethod(instance: Any, methodName: String, vararg args: Any?): Any? {
        var cls: Class<*>? = instance.javaClass
        while (cls != null) {
            cls.declaredMethods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }?.let {
                it.isAccessible = true
                return it.invoke(instance, *args)
            }
            cls = cls.superclass
        }
        throw NoSuchMethodException(methodName)
    }

    private fun getPropByShell(propName: String): String {
        return try {
            val p = Runtime.getRuntime().exec("getprop $propName")
            BufferedReader(InputStreamReader(p.inputStream), 1024).use { it.readLine() ?: "" }
        } catch (ignore: IOException) {
            ""
        }
    }

    /** First non-blank value among [propNames], or an empty string. */
    private fun firstPropByShell(vararg propNames: String): String {
        propNames.forEach { name ->
            val value = getPropByShell(name).trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    /**
     * The user-facing device name. The market name is what the user recognises
     * ("Redmi K70") and it already carries the brand, so the manufacturer is never
     * prepended; [android.os.Build.MODEL] is usually a codename ("23113RKC6C") and
     * only serves as the fallback.
     */
    val deviceModel: String
        get() = firstPropByShell(
            "ro.product.marketname",
            "ro.product.vendor.marketname",
            "ro.product.odm.marketname",
        )
            .ifBlank { android.os.Build.MODEL.orEmpty().trim() }
            .ifBlank { android.os.Build.DEVICE.orEmpty().trim() }

    val isHyperOS: Boolean
        get() {
            return getPropByShell("ro.mi.os.version.code").isNotEmpty()
        }
}
