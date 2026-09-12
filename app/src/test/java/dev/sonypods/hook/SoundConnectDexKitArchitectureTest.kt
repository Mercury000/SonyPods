package dev.sonypods.hook

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundConnectDexKitArchitectureTest {
    private val sourceRoot: Path = locateSourceRoot()

    @Test
    fun handoverConsumesResolvedSymbolsOnly() {
        val source = sourceRoot.resolve("dev/sonypods/hook/SoundConnectHandoverHook.kt").toFile().readText()

        assertTrue(source.contains("requireSymbols(SoundConnectServiceSymbols)"))
        assertTrue(source.contains("requireSymbols(SoundConnectSessionSymbols)"))
        assertTrue(source.contains("runtime.symbols(appClassLoader, application)"))
        assertTrue(source.contains("symbols.method(\"onBind\")"))
        assertTrue(source.contains("sessionContract?.firstSession"))
        assertFalse(source.contains("findMethodByParamCount"))
        assertFalse(source.contains("callMethod(instance"))
        assertFalse(source.contains("platform.connection.connection.p0"))
        assertFalse(source.contains("\"r1\""))
        assertFalse(source.contains("\"u1\""))
        assertFalse(source.contains("\"V0\""))
        assertFalse(source.contains("\"z0\""))
    }

    @Test
    fun sessionBundleUsesSemanticDexEvidence() {
        val source = sourceRoot.resolve("dev/sonypods/hook/SoundConnectSymbols.kt").toFile().readText()

        assertTrue(source.contains("usingStrings("))
        assertTrue(source.contains("invokesMapMethod(\"put\", 2)"))
        assertTrue(source.contains("invokesMapMethod(\"remove\", 1)"))
        assertTrue(source.contains("sharedReadFields"))
        assertFalse(source.contains("C14356p0"))
        assertFalse(source.contains("m62011r1"))
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

