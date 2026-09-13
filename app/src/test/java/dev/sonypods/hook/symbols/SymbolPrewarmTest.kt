package dev.sonypods.hook.symbols

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolPrewarmTest {
    private val loader = javaClass.classLoader!!

    @Test
    fun matchingLoaderHandsOverExactlyOnce() {
        val resolver = resolver()
        val prewarm = SymbolPrewarm("target", loader, listOf(definition()), { _, _ -> }) { _, _ -> resolver }

        prewarm.start()
        assertTrue(prewarm.awaitBatch())

        assertSame(resolver, prewarm.takeResolverIfLoader(loader))
        assertNull("handover must be single-shot", prewarm.takeResolverIfLoader(loader))
        assertFalse("pre-warm resolved the declared bundle", resolver.resolve(definition()).fromCache)
    }

    @Test
    fun nonMatchingLoaderNeverHandsOver() {
        val resolver = resolver()
        val prewarm = SymbolPrewarm("target", loader, listOf(definition()), { _, _ -> }) { _, _ -> resolver }

        prewarm.start()
        assertTrue(prewarm.awaitBatch())
        prewarm.close()

        val otherLoader = object : ClassLoader(loader) {}
        assertNull(prewarm.takeResolverIfLoader(otherLoader))
        assertNull("closed pre-warm must not hand over", prewarm.takeResolverIfLoader(loader))
    }

    private fun resolver() = TargetSymbolResolver(
        TargetArtifact("target", 2, listOf(ArtifactFile("base.apk", 100, 200))),
        loader,
        MemorySymbolCache(),
        SymbolQueryFactory { fakeQuery() },
    )

    private fun definition(): DexKitSymbolBundleDefinition = object : DexKitSymbolBundleDefinition {
        override val id = "prewarm.bundle"
        override val schemaVersion = 1
        override val requiredSymbols = setOf("string")
        override fun resolve(query: SymbolQuery) = mapOf(
            "string" to SymbolReference(SymbolKind.CLASS, "Ljava/lang/String;"),
        )
    }

    private fun fakeQuery() = object : SymbolQuery {
        override val bridge = null
        override fun requireUnique(symbol: String, descriptors: Collection<String>): String =
            descriptors.distinct().single()
    }
}
