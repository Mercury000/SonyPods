package dev.sonypods.leaudio

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.ParcelUuid
import java.lang.reflect.InvocationTargetException
import java.util.UUID

/**
 * The privileged stack calls the LE Audio flows need.
 *
 * Usable only inside `com.android.bluetooth`, where `AdapterService` lives. Its class is resolved
 * through the host classloader on purpose: a bare `Class.forName` resolves against the module's own
 * loader, which cannot see host classes and fails with ClassNotFoundException.
 */
internal class LeAudioStack(context: Context, private val log: (String) -> Unit) {
    private val loader: ClassLoader? = runCatching { context.classLoader }.getOrNull()

    private fun adapterService(): Any? = runCatching {
        val clazz = loader?.loadClass("com.android.bluetooth.btservice.AdapterService") ?: return null
        runCatching {
            clazz.getDeclaredField("sAdapterService").apply { isAccessible = true }.get(null)
        }.getOrNull()
            ?: clazz.getDeclaredMethod("getAdapterService").apply { isAccessible = true }.invoke(null)
    }.getOrNull()

    /**
     * `AdapterService.removeBond`, the entry point the framework's own binder path lands on.
     *
     * Preferred over `BluetoothDevice.removeBond()`, which from inside this process goes back out
     * over Binder to the service that would have served it anyway. Note that it drops **every**
     * member of the device's CSIS group, not just this device — which is exactly what the flow
     * wants of the classic bond, and would be destructive once an LE bond exists.
     */
    fun removeBond(device: BluetoothDevice): Boolean? {
        val adapter = adapterService() ?: return null
        return runCatching {
            adapter.javaClass
                .getMethod("removeBond", BluetoothDevice::class.java)
                .apply { isAccessible = true }
                .invoke(adapter, device) as? Boolean
        }.onFailure { log("AdapterService.removeBond failed: ${it.reason()}") }.getOrNull()
    }

    /**
     * The coordinated set [device] belongs to, or null while the stack knows of none.
     *
     * A post-bond fact the profile answers directly, with no Tandem session and no `bt_config` read:
     * CSIS connects over an encrypted link and reads the Set Identity. That makes it the right
     * fallback for relating two identities of a headset [dev.sonypods.device.HeadsetRegistry] has
     * never been told about. Sound Connect relates devices by nothing else —
     * `BtProfileGateway.j(BluetoothDevice)`, used by both its LE Audio and pairing-state receivers.
     */
    fun groupId(device: BluetoothDevice): Int? {
        val csip = profileService("getCsipSetCoordinatorService") ?: return null
        return runCatching {
            csip.javaClass
                .getMethod("getGroupId", BluetoothDevice::class.java, ParcelUuid::class.java)
                .apply { isAccessible = true }
                .invoke(csip, device, CAP) as? Int
        }.onFailure { log("CsipSetCoordinatorService.getGroupId failed: ${it.reason()}") }
            .getOrNull()?.takeIf { it != GROUP_ID_INVALID }
    }

    /** One profile service, or null while that profile is not up. */
    private fun profileService(getter: String): Any? {
        val adapter = adapterService() ?: return null
        val answered = runCatching {
            adapter.javaClass.getMethod(getter).apply { isAccessible = true }.invoke(adapter)
        }.getOrNull()
        // An empty Optional is the stack's authoritative "not up"; unwrapping with elvis would
        // hand the Optional itself back as the service.
        return if (answered is java.util.Optional<*>) answered.orElse(null) else answered
    }

    /** InvocationTargetException hides the reason a reflective call was rejected. */
    private fun Throwable.reason(): String {
        val cause = (this as? InvocationTargetException)?.targetException ?: this
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    companion object {
        private const val GROUP_ID_INVALID = -1

        /** Common Audio Service: the set type CSIS files a LE Audio headset's group under. */
        private val CAP = ParcelUuid(UUID.fromString("00001853-0000-1000-8000-00805F9B34FB"))
    }
}
