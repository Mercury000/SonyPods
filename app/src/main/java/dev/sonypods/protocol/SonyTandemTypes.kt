package dev.sonypods.protocol

data class TandemMessage(
    val dataType: Byte,
    val command: Byte,
    val payload: ByteArray = byteArrayOf(),
) {
    fun toByteArray(): ByteArray = byteArrayOf(dataType, command) + payload

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TandemMessage) return false
        return dataType == other.dataType &&
            command == other.command &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = dataType.toInt()
        result = 31 * result + command
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object SonyTandemFrame {
    fun message(command: Byte, payload: ByteArray = byteArrayOf()): ByteArray =
        TandemMessage(SonyTandemConstants.DATA_MDR, command, payload).toByteArray()
}

sealed interface ParsedTandemResponse {
    val raw: ByteArray

    data class DeviceInfo(
        val type: DeviceInfoType?,
        val text: String?,
        override val raw: ByteArray,
        /** Raw numeric colour code from a SERIES_AND_COLOR_INFO payload (`payload[2]`), if any.
         * Used to match the catalog image by code so the fragile per-protocol colour-label
         * tables (which disagree between V1 and V2 for codes 0x06–0x0B) are bypassed. */
        val colorCode: Int? = null,
    ) : ParsedTandemResponse

    data class CommonStatus(
        val type: CommonInquiredType?,
        val text: String?,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class Battery(
        val kind: PowerInquiredType?,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class EqEbb(
        val type: EqEbbInquiredType?,
        val enabled: Boolean? = null,
        val preset: EqPresetId? = null,
        val clearBass: Int? = null,
        val bandSteps: List<Int> = emptyList(),
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class EqBandInfo(
        val type: EqBandInformationType?,
        val value: Int,
    )

    data class EqEbbExtendedInfo(
        val type: EqEbbInquiredType?,
        val bands: List<EqBandInfo>,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class NoiseControl(
        val type: NcAsmInquiredType?,
        val values: List<Int>,
        val enabled: Boolean? = null,
        val ambientSoundEnabled: Boolean? = null,
        val ambientLevel: Int? = null,
        val ambientMode: AmbientSoundMode? = null,
        val controlMode: NoiseControlMode? = null,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class PlaybackAck(
        val values: List<Int>,
        val status: PlaybackStatus = PlaybackStatus.UNKNOWN,
        val isUnsolicited: Boolean = false,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class LeaStatus(
        val type: LeaInquiredType?,
        val values: List<Int>,
        val enabled: LeaEnableDisable? = null,
        val streamingStatusL: LeaStreamingStatus? = null,
        val streamingStatusR: LeaStreamingStatus? = null,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class LeaPairedHistoryStatus(
        val type: LeaInquiredType?,
        val values: List<Int>,
        val pairedHistory: LeaPairedHistory? = null,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class QuickAccess(
        val key: QuickAccessKey? = null,
        val function: QuickAccessFunction? = null,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class WearingStatus(
        val status: WearingDetectionStatus? = null,
        val result: WearingDetectionResult? = null,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class Unknown(
        val dataType: Int?,
        val command: Int?,
        val payload: ByteArray,
        override val raw: ByteArray,
    ) : ParsedTandemResponse

    data class Table2Common(
        val family: String,
        val command: Int,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Table2Common) return false
            return family == other.family &&
                command == other.command &&
                values == other.values &&
                raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int {
            var result = family.hashCode()
            result = 31 * result + command
            result = 31 * result + values.hashCode()
            result = 31 * result + raw.contentHashCode()
            return result
        }
    }

    data class Table2Generic(
        val family: String,
        val inquiredType: Int?,
        val values: List<Int>,
        override val raw: ByteArray,
    ) : ParsedTandemResponse {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Table2Generic) return false
            return family == other.family &&
                inquiredType == other.inquiredType &&
                values == other.values &&
                raw.contentEquals(other.raw)
        }

        override fun hashCode(): Int {
            var result = family.hashCode()
            result = 31 * result + (inquiredType ?: 0)
            result = 31 * result + values.hashCode()
            result = 31 * result + raw.contentHashCode()
            return result
        }
    }
}

val Byte.unsigned: Int
    get() = toInt() and 0xFF

fun ByteArray.hexString(): String = joinToString(" ") { "%02X".format(it.unsigned) }

fun ByteArray.unsignedList(): List<Int> = map { it.unsigned }

fun Byte.percentageOrNull(): Int? = unsigned.takeIf { it in 0..100 }
