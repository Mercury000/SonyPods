package dev.sonypods.ui.dialogs

import com.mercury.sonypods.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Sound Connect's LEA_PAIRING_GUIDE follow-up help content. */
@Composable
fun LeAudioPairingHelpDialog(
    show: Boolean,
    targetEnabled: Boolean,
    formFactor: String? = null,
    /** The bonded LE Audio identity the stack reports, or null when the phone holds none. */
    identityAddress: String? = null,
    pairStage: String = STAGE_IDLE,
    /** Whether the pairer is working, per the snapshot; it knows every busy stage. */
    pairing: Boolean = false,
    pairMessage: String = "",
    onPair: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    // Whether the phone is paired is the stack's answer, never the pairer's: a run that ended in
    // SUCCESS says nothing about the bond still being there, and a bond dropped in system settings
    // is invisible from inside a finished run. This is the same identity the switch row keys on.
    val paired = identityAddress != null
    val failed = pairStage == STAGE_FAILED

    // The gesture differs by form factor, and over-ear models have no charging case at all.
    // Neither variant names a specific model's button, so this stays correct for any Sony
    // headset the module connects to; the exact gesture lives in the headset's manual.
    val resetHint = when (formFactor) {
        FORM_TRUE_WIRELESS -> stringResource(R.string.lea_reset_hint_tws)
        FORM_HEADSET -> stringResource(R.string.lea_reset_hint_headset)
        else -> stringResource(R.string.lea_reset_hint_generic_1) +
            stringResource(R.string.lea_reset_hint_generic_2)
    }

    OverlayDialog(
        title = if (targetEnabled) stringResource(R.string.lea_help_title_le) else stringResource(R.string.lea_help_title_classic),
        summary = if (targetEnabled) {
            stringResource(R.string.lea_help_intro_le)
        } else {
            stringResource(R.string.lea_help_intro_classic)
        },
        show = show,
        onDismissRequest = onDismiss,
    ) {
        // One line of content next to the summary: the summary already states what the LE
        // identity is and that the module drives the rest, so the body only carries what it
        // cannot — the concrete reset gesture, live progress, or the failure reason.
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!targetEnabled) {
                Text(stringResource(R.string.lea_help_classic_steps))
            } else {
                when {
                    pairing -> Text(pairMessage.ifEmpty { stringResource(R.string.lea_searching) })
                    // Never the run's message here: a finished run reports nothing on success, so
                    // anything still in it is a failure reason from an earlier attempt — which this
                    // line would contradict.
                    identityAddress != null ->
                        Text(stringResource(R.string.lea_help_paired, "（$identityAddress）"))
                    else -> {
                        if (failed && pairMessage.isNotEmpty()) Text(pairMessage)
                        Text(resetHint)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (targetEnabled && !paired) {
                TextButton(
                    text = stringResource(R.string.close),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (failed) stringResource(R.string.lea_repair_again) else stringResource(R.string.lea_start_pairing),
                    onClick = onPair,
                    enabled = !pairing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            } else {
                TextButton(
                    text = stringResource(R.string.close),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

private const val STAGE_IDLE = "IDLE"
private const val STAGE_FAILED = "FAILED"

/** [dev.sonypods.headphones.HeadphoneFormFactor] names, as carried by the state snapshot. */
private const val FORM_HEADSET = "HEADSET"
private const val FORM_TRUE_WIRELESS = "TRUE_WIRELESS"
