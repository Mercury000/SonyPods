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
    formFactor: String? = null,
    pairStage: String = STAGE_IDLE,
    pairMessage: String = "",
    pairedAddress: String? = null,
    onPair: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val pairing = pairStage == STAGE_SCANNING || pairStage == STAGE_PAIRING
    val paired = pairStage == STAGE_SUCCESS
    val failed = pairStage == STAGE_FAILED

    // The gesture differs by form factor, and over-ear models have no charging case at all.
    // Neither variant names a specific model's button, so this stays correct for any Sony
    // headset the module connects to; the exact gesture lives in the headset's manual.
    val resetHint = when (formFactor) {
        FORM_TRUE_WIRELESS -> "入耳式：放回充电盒、保持盒盖打开，按住盒内按键约 7 秒。"
        FORM_HEADSET -> "头戴式：先关机，然后按住电源键约 7 秒。"
        else -> "具体手势见耳机说明书：多数机型为关机后长按电源键约 7 秒，" +
            "入耳式则是放回充电盒后按住盒内按键。"
    }

    OverlayDialog(
        title = if (targetEnabled) "LE Audio 配对帮助" else "经典音频配对帮助",
        summary = if (targetEnabled) {
            "耳机已切换为 LE Audio 优先。它的 LE Audio 是一个独立的低功耗身份，" +
                "只在配对模式下接受新设备绑定，且系统蓝牙的搜索列表不会显示它。" +
                "请重置耳机，其余步骤由本模块自动完成。"
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
            if (!targetEnabled) {
                Text("1. 打开手机蓝牙设置。")
                Text("2. 移除或忽略当前已配对的耳机。")
                Text("3. 让耳机进入配对模式并重新配对。")
                Text("4. 重新连接后，将使用经典蓝牙音频。")
            } else {
                when {
                    pairing -> {
                        Text("请现在重置耳机，使其进入配对模式。")
                        Text("放回充电盒并按住盒内按键，直到指示灯闪烁。")
                        Text(pairMessage.ifEmpty { "正在查找并配对…" })
                    }
                    paired -> {
                        Text("已配对 LE Audio 身份${pairedAddress?.let { "（$it）" }.orEmpty()}。")
                        Text("播放音频后系统即会使用 LC3。")
                    }
                    else -> {
                        if (failed && pairMessage.isNotEmpty()) Text(pairMessage)
                        Text("耳机的 LE Audio 身份只在配对模式下接受新设备绑定。")
                        Text("1. 让耳机进入配对模式，指示灯通常会蓝色快闪。")
                        Text("2. $resetHint")
                        Text("3. 完成后点击下方按钮，模块会自动查找并配对。")
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
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = if (failed) "重新配对" else "开始配对",
                    onClick = onPair,
                    enabled = !pairing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            } else {
                TextButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

private const val STAGE_IDLE = "IDLE"
private const val STAGE_SCANNING = "SCANNING"
private const val STAGE_PAIRING = "PAIRING"
private const val STAGE_SUCCESS = "SUCCESS"
private const val STAGE_FAILED = "FAILED"

/** [dev.sonypods.headphones.HeadphoneFormFactor] names, as carried by the state snapshot. */
private const val FORM_HEADSET = "HEADSET"
private const val FORM_TRUE_WIRELESS = "TRUE_WIRELESS"
