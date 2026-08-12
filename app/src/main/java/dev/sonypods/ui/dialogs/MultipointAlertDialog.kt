package dev.sonypods.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sonypods.protocol.SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_MULTIPOINT_LDAC_DISABLE
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * Confirmation dialog for the device-driven multipoint reconnection alert
 * (official "需要重新连接" flow). Mirrors SC `MultipointSettingChangeCautionDialogFragment`:
 * title = Msg_MultiPoint_Confirm_Reconnection_Title, message differs by alert type
 * (7 = turning off, 6 = turning on / LDAC disable).
 */
@Composable
fun MultipointAlertDialog(
    show: Boolean,
    messageType: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    OverlayDialog(
        title = "需要重新连接",
        summary = if (messageType == ALERT_MESSAGE_TYPE_MULTIPOINT_LDAC_DISABLE) {
            "音频设备将重新连接。开启该设置后将无法使用 LDAC，即使选择音质优先也是如此。要重新连接吗？"
        } else {
            "音频设备暂时断开连接，将自动重新连接。"
        },
        show = show,
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
