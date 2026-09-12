package dev.sonypods.hook.symbols

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Serializes one DEX scan within this process and, when backed by a file, across host processes. */
interface SymbolScanCoordinator {
    fun <T> withScanLock(key: String, action: () -> T): T
}

/** Used before an Android Context is available and by non-file-backed resolvers. */
object ProcessSymbolScanCoordinator : SymbolScanCoordinator {
    override fun <T> withScanLock(key: String, action: () -> T): T =
        ProcessScanStripes.withLock(key, action)
}

/**
 * A package-private file lock prevents two processes from scanning the same bundle. The JVM stripe
 * is still required because overlapping locks on the same file throw instead of waiting in one JVM.
 */
class FileSymbolScanCoordinator(private val directory: File) : SymbolScanCoordinator {
    override fun <T> withScanLock(key: String, action: () -> T): T {
        val lockFile = File(directory, "${sha256(key)}.lock")
        return ProcessScanStripes.withLock(lockFile.absolutePath) {
            ensureDirectory()
            RandomAccessFile(lockFile, "rw").use { file ->
                file.channel.use { channel ->
                    channel.lock().use { action() }
                }
            }
        }
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("cannot create symbol scan lock directory: $directory")
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Fixed stripes avoid an unbounded per-fingerprint lock map while retaining bundle-level concurrency. */
private object ProcessScanStripes {
    private val locks = Array(64) { Any() }

    fun <T> withLock(key: String, action: () -> T): T {
        val lock = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]
        return synchronized(lock) { action() }
    }
}
