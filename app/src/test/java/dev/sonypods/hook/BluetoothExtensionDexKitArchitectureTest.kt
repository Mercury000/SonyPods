package dev.sonypods.hook

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothExtensionDexKitArchitectureTest {
    private val sourceRoot: Path = locateSourceRoot()

    @Test
    fun extensionDexKitHooksWaitForPersistentApplicationResolver() {
        val hooks = listOf(
            source("BluetoothUpstreamHeadsetHook.kt"),
            source("MiBluetoothToastHook.kt"),
            source("OfficialFastConnectDialogHook.kt"),
        )
        hooks.forEach { hook ->
            assertTrue(hook.contains("callApplicationOnCreate"))
            assertTrue(hook.contains("runtime.symbols(appClassLoader, appContext)"))
        }
        val upstreamOnHook = hooks[0].substringAfter("override fun onHook()")
            .substringBefore("@Synchronized")
        val toastOnHook = hooks[1].substringAfter("override fun onHook()")
            .substringBefore("@Synchronized")
        assertFalse(upstreamOnHook.contains("requireSymbols("))
        assertFalse(toastOnHook.contains("requireSymbols("))
    }

    @Test
    fun headsetAndNotificationHooksConsumeResolvedSymbols() {
        val upstream = source("BluetoothUpstreamHeadsetHook.kt")
        val toast = source("MiBluetoothToastHook.kt")
        val official = source("OfficialFastConnectDialogHook.kt")

        assertTrue(upstream.contains("requireSymbols(BluetoothExtensionHeadsetSymbols)"))
        assertTrue(upstream.contains("requireSymbols(BluetoothExtensionNotificationSymbols)"))
        assertTrue(upstream.contains("headsetSymbols.method(\"checkSupport\")"))
        assertTrue(upstream.contains("notificationSymbols.field(\"requestDeviceField\")"))
        assertFalse(upstream.contains("BinderC6776v\""))
        assertFalse(upstream.contains("C4705R2"))
        assertFalse(upstream.contains("f18110e"))
        assertFalse(upstream.contains("getDeclaredMethod("))
        assertFalse(upstream.contains("callMethod("))
        assertFalse(upstream.contains("methodNames"))
        assertTrue(upstream.contains("(callback as? IInterface)?.asBinder()"))
        assertFalse(upstream.contains("findClass("))

        assertTrue(toast.contains("notificationSymbols.constructorsWithPrefix(\"constructor.\")"))
        assertTrue(official.contains("symbols.constructorsWithPrefix(\"constructor.\")"))
        assertFalse(toast.contains("findConstructorsByParamCount"))
        assertFalse(official.contains("findConstructorsByParamCount"))
    }

    @Test
    fun symbolBundlesUseSemanticDexEvidence() {
        val symbols = source("BluetoothExtensionSymbols.kt")
        assertTrue(symbols.contains("bridge.findClass"))
        assertTrue(symbols.contains("bridge.findMethod"))
        assertTrue(symbols.contains("FieldUsingType.Write"))
        assertTrue(symbols.contains("FieldUsingType.Read"))
        assertTrue(symbols.contains("update.paramTypeNames.single()"))
        assertTrue(symbols.contains("callbackFactory"))
        assertFalse(symbols.contains("mContext"))
        assertFalse(symbols.contains("mMiuiBluetoothNotification"))
        assertFalse(symbols.contains("C0878c"))
        assertFalse(symbols.contains("AbstractBinderC0847a"))
    }

    @Test
    fun dynamicFastConnectFeatureRemainsLifecycleLocated() {
        val official = source("OfficialFastConnectDialogHook.kt")
        assertTrue(official.contains("installFrameworkActivityHooks()"))
        assertTrue(official.contains("controller?.javaClass?.name"))
        assertTrue(official.contains("The modern activity is loaded from a Qigsaw feature"))
    }

    private fun source(name: String): String =
        sourceRoot.resolve("dev/sonypods/hook/$name").toFile().readText()

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
