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
    fun remoteHeadsetStateRemainsAuthoritativeInFusionRegistry() {
        val source = source("MiLinkFusionRegistryHook.kt")

        assertTrue(source.contains("refreshRegistry(svc, broadcast = false)"))
        assertTrue(source.contains("if (!broadcast && hasAuthoritativeMode(existing))"))
        assertTrue(source.contains("return mode in 0..2"))
        assertFalse(source.contains("this.result = completed(100)"))
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
    fun wearCapabilityIsInitializedBeforeActiveCallbackCanPublish() {
        val source = source("MiLinkFusionRegistryHook.kt")
        val runtime = source("MiLinkServiceHook.kt")

        assertTrue(source.contains("fun onApplicationReady()"))
        assertTrue(runtime.contains("fusionRegistryHook.onApplicationReady()"))
        assertTrue(source.contains("hook.requireSymbols(MiLinkWearSymbols)"))
        assertTrue(source.contains("wearCallbackMethods.forEach"))
        assertTrue(source.contains("if (registered) return@hookBefore"))
        assertTrue(source.contains("seedWearCapabilityFromController(listener)"))
        assertTrue(source.contains("callMethod(wearController, \"getSupportAncMode\", wearService)"))
        assertTrue(source.contains("supportFuture.getNow(null)"))
        assertTrue(source.contains("wearSupportModeField.setInt(notify, supportMode)"))
        assertFalse(source.contains("DexFile"))
        assertFalse(source.contains("declaredFields"))
        assertFalse(source.contains("declaredMethods"))
        assertFalse(source.contains("applicationClassNames"))
        assertFalse(source.contains("registerServiceNotify"))
        assertFalse(source.contains("announceCurrentState"))
        assertFalse(source.contains("WEAR_HEADSET_CONTROLLER"))
        assertFalse(source.contains("WEAR_CONTROLLER_FIELD"))
        assertFalse(source.contains("WEAR_SERVICE_FIELD"))
        assertFalse(source.contains("WEAR_SUPPORT_MODE_FIELD"))
        assertFalse(source.contains("com.miui.circulate.wear.agent.device.controller.b"))
        assertFalse(source.contains("WEAR_SHARE_DEVICE"))
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

    @Test
    fun cardArtUsesResolvedFieldsOnly() {
        val source = source("MiLinkCardArtHook.kt")

        assertTrue(source.contains("hook.requireSymbols(MiLinkCardArtSymbols)"))
        assertTrue(source.contains("symbols.field(\"deviceInfoField\")"))
        assertTrue(source.contains("symbols.field(\"circulateServicesField\")"))
        assertTrue(source.contains("symbols.field(\"deviceIdField\")"))
        assertFalse(source.contains("declaredFields"))
        assertFalse(source.contains("getDeclaredField"))
        assertFalse(source.contains("CIRCULATE_DEVICE_INFO_CLASS"))
    }

    @Test
    fun milinkHasNoAmbiguousMethodOrFieldFallback() {
        val sources = sourceRoot.resolve("dev/sonypods/hook/milink")
            .toFile()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(sources.contains("findMethodByParamCount"))
        assertFalse(sources.contains("declaredConstructors"))
        assertFalse(sources.contains("getDeclaredField"))
        assertFalse(sources.contains("notify.javaClass.methods.firstOrNull"))
        assertTrue(sources.contains("MiLinkStableSymbols"))
        assertTrue(sources.contains("MiLinkRuntimeSymbols"))
    }

    @Test
    fun deviceMetaGuardUsesResolvedSymbolsOnly() {
        val source = source("MiLinkDeviceMetaGuardHook.kt")

        assertTrue(source.contains("hook.requireSymbols(MiLinkDeviceMetaSymbols)"))
        assertTrue(source.contains("symbols.field(\"deviceType\")"))
        assertTrue(source.contains("symbols.field(\"title\")"))
        assertFalse(source.contains("declaredMethods"))
        assertFalse(source.contains("stringFieldOf"))
        assertFalse(source.contains("Regex("))
    }
}
