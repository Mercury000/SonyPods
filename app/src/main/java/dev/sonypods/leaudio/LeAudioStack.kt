package dev.sonypods.leaudio

import android.bluetooth.BluetoothDevice
import android.content.Context
import java.lang.reflect.InvocationTargetException

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

    /** InvocationTargetException hides the reason a reflective call was rejected. */
    private fun Throwable.reason(): String {
        val cause = (this as? InvocationTargetException)?.targetException ?: this
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }
}
