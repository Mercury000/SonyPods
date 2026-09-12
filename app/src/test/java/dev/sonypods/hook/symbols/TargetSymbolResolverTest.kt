package dev.sonypods.hook.symbols

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun cacheIsRecheckedAfterAcquiringScanLock() {
        val cache = MemorySymbolCache()
        val target = target()
        val definition = StringBundle()
        var opened = false
        var lockCount = 0
        val coordinator = object : SymbolScanCoordinator {
            override fun <T> withScanLock(key: String, action: () -> T): T {
                lockCount++
                cache.write(cached(definition, target, validReferences()))
                return action()
            }
        }
        val resolver = TargetSymbolResolver(
            target,
            loader,
            cache,
            SymbolQueryFactory {
                opened = true
                error("must not scan after another process published the cache")
            },
            scanCoordinator = coordinator,
        )

        val bundle = resolver.resolve(definition)

        assertTrue(bundle.fromCache)
        assertFalse(opened)
        assertEquals(1, lockCount)
    }

    @Test
    fun scanLockFollowsSharedCacheIdentityAcrossFingerprints() {
        val cache = MemorySymbolCache()
        val definition = StringBundle()
        val keys = mutableListOf<String>()
        val coordinator = object : SymbolScanCoordinator {
            override fun <T> withScanLock(key: String, action: () -> T): T {
                keys += key
                return action()
            }
        }
        fun resolve(target: TargetArtifact) = TargetSymbolResolver(
            target,
            loader,
            cache,
            SymbolQueryFactory { fakeQuery() },
            scanCoordinator = coordinator,
        ).resolve(definition)

        resolve(target().copy(versionCode = 1))
        cache.remove(definition.id)
        resolve(target().copy(versionCode = 2))

        assertEquals(2, keys.size)
        assertEquals(keys[0], keys[1])
    }

    @Test
    fun fileCoordinatorAllowsOnlyOneResolverToScanSharedBundle() {
        val root = Files.createTempDirectory("symbols-coordination").toFile()
        val cacheDirectory = File(root, "cache")
        val lockDirectory = File(root, "locks")
        val target = target()
        val scans = AtomicInteger()
        val start = CountDownLatch(1)
        val definition = object : StringBundle() {
            override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
                scans.incrementAndGet()
                Thread.sleep(150)
                return validReferences()
            }
        }
        fun resolver() = TargetSymbolResolver(
            target,
            loader,
            FileSymbolCache(cacheDirectory),
            SymbolQueryFactory { fakeQuery() },
            scanCoordinator = FileSymbolScanCoordinator(lockDirectory),
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(resolver(), resolver()).map { resolver ->
                executor.submit<ResolvedSymbolBundle> {
                    start.await()
                    resolver.resolve(definition)
                }
            }
            start.countDown()
            val bundles = futures.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, scans.get())
            assertEquals(1, bundles.count { it.fromCache })
            assertEquals(1, bundles.count { !it.fromCache })
        } finally {
            executor.shutdownNow()
            root.deleteRecursively()
        }
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


    @Test
    fun fixedBundleBypassesDexKitAndCache() {
        val cache = MemorySymbolCache()
        var opened = false
        val definition = object : FixedSymbolBundleDefinition {
            override val id = "fixed"
            override val schemaVersion = 1
            override val requiredSymbols = setOf("string", "length")
            override val symbols = validReferences()
        }
        val resolver = TargetSymbolResolver(
            target(),
            loader,
            cache,
            SymbolQueryFactory {
                opened = true
                error("must not scan")
            },
        )

        val bundle = resolver.resolve(definition)

        assertFalse(bundle.fromCache)
        assertFalse(opened)
        assertEquals(6, bundle.method("length").invoke("abcdef"))
        assertEquals(null, cache.read(definition.id))
    }


    @Test
    fun fixedBundleAcceptsRequiredMultiSymbolGroup() {
        val definition = object : FixedSymbolBundleDefinition {
            override val id = "fixed-group"
            override val schemaVersion = 1
            override val requiredSymbols = setOf("string")
            override val requiredPrefixes = setOf("field.")
            override val symbols = mapOf(
                "string" to SymbolReference(SymbolKind.CLASS, "Ljava/lang/String;"),
                "field.0" to SymbolReference(SymbolKind.CLASS, "Ljava/lang/String;"),
                "field.1" to SymbolReference(SymbolKind.CLASS, "Ljava/lang/Integer;"),
            )
        }
        val resolver = TargetSymbolResolver(target(), loader, MemorySymbolCache(), SymbolQueryFactory { error("must not scan") })

        val bundle = resolver.resolve(definition)

        assertEquals(2, bundle.descriptorsWithPrefix("field.").size)
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

    private open inner class StringBundle : DexKitSymbolBundleDefinition {
        override val id = "bundle"
        override val schemaVersion = 3
        override val requiredSymbols = setOf("string", "length")
        override fun resolve(query: SymbolQuery) = validReferences()
    }
}
