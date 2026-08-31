package dev.sonypods.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mercury.sonypods.R
import dev.sonypods.config.CardLocation
import dev.sonypods.config.VisibilityConfig
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * Second-level page behind the settings entry of the same name. Detail-page
 * cards pick a location — earphone page, the "更多设置" sub-page, or hidden —
 * while badges and the LE-Audio-restricted behaviour stay boolean switches.
 * Every control is shown unconditionally; a relocated or hidden card can only
 * ever remove a UI element whose own support condition already passed.
 */
@Composable
fun VisibilitySettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    visibility: VisibilityConfig = VisibilityConfig(),
    onVisibilityChange: (VisibilityConfig) -> Unit = {},
) {
    val sectionTitleInsideMargin = PaddingValues(start = 14.dp, top = 14.dp, end = 28.dp, bottom = 6.dp)
    val locations = CardLocation.entries
    val locationLabels = listOf(
        stringResource(R.string.location_detail_page),
        stringResource(R.string.location_more_page),
        stringResource(R.string.location_hidden),
    )

    fun locationSelector(
        title: Int,
        current: CardLocation,
        update: (CardLocation) -> VisibilityConfig,
    ): @Composable () -> Unit = {
        OverlayDropdownPreference(
            title = stringResource(title),
            items = locationLabels,
            selectedIndex = locations.indexOf(current),
            onSelectedIndexChange = { index ->
                locations.getOrNull(index)?.let { onVisibilityChange(update(it)) }
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp,
        ),
    ) {
        item {
            SmallTitle(
                text = stringResource(R.string.visibility_section_bluetooth_page),
                modifier = Modifier.fillMaxWidth(),
                insideMargin = sectionTitleInsideMargin,
            )
            Card {
                SwitchPreference(
                    title = stringResource(R.string.sound_quality_badges),
                    checked = visibility.bluetoothBadge,
                    onCheckedChange = { onVisibilityChange(visibility.copy(bluetoothBadge = it)) },
                )
            }
        }
        item {
            SmallTitle(
                text = stringResource(R.string.visibility_section_module_page),
                modifier = Modifier.fillMaxWidth(),
                insideMargin = sectionTitleInsideMargin,
            )
            Card {
                SwitchPreference(
                    title = stringResource(R.string.sound_quality_badges),
                    checked = visibility.detailBadge,
                    onCheckedChange = { onVisibilityChange(visibility.copy(detailBadge = it)) },
                )
                locationSelector(R.string.speak_to_chat_title, visibility.speakToChat) { visibility.copy(speakToChat = it) }()
                locationSelector(R.string.sony_eq_title, visibility.eq) { visibility.copy(eq = it) }()
                locationSelector(R.string.playback_title, visibility.playback) { visibility.copy(playback = it) }()
                locationSelector(
                    R.string.sl_current_pressure,
                    visibility.safeListening,
                ) { visibility.copy(safeListening = it) }()
                locationSelector(
                    R.string.connection_quality_title,
                    visibility.connectionQuality,
                ) { visibility.copy(connectionQuality = it) }()
                locationSelector(R.string.dsee, visibility.dsee) { visibility.copy(dsee = it) }()
                locationSelector(R.string.card_ldac_title, visibility.ldac) { visibility.copy(ldac = it) }()
                locationSelector(R.string.card_le_audio_title, visibility.leAudioCard) { visibility.copy(leAudioCard = it) }()
                locationSelector(
                    R.string.card_le_audio_toggle_title,
                    visibility.leAudioToggle,
                ) { visibility.copy(leAudioToggle = it) }()
                locationSelector(R.string.card_gesture_title, visibility.gestures) { visibility.copy(gestures = it) }()
                locationSelector(R.string.mp_connect_two_title, visibility.multipoint) { visibility.copy(multipoint = it) }()
                locationSelector(R.string.firmware_version, visibility.firmware) { visibility.copy(firmware = it) }()
            }
        }
        item {
            SmallTitle(
                text = stringResource(R.string.visibility_section_lea_restricted),
                modifier = Modifier.fillMaxWidth(),
                insideMargin = sectionTitleInsideMargin,
            )
            Card {
                SwitchPreference(
                    title = stringResource(R.string.connection_quality_title),
                    checked = visibility.leaRestrictedConnectionQuality,
                    onCheckedChange = { onVisibilityChange(visibility.copy(leaRestrictedConnectionQuality = it)) },
                )
                SwitchPreference(
                    title = stringResource(R.string.card_ldac_title),
                    checked = visibility.leaRestrictedLdac,
                    onCheckedChange = { onVisibilityChange(visibility.copy(leaRestrictedLdac = it)) },
                )
                SwitchPreference(
                    title = stringResource(R.string.mp_connect_two_title),
                    checked = visibility.leaRestrictedMultipoint,
                    onCheckedChange = { onVisibilityChange(visibility.copy(leaRestrictedMultipoint = it)) },
                )
                SwitchPreference(
                    title = stringResource(R.string.qa_card_title),
                    checked = visibility.leaRestrictedQuickAccess,
                    onCheckedChange = { onVisibilityChange(visibility.copy(leaRestrictedQuickAccess = it)) },
                )
            }
        }
    }
}
