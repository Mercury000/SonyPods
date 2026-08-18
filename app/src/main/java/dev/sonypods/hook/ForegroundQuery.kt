package dev.sonypods.hook

import android.app.ActivityManager
import android.content.Context

/**
 * "Is this package holding the foreground right now?"
 *
 * Reads the live process-scheduler state through the hidden
 * `ActivityManager.getUidProcessState`, which needs no broadcast round trip and
 * reports a killed process as not-foreground, so there is no stale state to
 * invalidate. Querying a uid other than the caller's requires
 * PACKAGE_USAGE_STATS, which the hosts these hooks run in (uid `bluetooth`)
 * hold; a build where the hidden API or the permission is missing fails open to
 * not-foreground rather than suppressing whatever the caller gates on this.
 */
object ForegroundQuery {

    fun isPackageForeground(context: Context, packageName: String): Boolean = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
        val state = ActivityManager::class.java
            .getMethod("getUidProcessState", Int::class.javaPrimitiveType)
            .invoke(am, uid) as Int
        val topState = ActivityManager::class.java
            .getField("PROCESS_STATE_TOP")
            .getInt(null)
        state == topState
    }.getOrDefault(false)

    /**
     * The first package in [packages] that currently holds the foreground, or null.
     *
     * Answering membership one package at a time is deliberate: it needs only the
     * per-uid state above, whereas asking "which package is on top" would need
     * task enumeration and a permission these processes do not have.
     */
    fun firstForegroundIn(context: Context, packages: Set<String>): String? =
        packages.firstOrNull { isPackageForeground(context, it) }
}
