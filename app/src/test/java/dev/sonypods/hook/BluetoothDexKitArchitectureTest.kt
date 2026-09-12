package dev.sonypods.hook

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDexKitArchitectureTest {
    private val sourceRoot: Path = locateSourceRoot()

    @Test
    fun standardBluetoothScopeDoesNotLoadMiuiExtensionHook() {
        val source = source("HookEntry.kt")
        val standard = source.substringAfter("\"com.android.bluetooth\" -> {")
            .substringBefore("\"com.android.settings\" ->")
        val extension = source.substringAfter("\"com.xiaomi.bluetooth\" -> {")
            .substringBefore("\"com.sony.songpal.mdr\" ->")

        assertFalse(standard.contains("BluetoothUpstreamHeadsetHook"))
        assertTrue(extension.contains("BluetoothUpstreamHeadsetHook"))
    }

    @Test
    fun bluetoothDexKitHooksWaitForPersistentApplicationResolver() {
        val dispatcher = source("HeadsetStateDispatcher.kt")
        val allowList = source("LeAudioAllowListHook.kt")
        val dispatcherOnHook = dispatcher.substringAfter("override fun onHook()")
            .substringBefore("@Synchronized")
        val allowListOnHook = allowList.substringAfter("override fun onHook()")
            .substringBefore("internal fun startAfterReload")

        listOf(dispatcher, allowList).forEach { hook ->
            assertTrue(hook.contains("callApplicationOnCreate"))
            assertTrue(hook.contains("runtime.symbols(appClassLoader, appContext)"))
        }
        assertFalse(dispatcherOnHook.contains("requireSymbols("))
        assertFalse(allowListOnHook.contains("requireSymbols("))
    }

    @Test
    fun dispatcherConsumesResolvedBluetoothSymbolsOnly() {
        val source = source("HeadsetStateDispatcher.kt")

        assertTrue(source.contains("requireSymbols(BluetoothAdapterSymbols)"))
        assertTrue(source.contains("requireSymbols(BluetoothProfileSymbols)"))
        assertTrue(source.contains("profileSymbols.method(\"a2dpConnectionChanged\")"))
        assertTrue(source.contains("adapterSymbols.methodsWithPrefix(\"deviceDisconnect.\")"))
        assertFalse(source.contains("findMethodByParamCount"))
        assertFalse(source.contains("findClass("))
        assertFalse(source.contains("getObjectField(instance, \"mHandler\")"))
        assertFalse(source.contains("getMethod(\"getGroupDevices\""))
    }

    @Test
    fun allowListHookConsumesResolvedMethod() {
        val source = source("LeAudioAllowListHook.kt")
        assertTrue(source.contains("requireSymbols(BluetoothAdapterSymbols)"))
        assertTrue(source.contains("symbols.method(\"isLeAudioAllowed\")"))
        assertTrue(source.countOccurrences("findMethod(") == 1)
        assertTrue(source.contains("android.app.Instrumentation"))
    }
    @Test
    fun bundlesUseDexKitAndExactSignatures() {
        val source = source("BluetoothSymbols.kt")
        assertTrue(source.contains("bridge.findClass"))
        assertTrue(source.contains("bridge.findMethod"))
        assertTrue(source.contains("name(\"disconnectAllEnabledProfiles\")"))
        assertTrue(source.contains("method.paramTypeNames.firstOrNull() == \"android.bluetooth.BluetoothDevice\""))
        assertTrue(source.contains("\"notifyConnectionStateChanged\","))
        assertTrue(source.contains("requireNamedField(\"mHandler\", \"android.os.Handler\")"))
    }

    private fun String.countOccurrences(value: String): Int =
        windowed(value.length).count { it == value }

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
