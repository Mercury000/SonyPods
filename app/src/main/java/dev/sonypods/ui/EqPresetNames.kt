package dev.sonypods.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mercury.sonypods.R
import dev.sonypods.protocol.EqPresetId

/** Localized preset name for UI display; the enum's [EqPresetId.displayName] stays English for logs. */
@Composable
fun EqPresetId.localizedName(): String = when (this) {
    EqPresetId.OFF -> stringResource(R.string.eq_preset_off)
    EqPresetId.ROCK -> stringResource(R.string.eq_preset_rock)
    EqPresetId.POP -> stringResource(R.string.eq_preset_pop)
    EqPresetId.JAZZ -> stringResource(R.string.eq_preset_jazz)
    EqPresetId.DANCE -> stringResource(R.string.eq_preset_dance)
    EqPresetId.EDM -> stringResource(R.string.eq_preset_edm)
    EqPresetId.R_AND_B_HIP_HOP -> stringResource(R.string.eq_preset_r_and_b_hip_hop)
    EqPresetId.ACOUSTIC -> stringResource(R.string.eq_preset_acoustic)
    EqPresetId.BRIGHT -> stringResource(R.string.eq_preset_bright)
    EqPresetId.EXCITED -> stringResource(R.string.eq_preset_excited)
    EqPresetId.MELLOW -> stringResource(R.string.eq_preset_mellow)
    EqPresetId.RELAXED -> stringResource(R.string.eq_preset_relaxed)
    EqPresetId.VOCAL -> stringResource(R.string.eq_preset_vocal)
    EqPresetId.TREBLE -> stringResource(R.string.eq_preset_treble)
    EqPresetId.BASS -> stringResource(R.string.eq_preset_bass)
    EqPresetId.SPEECH -> stringResource(R.string.eq_preset_speech)
    EqPresetId.GAMING_EQ -> stringResource(R.string.eq_preset_gaming)
    EqPresetId.FPS_1 -> stringResource(R.string.eq_preset_fps1)
    EqPresetId.FPS_2 -> stringResource(R.string.eq_preset_fps2)
    EqPresetId.FPS_3 -> stringResource(R.string.eq_preset_fps3)
    EqPresetId.HEAVY -> stringResource(R.string.eq_preset_heavy)
    EqPresetId.CLEAR -> stringResource(R.string.eq_preset_clear)
    EqPresetId.HARD -> stringResource(R.string.eq_preset_hard)
    EqPresetId.SOFT -> stringResource(R.string.eq_preset_soft)
    EqPresetId.FLAT -> stringResource(R.string.eq_preset_flat)
    EqPresetId.LIVE_SOUND -> stringResource(R.string.eq_preset_live_sound)
    EqPresetId.ULT -> stringResource(R.string.eq_preset_ult)
    EqPresetId.ULT_1 -> stringResource(R.string.eq_preset_ult1)
    EqPresetId.ULT_2 -> stringResource(R.string.eq_preset_ult2)
    EqPresetId.CUSTOM -> stringResource(R.string.eq_preset_custom)
    EqPresetId.USER_SETTING1 -> stringResource(R.string.eq_preset_user_setting1)
    EqPresetId.USER_SETTING2 -> stringResource(R.string.eq_preset_user_setting2)
    EqPresetId.USER_SETTING3 -> stringResource(R.string.eq_preset_user_setting3)
    EqPresetId.USER_SETTING4 -> stringResource(R.string.eq_preset_user_setting4)
    EqPresetId.USER_SETTING5 -> stringResource(R.string.eq_preset_user_setting5)
    EqPresetId.UNSPECIFIED -> stringResource(R.string.eq_preset_unspecified)
}
