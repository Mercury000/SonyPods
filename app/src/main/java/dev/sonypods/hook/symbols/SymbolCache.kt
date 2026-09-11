package dev.sonypods.hook.symbols

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface SymbolCache {
    fun read(bundleId: String): CachedSymbolBundle?
    fun write(bundle: CachedSymbolBundle)
    fun remove(bundleId: String)
}

@Serializable
data class CachedSymbolBundle(
    val formatVersion: Int = CACHE_FORMAT_VERSION,
    val bundleId: String,
    val schemaVersion: Int,
    val targetFingerprint: String,
    val symbols: Map<String, CachedSymbolReference>,
) {
    companion object { const val CACHE_FORMAT_VERSION = 1 }
}

@Serializable
data class CachedSymbolReference(val kind: String, val descriptor: String) {
    fun toReference(): SymbolReference = SymbolReference(SymbolKind.valueOf(kind), descriptor)

    companion object {
        fun from(reference: SymbolReference) = CachedSymbolReference(reference.kind.name, reference.descriptor)
    }
}

/** One JSON file per bundle; writes are atomic so a killed target process cannot poison the cache. */
class FileSymbolCache(private val directory: File) : SymbolCache {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun read(bundleId: String): CachedSymbolBundle? {
        val file = fileFor(bundleId)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<CachedSymbolBundle>(file.readText()) }.getOrNull()
    }

    override fun write(bundle: CachedSymbolBundle) {
        directory.mkdirs()
        val target = fileFor(bundle.bundleId)
        val temporary = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
        temporary.writeText(json.encodeToString(bundle))
        runCatching {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun remove(bundleId: String) {
        fileFor(bundleId).delete()
    }

    private fun fileFor(bundleId: String): File {
        require(bundleId.matches(Regex("[A-Za-z0-9._-]+"))) { "unsafe symbol bundle id: $bundleId" }
        return File(directory, "$bundleId.json")
    }
}

class MemorySymbolCache : SymbolCache {
    private val entries = linkedMapOf<String, CachedSymbolBundle>()
    @Synchronized override fun read(bundleId: String) = entries[bundleId]
    @Synchronized override fun write(bundle: CachedSymbolBundle) { entries[bundle.bundleId] = bundle }
    @Synchronized override fun remove(bundleId: String) { entries.remove(bundleId) }
}