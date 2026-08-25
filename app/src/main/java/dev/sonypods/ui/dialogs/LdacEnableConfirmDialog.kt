package dev.sonypods.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mercury.sonypods.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * 开启 LDAC 前的确认弹窗，对应系统设置里同一开关的确认框
 * （`bt_ldac_open_dialog_*`）：确认后才写入，取消则维持当前编解码器。
 * 关闭方向不确认，与系统一致。
 */
@Composable
fun LdacEnableConfirmDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    OverlayDialog(
        title = stringResource(R.string.ldac_enable_confirm_title),
        summary = stringResource(R.string.ldac_enable_confirm_body),
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
