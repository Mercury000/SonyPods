package dev.sonypods.device

/**
 * Unified device identity representation for LE/Classic judgment.
 *
 * A Sony headset with LE Audio enabled bonds as two separate addresses:
 * - **LE identity**: exposes LE Audio services (ASCS), carries LC3 audio
 * - **Control identity**: exposes Sony private services (Tandem/IAP), carries classic audio
 *
 * This data class captures the identity type and the mapping between the two addresses.
 */
data class DeviceIdentity(
    /** Normalized Bluetooth address (uppercase XX:XX:XX:XX:XX:XX). */
    val address: String,
    /** Identity type: LE, CLASSIC, or DUAL (both identities present). */
    val type: IdentityType,
    /** The paired LE/Control address if this is a dual-mode device. */
    val pairedAddress: String? = null,
    /** Device name for display/debugging. */
    val name: String? = null,
    /** Source of this identity information. */
    val source: IdentitySource,
) {
    companion object {
        /**
         * Parse a serialized identity string back to [DeviceIdentity].
         * Format: "TYPE|ADDRESS|PAIRED_ADDRESS|SOURCE|NAME"
         */
        fun deserialize(serialized: String): DeviceIdentity? {
            val parts = serialized.split("|", limit = 5)
            if (parts.size < 4) return null
            return runCatching {
                DeviceIdentity(
                    address = parts[1],
                    type = IdentityType.valueOf(parts[0]),
                    pairedAddress = parts[2].takeIf { it.isNotEmpty() },
                    source = IdentitySource.valueOf(parts[3]),
                    name = parts.getOrNull(4)?.takeIf { it.isNotEmpty() },
                )
            }.getOrNull()
        }
    }

    /** Serialize to a string for SharedPreferences storage. */
    fun serialize(): String = buildString {
        append(type.name)
        append("|")
        append(address)
        append("|")
        append(pairedAddress.orEmpty())
        append("|")
        append(source.name)
        append("|")
        append(name.orEmpty())
    }

    /** Whether this identity is the LE Audio identity (not the control/Tandem identity). */
    val isLeAudioIdentity: Boolean get() = type == IdentityType.LE

    /** Whether this identity is the Classic/Tandem identity. */
    val isClassicIdentity: Boolean get() = type == IdentityType.CLASSIC

    /** Whether this device has both LE and Classic identities. */
    val isDualMode: Boolean get() = type == IdentityType.DUAL

    /** The control address (for Tandem), given either identity. */
    val controlAddress: String? get() = if (type == IdentityType.LE) pairedAddress else address

    /**
     * The LE address (for LE Audio), given either identity.
     *
     * [IdentityType.DUAL] with no partner is a single-address CTKD device — one bond serving both
     * transports — so it is its own LE address. Answering null there is what left the LE Audio
     * policy with no device to write on for an over-ear model.
     */
    val leAddress: String?
        get() = when (type) {
            IdentityType.LE -> address
            IdentityType.CLASSIC -> pairedAddress
            IdentityType.DUAL -> pairedAddress ?: address
            IdentityType.UNKNOWN -> null
        }
}

/**
 * The type of Bluetooth identity this address represents.
 */
enum class IdentityType {
    /** LE Audio identity only (exposes ASCS, no Sony services). */
    LE,
    /** Classic identity only (exposes Sony services, no ASCS). */
    CLASSIC,
    /** Dual-mode device (both LE and Classic identities present). */
    DUAL,
    /** Identity type not yet determined. */
    UNKNOWN,
}

/**
 * Where this identity information came from.
 */
enum class IdentitySource {
    /** Related by CSIS group id — the stack's own answer, and the only authoritative one. */
    CSIS_GROUP,
    /** Recorded during module-managed pairing, which states the direction outright. */
    PAIRING,
    /** Derived from bt_config.conf key material. Classifies one address; never pairs two. */
    BT_CONFIG,
    /** Derived from BluetoothDevice.type and UUID analysis. */
    BLUETOOTH_DEVICE,
    /** Derived from BLE advertisement analysis. */
    ADVERTISEMENT,
    /** Cached in engine-side local storage. */
    ENGINE_CACHE,
}
