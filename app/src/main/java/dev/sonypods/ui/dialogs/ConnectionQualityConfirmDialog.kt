package dev.sonypods.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mercury.sonypods.R
import dev.sonypods.protocol.ConnectionQualityMode
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 切换 Bluetooth 连接质量前的确认弹窗，文案复刻官方
 * `ConnectionModeAlertDialogFragment`（CONFIRM 型）：
 * 确认后才真正发送 AUDIO_SET_PARAM，取消则停留在当前值。
 */
@Composable
fun ConnectionQualityConfirmDialog(
    target: ConnectionQualityMode,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    OverlayDialog(
        title = stringResource(R.string.connection_quality_change_title),
        summary = if (target == ConnectionQualityMode.SOUND_QUALITY_PRIOR) {
            stringResource(R.string.connection_quality_confirm_sound_body)
        } else {
            stringResource(R.string.connection_quality_confirm_connection_body)
        },
        show = true,
        onDismissRequest = onCancel,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.confirm),
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
