package dev.sonypods.hook

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDexKitArchitectureTest {
    private val sourceRoot: Path = locateSourceRoot()

    @Test
    fun settingsHooksConsumeResolvedTargetSymbols() {
        val headset = source("SettingsHeadsetHook.kt")
        val render = source("SettingsRenderHook.kt")
        val hooks = headset + render

        listOf(
            "SettingsActivitySymbols",
            "SettingsSupportSymbols",
            "SettingsBatterySymbols",
            "SettingsFragmentSymbols",
            "SettingsRenderSymbols",
        ).forEach { assertTrue(it, hooks.contains(it)) }
        assertFalse(hooks.contains("findMethod("))
        assertFalse(hooks.contains("findMethodByParamCount("))
        assertFalse(hooks.contains("findClass("))
        assertFalse(hooks.contains("declaredFields"))
        assertFalse(hooks.contains("IMiuiHeadsetService\$Stub\$Proxy"))
        assertTrue(hooks.contains("SettingsServiceProxySymbols"))
    }

    @Test
    fun proxyIdentityAndControlHooksUseResolvedStableAbi() {
        val source = source("SettingsHeadsetHook.kt")
        assertTrue(source.contains("hookServiceProxy()"))
        assertTrue(source.contains("serviceProxySymbols.method(symbol)"))
        assertTrue(source.contains("hookProxyString(\"getDeviceInfo\")"))
        assertTrue(source.contains("hookProxyString(\"isSupportAudioSwitch\")"))
        assertFalse(source.contains("findMethod(className"))
    }

    @Test
    fun symbolBundlesUseSemanticDexAnchors() {
        val source = source("SettingsSymbols.kt")
        assertTrue(source.contains("com.android.settings.bluetooth.MiuiHeadsetActivity"))
        assertTrue(source.contains("com.android.settings.bluetooth.HeadsetIDConstants"))
        assertTrue(source.contains("com.android.settings.bluetooth.MiuiHeadsetFragment"))
        assertTrue(source.contains("com.android.settings.bluetooth.tws.MiuiHeadsetAnimation"))
        assertTrue(source.contains("className(className)"))
        assertTrue(source.contains("requireMethod"))
        assertTrue(source.contains("requireNamedField"))
    }

    private fun source(name: String): String =
        sourceRoot.resolve("dev/sonypods/hook/$name").toFile().readText()

    private fun locateSourceRoot(): Path {
        val candidates = listOf(Paths.get("src/main/java"), Paths.get("app/src/main/java"), Paths.get("../app/src/main/java"))
        return candidates.firstOrNull { Files.isDirectory(it.resolve("dev/sonypods/hook")) }
            ?: error("cannot locate app/src/main/java from ${Paths.get("").toAbsolutePath()}")
    }
}
