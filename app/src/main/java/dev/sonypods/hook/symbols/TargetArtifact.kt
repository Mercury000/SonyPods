package dev.sonypods.hook.symbols

import java.io.File
import java.security.MessageDigest

/** Immutable identity of all DEX-bearing artifacts visible for one target package version. */
data class TargetArtifact(
    val packageName: String,
    val versionCode: Long,
    val files: List<ArtifactFile>,
) {
    init {
        require(packageName.isNotBlank())
        require(files.isNotEmpty()) { "target artifact has no APK files" }
    }

    val fingerprint: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        fun put(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        put(packageName)
        put(versionCode.toString())
        files.sortedBy { it.path }.forEach { file ->
            put(file.path)
            put(file.length.toString())
            put(file.lastModified.toString())
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun fromFiles(packageName: String, versionCode: Long, apkFiles: Collection<File>): TargetArtifact {
            val files = apkFiles.map { it.absoluteFile }
                .distinctBy { it.path }
                .map {
                    require(it.isFile) { "target APK does not exist: $it" }
                    ArtifactFile(it.path, it.length(), it.lastModified())
                }
            return TargetArtifact(packageName, versionCode, files)
        }
    }
}

data class ArtifactFile(val path: String, val length: Long, val lastModified: Long)