package dev.sonypods.hook

import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolReference
import org.junit.Assert.assertThrows
import org.junit.Test

class BluetoothSymbolsTest {
    @Test
    fun adapterContractAcceptsServiceAndBinderDisconnectEntries() {
        BluetoothAdapterSymbols.validate(validAdapterSymbols())
    }

    @Test
    fun adapterContractRejectsDisconnectOutsideBluetoothAdapterPackage() {
        val symbols = validAdapterSymbols().toMutableMap()
        symbols["deviceDisconnect.1"] = method(
            "Lcom/example/AdapterBinder;->disconnectAllEnabledProfiles(Landroid/bluetooth/BluetoothDevice;)I",
        )
        assertThrows(IllegalArgumentException::class.java) {
            BluetoothAdapterSymbols.validate(symbols)
        }
    }

    @Test
    fun profileContractAcceptsExactTransitionSignatures() {
        BluetoothProfileSymbols.validate(validProfileSymbols())
    }

    @Test
    fun profileContractRejectsWrongLeAudioOverload() {
        val symbols = validProfileSymbols().toMutableMap()
        symbols["leAudioConnectionChanged"] = method(
            "$LE_AUDIO->notifyConnectionStateChanged(Landroid/bluetooth/BluetoothDevice;IIZ)V",
        )
        assertThrows(IllegalArgumentException::class.java) {
            BluetoothProfileSymbols.validate(symbols)
        }
    }

    private fun validAdapterSymbols(): Map<String, SymbolReference> = mapOf(
        "adapterService" to clazz(ADAPTER),
        "onCreate" to method("$ADAPTER->onCreate()V"),
        "isLeAudioAllowed" to method(
            "$ADAPTER->isLeAudioAllowed(Landroid/bluetooth/BluetoothDevice;)Z",
        ),
        "deviceDisconnect.0" to method(
            "$ADAPTER->disconnectAllEnabledProfiles(Landroid/bluetooth/BluetoothDevice;)I",
        ),
        "deviceDisconnect.1" to method(
            "$ADAPTER_BINDER->disconnectAllEnabledProfiles(Landroid/bluetooth/BluetoothDevice;Landroid/content/AttributionSource;)I",
        ),
    )

    private fun validProfileSymbols(): Map<String, SymbolReference> = mapOf(
        "a2dpService" to clazz(A2DP),
        "a2dpConnectionChanged" to method(
            "$A2DP->handleConnectionStateChanged(Landroid/bluetooth/BluetoothDevice;II)V",
        ),
        "a2dpHandler" to field("$A2DP->mHandler:Landroid/os/Handler;"),
        "leAudioService" to clazz(LE_AUDIO),
        "leAudioConnectionChanged" to method(
            "$LE_AUDIO->notifyConnectionStateChanged(Landroid/bluetooth/BluetoothDevice;II)V",
        ),
        "leAudioActiveDeviceChanged" to method(
            "$LE_AUDIO->notifyActiveDeviceChanged(Landroid/bluetooth/BluetoothDevice;)V",
        ),
        "leAudioGroupDevices" to method(
            "$LE_AUDIO->getGroupDevices(Landroid/bluetooth/BluetoothDevice;)Ljava/util/List;",
        ),
        "leAudioHandler" to field("$LE_AUDIO->mHandler:Landroid/os/Handler;"),
    )

    private fun clazz(descriptor: String) = SymbolReference(SymbolKind.CLASS, descriptor)
    private fun method(descriptor: String) = SymbolReference(SymbolKind.METHOD, descriptor)
    private fun field(descriptor: String) = SymbolReference(SymbolKind.FIELD, descriptor)

    companion object {
        private const val ADAPTER = "Lcom/android/bluetooth/btservice/AdapterService;"
        private const val ADAPTER_BINDER = "Lcom/android/bluetooth/btservice/AdapterServiceBinder;"
        private const val A2DP = "Lcom/android/bluetooth/a2dp/A2dpService;"
        private const val LE_AUDIO = "Lcom/android/bluetooth/le_audio/LeAudioService;"
    }
}
