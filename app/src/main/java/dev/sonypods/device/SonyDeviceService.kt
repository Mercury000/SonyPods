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

    /** LE Audio identity address -> the address that carries Tandem. */
    private val leAudioAliases = ConcurrentHashMap<String, String>()

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

    /**
     * Whether this is the headset's LE Audio identity rather than the one carrying Tandem.
     *
     * A Sony headset with LE Audio enabled bonds as two separate addresses: the classic one
     * exposes Sony's private services and is the only way to reach Tandem, while the LE one
     * exposes nothing but the LE Audio service set and carries the LC3 audio. Both answer to
     * the same name, so name matching alone cannot tell them apart — the presence of a Sony
     * private service can.
     */
    fun isLeAudioIdentity(device: BluetoothDevice?): Boolean {
        if (device == null) return false
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
     */
    fun resolveControlAddress(address: String?): String? {
        val normalized = normalizeAddress(address) ?: return address
        return leAudioAliases[normalized] ?: address
    }

    /** Records that [leAddress] is the LE Audio identity of the headset at [controlAddress]. */
    fun linkLeAudioIdentity(leAddress: String?, controlAddress: String?) {
        val le = normalizeAddress(leAddress) ?: return
        val control = normalizeAddress(controlAddress) ?: return
        if (le == control) return
        leAudioAliases[le] = control
        rememberAddress(control)
    }

    /**
     * Pairs up the two identities of every bonded Sony headset.
     *
     * Matching is by name, because that is what the headset advertises on both — and when
     * exactly one control identity is bonded there is nothing to be ambiguous about, so an
     * LE identity whose name was never resolved is still linked.
     */
    fun linkLeAudioIdentities(bonded: Collection<BluetoothDevice>) {
        val sony = bonded.filter(::isSony)
        if (sony.isEmpty()) return
        val (leIdentities, controls) = sony.partition(::isLeAudioIdentity)
        if (leIdentities.isEmpty() || controls.isEmpty()) return
        leIdentities.forEach { le ->
            val leName = runCatching { le.name }.getOrNull()
            val match = controls.firstOrNull { control ->
                val controlName = runCatching { control.name }.getOrNull()
                leName != null && controlName != null &&
                    controlName.equals(leName.removeLePrefix(), ignoreCase = true)
            } ?: controls.singleOrNull()
            match?.let { linkLeAudioIdentity(le.address, it.address) }
        }
    }

    private fun String.removeLePrefix(): String =
        removePrefix("LE_").removePrefix("le_").removePrefix("LE-").removePrefix("le-")

    fun leAudioAliasSnapshot(): Map<String, String> = leAudioAliases.toMap()

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
