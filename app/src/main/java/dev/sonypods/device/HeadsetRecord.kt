package dev.sonypods.device

import java.util.Locale

/**
 * One headset we have had a Tandem session with.
 *
 * The model keeps three things deliberately separate, because the headset's own replies only settle
 * two of them:
 *
 * - [key] — the Tandem identifier from `CONNECT_RET_CAPABILITY_INFO`, which is what the capability
 *   store is keyed by and what makes two sessions "the same headset". It is an arbitrary UTF-8
 *   string; on the measured LinkBuds S it happens to be a BD address, but nothing guarantees that,
 *   so it is never dialed.
 * - [addresses] — every address known to serve this headset, in no particular order and with **no
 *   direction**. This is SC's `group_device_address`, and SC's same-headset predicate
 *   (`SettingsTopPresenter.m59962m`) is exactly set membership over it.
 * - [controlAddress] — the classic identity, and only when something has actually proved it. Null is
 *   the normal state.
 *
 * The reason direction is its own field rather than a position in [addresses]: `LEA_RET_CAPABILITY`
 * labels its first field *Device Unique Id*, which invites reading it as the classic address, and
 * that reading is wrong. Measured 2026-09-05 the headset answers
 * `unique=80:99:E7:D8:60:09 le=[C5:93:15:6B:E6:34, 80:99:E7:D8:60:09]` while `80:99:…` is the address
 * the LE Audio profile is connected on and `C5:93:…` is the classic one. A model that forces every
 * writer to name a control address makes writers guess, and three of the four have nothing to guess
 * from.
 *
 * @property service Which transport was carrying audio at the last sync. SC's
 *   `model_pairing_service`, from the headset's own `StreamingStatus`, not from any stack-side signal.
 * @property supportsLeClassic Whether the headset declares an LE/Classic capability type at all.
 *   SC's `support_le_classic`.
 * @property bothPairedHistory Whether the headset reports `BOTH_CLASSIC_BT_BLE` paired history — it
 *   holds both a classic and an LE bond with this phone. SC's `is_both_classic_le_history_exist`.
 */
data class HeadsetRecord(
    val key: String,
    val addresses: List<String> = emptyList(),
    val controlAddress: String? = null,
    val name: String? = null,
    val service: PairingService = PairingService.CLASSIC,
    val supportsLeClassic: Boolean = false,
    val bothPairedHistory: Boolean = false,
) {
    /**
     * Whether [address] is one of this headset's identities.
     *
     * Set membership is the whole predicate — no direction, no LE/Classic test, matching SC.
     */
    fun owns(address: String?): Boolean =
        normalizeAddress(address)?.let { it in addresses } == true

    /**
     * Whether [address] is an identity of this headset that is *not* its classic one.
     *
     * False whenever the direction has never been proved, which is the safe answer: the caller that
     * asks this is deciding whether to skip work that only the control identity may do, and skipping
     * it on a guess costs the session its capability table.
     */
    fun isLeIdentity(address: String?): Boolean {
        val control = controlAddress ?: return false
        val normalized = normalizeAddress(address) ?: return false
        return normalized in addresses && normalized != control
    }

    /**
     * Serialize to one line of the engine's store.
     *
     * Every field is escaped: [key] and [name] both come from the headset's own reply, and the store
     * is newline-delimited with `|`-separated fields, so an unescaped separator in either would shift
     * or split a record. [name] can also be a system-settings alias, i.e. user input.
     */
    fun serialize(): String = listOf(
        escape(key),
        addresses.joinToString(","),
        controlAddress.orEmpty(),
        service.name,
        if (supportsLeClassic) "1" else "0",
        if (bothPairedHistory) "1" else "0",
        escape(name.orEmpty()),
    ).joinToString("|")

    companion object {
        private const val FIELD_COUNT = 7

        fun deserialize(line: String): HeadsetRecord? {
            val parts = line.split("|")
            // A shorter line is a record written by an older build, whose first field meant
            // "the control address". It is a cache, so dropping it costs one relearn.
            if (parts.size != FIELD_COUNT) return null
            val key = unescape(parts[0]).takeIf { it.isNotBlank() } ?: return null
            return HeadsetRecord(
                key = key,
                addresses = parts[1].split(",").mapNotNull(::normalizeAddress).distinct(),
                controlAddress = normalizeAddress(parts[2]),
                service = runCatching { PairingService.valueOf(parts[3]) }
                    .getOrDefault(PairingService.CLASSIC),
                supportsLeClassic = parts[4] == "1",
                bothPairedHistory = parts[5] == "1",
                name = unescape(parts[6]).takeIf { it.isNotEmpty() },
            )
        }

        private fun escape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("|", "\\p")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        private fun unescape(value: String): String = buildString {
            var index = 0
            while (index < value.length) {
                val c = value[index]
                if (c != '\\' || index == value.lastIndex) {
                    append(c)
                    index++
                    continue
                }
                when (value[index + 1]) {
                    'p' -> append('|')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    '\\' -> append('\\')
                    else -> append(value[index + 1])
                }
                index += 2
            }
        }

        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")

        /**
         * Uppercase and validate.
         *
         * SC does neither — this exists because the headset's own validator
         * (`com.sony.songpal.util.C15210a`) accepts `[0-9A-Fa-f]` while every comparison SC then
         * makes is a raw `String.equals`, so a lowercase reply would silently fail to match the
         * bonded set.
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
