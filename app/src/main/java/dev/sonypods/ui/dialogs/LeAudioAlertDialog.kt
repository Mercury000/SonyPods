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

/** Sony's connection-method confirmation using device-supplied limitation ids. */
@Composable
fun LeAudioAlertDialog(
    show: Boolean,
    targetEnabled: Boolean,
    inquiredType: Int?,
    messageType: Int?,
    itemCodes: List<Int>,
    deviceAlert: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val limitations = if (itemCodes.isNotEmpty()) {
        itemCodes.distinct().map(::leAudioLimitationLabel)
    } else if (!deviceAlert && targetEnabled) {
        defaultLeAudioLimitations
    } else {
        fixedLeAudioLimitations(messageType)
    }
    val summary = leAudioAlertSummary(targetEnabled, inquiredType, messageType, deviceAlert)

    OverlayDialog(
        title = when {
            !deviceAlert -> "需要再次配对"
            targetEnabled -> "LE Audio连接"
            else -> "经典音频连接"
        },
        summary = summary,
        show = show,
        onDismissRequest = onCancel,
    ) {
        if (targetEnabled && limitations.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("注意\n如更改为[LE Audio优先]，以下功能将无法使用。")
                limitations.forEach { Text("• $it") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(text = "取消", onClick = onCancel, modifier = Modifier.weight(1f))
            TextButton(
                text = "确定",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private fun leAudioAlertSummary(
    targetEnabled: Boolean,
    inquiredType: Int?,
    messageType: Int?,
    deviceAlert: Boolean,
): String {
    if (!deviceAlert) {
        return "再次配对以便将耳机连接更改为[LE Audio优先]。\n同时重新连接至耳机。要继续吗？"
    }
    if (targetEnabled) {
        return when (messageType) {
            45, 15 -> "将耳机的连接待机模式更改为[LE Audio优先]。要继续吗？"
            47, 52, 54, 56, 64, 12, 16 ->
                "进入配对模式并将耳机连接更改为[LE Audio优先]。要继续吗？"
            17 -> "将耳机连接更改为[LE Audio优先]。要继续吗？"
            else -> "再次配对以便将耳机连接更改为[LE Audio优先]。\n同时重新连接至耳机。要继续吗？"
        }
    }
    return when (messageType) {
        44, 14 -> "将耳机的连接待机模式更改为[仅经典音频]。要继续吗？"
        46 -> "进入配对模式并将耳机连接更改为[仅经典音频]。要继续吗？"
        116 -> "从LE Audio切换为经典音频的音质优先模式。要继续吗？"
        117 -> "从LE Audio切换为经典音频的连接质量优先模式。要继续吗？"
        118 -> "将经典音频更改为音质优先模式。要继续吗？"
        119 -> "将经典音频更改为连接质量优先模式。要继续吗？"
        else -> "将耳机连接更改为[仅经典音频]。同时重新连接至耳机。要继续吗？"
    }
}

/**
 * Fixed-message alerts already encode the limitation set in their message
 * type, and Sound Connect uses a localized resource for each type. The raw
 * item list is authoritative only for FLEXIBLE_MESSAGE alerts; guessing a
 * fixed list here produces incorrect warnings on models with different
 * capability combinations.
 */
private fun fixedLeAudioLimitations(messageType: Int?): List<String> = when (messageType) {
    // The fixed message type is the device's capability combination. These
    // labels mirror the combinations selected by Sound Connect's fixed UI;
    // flexible alerts use the device-supplied item list above instead.
    47, 49 -> listOf("语音助手", "连接模式")
    52, 53 -> listOf("可分配设置", "语音助手", "Quick Access功能", "连接模式")
    54, 55 -> listOf("语音助手", "连接模式", "设备配对管理")
    56, 57 -> listOf("语音助手", "连接模式", "Quick Access功能")
    64, 65 -> listOf("语音助手", "连接模式", "Quick Access功能", "设备配对管理")
    else -> emptyList()
}

private val defaultLeAudioLimitations = listOf(
    "LDAC音频播放",
    "空间声音和头部跟踪",
    "同时连接到2台设备",
    "语音助手",
    "Quick Access功能",
    "Sound AR功能",
    "部分Scene-based Listening功能",
    "Auto Switch",
)

private fun leAudioLimitationLabel(code: Int): String = when (code) {
    0 -> "均衡器"
    1 -> "DSEE"
    2 -> "Speak-to-Chat"
    3 -> "自动音量控制"
    4 -> "通过语音激活语音助手"
    5 -> "GATT功能"
    6 -> "LDAC音频播放"
    7 -> "音质优先"
    8 -> "Google Assistant"
    9 -> "语音助手"
    10 -> "软件更新"
    11 -> "同时连接到2台设备"
    12 -> "语音助手唤醒词"
    13 -> "BGM模式"
    14 -> "电池保护模式"
    15 -> "头部跟踪"
    16 -> "LE Audio"
    17 -> "沉浸式音频"
    18 -> "Link Auto Switch"
    19 -> "部分Auto Play功能"
    20 -> "降噪功能"
    21 -> "Sound AR功能"
    22 -> "Voice UI"
    23 -> "Quick Access功能"
    24 -> "连接模式"
    25 -> "Auto Play"
    else -> "其他受限功能（0x%02X）".format(code and 0xFF)
}
