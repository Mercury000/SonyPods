package dev.sonypods.utils

import android.content.Context

/**
 * Resolves this module's string resources from processes that are not the module
 * app itself — the Tandem engine inside `com.android.bluetooth`, the settings/fast-connect
 * hooks, and the focus-island renderer all build user-visible copy there.
 *
 * `R.string.*` constants are plain ints, so they are only valid once resolved against the
 * module APK's resource table; [createPackageContext] provides exactly that (the same
 * pattern [PodImageLoader] uses for images). In the module app process the passed-in
 * context is used directly. Locale follows the system configuration of the hosting
 * process, matching what the rest of that surface shows.
 */
object ModuleText {
    private const val MODULE_PACKAGE = "com.mercury.sonypods"

    fun get(context: Context, resId: Int, vararg args: Any?): String = runCatching {
        val ctx = if (context.packageName == MODULE_PACKAGE) context else context.createPackageContext(
            MODULE_PACKAGE,
            Context.CONTEXT_IGNORE_SECURITY,
        )
        if (args.isEmpty()) ctx.resources.getString(resId) else ctx.resources.getString(resId, *args)
    }.getOrDefault("")
}
