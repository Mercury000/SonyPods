package dev.sonypods.bridge

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.provider.Settings
import android.util.Base64
import dev.sonypods.hook.Log

/**
 * The single event-driven state channel between the Sony engine and its consumers,
 * carried by the settings provider.
 *
 * The engine writes one encoded [Bundle] under [KEY] on every real state change; the
 * settings provider notifies exactly the processes that registered a content observer
 * for that URI. Consumers read the value on registration (current state) and on every
 * notification (change). No process is woken unless it subscribed, nothing is polled,
 * and a dead engine publishes nothing.
 *
 * A binder published through the service manager cannot be used here: the engine runs
 * in the `bluetooth` SELinux domain, which is denied `service_manager add` (and even
 * `find`) on this platform.
 */
object SonyStateBus {
    /** Settings.Global key holding the encoded envelope. */
    const val KEY = "sonypods_state_bus_v1"

    fun uri(): Uri = Settings.Global.getUriFor(KEY)

    /** Reads and decodes the currently published envelope, or null when none exists. */
    fun read(resolver: ContentResolver): Bundle? {
        val value = runCatching { Settings.Global.getString(resolver, KEY) }.getOrNull() ?: return null
        return runCatching { decode(value) }.getOrNull()
    }

    internal fun encode(envelope: Bundle): String {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(envelope)
            Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)
        } finally {
            parcel.recycle()
        }
    }

    internal fun decode(encoded: String): Bundle? {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            parcel.readBundle(SonyStateBus::class.java.classLoader)
        } finally {
            parcel.recycle()
        }
    }
}

/** Engine-side writer. One instance per engine generation. */
class SonyStateWriter(context: Context) {

    private val resolver: ContentResolver = context.applicationContext?.contentResolver
        ?: context.contentResolver

    /** Publishes one state change and wakes every subscribed process. */
    fun emit(envelope: Bundle) {
        runCatching {
            Settings.Global.putString(resolver, SonyStateBus.KEY, SonyStateBus.encode(envelope))
        }.onFailure { Log.w(TAG, "state publish failed", it) }
    }

    /** Removes the value so a later process start cannot read a stale state. */
    fun clear() {
        runCatching { Settings.Global.putString(resolver, SonyStateBus.KEY, null) }
    }

    private companion object {
        const val TAG = "SonyPods-Bus"
    }
}
