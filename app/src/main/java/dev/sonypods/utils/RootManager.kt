package dev.sonypods.utils

import android.util.Log

object RootManager {
    private const val TAG = "SonyPods-App"
    private val packageNameRegex = Regex("^[A-Za-z0-9_.]+$")
    private val allowedScopes = setOf(
        "com.android.bluetooth",
        "com.android.settings",
        "com.milink.service",
        "com.xiaomi.bluetooth",
        "com.sony.songpal.mdr",
    )

    private val allowedReadPaths = setOf(
        "/data/data/com.sony.songpal.mdr/shared_prefs/safe_listening_settings_preference.xml",
    )

    /** Read one of [allowedReadPaths] as root. The allowlist keeps the path out of
     * the shell word that `su -c` parses. Null means the read failed — no root, or
     * the file does not exist. */
    fun readTextAsRoot(path: String): String? {
        if (path !in allowedReadPaths) return null
        return runRootText("cat $path")
    }

    fun restartPackages(packages: Collection<String>): Boolean {
        val targets = packages.distinct().filter { it in allowedScopes && it.matches(packageNameRegex) }
        if (targets.isEmpty()) return false

        return runCatching {
            val command = targets.joinToString("; ") { "am force-stop $it" }
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    fun hasRootAccess(): Boolean {
        return runRootText("echo yes")?.trim() == "yes"
    }

    private fun runRootText(command: String): String? {
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode == 0) output else null
        }.onFailure { Log.e(TAG, "root text failed command=$command", it) }.getOrNull()
    }
}
