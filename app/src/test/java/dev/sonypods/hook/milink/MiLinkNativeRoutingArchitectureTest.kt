package dev.sonypods.hook.milink

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiLinkNativeRoutingArchitectureTest {
    private val sourceRoot: Path = sequenceOf(
        Path.of("src/main/java"),
        Path.of("app/src/main/java"),
    ).firstOrNull { Files.isDirectory(it) }
        ?: error("Cannot locate main source root from ${Path.of("").toAbsolutePath()}")

    private fun source(name: String): String =
        sourceRoot.resolve("dev/sonypods/hook/milink/$name").readText()

    @Test
    fun fusionRegistryDoesNotInterceptControllerWrites() {
        val source = source("MiLinkFusionRegistryHook.kt")

        assertFalse(source.contains("fusion-registry-set-anc"))
        assertFalse(source.contains("fusion-registry-set-volume"))
        assertFalse(source.contains("\"setNoiseCancelling\""))
        assertFalse(source.contains("\"setVolume\""))
    }

    @Test
    fun commandRoutingDoesNotDependOnLocalHolderSnapshot() {
        val sources = listOf(
            source("MiLinkFusionRegistryHook.kt"),
            source("MiLinkServiceHook.kt"),
            source("MiLinkRemoteProtocolHook.kt"),
        ).joinToString("\n")

        listOf("isCurrentlyHeld", "heldHere", "engineHeldAddress", "snapshotReceived")
            .forEach { forbidden -> assertFalse(forbidden, sources.contains(forbidden)) }
    }

    @Test
    fun endpointTranslationRemainsInstalled() {
        val runtime = source("MiLinkServiceHook.kt")
        val remote = source("MiLinkRemoteProtocolHook.kt")

        assertTrue(runtime.contains("hookAncCommand"))
        assertTrue(runtime.contains("hookAncStateBlock"))
        assertTrue(remote.contains("HeadsetRemoteImpl"))
        assertTrue(remote.contains("applyRemoteAncMode"))
    }
}
