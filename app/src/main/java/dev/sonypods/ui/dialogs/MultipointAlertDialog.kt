package dev.sonypods.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mercury.sonypods.R
import dev.sonypods.protocol.SonyTandemV2Table1Protocol.ALERT_MESSAGE_TYPE_MULTIPOINT_LDAC_DISABLE
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import androidx.compose.ui.res.stringResource

/**
 * Confirmation dialog for the device-driven multipoint reconnection alert
 * (official stringResource(R.string.mp_reconnect_required_title) flow). Mirrors SC `MultipointSettingChangeCautionDialogFragment`:
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
        title = stringResource(R.string.mp_reconnect_required_title),
        summary = if (messageType == ALERT_MESSAGE_TYPE_MULTIPOINT_LDAC_DISABLE) {
            stringResource(R.string.mp_ldac_off_body)
        } else {
            stringResource(R.string.mp_auto_reconnect_body)
        },
        show = show,
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
