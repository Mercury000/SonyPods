package dev.sonypods.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import dev.sonypods.protocol.SonyGatt
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Single Sony-device identity service used by the app and every hooked process.
 *
 * A Bluetooth name is only one signal: users can rename a headset and Sony LE
 * endpoints may expose a generated name such as LE_WF-....  A previously
 * confirmed address, Sony's GATT/SPP UUIDs, or a parsed Sony advertisement are
 * stronger signals and must continue to work when the name is unavailable.
 *
 * The address set is intentionally process-local.  Hook processes receive the
 * current address through [dev.sonypods.bridge.HookStateMirror], while the
 * Bluetooth process also learns it when a Sony device is accepted.  No arbitrary
 * address is accepted merely because it is syntactically a Bluetooth address.
 */
@SuppressLint("MissingPermission")
object SonyDeviceService {
    private val knownAddresses = ConcurrentHashMap.newKeySet<String>()

    private val sonyServiceUuids: Set<UUID> = setOf(
        SonyGatt.BLUETOOTH_IAP_CONNECTION_SERVICE,
        SonyGatt.BLUETOOTH_IAP_CONNECTION_MC_SERVICE,
        SonyGatt.BLE_PAIRING_TWS_HPC_SERVICE,
        SonyGatt.TANDEM_V2_HPC_SERVICE,
        SonyGatt.TANDEM_V2_MC_SERVICE,
        SonyGatt.TANDEM_V1_MC_SERVICE,
        SonyGatt.TANDEM_SSH_SERVICE,
        SonyGatt.BLUETOOTH_PAIRING_COMPLETE_NAME_SERVICE,
        SonyGatt.LE_AUDIO_CAPABILITY_FOR_HPC,
        UUID.fromString("443cce33-e85d-4b85-8d53-6e319ede53ae"),
        UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2"),
        UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba"),
    )

    private val sonyNamePrefixes = listOf("wf-", "wh-", "wi-", "xba-", "mdr-")

    /** Classifies an Android Bluetooth device using every locally available signal. */
    fun isSony(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = runCatching { device.address }.getOrNull()
        val names = listOfNotNull(
            runCatching { device.name }.getOrNull(),
            runCatching { device.alias }.getOrNull(),
        )
        val serviceUuids = runCatching { device.uuids.orEmpty().map { it.uuid } }.getOrDefault(emptyList())
        return isSony(
            address = address,
            names = names,
            serviceUuids = serviceUuids,
        )
    }

    /** Classifies a scan result or a reflected device when no BluetoothDevice exists. */
    fun isSony(
        address: String? = null,
        name: String? = null,
        names: Collection<String?> = emptyList(),
        serviceUuids: Collection<UUID> = emptyList(),
        hasSonyAdvertisement: Boolean = false,
    ): Boolean {
        val normalizedAddress = normalizeAddress(address)
        val allNames = buildList {
            name?.let(::add)
            addAll(names)
        }
        val byKnownAddress = normalizedAddress != null && normalizedAddress in knownAddresses
        val byName = allNames.any(::isSonyName)
        val byGattOrSpp = serviceUuids.any { it in sonyServiceUuids }
        val result = byKnownAddress || byName || byGattOrSpp || hasSonyAdvertisement
        if (result) normalizedAddress?.let(::rememberAddress)
        return result
    }

    fun isSonyName(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalized.isEmpty()) return false
        val withoutLePrefix = normalized.removePrefix("le_").removePrefix("le-")
        return withoutLePrefix.contains("sony") ||
            withoutLePrefix.contains("linkbuds") ||
            sonyNamePrefixes.any(withoutLePrefix::startsWith)
    }

    fun isKnownSonyAddress(address: String?): Boolean =
        normalizeAddress(address)?.let { it in knownAddresses } == true

    fun rememberAddress(address: String?) {
        normalizeAddress(address)?.let(knownAddresses::add)
    }

    fun knownAddressSnapshot(): Set<String> = knownAddresses.toSet()

    private fun normalizeAddress(address: String?): String? {
        val normalized = address?.trim()?.uppercase(Locale.ROOT) ?: return null
        return normalized.takeIf { it.matches(BLUETOOTH_ADDRESS) }
    }

    private val BLUETOOTH_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
}
