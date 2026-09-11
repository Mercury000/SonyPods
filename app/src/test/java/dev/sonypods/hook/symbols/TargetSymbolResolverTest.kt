package dev.sonypods.hook.symbols

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TargetSymbolResolverTest {
    private val loader = javaClass.classLoader!!

    @Test
    fun fingerprintIncludesEveryArtifactIdentity() {
        val a = TargetArtifact(
            "target", 7,
            listOf(ArtifactFile("base.apk", 10, 20), ArtifactFile("split.apk", 30, 40)),
        )
        val reordered = a.copy(files = a.files.reversed())
        val changed = a.copy(files = a.files.map { if (it.path == "split.apk") it.copy(length = 31) else it })

        assertEquals(a.fingerprint, reordered.fingerprint)
        assertNotEquals(a.fingerprint, changed.fingerprint)
    }

    @Test
    fun validCacheHitDoesNotOpenDexKit() {
        val cache = MemorySymbolCache()
        val target = target()
        val definition = StringBundle()
        cache.write(cached(definition, target, validReferences()))
        var opened = false
        val resolver = TargetSymbolResolver(target, loader, cache, SymbolQueryFactory {
            opened = true
            error("must not scan")
        })

        val bundle = resolver.resolve(definition)

        assertTrue(bundle.fromCache)
        assertFalse(opened)
        assertEquals(2, bundle.keys.size)
        assertEquals(6, bundle.method("length").invoke("abcdef"))
    }

    @Test
    fun staleCacheIsReplacedByOneAllOrNothingScan() {
        val cache = MemorySymbolCache()
        val target = target()
        val definition = StringBundle()
        cache.write(cached(definition, target.copy(versionCode = 1), validReferences()))
        var opened = 0
        var closed = 0
        val resolver = TargetSymbolResolver(target, loader, cache, SymbolQueryFactory {
            opened++
            fakeQuery { closed++ }
        })

        val bundle = resolver.resolve(definition)

        assertFalse(bundle.fromCache)
        assertEquals(1, opened)
        assertEquals(1, closed)
        assertEquals(target.fingerprint, cache.read(definition.id)?.targetFingerprint)
    }

    @Test
    fun incompleteScanFailsWithoutWritingPartialBundle() {
        val cache = MemorySymbolCache()
        val definition = object : StringBundle() {
            override fun resolve(query: SymbolQuery) = mapOf(
                "string" to SymbolReference(SymbolKind.CLASS, "Ljava/lang/String;"),
            )
        }
        val resolver = TargetSymbolResolver(target(), loader, cache, SymbolQueryFactory { fakeQuery() })

        try {
            resolver.resolve(definition)
            fail("expected fail-closed resolution")
        } catch (expected: SymbolResolutionException) {
            assertTrue(expected.message!!.contains("missing required symbols"))
        }
        assertEquals(null, cache.read(definition.id))
    }

    @Test
    fun fileCacheRejectsCorruptJson() {
        val directory = Files.createTempDirectory("symbols-cache").toFile()
        try {
            File(directory, "bundle.json").writeText("not-json")
            assertEquals(null, FileSymbolCache(directory).read("bundle"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun target() = TargetArtifact(
        "target", 2,
        listOf(ArtifactFile("base.apk", 100, 200)),
    )

    private fun cached(
        definition: SymbolBundleDefinition,
        target: TargetArtifact,
        references: Map<String, SymbolReference>,
    ) = CachedSymbolBundle(
        bundleId = definition.id,
        schemaVersion = definition.schemaVersion,
        targetFingerprint = target.fingerprint,
        symbols = references.mapValues { CachedSymbolReference.from(it.value) },
    )

    private fun validReferences() = mapOf(
        "string" to SymbolReference(SymbolKind.CLASS, "Ljava/lang/String;"),
        "length" to SymbolReference(SymbolKind.METHOD, "Ljava/lang/String;->length()I"),
    )

    private fun fakeQuery(onClose: () -> Unit = {}) = object : SymbolQuery {
        override val bridge = null
        override fun candidates(symbol: String, descriptors: Collection<String>) = descriptors.distinct()
        override fun requireUnique(symbol: String, descriptors: Collection<String>): String {
            val values = candidates(symbol, descriptors)
            check(values.size == 1)
            return values.single()
        }
        override fun close() = onClose()
    }

    private open inner class StringBundle : SymbolBundleDefinition {
        override val id = "bundle"
        override val schemaVersion = 3
        override val requiredSymbols = setOf("string", "length")
        override fun resolve(query: SymbolQuery) = validReferences()
    }
}