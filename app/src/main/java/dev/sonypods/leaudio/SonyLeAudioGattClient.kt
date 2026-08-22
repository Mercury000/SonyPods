package dev.sonypods.leaudio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.sonypods.protocol.SonyGatt
import dev.sonypods.protocol.hexString
import java.util.UUID

/**
 * Small, independent GATT session used by the LE Audio hand-over flow.
 *
 * It deliberately does not share the Tandem GATT instance. Sony's LE Audio
 * hand-over opens several short-lived GATT sessions while the normal Tandem
 * session is being torn down and recreated.
 */
class SonyLeAudioGattClient(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onReady()
        fun onDisconnected(status: Int)
        fun onFailure(reason: String)
        fun onCharacteristicChanged(uuid: UUID, value: ByteArray)
        fun onLog(message: String)
    }

    private sealed class Operation {
        abstract val uuid: UUID
        abstract val callback: (Boolean, ByteArray?, String?) -> Unit

        data class Read(
            override val uuid: UUID,
            override val callback: (Boolean, ByteArray?, String?) -> Unit,
        ) : Operation()

        data class Write(
            override val uuid: UUID,
            val value: ByteArray,
            val withResponse: Boolean,
            override val callback: (Boolean, ByteArray?, String?) -> Unit,
        ) : Operation()

        data class Notify(
            override val uuid: UUID,
            val enabled: Boolean,
            override val callback: (Boolean, ByteArray?, String?) -> Unit,
        ) : Operation()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val operations = ArrayDeque<Operation>()
    private var activeOperation: Operation? = null
    private var operationTimeout: Runnable? = null
    private var connectionTimeout: Runnable? = null
    private var disconnectTimeout: Runnable? = null
    private var gatt: BluetoothGatt? = null
    private var ready = false
    private var closed = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            if (this@SonyLeAudioGattClient.gatt !== gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectionTimeout?.let(handler::removeCallbacks)
                connectionTimeout = null
                log("LE Audio GATT connected ${gatt.device.address}; discovering services")
                if (!gatt.discoverServices()) {
                    failSession("LE Audio GATT service discovery enqueue failed")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("LE Audio GATT disconnected status=$status")
                finishDisconnected(gatt, status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (this@SonyLeAudioGattClient.gatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failSession("LE Audio GATT service discovery failed: $status")
                return
            }
            ready = true
            val services = gatt.services.joinToString { SonyGatt.serviceLabel(it.uuid) }
            log("LE Audio GATT services ready: [$services]")
            listener.onReady()
            drain()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (this@SonyLeAudioGattClient.gatt !== gatt) return
            val operation = activeOperation as? Operation.Read ?: return
            if (operation.uuid != characteristic.uuid) return
            completeOperation(
                operation,
                status == BluetoothGatt.GATT_SUCCESS,
                value,
                if (status == BluetoothGatt.GATT_SUCCESS) null else "read status=$status",
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (this@SonyLeAudioGattClient.gatt !== gatt) return
            val operation = activeOperation as? Operation.Write ?: return
            if (operation.uuid != characteristic.uuid) return
            completeOperation(
                operation,
                status == BluetoothGatt.GATT_SUCCESS,
                null,
                if (status == BluetoothGatt.GATT_SUCCESS) null else "write status=$status",
            )
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (this@SonyLeAudioGattClient.gatt !== gatt) return
            val operation = activeOperation as? Operation.Notify ?: return
            if (descriptor.characteristic?.uuid != operation.uuid) return
            completeOperation(
                operation,
                status == BluetoothGatt.GATT_SUCCESS,
                null,
                if (status == BluetoothGatt.GATT_SUCCESS) null else "descriptor write status=$status",
            )
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (this@SonyLeAudioGattClient.gatt !== gatt) return
            log("LE Audio GATT notify ${SonyGatt.characteristicLabel(characteristic.uuid)}=${value.hexString()}")
            listener.onCharacteristicChanged(characteristic.uuid, value.copyOf())
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String): Boolean {
        if (!hasConnectPermission()) {
            listener.onFailure("Bluetooth connect permission is missing")
            return false
        }
        close()
        closed = false
        val remote = runCatching {
            context.getSystemService(android.bluetooth.BluetoothManager::class.java)
                ?.adapter
                ?.getRemoteDevice(address)
        }.getOrNull()
        if (remote == null) {
            listener.onFailure("Cannot resolve LE Audio device $address")
            return false
        }
        log("Opening independent LE Audio GATT to ${remote.address}")
        gatt = if (Build.VERSION.SDK_INT >= 37) {
            val settings = android.bluetooth.BluetoothGattConnectionSettings.Builder()
                .setAutoConnectEnabled(false)
                .setTransport(BluetoothDevice.TRANSPORT_LE)
                .build()
            remote.connectGatt(settings, context.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
        connectionTimeout = Runnable {
            if (!ready && gatt != null) failSession("Timed out connecting to LE Audio GATT $address")
        }.also { handler.postDelayed(it, CONNECTION_TIMEOUT_MS) }
        return gatt != null
    }

    fun hasCharacteristic(uuid: UUID): Boolean =
        gatt?.services?.any { service -> service.getCharacteristic(uuid) != null } == true

    fun read(
        uuid: UUID,
        callback: (Boolean, ByteArray?, String?) -> Unit,
    ) {
        enqueue(Operation.Read(uuid, callback))
    }

    fun write(
        uuid: UUID,
        value: ByteArray,
        withResponse: Boolean = true,
        callback: (Boolean, ByteArray?, String?) -> Unit = { _, _, _ -> },
    ) {
        enqueue(Operation.Write(uuid, value.copyOf(), withResponse, callback))
    }

    fun setNotification(
        uuid: UUID,
        enabled: Boolean,
        callback: (Boolean, ByteArray?, String?) -> Unit = { _, _, _ -> },
    ) {
        enqueue(Operation.Notify(uuid, enabled, callback))
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val activeGatt = gatt ?: return
        log("Closing independent LE Audio GATT")
        activeGatt.disconnect()
        connectionTimeout?.let(handler::removeCallbacks)
        connectionTimeout = null
        operationTimeout?.let(handler::removeCallbacks)
        operationTimeout = null
        activeOperation = null
        operations.clear()
        ready = false
        val expectedGatt = activeGatt
        disconnectTimeout = Runnable {
            if (gatt === expectedGatt) {
                log("LE Audio GATT disconnect callback timed out; closing locally")
                expectedGatt.close()
                gatt = null
                listener.onDisconnected(BluetoothGatt.GATT_SUCCESS)
            }
        }.also { handler.postDelayed(it, DISCONNECT_TIMEOUT_MS) }
    }

    fun close() {
        closed = true
        connectionTimeout?.let(handler::removeCallbacks)
        operationTimeout?.let(handler::removeCallbacks)
        disconnectTimeout?.let(handler::removeCallbacks)
        connectionTimeout = null
        operationTimeout = null
        disconnectTimeout = null
        activeOperation = null
        operations.clear()
        gatt?.let {
            runCatching {
                @SuppressLint("MissingPermission")
                it.disconnect()
                it.close()
            }
        }
        clearSession()
    }

    private fun enqueue(operation: Operation) {
        if (closed) {
            operation.callback(false, null, "GATT client is closed")
            return
        }
        operations.addLast(operation)
        drain()
    }

    @SuppressLint("MissingPermission")
    private fun drain() {
        if (!ready || activeOperation != null || operations.isEmpty()) return
        val activeGatt = gatt ?: run {
            failSession("No active LE Audio GATT")
            return
        }
        val operation = operations.removeFirst()
        val characteristic = findCharacteristic(operation.uuid)
        if (characteristic == null) {
            operation.callback(false, null, "Characteristic ${SonyGatt.characteristicLabel(operation.uuid)} is missing")
            drain()
            return
        }
        activeOperation = operation
        operationTimeout = Runnable {
            if (activeOperation === operation) {
                completeOperation(operation, false, null, "operation timeout")
            }
        }.also { handler.postDelayed(it, OPERATION_TIMEOUT_MS) }
        val accepted = when (operation) {
            is Operation.Read -> activeGatt.readCharacteristic(characteristic)
            is Operation.Write -> {
                val writeType = if (
                    operation.withResponse &&
                    (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                activeGatt.writeCharacteristic(characteristic, operation.value, writeType) ==
                    BluetoothGatt.GATT_SUCCESS
            }
            is Operation.Notify -> startNotificationWrite(activeGatt, characteristic, operation.enabled)
        }
        if (!accepted) {
            completeOperation(operation, false, null, "operation rejected by Android Bluetooth stack")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNotificationWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean,
    ): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, enabled)) return false
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            ?: return false
        val value = if (enabled) {
            if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        return gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
    }

    private fun completeOperation(
        operation: Operation,
        success: Boolean,
        value: ByteArray?,
        error: String?,
    ) {
        if (activeOperation !== operation) return
        operationTimeout?.let(handler::removeCallbacks)
        operationTimeout = null
        activeOperation = null
        if (!success) {
            log("${operation.javaClass.simpleName} ${SonyGatt.characteristicLabel(operation.uuid)} failed: ${error.orEmpty()}")
        } else if (operation is Operation.Write) {
            log("Write ${SonyGatt.characteristicLabel(operation.uuid)}=${operation.value.hexString()} succeeded")
        } else if (operation is Operation.Read) {
            log("Read ${SonyGatt.characteristicLabel(operation.uuid)}=${value?.hexString().orEmpty()}")
        }
        operation.callback(success, value, error)
        drain()
    }

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services
            ?.asSequence()
            ?.flatMap(BluetoothGattService::getCharacteristics)
            ?.firstOrNull { it.uuid == uuid }

    @SuppressLint("MissingPermission")
    private fun failSession(reason: String) {
        log(reason)
        listener.onFailure(reason)
        gatt?.disconnect()
        gatt?.close()
        clearSession()
    }

    private fun finishDisconnected(disconnectedGatt: BluetoothGatt, status: Int) {
        if (gatt !== disconnectedGatt) return
        connectionTimeout?.let(handler::removeCallbacks)
        connectionTimeout = null
        disconnectTimeout?.let(handler::removeCallbacks)
        disconnectTimeout = null
        operationTimeout?.let(handler::removeCallbacks)
        operationTimeout = null
        activeOperation?.callback(false, null, "GATT disconnected status=$status")
        activeOperation = null
        operations.clear()
        ready = false
        disconnectedGatt.close()
        gatt = null
        listener.onDisconnected(status)
    }

    private fun clearSession() {
        ready = false
        activeOperation = null
        operations.clear()
        gatt = null
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    private fun log(message: String) {
        listener.onLog(message)
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 12_000L
        private const val OPERATION_TIMEOUT_MS = 5_000L
        private const val DISCONNECT_TIMEOUT_MS = 2_000L
        private val CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
