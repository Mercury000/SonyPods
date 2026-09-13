package dev.sonypods.hook

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolPrewarmDeclarationTest {
    private val sourceRoot: Path = locateSourceRoot()

    @Test
    fun declaredBundlesMatchEveryRequireSymbolsCallSite() {
        val hookDirectory = sourceRoot.resolve("dev/sonypods/hook")
        val used = mutableSetOf<String>()
        Files.walk(hookDirectory).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .forEach { path ->
                    val text = path.toFile().readText()
                    Regex("requireSymbols\\(\\s*([A-Za-z0-9_]+)").findAll(text)
                        .filter { it.groupValues[1].first().isUpperCase() }
                        .forEach { used += it.groupValues[1] }
                }
        }
        val declared = scopeSymbolBundles.values.flatten().mapNotNull { it::class.simpleName }.toSet()

        assertTrue("no requireSymbols call sites found", used.isNotEmpty())
        assertEquals(used, declared)
    }

    @Test
    fun everySupportedScopeDeclaresBundles() {
        assertEquals(
            sortedSetOf(
                "com.android.bluetooth",
                "com.android.settings",
                "com.milink.service",
                "com.xiaomi.bluetooth",
                "com.sony.songpal.mdr",
            ),
            sortedSetOf(*scopeSymbolBundles.keys.toTypedArray()),
        )
        assertTrue(scopeSymbolBundles.values.none { it.isEmpty() })
    }

    private fun locateSourceRoot(): Path {
        val candidates = listOf(
            Paths.get("src/main/java"),
            Paths.get("app/src/main/java"),
            Paths.get("../app/src/main/java"),
        )
        return candidates.firstOrNull { Files.isDirectory(it.resolve("dev/sonypods/hook")) }
            ?: error("cannot locate app/src/main/java from ${Paths.get("").toAbsolutePath()}")
    }
}
