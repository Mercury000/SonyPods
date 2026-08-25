package dev.sonypods.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        title = "更改蓝牙连接质量",
        summary = if (target == ConnectionQualityMode.SOUND_QUALITY_PRIOR) {
            "重新连接到音频设备。此外，如果更改为声音质量优先，可能无法使用声音质量设置。是否更改？"
        } else {
            "重新连接到音频设备。是否更改？"
        },
        show = true,
        onDismissRequest = onCancel,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = "取消",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = "确认",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
