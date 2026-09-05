package dev.sonypods.device

import java.util.Locale

/**
 * One headset we have had a Tandem session with, identified the way Sound Connect identifies one.
 *
 * SC does not classify a bond; it asks the headset. Two Tandem replies carry addresses:
 *
 * - `CONNECT_RET_CAPABILITY_INFO` names the identity the control link belongs to. It arrives on frame
 *   three of every session and is what the capability store is keyed by, so it is [uniqueId].
 * - `LEA_RET_CAPABILITY` (0x41, inquired type 0x00/0x01/0x02 — SC `kf0.C21806t` / `C21804r` /
 *   `C21807u`) lists every address serving the headset: a *Device Unique Id* plus one or two *LE*
 *   addresses, 17 ASCII bytes each.
 *
 * The second reply's field names invite a direction, and that reading is wrong. Measured on a
 * LinkBuds S (2026-09-05) it answers `unique=80:99:E7:D8:60:09 le=[C5:93:15:6B:E6:34,
 * 80:99:E7:D8:60:09]` while `80:99:E7:D8:60:09` is the address the LE Audio profile is connected on
 * and `C5:93:15:6B:E6:34` is the classic one. So its "Device Unique Id" is *not* the classic BD
 * address, and only the capability info settles direction. SC's own same-headset predicate agrees:
 * `SettingsTopPresenter.m59962m` is set membership over own-address plus the reported group, with no
 * direction and no LE/Classic test.
 *
 * @property uniqueId The control identity, from `CONNECT_RET_CAPABILITY_INFO`. Normalized, non-blank.
 * @property leAddresses The headset's other addresses, filtered to the ones the OS is bonded to.
 *   SC's `group_device_address`.
 * @property service Which transport was carrying audio at the last sync. SC's `model_pairing_service`,
 *   derived from the headset's own `StreamingStatus` rather than from any stack-side signal.
 * @property supportsLeClassic Whether the headset declares one of the LE/Classic capability types
 *   at all. SC's `support_le_classic`.
 * @property bothPairedHistory Whether the headset reports `BOTH_CLASSIC_BT_BLE` paired history —
 *   i.e. it holds both a classic and an LE bond with this phone. SC's
 *   `is_both_classic_le_history_exist`.
 */
data class HeadsetRecord(
    val uniqueId: String,
    val name: String? = null,
    val leAddresses: List<String> = emptyList(),
    val service: PairingService = PairingService.CLASSIC,
    val supportsLeClassic: Boolean = false,
    val bothPairedHistory: Boolean = false,
) {
    /** Every address known to serve this headset, control address first. */
    val addresses: List<String> get() = listOf(uniqueId) + leAddresses

    /**
     * Whether [address] is one of this headset's identities.
     *
     * SC's `SettingsTopPresenter.m59962m`: own address, or a member of the reported group. There is
     * no direction and no LE/Classic test here — set membership is the whole predicate.
     */
    fun owns(address: String?): Boolean {
        val normalized = normalizeAddress(address) ?: return false
        return normalized == uniqueId || normalized in leAddresses
    }

    /** Whether [address] is an LE identity of this headset rather than its control address. */
    fun isLeIdentity(address: String?): Boolean {
        val normalized = normalizeAddress(address) ?: return false
        return normalized != uniqueId && normalized in leAddresses
    }

    /** Serialize to one line. [name] stays last so a name containing `|` cannot shift a field. */
    fun serialize(): String = listOf(
        uniqueId,
        leAddresses.joinToString(","),
        service.name,
        if (supportsLeClassic) "1" else "0",
        if (bothPairedHistory) "1" else "0",
        name.orEmpty(),
    ).joinToString("|")

    companion object {
        fun deserialize(line: String): HeadsetRecord? {
            val parts = line.split("|", limit = 6)
            if (parts.size < 5) return null
            val uniqueId = normalizeAddress(parts[0]) ?: return null
            return HeadsetRecord(
                uniqueId = uniqueId,
                leAddresses = parts[1].split(",").mapNotNull(::normalizeAddress).distinct(),
                service = runCatching { PairingService.valueOf(parts[2]) }
                    .getOrDefault(PairingService.CLASSIC),
                supportsLeClassic = parts[3] == "1",
                bothPairedHistory = parts[4] == "1",
                name = parts.getOrNull(5)?.takeIf { it.isNotEmpty() },
            )
        }

        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

        /**
         * Uppercase and validate.
         *
         * SC does neither — [dev.sonypods.device.HeadsetRegistry] keeps this because the headset's
         * own validator (`com.sony.songpal.util.C15210a`) accepts `[0-9A-Fa-f]` while every
         * comparison SC then makes is a raw `String.equals`, so a lowercase reply would silently
         * fail to match the bonded set.
         */
        fun normalizeAddress(address: String?): String? {
            val normalized = address?.trim()?.uppercase(Locale.ROOT) ?: return null
            return normalized.takeIf { it.matches(BLUETOOTH_ADDRESS) }
        }
    }
}

/**
 * Which transport is carrying audio, as the headset reports it.
 *
 * SC's `ActiveDevice.PairingService`. Decided by `bt.C5960f.m26575c(DeviceState)`: the Tandem LEA
 * status' `StreamingStatus` is `VIA_LE_AUDIO_UNICAST` for [LEA], anything else (including
 * `VIA_A2DP`) for [CLASSIC]. Nothing in SC ever looks at `BluetoothDevice.getType()` — the constant
 * `DEVICE_TYPE_LE` does not appear anywhere in its APK.
 */
enum class PairingService { CLASSIC, LEA }
