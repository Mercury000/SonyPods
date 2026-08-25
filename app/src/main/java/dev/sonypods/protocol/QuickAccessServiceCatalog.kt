package dev.sonypods.protocol

import androidx.annotation.StringRes
import com.mercury.sonypods.R

/**
 * Sound Connect's Quick Access service directory.
 *
 * The headphone capability response only describes the slots and may omit a
 * service after it is no longer selected.  Sound Connect keeps the SAR service
 * directory available independently, so this catalog must not be treated as a
 * device-capability-only list.
 *
 * Display names live in string resources ([QuickAccessService.nameRes]); this
 * engine-side catalog only carries codes and resource IDs.
 */
data class QuickAccessService(
    val code: Int,
    @StringRes val nameRes: Int,
)

object QuickAccessServiceCatalog {
    /** Known SAR service IDs used by Sound Connect 13.x. */
    val entries: List<QuickAccessService> = listOf(
        QuickAccessService(0x00, R.string.qa_service_none),
        QuickAccessService(0x01, R.string.qa_service_spotify),
        QuickAccessService(0x02, R.string.qa_service_endel),
        QuickAccessService(0x03, R.string.qa_service_amazon_music),
        QuickAccessService(0x04, R.string.qa_service_tencent_xiaowei),
        QuickAccessService(0x05, R.string.qa_service_ximalaya),
        QuickAccessService(0x06, R.string.qa_service_kugou_music),
        QuickAccessService(0x07, R.string.qa_service_qq_music),
        QuickAccessService(0x08, R.string.qa_service_eye_navi),
        QuickAccessService(0x09, R.string.qa_service_netease_music),
        QuickAccessService(0x0A, R.string.qa_service_apple_music),
        QuickAccessService(0x0C, R.string.qa_service_youtube_music),
    )

    private val knownCodes: Set<Int> = entries.mapTo(linkedSetOf()) { it.code }

    fun isKnown(code: Int): Boolean = code in knownCodes

    /** Resource ID for a known service code, null for raw/region-specific IDs. */
    fun nameRes(code: Int): Int? = entries.firstOrNull { it.code == code }?.nameRes

    /**
     * Build the selector list without losing services that are not currently
     * selected on the headphone.  Capability and current values are appended
     * so newer/region-specific raw IDs remain usable too.
     */
    fun candidates(
        capabilityCodes: List<Int>,
        currentCode: Int?,
        defaultCode: Int? = null,
    ): List<Int> = buildList {
        addAll(entries.map { it.code })
        addAll(capabilityCodes.filter { it in 0..0xFF })
        defaultCode?.takeIf { it in 0..0xFF }?.let(::add)
        currentCode?.takeIf { it in 0..0xFF }?.let(::add)
    }.distinct()
}
