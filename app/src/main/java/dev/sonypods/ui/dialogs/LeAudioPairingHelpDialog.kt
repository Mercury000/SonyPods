package dev.sonypods.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        title = if (targetEnabled) "LE Audio 配对帮助" else "经典音频配对帮助",
        summary = if (targetEnabled) {
            "耳机已切换为 LE Audio 优先。需要在手机蓝牙设置中重新配对耳机，完成后手机才会重新建立对应连接。"
        } else {
            "耳机已切换为仅经典音频。需要在手机蓝牙设置中重新配对耳机，完成后手机才会重新建立经典音频连接。"
        },
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("1. 打开手机蓝牙设置。")
            Text("2. 移除或忽略当前已配对的耳机。")
            Text("3. 让耳机进入配对模式并重新配对。")
            Text(
                if (targetEnabled) {
                    "4. 重新连接后，系统是否使用 LC3 由手机蓝牙系统决定。"
                } else {
                    "4. 重新连接后，将使用经典蓝牙音频。"
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
