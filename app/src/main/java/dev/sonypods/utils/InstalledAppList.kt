package dev.sonypods.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** A launchable application shown in the popup policy picker. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
)

private fun isSystemApp(pm: android.content.pm.PackageManager, appInfo: ApplicationInfo): Boolean =
    appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 ||
        runCatching {
            pm.getApplicationInfo(appInfo.packageName, android.content.pm.PackageManager.MATCH_SYSTEM_ONLY)
            true
        }.getOrDefault(false)

fun loadLaunchableApps(
    context: Context,
    includeSystemApps: Boolean = false,
    includePackages: Set<String> = emptySet(),
): List<InstalledApp> {
    val pm = context.packageManager
    val intents = listOf(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        // The current desktop/launcher is normally a HOME activity, not a launcher
        // icon activity, so querying CATEGORY_LAUNCHER alone hides it from the picker.
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
    )
    return intents
        .flatMap { pm.queryIntentActivities(it, 0) }
        .asSequence()
        .map { it.activityInfo.applicationInfo }
        .filter { includeSystemApps || it.packageName in includePackages || !isSystemApp(pm, it) }
        .map { appInfo ->
            val packageName = appInfo.packageName
            packageName to InstalledApp(
                packageName = packageName,
                label = appInfo.loadLabel(pm).toString().ifBlank { packageName },
                icon = appInfo.loadIcon(pm).toBitmap(96, 96).asImageBitmap(),
            )
        }
        .distinctBy { it.first }
        .map { it.second }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        .toList()
}
