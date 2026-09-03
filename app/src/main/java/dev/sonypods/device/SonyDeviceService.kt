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
     * A Sony headset with LE Audio enabled bonds as two separate addresses: the classic one
     * exposes Sony's private services and is the only way to reach Tandem, while the LE one
     * exposes nothing but the LE Audio service set and carries the LC3 audio. Both answer to
     * the same name, so name matching alone cannot tell them apart — the presence of a Sony
     * private service can.
     *
     * Uses [UnifiedDeviceIdentityService] as the primary source (from Remote Preferences),
     * falling back to [BluetoothDevice.type] and UUID analysis if not available.
     */
    fun isLeAudioIdentity(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        val address = runCatching { device.address }.getOrNull() ?: return false

        // Primary: Check UnifiedDeviceIdentityService (Remote Preferences + bt_config.conf)
        val unifiedType = UnifiedDeviceIdentityService.getIdentityType(address)
        if (unifiedType != IdentityType.UNKNOWN) {
            return unifiedType == IdentityType.LE
        }

        // Fallback: Transport type is carried by the bond record itself and needs no discovery,
        // so a freshly started process can classify before any GATT cache exists.
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
     * The address to talk to for control, given either of the headset's two identities.
     *
     * Returns [address] unchanged when no LE Audio counterpart is known, so callers can use
     * this unconditionally.
     *
     * Answered by [UnifiedDeviceIdentityService] — the single identity service.
     */
    fun resolveControlAddress(address: String?): String? {
        val normalized = normalizeAddress(address) ?: return address
        return UnifiedDeviceIdentityService.resolveControlAddress(normalized)
    }

    /**
     * Records that [leAddress] is the LE Audio identity of the headset at [controlAddress].
     *
     * The direction is stated by the caller — the pairing flow, which is the one moment both
     * addresses are in hand. Nothing here re-derives it, and there is no second alias map to
     * disagree with [UnifiedDeviceIdentityService].
     */
    fun linkLeAudioIdentity(leAddress: String?, controlAddress: String?) {
        val le = normalizeAddress(leAddress) ?: return
        val control = normalizeAddress(controlAddress) ?: return
        if (le == control) return
        rememberAddress(control)
        UnifiedDeviceIdentityService.recordIdentityPair(le, control)
    }


    /**
     * Snapshot of all known LE Audio identity aliases.
     *
     * Answered by [UnifiedDeviceIdentityService] — the single identity service.
     */
    fun leAudioAliasSnapshot(): Map<String, String> =
        UnifiedDeviceIdentityService.leToControlPairs()
            .mapNotNull { entry ->
                val le = entry.substringBefore('>')
                val control = entry.substringAfter('>', "")
                if (le.isBlank() || control.isBlank() || le == control) null else le to control
            }
            .toMap()

    /**
     * The LE Audio identity of the headset reachable at [controlAddress], if one is known.
     *
     * The inverse of [resolveControlAddress]. MiLink circulate hands a peer a single address
     * and the peer can only act on the identity it is itself bonded to, so a transfer that
     * was refused for one identity has to be retried against the other.
     *
     * Answered by [UnifiedDeviceIdentityService] — the single identity service.
     */
    fun leAudioIdentityFor(controlAddress: String?): String? {
        val control = normalizeAddress(controlAddress) ?: return null
        return UnifiedDeviceIdentityService.leAudioAddressFor(control)
    }

    /**
     * Both bonded identities of one headset, given either of them.
     *
     * Answered by [UnifiedDeviceIdentityService] — the single identity service.
     */
    fun identityAliasesOf(address: String?): List<String> {
        val normalized = normalizeAddress(address) ?: return emptyList()
        val pair = UnifiedDeviceIdentityService.identityPairFor(normalized) ?: return emptyList()
        return if (normalized == pair.first) listOf(pair.second) else listOf(pair.first)
    }

    fun rememberAddress(address: String?) {
        normalizeAddress(address)?.let(knownAddresses::add)
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
