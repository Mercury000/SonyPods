package dev.sonypods.ui.dialogs

import com.mercury.sonypods.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/** Explains why the sound-pressure readout needs root: the measurement switch
 * belongs to Sound Connect and the headphones cannot be asked for its state. */
@Composable
fun SafeListeningRootHelpDialog(show: Boolean, onDismiss: () -> Unit) {
    OverlayDialog(
        title = stringResource(R.string.sl_root_help_title),
        summary = stringResource(R.string.sl_root_help_summary),
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.sl_root_help_owner))
            Text(stringResource(R.string.sl_root_help_risk))
            Text(stringResource(R.string.sl_root_help_without))
        }
        TextButton(
            text = stringResource(R.string.close),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}
