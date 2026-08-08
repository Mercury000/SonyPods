package dev.sonypods.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
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

@Composable
fun PowerOffDialog(
    show: Boolean,
    deviceName: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    val effectiveName = if (deviceName.isBlank()) stringResource(R.string.pod_info) else deviceName
    OverlayDialog(
        title = stringResource(R.string.power_off_title),
        summary = stringResource(R.string.power_off_summary, effectiveName),
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Text(
            text = stringResource(R.string.power_off_warning),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.power_off_confirm),
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
