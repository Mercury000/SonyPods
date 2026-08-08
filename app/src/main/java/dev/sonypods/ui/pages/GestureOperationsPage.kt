package dev.sonypods.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sonypods.bridge.QuickAccessActionSnapshot
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.data.GestureOperationKey
import dev.sonypods.protocol.AssignableSettingsAction
import dev.sonypods.protocol.AssignableSettingsFunction
import dev.sonypods.protocol.AssignableSettingsKey
import dev.sonypods.protocol.AssignableSettingsPreset
import dev.sonypods.protocol.AssignableSettingsType
import dev.sonypods.protocol.QuickAccessFunction
import dev.sonypods.protocol.GestureNoiseControlMode
import dev.sonypods.ui.SonyDetailActions
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.lazy.LazyColumn

/**
 * The third-level gesture page.  The shell owns the title/back bar; this page
 * therefore only renders the gesture content and never repeats earphone-level
 * actions such as power-off or system settings.
 */
@Composable
internal fun GestureOperationsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions,
) {
    val keys = uiState.gestureOperationKeys
    val hasAmbientControl = keys.any { key ->
        key.actions.any { action ->
            action.function.isGestureAmbientFunction() ||
                action.availableFunctions.any { it.isGestureAmbientFunction() }
        }
    }
    val ambientSupportsNcss = keys.any { key ->
        key.actions.any { action ->
            action.function.supportsGestureNcss() || action.availableFunctions.any { it.supportsGestureNcss() }
        }
    }
    val derivedAmbientSelection = remember(keys) { keys.ambientSelection() }
    var ambientSelection by remember(keys) { mutableStateOf(derivedAmbientSelection) }
    LaunchedEffect(keys) {
        ambientSelection = keys.ambientSelection()
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (keys.isEmpty()) {
            item {
                Text(
                    text = "正在读取耳机支持的手势操作…",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        keys.forEach { key ->
            item(key.key.name) {
                GestureKeyCard(key = key, actions = actions)
            }
        }

        if (hasAmbientControl) {
            item("ambient-control") {
                AmbientGestureCard(
                    selection = ambientSelection,
                    supportsNcss = ambientSupportsNcss,
                    onSelectionChange = { selection ->
                        ambientSelection = selection
                        if (selection.size >= 2) actions.onGestureAmbientModesChange(selection)
                    },
                )
            }
        }

        if (uiState.quickAccessActions.isNotEmpty()) {
            item("quick-access") {
                QuickAccessCard(
                    actions = uiState.quickAccessActions,
                    currentFunctionCodes = uiState.quickAccessFunctionCodes,
                    onFunctionChange = actions.onQuickAccessFunctionChange,
                )
            }
        }

        item { Spacer(modifier = Modifier.height(bottomContentPadding)) }
    }
}

@Composable
private fun GestureKeyCard(
    key: GestureOperationKey,
    actions: SonyDetailActions,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        if (key.availablePresets.isNotEmpty()) {
            OverlayDropdownPreference(
                title = gestureKeyLabel(key.key, key.type),
                summary = gesturePresetLabel(key.currentPreset),
                items = key.availablePresets.map(::gesturePresetLabel),
                selectedIndex = key.availablePresets.indexOf(key.currentPreset).coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    key.availablePresets.getOrNull(index)?.let {
                        actions.onGesturePresetChange(key.key, it)
                    }
                },
            )
        } else {
            // Some button/face-tap capabilities expose no selectable preset;
            // keep their physical control label while avoiding the removed
            // "触控 · 已启用" summary.
            BasicComponent(title = gestureKeyLabel(key.key, key.type))
        }
    }
}

@Composable
private fun QuickAccessCard(
    actions: List<QuickAccessActionSnapshot>,
    currentFunctionCodes: List<Int>,
    onFunctionChange: (Int, Int) -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        BasicComponent(
            title = "Quick Access",
        )
        actions.forEachIndexed { index, action ->
            val current = currentFunctionCodes.getOrNull(index)
                ?: action.currentFunctionCode
                ?: action.defaultFunctionCode
            // The device capability response can omit a service after the user
            // switches away from it. Sound Connect keeps the complete service
            // catalog available, so retain the known catalog in every selector
            // and append any newer/raw IDs reported by the device.
            val functions = buildList {
                addAll(QuickAccessFunction.entries
                    .filter { it != QuickAccessFunction.OUT_OF_RANGE }
                    .map { it.code.toInt() and 0xFF })
                addAll(action.availableFunctionCodes)
                add(current)
            }.distinct()
            if (functions.isNotEmpty()) {
                OverlayDropdownPreference(
                    title = gestureActionLabel(action.actionCode),
                    summary = quickAccessFunctionLabel(current),
                    items = functions.map(::quickAccessFunctionLabel),
                    selectedIndex = functions.indexOf(current).coerceAtLeast(0),
                    onSelectedIndexChange = { selected ->
                        functions.getOrNull(selected)?.let { onFunctionChange(index, it) }
                    },
                )
            }
        }
    }
}

