package dev.sonypods.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mercury.sonypods.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Sony's connection-method confirmation using device-supplied limitation ids. */
@Composable
fun LeAudioAlertDialog(
    show: Boolean,
    targetEnabled: Boolean,
    inquiredType: Int?,
    messageType: Int?,
    itemCodes: List<Int>,
    deviceAlert: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val limitations = if (itemCodes.isNotEmpty()) {
        itemCodes.distinct().map { leAudioLimitationLabel(it) }
    } else if (!deviceAlert && targetEnabled) {
        defaultLeAudioLimitations()
    } else {
        fixedLeAudioLimitations(messageType)
    }
    val summary = leAudioAlertSummary(targetEnabled, inquiredType, messageType, deviceAlert)

    OverlayDialog(
        title = when {
            !deviceAlert -> stringResource(R.string.lea_repair_needed)
            targetEnabled -> stringResource(R.string.lea_mode_le)
            else -> stringResource(R.string.lea_mode_classic)
        },
        summary = summary,
        show = show,
        onDismissRequest = onCancel,
    ) {
        if (targetEnabled && limitations.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.lea_unavailable_note))
                limitations.forEach { Text("• $it") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(text = stringResource(R.string.cancel), onClick = onCancel, modifier = Modifier.weight(1f))
            TextButton(
                text = stringResource(R.string.confirm),
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun leAudioAlertSummary(
    targetEnabled: Boolean,
    inquiredType: Int?,
    messageType: Int?,
    deviceAlert: Boolean,
): String {
    if (!deviceAlert) {
        return stringResource(R.string.lea_confirm_repair_le)
    }
    if (targetEnabled) {
        return when (messageType) {
            45, 15 -> stringResource(R.string.lea_confirm_standby_le)
            47, 52, 54, 56, 64, 12, 16 ->
                stringResource(R.string.lea_confirm_pairing_le)
            17 -> stringResource(R.string.lea_confirm_le)
            else -> stringResource(R.string.lea_confirm_repair_le)
        }
    }
    return when (messageType) {
        44, 14 -> stringResource(R.string.lea_confirm_standby_classic)
        46 -> stringResource(R.string.lea_confirm_pairing_classic)
        116 -> stringResource(R.string.lea_confirm_switch_sound)
        117 -> stringResource(R.string.lea_confirm_switch_connection)
        118 -> stringResource(R.string.lea_confirm_classic_sound)
        119 -> stringResource(R.string.lea_confirm_classic_connection)
        else -> stringResource(R.string.lea_confirm_classic_reconnect)
    }
}

/**
 * Fixed-message alerts already encode the limitation set in their message
 * type, and Sound Connect uses a localized resource for each type. The raw
 * item list is authoritative only for FLEXIBLE_MESSAGE alerts; guessing a
 * fixed list here produces incorrect warnings on models with different
 * capability combinations.
 */
@Composable
private fun fixedLeAudioLimitations(messageType: Int?): List<String> = when (messageType) {
    // The fixed message type is the device's capability combination. These
    // labels mirror the combinations selected by Sound Connect's fixed UI;
    // flexible alerts use the device-supplied item list above instead.
    47, 49 -> listOf(stringResource(R.string.lea_feat_voice_assistant), stringResource(R.string.lea_feat_connection_mode))
    52, 53 -> listOf(stringResource(R.string.lea_feat_assignable), stringResource(R.string.lea_feat_voice_assistant), stringResource(R.string.lea_feat_quick_access), stringResource(R.string.lea_feat_connection_mode))
    54, 55 -> listOf(stringResource(R.string.lea_feat_voice_assistant), stringResource(R.string.lea_feat_connection_mode), stringResource(R.string.lea_feat_pairing_mgmt))
    56, 57 -> listOf(stringResource(R.string.lea_feat_voice_assistant), stringResource(R.string.lea_feat_connection_mode), stringResource(R.string.lea_feat_quick_access))
    64, 65 -> listOf(stringResource(R.string.lea_feat_voice_assistant), stringResource(R.string.lea_feat_connection_mode), stringResource(R.string.lea_feat_quick_access), stringResource(R.string.lea_feat_pairing_mgmt))
    else -> emptyList()
}

@Composable
private fun defaultLeAudioLimitations(): List<String> = listOf(
    stringResource(R.string.lea_feat_ldac),
    stringResource(R.string.lea_feat_spatial_head_tracking),
    stringResource(R.string.lea_feat_multipoint),
    stringResource(R.string.lea_feat_voice_assistant),
    stringResource(R.string.lea_feat_quick_access),
    stringResource(R.string.lea_feat_sound_ar),
    stringResource(R.string.lea_feat_scene_listening),
    stringResource(R.string.lea_feat_auto_switch),
)

@Composable
private fun leAudioLimitationLabel(code: Int): String = when (code) {
    0 -> stringResource(R.string.lea_feat_equalizer)
    1 -> stringResource(R.string.lea_feat_dsee)
    2 -> stringResource(R.string.lea_feat_speak_to_chat)
    3 -> stringResource(R.string.lea_feat_auto_volume)
    4 -> stringResource(R.string.lea_feat_voice_activation)
    5 -> stringResource(R.string.lea_feat_gatt)
    6 -> stringResource(R.string.lea_feat_ldac)
    7 -> stringResource(R.string.lea_feat_sound_prior)
    8 -> stringResource(R.string.lea_feat_google_assistant)
    9 -> stringResource(R.string.lea_feat_voice_assistant)
    10 -> stringResource(R.string.lea_feat_software_update)
    11 -> stringResource(R.string.lea_feat_multipoint)
    12 -> stringResource(R.string.lea_feat_wake_word)
    13 -> stringResource(R.string.lea_feat_bgm_mode)
    14 -> stringResource(R.string.lea_feat_battery_safe)
    15 -> stringResource(R.string.lea_feat_head_tracking)
    16 -> stringResource(R.string.card_le_audio_title)
    17 -> stringResource(R.string.lea_feat_immersive_audio)
    18 -> stringResource(R.string.lea_feat_link_auto_switch)
    19 -> stringResource(R.string.lea_feat_auto_play)
    20 -> stringResource(R.string.lea_feat_noise_cancelling)
    21 -> stringResource(R.string.lea_feat_sound_ar)
    22 -> stringResource(R.string.lea_feat_voice_ui)
    23 -> stringResource(R.string.lea_feat_quick_access)
    24 -> stringResource(R.string.lea_feat_connection_mode)
    25 -> stringResource(R.string.lea_auto_play)
    else -> stringResource(R.string.lea_restricted_other_fmt, code and 0xFF)
}
