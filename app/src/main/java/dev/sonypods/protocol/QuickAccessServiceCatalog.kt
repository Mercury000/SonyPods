package dev.sonypods.protocol

/**
 * Sound Connect's Quick Access service directory.
 *
 * The headphone capability response only describes the slots and may omit a
 * service after it is no longer selected.  Sound Connect keeps the SAR service
 * directory available independently, so this catalog must not be treated as a
 * device-capability-only list.
 */
data class QuickAccessService(
    val code: Int,
    val displayName: String,
)

object QuickAccessServiceCatalog {
    /** Known SAR service IDs used by Sound Connect 13.x. */
    val entries: List<QuickAccessService> = listOf(
        QuickAccessService(0x00, "无操作"),
        QuickAccessService(0x01, "Spotify"),
        QuickAccessService(0x02, "Endel"),
        QuickAccessService(0x03, "Amazon Music"),
        QuickAccessService(0x04, "腾讯小微"),
        QuickAccessService(0x05, "喜马拉雅"),
        QuickAccessService(0x06, "酷狗音乐"),
        QuickAccessService(0x07, "QQ音乐"),
        QuickAccessService(0x08, "Eye Navi"),
        QuickAccessService(0x09, "网易云音乐"),
        QuickAccessService(0x0A, "Apple Music"),
        QuickAccessService(0x0C, "YouTube Music"),
    )

    private val knownCodes: Set<Int> = entries.mapTo(linkedSetOf()) { it.code }
    private val labels: Map<Int, String> = entries.associate { it.code to it.displayName }

    fun isKnown(code: Int): Boolean = code in knownCodes

    fun label(code: Int): String = labels[code] ?: "服务（0x%02X）".format(code and 0xFF)

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