@Composable
private fun AmbientGestureCard(
    selection: Set<GestureNoiseControlMode>,
    supportsNcss: Boolean,
    onSelectionChange: (Set<GestureNoiseControlMode>) -> Unit,
) {
    val options = buildList {
        add(GestureNoiseControlMode.NOISE_CANCELLING to "降噪")
        if (supportsNcss) add(GestureNoiseControlMode.NOISE_CANCELLING_SPEECH to "降噪增强（NCSS）")
        add(GestureNoiseControlMode.AMBIENT_SOUND to "环境声音")
        add(GestureNoiseControlMode.OFF to "关闭")
    }
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        BasicComponent(
            title = "环境声音控制",
            summary = "每次按下触摸感应器切换所选设置（至少选择两种）",
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { (mode, label) ->
                val checked = mode in selection
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Checkbox) {
                            val next = if (checked) selection - mode else selection + mode
                            onSelectionChange(next)
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = label, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Checkbox(
                        state = ToggleableState(checked),
                        onClick = {
                            val next = if (checked) selection - mode else selection + mode
                            onSelectionChange(next)
                        },
                    )
                }
            }
            if (selection.size < 2) {
                Text(
                    text = "至少选择两种设置",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MiuixTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private fun List<GestureOperationKey>.ambientSelection(): Set<GestureNoiseControlMode> =
    asSequence()
        .flatMap { it.actions.asSequence() }
        .mapNotNull { action -> gestureModesForFunction(action.function) }
        .firstOrNull()
        ?: setOf(
            GestureNoiseControlMode.NOISE_CANCELLING,
            GestureNoiseControlMode.AMBIENT_SOUND,
        )

private fun gestureModesForFunction(function: AssignableSettingsFunction): Set<GestureNoiseControlMode>? = when (function) {
    AssignableSettingsFunction.NC_ASM_OFF -> setOf(
        GestureNoiseControlMode.NOISE_CANCELLING,
        GestureNoiseControlMode.AMBIENT_SOUND,
        GestureNoiseControlMode.OFF,
    )
    AssignableSettingsFunction.NC_ASM -> setOf(
        GestureNoiseControlMode.NOISE_CANCELLING,
        GestureNoiseControlMode.AMBIENT_SOUND,
    )
    AssignableSettingsFunction.NC_OFF -> setOf(
        GestureNoiseControlMode.NOISE_CANCELLING,
        GestureNoiseControlMode.OFF,
    )
    AssignableSettingsFunction.ASM_OFF -> setOf(
        GestureNoiseControlMode.AMBIENT_SOUND,
        GestureNoiseControlMode.OFF,
    )
    AssignableSettingsFunction.NC_NCSS_ASM_OFF -> setOf(
        GestureNoiseControlMode.NOISE_CANCELLING,
        GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
        GestureNoiseControlMode.AMBIENT_SOUND,
        GestureNoiseControlMode.OFF,
    )
    AssignableSettingsFunction.NC_NCSS_ASM -> setOf(
        GestureNoiseControlMode.NOISE_CANCELLING,
        GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
        GestureNoiseControlMode.AMBIENT_SOUND,
    )
    AssignableSettingsFunction.NC_NCSS_OFF -> setOf(
        GestureNoiseControlMode.NOISE_CANCELLING,
        GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
        GestureNoiseControlMode.OFF,
    )
    AssignableSettingsFunction.NCSS_ASM_OFF -> setOf(
        GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
        GestureNoiseControlMode.AMBIENT_SOUND,
        GestureNoiseControlMode.OFF,
    )
    AssignableSettingsFunction.NC_NCSS -> setOf(
        GestureNoiseControlMode.NOISE_CANCELLING,
        GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
    )
    AssignableSettingsFunction.NCSS_ASM -> setOf(
        GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
        GestureNoiseControlMode.AMBIENT_SOUND,
    )
    AssignableSettingsFunction.NCSS_OFF -> setOf(
        GestureNoiseControlMode.NOISE_CANCELLING_SPEECH,
        GestureNoiseControlMode.OFF,
    )
    else -> null
}

private fun AssignableSettingsFunction.isGestureAmbientFunction(): Boolean =
    gestureModesForFunction(this) != null

private fun AssignableSettingsFunction.supportsGestureNcss(): Boolean = this in setOf(
    AssignableSettingsFunction.NC_NCSS_ASM_OFF,
    AssignableSettingsFunction.NC_NCSS_ASM,
    AssignableSettingsFunction.NC_NCSS_OFF,
    AssignableSettingsFunction.NCSS_ASM_OFF,
    AssignableSettingsFunction.NC_NCSS,
    AssignableSettingsFunction.NCSS_ASM,
    AssignableSettingsFunction.NCSS_OFF,
)

private fun gestureKeyLabel(key: AssignableSettingsKey, type: AssignableSettingsType): String = when {
    type == AssignableSettingsType.FACE_TAP -> "面部轻击"
    else -> when (key) {
        AssignableSettingsKey.LEFT_SIDE -> "左耳"
        AssignableSettingsKey.RIGHT_SIDE -> "右耳"
        AssignableSettingsKey.CUSTOM -> "自定义"
        AssignableSettingsKey.C -> "C 按键"
        AssignableSettingsKey.NC_AMB_KEY -> "降噪/环境声按键"
        AssignableSettingsKey.NC_AMBIENT_KEY -> "降噪/环境声按键 2"
        AssignableSettingsKey.OUT_OF_RANGE -> "未知按键"
    }
}

private fun gestureActionLabel(action: AssignableSettingsAction): String = gestureActionLabel(action.code.toInt() and 0xFF)

private fun gestureActionLabel(actionCode: Int): String = when (actionCode) {
    0x00 -> "单击"
    0x01 -> "双击"
    0x02 -> "三击"
    0x03 -> "重复点击"
    0x10 -> "单击并按住"
    0x11 -> "双击并按住"
    0x21 -> "长按开始激活"
    0x22 -> "激活期间长按"
    else -> "未知动作（0x%02X）".format(actionCode)
}

private fun gestureFunctionLabel(function: AssignableSettingsFunction): String = when (function) {
    AssignableSettingsFunction.NO_FUNCTION -> "无操作"
    AssignableSettingsFunction.NC_ASM_OFF -> "降噪/环境声音/关闭"
    AssignableSettingsFunction.NC_ASM -> "降噪/环境声音"
    AssignableSettingsFunction.NC_OFF -> "降噪/关闭"
    AssignableSettingsFunction.ASM_OFF -> "环境声音/关闭"
    AssignableSettingsFunction.QUICK_ATTENTION -> "快速注意"
    AssignableSettingsFunction.NC_OPTIMIZER -> "降噪优化"
    AssignableSettingsFunction.PLAY_PAUSE -> "播放/暂停"
    AssignableSettingsFunction.NEXT_TRACK -> "下一曲"
    AssignableSettingsFunction.PREV_TRACK -> "上一曲"
    AssignableSettingsFunction.VOLUME_UP -> "音量增加"
    AssignableSettingsFunction.VOLUME_DOWN -> "音量减少"
    AssignableSettingsFunction.VOICE_RECOGNITION -> "语音助手"
    AssignableSettingsFunction.GET_YOUR_NOTIFICATION -> "读取通知"
    AssignableSettingsFunction.TALK_TO_GOOGLE_ASSISTANT -> "Google 助理"
    AssignableSettingsFunction.STOP_GOOGLE_ASSISTANT -> "停止 Google 助理"
    AssignableSettingsFunction.VOICE_INPUT_CANCEL -> "取消语音输入"
    AssignableSettingsFunction.TALK_TO_TENCENT_XIAOWEI -> "腾讯小微"
    AssignableSettingsFunction.CANCEL_VOICE_RECOGNITION -> "取消语音识别"
    AssignableSettingsFunction.VOICE_INPUT_AMAZON_ALEXA -> "Amazon Alexa"
    AssignableSettingsFunction.CANCEL_AMAZON_ALEXA -> "取消 Alexa"
    AssignableSettingsFunction.CANCEL_TENCENT_XIAOWEI -> "取消腾讯小微"
    AssignableSettingsFunction.NEXT_TRACK_STOP_GEMINI_LIVE -> "下一曲并停止 Gemini"
    AssignableSettingsFunction.PREV_TRACK_STOP_GEMINI_LIVE -> "上一曲并停止 Gemini"
    AssignableSettingsFunction.LAUNCH_MLP -> "启动 MLP"
    AssignableSettingsFunction.TALK_TO_YOUR_MLP -> "MLP 助手"
    AssignableSettingsFunction.SPTF_ONE_TOUCH -> "Spotify 点按"
    AssignableSettingsFunction.QUICK_ACCESS1 -> "Quick Access 1"
    AssignableSettingsFunction.QUICK_ACCESS2 -> "Quick Access 2"
    AssignableSettingsFunction.TALK_TO_TENCENT_XIAOWEI_CANCEL -> "腾讯小微/取消"
    AssignableSettingsFunction.Q_MSC_ONE_TOUCH -> "QQ Music 点按"
    AssignableSettingsFunction.TEAMS -> "Teams"
    AssignableSettingsFunction.TEAMS_VOICE_SKILLS -> "Teams 语音技能"
    AssignableSettingsFunction.NC_NCSS_ASM_OFF -> "降噪/降噪增强/环境声音/关闭"
    AssignableSettingsFunction.NC_NCSS_ASM -> "降噪/降噪增强/环境声音"
    AssignableSettingsFunction.NC_NCSS_OFF -> "降噪/降噪增强/关闭"
    AssignableSettingsFunction.NCSS_ASM_OFF -> "降噪增强/环境声音/关闭"
    AssignableSettingsFunction.NC_NCSS -> "降噪/降噪增强"
    AssignableSettingsFunction.NCSS_ASM -> "降噪增强/环境声音"
    AssignableSettingsFunction.NCSS_OFF -> "降噪增强/关闭"
    AssignableSettingsFunction.AMB_SETTING -> "环境声音设置"
    AssignableSettingsFunction.STANDARD_VOICE_SOUND -> "标准语音声音"
    AssignableSettingsFunction.LISTENING_MODE -> "聆听模式"
    AssignableSettingsFunction.MIC_MUTE -> "麦克风静音"
    AssignableSettingsFunction.GAME_UP -> "游戏模式"
    AssignableSettingsFunction.CHAT_UP -> "聊天模式"
    AssignableSettingsFunction.OUT_OF_RANGE -> "未知功能"
}

private fun gesturePresetLabel(preset: AssignableSettingsPreset): String = when (preset) {
    AssignableSettingsPreset.AMBIENT_SOUND_CONTROL -> "环境声音控制"
    AssignableSettingsPreset.VOLUME_CONTROL -> "音量控制"
    AssignableSettingsPreset.PLAYBACK_CONTROL -> "播放控制"
    AssignableSettingsPreset.TRACK_CONTROL -> "曲目控制"
    AssignableSettingsPreset.PLAYBACK_CONTROL_VOICE_ASSISTANT_LIMITATION -> "播放控制（语音助手限制）"
    AssignableSettingsPreset.VOICE_RECOGNITION -> "语音识别"
    AssignableSettingsPreset.GOOGLE_ASSIST -> "Google 助理"
    AssignableSettingsPreset.AMAZON_ALEXA -> "Amazon Alexa"
    AssignableSettingsPreset.TENCENT_XIAOWEI -> "腾讯小微"
    AssignableSettingsPreset.MS -> "Microsoft"
    AssignableSettingsPreset.AMBIENT_SOUND_CONTROL_QUICK_ACCESS -> "环境声音控制/Quick Access"
    AssignableSettingsPreset.QUICK_ACCESS -> "Quick Access"
    AssignableSettingsPreset.TENCENT_XIAOWEI_Q_MSC -> "腾讯小微 Q MSC"
    AssignableSettingsPreset.TEAMS -> "Teams"
    AssignableSettingsPreset.GOOGLE_ASSISTANT_BT_CLASSIC_ONLY -> "Google 助理（经典蓝牙）"
    AssignableSettingsPreset.AMAZON_ALEXA_BT_CLASSIC_ONLY -> "Alexa（经典蓝牙）"
    AssignableSettingsPreset.TENCENT_XIAOWEI_BT_CLASSIC_ONLY -> "腾讯小微（经典蓝牙）"
    AssignableSettingsPreset.QUICK_ACCESS_BT_CLASSIC_ONLY -> "Quick Access（经典蓝牙）"
    AssignableSettingsPreset.AMBIENT_SOUND_CONTROL_QUICK_ACCESS_BT_CLASSIC_ONLY -> "环境声音控制/Quick Access（经典蓝牙）"
    AssignableSettingsPreset.TENCENT_XIAOWEI_Q_MSC_BT_CLASSIC_ONLY -> "腾讯小微 Q MSC（经典蓝牙）"
    AssignableSettingsPreset.AMBIENT_SOUND_CONTROL_MIC -> "环境声音/麦克风"
    AssignableSettingsPreset.LISTENING_MODE_QUICK_ACCESS -> "聆听模式/Quick Access"
    AssignableSettingsPreset.AMBIENT_SOUND_CONTROL_LISTENING_MODE -> "环境声音/聆听模式"
    AssignableSettingsPreset.CHAT_MIX -> "游戏/聊天混音"
    AssignableSettingsPreset.CUSTOM1 -> "自定义 1"
    AssignableSettingsPreset.CUSTOM2 -> "自定义 2"
    AssignableSettingsPreset.NO_FUNCTION -> "无操作"
    AssignableSettingsPreset.OUT_OF_RANGE -> "未知操作组"
}

private fun quickAccessFunctionLabel(code: Int): String = when (code) {
    0x00 -> "无操作"
    0x01 -> "Spotify"
    0x02 -> "Endel"
    0x03 -> "Amazon Music"
    0x04 -> "腾讯小微"
    0x05 -> "喜马拉雅"
    0x06 -> "酷狗音乐"
    0x07 -> "QQ音乐"
    0x08 -> "Eye Navi"
    0x09 -> "网易云音乐"
    0x0A -> "Apple Music"
    0x0C -> "YouTube Music"
    else -> "服务（0x%02X）".format(code)
}
