package dev.sonypods.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.sonypods.R
import dev.sonypods.bridge.QuickAccessActionSnapshot
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.config.VisibilityConfig
import dev.sonypods.data.GestureOperationAction
import dev.sonypods.data.GestureOperationKey
import dev.sonypods.protocol.AssignableSettingsAction
import dev.sonypods.protocol.AssignableSettingsFunction
import dev.sonypods.protocol.AssignableSettingsKey
import dev.sonypods.protocol.AssignableSettingsPreset
import dev.sonypods.protocol.AssignableSettingsType
import dev.sonypods.protocol.GestureNoiseControlMode
import dev.sonypods.protocol.QuickAccessServiceCatalog
import dev.sonypods.ui.SonyDetailActions
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
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
    visibility: VisibilityConfig,
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
                    text = stringResource(R.string.gesture_reading),
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

        // Quick Access talks to the headset's SAR/Spotify service directory over
        // classic Bluetooth; under LC3 the headset refuses the writes, so the card
        // greys out (mirroring the multipoint card) — or hides, per the visibility
        // setting.  Which functions actually lose availability comes from the
        // capability table (SC's FunctionCantBeUsedWithLEAConnectionType), not from
        // the mere fact that LE Audio is up.
        //
        // The greyed card must render even with no action list: the LE identity's
        // capability table (which the headset itself serves once the link is LC3,
        // whichever address was dialed) declares 0x4C but omits QUICK_ACCESS
        // entirely, so no capability reply ever populates the actions. SC keeps the
        // card visible in exactly this state — the declaration alone is the
        // evidence that the function exists but is unavailable over LE Audio.
        val quickAccessLeaRestricted = uiState.quickAccessLeaRestricted
        val showQuickAccess = uiState.quickAccessActions.isNotEmpty() || quickAccessLeaRestricted
        if (showQuickAccess &&
            !(quickAccessLeaRestricted && !visibility.leaRestrictedQuickAccess)
        ) {
            item("quick-access") {
                QuickAccessCard(
                    actions = uiState.quickAccessActions,
                    currentFunctionCodes = uiState.quickAccessFunctionCodes,
                    onFunctionChange = actions.onQuickAccessFunctionChange,
                    enabled = !quickAccessLeaRestricted,
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
                items = key.availablePresets.map { gesturePresetLabel(it) },
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
        if (key.actions.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                key.actions.forEach { action ->
                    ReadOnlyGestureAction(action)
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyGestureAction(action: GestureOperationAction) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = gestureActionLabel(action.action),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = gestureFunctionLabel(action.function),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun QuickAccessCard(
    actions: List<QuickAccessActionSnapshot>,
    currentFunctionCodes: List<Int>,
    onFunctionChange: (Int, Int) -> Unit,
    enabled: Boolean = true,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        val contentAlpha = if (enabled) 1f else 0.38f
        BasicComponent(
            title = stringResource(R.string.qa_card_title),
            enabled = enabled,
        )
        if (!enabled) {
            // 与「同时连接2台设备」相同：LE Audio 承载连接时隐藏选择器，
            // 在原位置居中说明这是连接方式决定的不可用，而非用户关闭。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.feature_unavailable_over_le_audio),
                    modifier = Modifier.padding(horizontal = 16.dp).alpha(contentAlpha),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            return@Card
        }
        actions.forEachIndexed { index, action ->
            val current = currentFunctionCodes.getOrNull(index)
                ?: action.currentFunctionCode
                ?: action.defaultFunctionCode
            // Quick Access capability is slot/action metadata, not the complete
            // SAR service directory.  A service disappears from that response
            // after switching away from it, while Sound Connect keeps it in the
            // selector.  Keep the static directory and append raw capability /
            // current IDs for forward compatibility.
            val functions = QuickAccessServiceCatalog.candidates(
                capabilityCodes = action.availableFunctionCodes,
                currentCode = current,
                defaultCode = action.defaultFunctionCode,
            )
            if (functions.isNotEmpty()) {
                OverlayDropdownPreference(
                    title = gestureActionLabel(action.actionCode),
                    items = functions.map { quickAccessFunctionLabel(it) },
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
        add(GestureNoiseControlMode.NOISE_CANCELLING to stringResource(R.string.nc_short))
        if (supportsNcss) add(GestureNoiseControlMode.NOISE_CANCELLING_SPEECH to stringResource(R.string.ncss_short))
        add(GestureNoiseControlMode.AMBIENT_SOUND to stringResource(R.string.gesture_ambient))
        add(GestureNoiseControlMode.OFF to stringResource(R.string.off))
    }
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        BasicComponent(
            title = stringResource(R.string.anc_control_title),
            summary = stringResource(R.string.anc_control_summary),
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
                    text = stringResource(R.string.anc_pick_two),
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

@Composable
private fun gestureKeyLabel(key: AssignableSettingsKey, type: AssignableSettingsType): String = when {
    type == AssignableSettingsType.FACE_TAP -> stringResource(R.string.gesture_face_tap)
    else -> when (key) {
        AssignableSettingsKey.LEFT_SIDE -> stringResource(R.string.gesture_key_left)
        AssignableSettingsKey.RIGHT_SIDE -> stringResource(R.string.side_right)
        AssignableSettingsKey.CUSTOM -> stringResource(R.string.gesture_key_custom)
        AssignableSettingsKey.C -> stringResource(R.string.gesture_key_c)
        AssignableSettingsKey.NC_AMB_KEY -> stringResource(R.string.gesture_key_nc_amb)
        AssignableSettingsKey.NC_AMBIENT_KEY -> stringResource(R.string.gesture_key_nc_amb_2)
        AssignableSettingsKey.OUT_OF_RANGE -> stringResource(R.string.gesture_key_unknown)
    }
}

@Composable
private fun gestureActionLabel(action: AssignableSettingsAction): String = gestureActionLabel(action.code.toInt() and 0xFF)

@Composable
private fun gestureActionLabel(actionCode: Int): String = when (actionCode) {
    0x00 -> stringResource(R.string.tap_single)
    0x01 -> stringResource(R.string.tap_double)
    0x02 -> stringResource(R.string.tap_triple)
    0x03 -> stringResource(R.string.tap_repeat)
    0x10 -> stringResource(R.string.gesture_act_single_hold)
    0x11 -> stringResource(R.string.gesture_act_double_hold)
    0x21 -> stringResource(R.string.gesture_act_hold_start)
    0x22 -> stringResource(R.string.gesture_act_hold_active)
    else -> stringResource(R.string.gesture_act_unknown_fmt, actionCode)
}

@Composable
private fun gestureFunctionLabel(function: AssignableSettingsFunction): String = when (function) {
    AssignableSettingsFunction.NO_FUNCTION -> stringResource(R.string.fn_no_op)
    AssignableSettingsFunction.NC_ASM_OFF -> stringResource(R.string.fn_nc_asm_off)
    AssignableSettingsFunction.NC_ASM -> stringResource(R.string.fn_nc_asm)
    AssignableSettingsFunction.NC_OFF -> stringResource(R.string.fn_nc_off)
    AssignableSettingsFunction.ASM_OFF -> stringResource(R.string.fn_asm_off)
    AssignableSettingsFunction.QUICK_ATTENTION -> stringResource(R.string.fn_quick_attention)
    AssignableSettingsFunction.NC_OPTIMIZER -> stringResource(R.string.fn_nc_optimizer)
    AssignableSettingsFunction.PLAY_PAUSE -> stringResource(R.string.fn_play_pause)
    AssignableSettingsFunction.NEXT_TRACK -> stringResource(R.string.fn_next_track)
    AssignableSettingsFunction.PREV_TRACK -> stringResource(R.string.fn_prev_track)
    AssignableSettingsFunction.VOLUME_UP -> stringResource(R.string.fn_volume_up)
    AssignableSettingsFunction.VOLUME_DOWN -> stringResource(R.string.fn_volume_down)
    AssignableSettingsFunction.VOICE_RECOGNITION -> stringResource(R.string.fn_voice_assistant)
    AssignableSettingsFunction.GET_YOUR_NOTIFICATION -> stringResource(R.string.fn_read_notification)
    AssignableSettingsFunction.TALK_TO_GOOGLE_ASSISTANT -> stringResource(R.string.fn_google_assistant)
    AssignableSettingsFunction.STOP_GOOGLE_ASSISTANT -> stringResource(R.string.fn_stop_google_assistant)
    AssignableSettingsFunction.VOICE_INPUT_CANCEL -> stringResource(R.string.fn_cancel_voice_input)
    AssignableSettingsFunction.TALK_TO_TENCENT_XIAOWEI -> stringResource(R.string.fn_tencent_xiaowei)
    AssignableSettingsFunction.CANCEL_VOICE_RECOGNITION -> stringResource(R.string.fn_cancel_voice_recognition)
    AssignableSettingsFunction.VOICE_INPUT_AMAZON_ALEXA -> stringResource(R.string.fn_alexa)
    AssignableSettingsFunction.CANCEL_AMAZON_ALEXA -> stringResource(R.string.fn_cancel_alexa)
    AssignableSettingsFunction.CANCEL_TENCENT_XIAOWEI -> stringResource(R.string.fn_cancel_xiaowei)
    AssignableSettingsFunction.NEXT_TRACK_STOP_GEMINI_LIVE -> stringResource(R.string.fn_next_stop_gemini)
    AssignableSettingsFunction.PREV_TRACK_STOP_GEMINI_LIVE -> stringResource(R.string.fn_prev_stop_gemini)
    AssignableSettingsFunction.LAUNCH_MLP -> stringResource(R.string.fn_launch_mlp)
    AssignableSettingsFunction.TALK_TO_YOUR_MLP -> stringResource(R.string.fn_mlp_assistant)
    AssignableSettingsFunction.SPTF_ONE_TOUCH -> stringResource(R.string.fn_spotify_tap)
    AssignableSettingsFunction.QUICK_ACCESS1 -> stringResource(R.string.fn_quick_access_1)
    AssignableSettingsFunction.QUICK_ACCESS2 -> stringResource(R.string.fn_quick_access_2)
    AssignableSettingsFunction.TALK_TO_TENCENT_XIAOWEI_CANCEL -> stringResource(R.string.fn_xiaowei_cancel)
    AssignableSettingsFunction.Q_MSC_ONE_TOUCH -> stringResource(R.string.fn_qq_music_tap)
    AssignableSettingsFunction.TEAMS -> stringResource(R.string.fn_teams)
    AssignableSettingsFunction.TEAMS_VOICE_SKILLS -> stringResource(R.string.fn_teams_voice)
    AssignableSettingsFunction.NC_NCSS_ASM_OFF -> stringResource(R.string.fn_nc_ncss_asm_off)
    AssignableSettingsFunction.NC_NCSS_ASM -> stringResource(R.string.fn_nc_ncss_asm)
    AssignableSettingsFunction.NC_NCSS_OFF -> stringResource(R.string.fn_nc_ncss_off)
    AssignableSettingsFunction.NCSS_ASM_OFF -> stringResource(R.string.fn_ncss_asm_off)
    AssignableSettingsFunction.NC_NCSS -> stringResource(R.string.fn_nc_ncss)
    AssignableSettingsFunction.NCSS_ASM -> stringResource(R.string.fn_ncss_asm)
    AssignableSettingsFunction.NCSS_OFF -> stringResource(R.string.fn_ncss_off)
    AssignableSettingsFunction.AMB_SETTING -> stringResource(R.string.fn_amb_setting)
    AssignableSettingsFunction.STANDARD_VOICE_SOUND -> stringResource(R.string.fn_standard_voice)
    AssignableSettingsFunction.LISTENING_MODE -> stringResource(R.string.fn_listening_mode)
    AssignableSettingsFunction.MIC_MUTE -> stringResource(R.string.fn_mic_mute)
    AssignableSettingsFunction.GAME_UP -> stringResource(R.string.fn_game_mode)
    AssignableSettingsFunction.CHAT_UP -> stringResource(R.string.fn_chat_mode)
    AssignableSettingsFunction.OUT_OF_RANGE -> stringResource(R.string.fn_unknown)
}

@Composable
private fun gesturePresetLabel(preset: AssignableSettingsPreset): String = when (preset) {
    AssignableSettingsPreset.AMBIENT_SOUND_CONTROL -> stringResource(R.string.anc_control_title)
    AssignableSettingsPreset.VOLUME_CONTROL -> stringResource(R.string.preset_volume_control)
    AssignableSettingsPreset.PLAYBACK_CONTROL -> stringResource(R.string.preset_playback_control)
    AssignableSettingsPreset.TRACK_CONTROL -> stringResource(R.string.preset_track_control)
    AssignableSettingsPreset.PLAYBACK_CONTROL_VOICE_ASSISTANT_LIMITATION -> stringResource(R.string.preset_playback_voice_limit)
    AssignableSettingsPreset.VOICE_RECOGNITION -> stringResource(R.string.preset_voice_recognition)
    AssignableSettingsPreset.GOOGLE_ASSIST -> stringResource(R.string.fn_google_assistant)
    AssignableSettingsPreset.AMAZON_ALEXA -> stringResource(R.string.fn_alexa)
    AssignableSettingsPreset.TENCENT_XIAOWEI -> stringResource(R.string.fn_tencent_xiaowei)
    AssignableSettingsPreset.MS -> stringResource(R.string.fn_microsoft)
    AssignableSettingsPreset.AMBIENT_SOUND_CONTROL_QUICK_ACCESS -> stringResource(R.string.preset_amb_quick_access)
    AssignableSettingsPreset.QUICK_ACCESS -> stringResource(R.string.qa_card_title)
    AssignableSettingsPreset.TENCENT_XIAOWEI_Q_MSC -> stringResource(R.string.preset_xiaowei_qmsc)
    AssignableSettingsPreset.TEAMS -> stringResource(R.string.fn_teams)
    AssignableSettingsPreset.GOOGLE_ASSISTANT_BT_CLASSIC_ONLY -> stringResource(R.string.preset_google_bt)
    AssignableSettingsPreset.AMAZON_ALEXA_BT_CLASSIC_ONLY -> stringResource(R.string.preset_alexa_bt)
    AssignableSettingsPreset.TENCENT_XIAOWEI_BT_CLASSIC_ONLY -> stringResource(R.string.preset_xiaowei_bt)
    AssignableSettingsPreset.QUICK_ACCESS_BT_CLASSIC_ONLY -> stringResource(R.string.preset_quick_access_bt)
    AssignableSettingsPreset.AMBIENT_SOUND_CONTROL_QUICK_ACCESS_BT_CLASSIC_ONLY -> stringResource(R.string.preset_amb_qa_bt)
    AssignableSettingsPreset.TENCENT_XIAOWEI_Q_MSC_BT_CLASSIC_ONLY -> stringResource(R.string.preset_xiaowei_qmsc_bt)
    AssignableSettingsPreset.AMBIENT_SOUND_CONTROL_MIC -> stringResource(R.string.preset_ambient_mic)
    AssignableSettingsPreset.LISTENING_MODE_QUICK_ACCESS -> stringResource(R.string.preset_listening_qa)
    AssignableSettingsPreset.AMBIENT_SOUND_CONTROL_LISTENING_MODE -> stringResource(R.string.preset_ambient_listening)
    AssignableSettingsPreset.CHAT_MIX -> stringResource(R.string.preset_chat_mix)
    AssignableSettingsPreset.CUSTOM1 -> stringResource(R.string.custom_1)
    AssignableSettingsPreset.CUSTOM2 -> stringResource(R.string.custom_2)
    AssignableSettingsPreset.NO_FUNCTION -> stringResource(R.string.fn_no_op)
    AssignableSettingsPreset.OUT_OF_RANGE -> stringResource(R.string.preset_unknown)
}

@Composable
private fun quickAccessFunctionLabel(code: Int): String {
    val res = QuickAccessServiceCatalog.nameRes(code)
        ?: return stringResource(R.string.qa_service_unknown_fmt, code)
    return stringResource(res)
}
