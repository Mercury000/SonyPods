package dev.sonypods.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.mercury.sonypods.R
import dev.sonypods.protocol.SonyGatt
import dev.sonypods.utils.ModuleText
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

    /** Audio Stream Control Service: present on the LE Audio identity, absent on the other. */
    private val ASCS_SERVICE: UUID = UUID.fromString("0000184E-0000-1000-8000-00805F9B34FB")

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

    private val sonyNamePrefixes = listOf(
        "wf-", "wh-", "wi-", "xba-", "mdr-",
        "srs-", "ult ", "ht-", "sa-", "lspx-", "gtk-", "gtx-", "mhc-",
    )

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

    /**
     * Whether this is the headset's LE Audio identity rather than the one carrying Tandem.
     *
     * Answered from [HeadsetRegistry]: the headset itself named both identities over Tandem
     * (`LEA_RET_CAPABILITY`), so an address is the LE one exactly when it appears in the record's
     * reported LE list and is not the record's own classic address.
     *
     * Falls back to [BluetoothDevice.type] and UUID analysis only for a headset no session has ever
     * identified — before the first connection the registry cannot know it, by construction.
     */
    fun isLeAudioIdentity(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = runCatching { device.address }.getOrNull() ?: return false

        HeadsetRegistry.recordFor(address)?.let { return it.isLeIdentity(address) }

        // Fallback: transport type is carried by the bond record itself and needs no discovery.
        // TYPE_DUAL(3) serves both transports and must not be called an LE-only identity.
        val transport = runCatching { device.type }.getOrDefault(DEVICE_TYPE_UNKNOWN)
        if (transport == DEVICE_TYPE_LE) return true
        if (transport != DEVICE_TYPE_UNKNOWN) return false

        // Fallback: UUID service analysis
        val serviceUuids = runCatching { device.uuids.orEmpty().map { it.uuid } }
            .getOrDefault(emptyList())
        return isLeAudioIdentity(serviceUuids)
    }

    fun isLeAudioIdentity(serviceUuids: Collection<UUID>): Boolean {
        if (serviceUuids.isEmpty()) return false
        return ASCS_SERVICE in serviceUuids && serviceUuids.none { it in sonyServiceUuids }
    }

    /**
     * The address to talk to for control, given any identity of the headset.
     *
     * Returns [address] unchanged when the headset is unknown, so callers can use this
     * unconditionally. This is SC `C14289i.m61741x`'s reverse lookup: find the record owning the
     * address, dial the record's own address.
     */
    fun resolveControlAddress(address: String?): String? {
        val normalized = normalizeAddress(address) ?: return address
        return HeadsetRegistry.controlAddressFor(normalized) ?: normalized
    }

    /**
     * Records that [leAddress] is an LE Audio identity of the headset at [controlAddress].
     *
     * The direction is stated by the caller — the module's own pairing flow, which is the one moment
     * both addresses are in hand and no Tandem session exists yet to ask.
     */
    fun linkLeAudioIdentity(leAddress: String?, controlAddress: String?) {
        val le = normalizeAddress(leAddress) ?: return
        val control = normalizeAddress(controlAddress) ?: return
        if (le == control) return
        rememberAddress(control)
        HeadsetRegistry.rememberLeAddress(le, control)
    }

    /** Snapshot of all known LE→control identity aliases. */
    fun leAudioAliasSnapshot(): Map<String, String> =
        HeadsetRegistry.all()
            .flatMap { record -> record.leAddresses.map { it to record.uniqueId } }
            .toMap()

    /**
     * An LE Audio identity of the headset reachable at [controlAddress], if one is known.
     *
     * The inverse of [resolveControlAddress]. MiLink circulate hands a peer a single address and the
     * peer can only act on the identity it is itself bonded to, so a transfer that was refused for
     * one identity has to be retried against the other.
     */
    fun leAudioIdentityFor(controlAddress: String?): String? {
        val control = normalizeAddress(controlAddress) ?: return null
        return HeadsetRegistry.leAddressesFor(control).firstOrNull()
    }

    /** Every other bonded identity of one headset, given any of them. */
    fun identityAliasesOf(address: String?): List<String> {
        val normalized = normalizeAddress(address) ?: return emptyList()
        val record = HeadsetRegistry.recordFor(normalized) ?: return emptyList()
        return record.addresses.filterNot { it == normalized }
    }

    fun rememberAddress(address: String?) {
        normalizeAddress(address)?.let(knownAddresses::add)
    }

    /**
     * The name to show for a Sony headset whose real one is not known yet.
     *
     * A session reports its model name only once `DEVICE_INFO` answers, and until then something has
     * to fill the notification, the island and the page title. Resolved through [ModuleText] because
     * most callers run inside a hooked system process, where the module's own resources are only
     * reachable through a package context.
     *
     * The literal is the last resort for the one case [ModuleText] cannot serve — it answers an empty
     * string when the package context or the resource lookup fails — and is deliberately the only
     * hard-coded copy of it left.
     */
    fun defaultDeviceName(context: Context?): String {
        val fromResources = context?.let { ModuleText.get(it, R.string.unknown_sony_device) }
        return fromResources?.takeIf { it.isNotBlank() } ?: "Sony headphones"
    }

    fun knownAddressSnapshot(): Set<String> = knownAddresses.toSet()

    private fun normalizeAddress(address: String?): String? {
        val normalized = address?.trim()?.uppercase(Locale.ROOT) ?: return null
        return normalized.takeIf { it.matches(BLUETOOTH_ADDRESS) }
    }

    private val BLUETOOTH_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

    private const val DEVICE_TYPE_UNKNOWN = 0
    private const val DEVICE_TYPE_LE = 2
}
