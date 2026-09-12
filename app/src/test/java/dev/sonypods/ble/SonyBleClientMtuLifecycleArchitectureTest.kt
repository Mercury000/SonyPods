package dev.sonypods.ble

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyBleClientMtuLifecycleArchitectureTest {
    private val source: String by lazy {
        val relative = Path.of("src/main/java/dev/sonypods/ble/SonyBleClient.kt")
        val candidates = sequenceOf(Path.of(""), Path.of("app"), Path.of("..", "app"))
            .map { it.resolve(relative).normalize() }
        candidates.firstOrNull { it.isRegularFile() }
            ?.let(Files::readString)
            ?: error("SonyBleClient.kt not found from ${Path.of("").toAbsolutePath()}")
    }

    @Test
    fun xiaomiGattUsesOnlyAutomaticMtuNegotiation() {
        assertFalse("SonyPods must not duplicate Xiaomi automatic MTU", source.contains("requestMtu("))
        assertTrue(source.contains("HandshakeStep.AwaitAutomaticMtu"))
        assertTrue(source.contains("markAutomaticMtuSettled(gatt)"))
    }

    @Test
    fun gattCloseIsEventDrivenRatherThanDelayDriven() {
        assertTrue(source.contains("IdentityHashMap<BluetoothGatt, GattLifecycle>"))
        assertTrue(source.contains("if (markAutomaticMtuSettled(gatt)) return"))
        assertFalse(source.contains("MTU_SETTLE_TIMEOUT_MS"))
        assertFalse(
            "MTU settlement must not be guessed with a timer",
            Regex("postDelayed\\s*\\([^)]*finishDeferredGattClose").containsMatchIn(source),
        )
        assertTrue(source.contains("requestGattClose(gatt, disconnectedObserved = true)"))
    }
}
