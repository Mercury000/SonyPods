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
import dev.sonypods.config.VisibilityConfig
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * Second-level page behind the settings entry of the same name: three grouped
 * switch cards — Bluetooth-page badge, module detail-page cards, and how the
 * LE-Audio-restricted cards behave. Every switch is shown unconditionally and
 * defaults to on; a false value can only hide a card whose own support
 * condition already passed.
 */
@Composable
fun VisibilitySettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    visibility: VisibilityConfig = VisibilityConfig(),
    onVisibilityChange: (VisibilityConfig) -> Unit = {},
) {
    val sectionTitleInsideMargin = PaddingValues(start = 14.dp, top = 14.dp, end = 28.dp, bottom = 6.dp)

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
                SwitchPreference(
                    title = stringResource(R.string.sony_eq_title),
                    checked = visibility.eq,
                    onCheckedChange = { onVisibilityChange(visibility.copy(eq = it)) },
                )
                SwitchPreference(
                    title = stringResource(R.string.playback_title),
                    checked = visibility.playback,
                    onCheckedChange = { onVisibilityChange(visibility.copy(playback = it)) },
                )
                SwitchPreference(
                    title = stringResource(R.string.connection_quality_title),
                    checked = visibility.connectionQuality,
                    onCheckedChange = { onVisibilityChange(visibility.copy(connectionQuality = it)) },
                )
                SwitchPreference(
                    title = stringResource(R.string.dsee),
                    checked = visibility.dsee,
                    onCheckedChange = { onVisibilityChange(visibility.copy(dsee = it)) },
                )
                SwitchPreference(
                    title = stringResource(R.string.card_ldac_title),
                    checked = visibility.ldac,
                    onCheckedChange = { onVisibilityChange(visibility.copy(ldac = it)) },
                )
                SwitchPreference(
                    title = stringResource(R.string.card_le_audio_title),
                    checked = visibility.leAudioCard,
                    onCheckedChange = { onVisibilityChange(visibility.copy(leAudioCard = it)) },
                )
                SwitchPreference(
                    title = stringResource(R.string.card_le_audio_toggle_title),
                    checked = visibility.leAudioToggle,
                    onCheckedChange = { onVisibilityChange(visibility.copy(leAudioToggle = it)) },
                )
                SwitchPreference(
                    title = stringResource(R.string.card_gesture_title),
                    checked = visibility.gestures,
                    onCheckedChange = { onVisibilityChange(visibility.copy(gestures = it)) },
                )
                SwitchPreference(
                    title = stringResource(R.string.mp_connect_two_title),
                    checked = visibility.multipoint,
                    onCheckedChange = { onVisibilityChange(visibility.copy(multipoint = it)) },
                )
                SwitchPreference(
                    title = stringResource(R.string.firmware_version),
                    checked = visibility.firmware,
                    onCheckedChange = { onVisibilityChange(visibility.copy(firmware = it)) },
                )
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
            }
        }
    }
}
