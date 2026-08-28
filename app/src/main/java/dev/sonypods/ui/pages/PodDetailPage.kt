package dev.sonypods.ui.pages

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.bridge.MultipointSnapshot
import dev.sonypods.config.VisibilityConfig
import dev.sonypods.protocol.ConnectionQualityMode
import dev.sonypods.protocol.DseeGeneration
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.SoundQualityCodec
import dev.sonypods.protocol.PlaybackStatus
import com.mercury.sonypods.R
import dev.sonypods.ui.SonyDetailActions
import dev.sonypods.ui.dialogs.ConnectionQualityConfirmDialog
import dev.sonypods.ui.dialogs.LdacEnableConfirmDialog
import dev.sonypods.ui.components.AncSwitch
import dev.sonypods.ui.components.AppIcons
import dev.sonypods.ui.localizedName
import dev.sonypods.ui.noiseAdaptiveSensitivityValue
import dev.sonypods.ui.components.PodStatus
import dev.sonypods.ui.toBatteryParams
import dev.sonypods.ui.toSinglePodParams
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.VolumeUp

@Composable
fun PodDetailPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    podName: String,
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions = SonyDetailActions(),
    /** Per-card show/hide switches from the module settings' visibility section. */
    visibility: VisibilityConfig = VisibilityConfig(),
    listState: LazyListState,
    boxImagePath: String? = null,
    /** Changes whenever the cached image record is rewritten, even if its path is stable. */
    boxImageRevision: Long = 0L,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (isLandscape) {
        val overviewListState = remember { LazyListState() }
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            LazyColumn(
                state = overviewListState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp, bottom = bottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Image(
                            painter = rememberPodImagePainter(boxImagePath, boxImageRevision),
                            contentDescription = stringResource(R.string.cd_earphones),
                            modifier = Modifier
                                .fillMaxWidth(0.62f)
                                .widthIn(max = 280.dp),
                            contentScale = ContentScale.FillWidth
                        )
                        if (visibility.detailBadge) {
                            SoundQualityBadges(
                                uiState = uiState,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
                item {
                    BatteryStatusCard(uiState = uiState)
                }
                item {
                    DeviceStatusCard(uiState = uiState)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                podControlItems(
                    uiState = uiState,
                    actions = actions,
                    visibility = visibility,
                    bottomContentPadding = bottomContentPadding,
                    includeBattery = false,
                    includeDeviceStatus = false,
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Image(
                    painter = rememberPodImagePainter(boxImagePath, boxImageRevision),
                    contentDescription = stringResource(R.string.cd_earphones),
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .padding(vertical = 16.dp),
                    contentScale = ContentScale.FillWidth
                )
                if (visibility.detailBadge) {
                    SoundQualityBadges(
                        uiState = uiState,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        podControlItems(
            uiState = uiState,
            actions = actions,
            visibility = visibility,
            bottomContentPadding = bottomContentPadding
        )
    }
}

@Composable
private fun rememberPodImagePainter(path: String?, revision: Long): Painter {
    // The downloader intentionally reuses the per-device path. The revision is
    // persisted together with the image record and changes after every completed
    // replacement, so Compose does not keep a Bitmap decoded from the old bytes.
    return remember(path, revision) {
        path?.let {
            runCatching { BitmapFactory.decodeFile(it) }
                .getOrNull()
                ?.let { bitmap -> BitmapPainter(bitmap.asImageBitmap()) }
        }
    } ?: painterResource(defaultLogoRes())
}

/** Effective night mode: AppTheme rewrites the LocalContext uiMode, so the manual
 * light/dark override is honoured here, not just the system setting. */
@Composable
private fun defaultLogoRes(): Int {
    val dark = LocalConfiguration.current.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    return if (dark) R.drawable.sony_logo_on_dark else R.drawable.sony_logo_on_light
}

private fun LazyListScope.podControlItems(
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions,
    visibility: VisibilityConfig,
    bottomContentPadding: Dp,
    includeBattery: Boolean = true,
    includeAnc: Boolean = true,
    includeDeviceStatus: Boolean = true,
) {
    if (includeBattery) {
        item {
            BatteryStatusCard(uiState = uiState)
        }
    }

    if (includeAnc && uiState.supportsNoiseControl) {
        item {
            AncControlCard(uiState = uiState, actions = actions)
        }
    }

    if (visibility.eq && uiState.supportsEq) {
        item {
            EqCard(uiState = uiState, actions = actions)
        }
    }

    if (visibility.playback && uiState.supportsPlaybackControl) {
        item {
            PlaybackCard(uiState = uiState, actions = actions)
        }
    }

    // While LE Audio carries the audio these cards degrade to a greyed-out note;
    // the paired switches let the user hide them outright for that duration.
    val hideConnectionQuality = uiState.connectionQualityRestrictedByLea &&
        !visibility.leaRestrictedConnectionQuality
    val hideLdac = uiState.usingLeAudio && !visibility.leaRestrictedLdac
    val hideMultipoint = uiState.connectedViaLeAudio && !visibility.leaRestrictedMultipoint

    if (uiState.supportsConnectionQuality && !hideConnectionQuality) {
        item {
            ConnectionQualityCard(uiState = uiState, actions = actions)
        }
    }

    if (uiState.supportsUpscaling && visibility.dsee) {
        item {
            UpscalingCard(uiState = uiState, actions = actions)
        }
    }

    if (uiState.ldacSupported && !hideLdac) {
        item {
            LdacCard(uiState = uiState, actions = actions)
        }
    }

    if (uiState.supportsLeAudio && visibility.leAudioCard) {
        item {
            LeAudioCard(
                uiState = uiState,
                actions = actions,
                showLeAudioToggle = visibility.leAudioToggle,
            )
        }
    }

    if (uiState.supportsGestureOperations && visibility.gestures) {
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.card_gesture_title),
                    summary = if (uiState.gestureOperationKeys.isEmpty()) stringResource(R.string.gesture_summary_reading) else stringResource(R.string.gesture_summary_custom),
                    onClick = actions.onOpenGestureOperations,
                )
            }
        }
    }

    if (uiState.supportsMultipoint && !hideMultipoint) {
        item {
            MultipointEntryCard(
                state = uiState.multipoint,
                leAudioRestricted = uiState.connectedViaLeAudio,
                onOpen = actions.onOpenMultipointSettings,
            )
        }
    }

    if (includeDeviceStatus && visibility.firmware) {
        item {
            DeviceStatusCard(uiState = uiState)
        }
    }

    item {
        Spacer(modifier = Modifier.height(bottomContentPadding))
    }
}

/**
 * Bluetooth 连接质量（AUDIO 域 CONNECTION_MODE 系）。复刻官方两档选择：
 * 声音质量优先 / 稳定连接优先。点击未选中项先弹确认框（文案同官方），
 * 确认后才发送；选中态只由耳机应答（RET/NTFY）驱动。
 *
 * 两种不可用形态都不隐藏卡片：
 * - 能力表宣告 0x4D（LE Audio 下不可用）→ 选择器位置显示「不可用」并置灰；
 * - AUDIO_STATUS EnableDisable=DISABLE → 选择器置灰。
 */
@Composable
private fun ConnectionQualityCard(
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions,
) {
    val current = uiState.connectionQualityModeName
        ?.let { name -> runCatching { ConnectionQualityMode.valueOf(name) }.getOrNull() }
    val disabled = uiState.connectionQualityRestrictedByLea ||
        uiState.connectionQualityEnabled == false
    var pendingMode by remember { mutableStateOf<ConnectionQualityMode?>(null) }

    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        if (disabled) {
            // 无 enabled 参数：叠一层透明拦截，禁止打开选择菜单
            Box {
                OverlayDropdownPreference(
                    title = stringResource(R.string.connection_quality_title),
                    summary = stringResource(R.string.feature_unavailable_over_le_audio),
                    items = listOf(stringResource(R.string.connection_quality_unavailable)),
                    selectedIndex = 0,
                    onSelectedIndexChange = { },
                    modifier = Modifier.alpha(0.38f),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { },
                )
            }
        } else {
            val options = listOf(
                ConnectionQualityMode.SOUND_QUALITY_PRIOR to
                    stringResource(R.string.connection_quality_sound_prior),
                ConnectionQualityMode.CONNECTION_QUALITY_PRIOR to
                    stringResource(R.string.connection_quality_connection_prior),
            )
            OverlayDropdownPreference(
                title = stringResource(R.string.connection_quality_title),
                items = options.map { it.second },
                selectedIndex = options.indexOfFirst { it.first == current }.coerceAtLeast(0),
                onSelectedIndexChange = { index ->
                    val target = options.getOrNull(index)?.first ?: return@OverlayDropdownPreference
                    if (target != current) pendingMode = target
                },
            )
        }
    }

    pendingMode?.let { target ->
        ConnectionQualityConfirmDialog(
            target = target,
            onConfirm = {
                actions.onConnectionQualityChange(target)
                pendingMode = null
            },
            onCancel = { pendingMode = null },
        )
    }
}

/**
 * DSEE / DSEE Extreme (AUDIO-domain upscaling). Title and description mirror
 * Sound Connect's `UpsclType` strings, picked by the AUDIO_RET_CAPABILITY
 * generation byte — not by which FunctionType the support list advertised.
 */
@Composable
private fun UpscalingCard(
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        SwitchPreference(
            title = upscalingTitle(uiState.upscalingTypeCode),
            summary = upscalingDescription(uiState.upscalingTypeCode),
            checked = uiState.upscalingEnabled == true,
            onCheckedChange = actions.onUpscalingEnabledChange,
        )
    }
}

/**
 * 系统侧单设备 LDAC 开关。
 *
 * 读写都在蓝牙进程里走 A2DP profile service 自己的方法（用户偏好 + 编解码器优先级），
 * 与系统设置里那个勾选框是同一套状态，因此两边显示一致。开启方向先确认（同官方），
 * 关闭方向直接写入；写入后短暂置灰，等协商结果落定再显示真实状态。
 *
 * 卡片只在能力表列出 LDAC 时出现——也就是 A2DP 已连接且该设备确实支持。但音频走
 * LE Audio（LC3）时 A2DP 不再承载媒体，开关写下去也不生效；与系统设置一致，此时置灰
 * 并把 summary 换成「仅在通过 Classic Audio 连接时启用」，不允许操作（同连接质量卡片）。
 */
@Composable
private fun LdacCard(
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions,
) {
    var confirming by remember { mutableStateOf(false) }
    val leaActive = uiState.usingLeAudio
    val enabled = uiState.ldacEnabled == true

    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        SwitchPreference(
            title = stringResource(R.string.card_ldac_title),
            summary = when {
                leaActive -> stringResource(R.string.feature_unavailable_over_le_audio)
                uiState.ldacSwitching -> stringResource(R.string.ldac_switching)
                enabled -> stringResource(R.string.ldac_on)
                uiState.ldacEnabled == false -> stringResource(R.string.ldac_off)
                else -> stringResource(R.string.ldac_unavailable)
            },
            checked = enabled,
            onCheckedChange = { target ->
                if (target) confirming = true else actions.onLdacEnabledChange(false)
            },
            enabled = !uiState.ldacSwitching && !leaActive,
        )
    }

    if (confirming) {
        LdacEnableConfirmDialog(
            onConfirm = {
                confirming = false
                actions.onLdacEnabledChange(true)
            },
            onCancel = { confirming = false },
        )
    }
}

/** UpsclType → official title (DSEEHX_Title / DSEE_Title / DSEEHX_AI_Title / DSEEULT_Title). */
@Composable
private fun upscalingTitle(typeCode: Int): String = when (typeCode) {
    0 -> stringResource(R.string.dsee_hx)
    2 -> stringResource(R.string.dsee_extreme)
    3 -> stringResource(R.string.dsee_ultimate)
    else -> stringResource(R.string.dsee)
}

/** Official per-generation descriptions (SC `*_Description` resources). */
@Composable
private fun upscalingDescription(typeCode: Int): String = when (typeCode) {
    0 -> stringResource(R.string.dsee_hx_description)
    2, 3 -> stringResource(R.string.dsee_ultimate_description)
    else -> stringResource(R.string.dsee_description)
}

@Composable
private fun LeAudioCard(
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions,
    showLeAudioToggle: Boolean = true,
) {
    val enabled = uiState.leaStatus == "ENABLE"
    // Two witnesses, either conclusive: the stack routing this headset's LE Audio group, or the
    // headset reporting a bud streaming unicast. The headset's own report only arrives with a LEA
    // status notification, so waiting for it alone left this summary stuck on "等待系统建立 LC3".
    val usingLc3 = uiState.usingLeAudio


    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        SwitchPreference(
            title = stringResource(R.string.card_le_audio_title),
            summary = when {
                uiState.leAudioSwitchPending -> stringResource(R.string.lea_switch_pending)
                usingLc3 -> stringResource(R.string.lea_status_lc3)
                enabled -> stringResource(R.string.lea_status_classic)
                else -> stringResource(R.string.lea_status_classic_only)
            },
            checked = enabled,
            onCheckedChange = { target ->
                if (!uiState.leAudioSwitchPending) actions.onLeAudioEnabledChange(target)
            },
        )
        // Under LE Audio priority the phone side is a switch of its own — the system's
        // per-device LE Audio permission, read from and written to the stack that draws the
        // settings switch. It needs a bonded identity the LE Audio profile applies to; a headset
        // switched over inside Sound Connect may have none, because its LE identity is a separate,
        // non-discoverable one the system pairing screen never lists, and only then does the row
        // lead into the pairing flow. Shown while LC3 is in use as well, since turning it off is
        // the whole point of surfacing it.
        if (showLeAudioToggle && enabled && !uiState.leAudioSwitchPending) {
            val pairing = uiState.leAudioDevicePairStage == "SCANNING" ||
                uiState.leAudioDevicePairStage == "PAIRING"
            val policy = uiState.leAudioPolicyAllowed
            // Nothing bonded for the permission to apply to, so there is nothing to toggle yet.
            val needsPairing = uiState.leAudioIdentityAddress == null
            SwitchPreference(
                title = stringResource(R.string.card_le_audio_toggle_title),
                summary = when {
                    pairing -> uiState.leAudioDevicePairMessage.ifEmpty { stringResource(R.string.lea_pairing) }
                    uiState.leAudioDevicePairStage == "FAILED" ->
                        uiState.leAudioDevicePairMessage.ifEmpty { stringResource(R.string.lea_pair_failed_retry) }
                    policy == true && usingLc3 -> stringResource(R.string.lea_on_lc3)
                    policy == true -> stringResource(R.string.lea_on_waiting_lc3)
                    policy == false -> stringResource(R.string.lea_off_fallback)
                    needsPairing -> stringResource(R.string.lea_needs_reset)
                    else -> stringResource(R.string.lea_state_unknown)
                },
                checked = policy == true,
                onCheckedChange = { target ->
                    // Without a bond there is nothing to permit yet, so the row leads into the
                    // pairing flow instead. Only raise the guide; it is hosted at the top level,
                    // because resetting an in-ear model means putting the buds in the case, which
                    // disconnects them and tears this page down.
                    if (needsPairing) actions.onLeAudioPairingGuide() else actions.onLeAudioPolicyChange(target)
                },
                enabled = !pairing,
            )
        }
    }
}

@Composable
private fun BatteryStatusCard(uiState: SonyStateSnapshot) {
    Card(
        modifier = Modifier.padding(horizontal = 12.dp)
    ) {
        PodStatus(
            batteryParams = uiState.toBatteryParams(),
            single = uiState.toSinglePodParams(),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
        )
    }
}

@Composable
private fun AncControlCard(uiState: SonyStateSnapshot, actions: SonyDetailActions) {
    Card(
        modifier = Modifier.padding(horizontal = 12.dp)
    ) {
        AncSwitch(
            ancStatus = uiState.noiseControlMode,
            onAncModeChange = actions.onAncModeChange,
            ambientLevel = uiState.ambientLevel,
            onAmbientLevelChange = actions.onAmbientLevelChange,
            ambientVoiceMode = uiState.ambientVoiceMode,
            onAmbientVoiceModeChange = actions.onAmbientVoiceModeChange,
            autoWindNoiseSupported = uiState.supportsAutoWindNoiseReduction,
            windNoiseSupported = uiState.supportsWindNoiseReduction,
            windNoiseReductionEnabled = uiState.windNoiseReduction,
            onWindNoiseReductionChange = actions.onWindNoiseReductionChange,
            noiseAdaptiveSupported = uiState.supportsNoiseAdaptive,
            noiseAdaptiveEnabled = uiState.noiseAdaptiveEnabled,
            onNoiseAdaptiveChange = actions.onNoiseAdaptiveChange,
            noiseAdaptiveSensitivity = uiState.noiseAdaptiveSensitivityValue(),
            onNoiseAdaptiveSensitivityChange = actions.onNoiseAdaptiveSensitivityChange,
        )
    }
}

@Composable
private fun MultipointEntryCard(
    state: MultipointSnapshot,
    /** Connected over LE Audio, where the headset cannot hold a second device at all. */
    leAudioRestricted: Boolean,
    onOpen: () -> Unit,
) {
    // Mirrors the official dashboard card (SC `so.q` /
    // multipoint_shortcut_layout.xml): title + disclosure chevron above a
    // fixed slot list numbered 1..maxConnected; the playback-right holder
    // carries the sound indicator and empty slots render greyed-out.
    val slotCount = maxOf(state.maxConnectedDevices, 2)
    // During an optimistic toggle, only the switch should change on the
    // settings page. Keep this dashboard card on the pre-tap content state.
    val multipointDisabled = if (state.multipointTogglePending) {
        state.multipointEnabled == true
    } else {
        state.multipointEnabled == false
    }
    // Under LC3 there is nothing to open: the settings page holds one switch the headset would
    // refuse, so the card stops being a link rather than leading to a dead end.
    if (leAudioRestricted) {
        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            MultipointEntryContent(
                state = state,
                slotCount = slotCount,
                multipointDisabled = multipointDisabled,
                leAudioRestricted = true,
            )
        }
        return
    }
    Card(
        modifier = Modifier.padding(horizontal = 12.dp),
        showIndication = true,
        onClick = onOpen,
    ) {
        MultipointEntryContent(
            state = state,
            slotCount = slotCount,
            multipointDisabled = multipointDisabled,
            leAudioRestricted = false,
        )
    }
}

@Composable
private fun MultipointEntryContent(
    state: MultipointSnapshot,
    slotCount: Int,
    multipointDisabled: Boolean,
    leAudioRestricted: Boolean,
) {
    val contentAlpha = if (leAudioRestricted) 0.38f else 1f
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.mp_connect_two_title),
            modifier = Modifier.weight(1f).alpha(contentAlpha),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        // No chevron while restricted: the card is not a link then.
        if (!leAudioRestricted) {
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
    if (leAudioRestricted || multipointDisabled) {
        // 功能关闭：不显示两个未连接槽位，居中显示"关闭"（高度约两个槽位行）。
        // 通过 LE Audio 连接时同样占用这个位置，说明这不是用户关掉的，而是连接方式决定的。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (leAudioRestricted) R.string.feature_unavailable_over_le_audio else R.string.off
                ),
                modifier = Modifier.padding(horizontal = 16.dp).alpha(contentAlpha),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    } else {
        (1..slotCount).forEach { slot ->
            val device = state.connectedDevices.firstOrNull { it.connectedStatus == slot }
            val holdsPlayback = device != null && state.playbackRight > 0 &&
                device.connectedStatus == state.playbackRight
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .padding(bottom = if (slot == slotCount) 10.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.mp_slot_number, slot),
                    modifier = Modifier.widthIn(min = 22.dp),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (holdsPlayback) {
                    Icon(
                        imageVector = MiuixIcons.VolumeUp,
                        contentDescription = stringResource(R.string.cd_now_playing),
                        modifier = Modifier.size(18.dp).padding(end = 2.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.widthIn(min = 4.dp))
                }
                Text(
                    text = device?.name?.ifBlank { device.address } ?: stringResource(R.string.mp_not_connected),
                    fontSize = 14.sp,
                    color = if (device != null) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
        }
    }
}


@Composable
private fun EqCard(uiState: SonyStateSnapshot, actions: SonyDetailActions) {
    val presets = uiState.eqAvailablePresets.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(uiState.eqPreset).ifEmpty { listOf(EqPresetId.OFF) }
    val currentPreset = uiState.eqPreset

    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        OverlayDropdownPreference(
            title = stringResource(R.string.sony_eq_title),
            items = presets.map { it.localizedName() },
            selectedIndex = presets.indexOf(currentPreset).coerceAtLeast(0),
            onSelectedIndexChange = { actions.onEqPresetChange(presets[it]) }
        )

        // Band/Clear Bass writes always land on a user preset, so only surface the
        // fine-tune sliders once one of those presets is active.
        val fineTuneVisible = currentPreset in listOf(
            EqPresetId.CUSTOM,
            EqPresetId.USER_SETTING1,
            EqPresetId.USER_SETTING2,
        )
        if (fineTuneVisible) {
            if (uiState.eqHasClearBass) {
                LabeledLevelSlider(
                    label = stringResource(R.string.eq_clear_bass),
                    value = uiState.eqClearBass,
                    range = uiState.eqClearBassMin..uiState.eqClearBassMax,
                    onValueChange = actions.onClearBassChange,
                )
            }

            val bandSteps = uiState.eqBandSteps
            if (bandSteps.isNotEmpty()) {
                val bandRange = uiState.eqBandMin..uiState.eqBandMax
                bandSteps.forEachIndexed { index, step ->
                    LabeledLevelSlider(
                        label = uiState.eqBandLabels.getOrNull(index)
                            ?: stringResource(R.string.eq_band_fmt, index + 1),
                        value = step,
                        range = bandRange,
                        onValueChange = { actions.onCustomEqBandChange(index, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledLevelSlider(
    label: String,
    value: Int?,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    val effective = (value ?: 0).coerceIn(range.first, range.last)
    var dragging by remember { mutableStateOf(false) }
    var draggingValue by remember { mutableFloatStateOf(effective.toFloat()) }
    // Follow device-reported value unless the user is mid-drag; commit once on
    // drag end to avoid flooding the Tandem transport.
    LaunchedEffect(effective) {
        if (!dragging) draggingValue = effective.toFloat()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = draggingValue.toInt().toString(),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = draggingValue,
            onValueChange = { newValue: Float ->
                dragging = true
                draggingValue = newValue
            },
            onValueChangeFinished = {
                dragging = false
                val rounded = draggingValue.toInt().coerceIn(range.first, range.last)
                if (rounded != effective) {
                    onValueChange(rounded)
                }
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}

@Composable
private fun PlaybackCard(uiState: SonyStateSnapshot, actions: SonyDetailActions) {
    // Tandem playback data (metadata / volume) present → official card form;
    // otherwise the media-key fallback form: title + status text + buttons.
    val tandemMode = uiState.playbackMusicVolumeStep > 0 ||
        uiState.playbackTrack != null ||
        uiState.playbackArtist != null ||
        uiState.playbackAlbum != null
    val playing = uiState.playbackStatus == PlaybackStatus.PLAYING
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.playback_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (!tandemMode) {
                    Text(
                        text = when (uiState.playbackStatus) {
                            PlaybackStatus.PLAYING -> stringResource(R.string.playback_playing)
                            PlaybackStatus.PAUSED -> stringResource(R.string.playback_paused)
                            else -> "—"
                        },
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // Official layout row 1: song info (left) + transport buttons (right).
            // Once Tandem playback data exists the name block is always drawn:
            // empty slots degrade to "unknown" text, so a stopped or unsettled
            // session never collapses into blank space.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tandemMode) {
                    PlaybackMetadata(
                        uiState = uiState,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                // 连接质量切换的重连窗口内播放控制置灰（官方以引导页/进度框接管，
                // 语义等价：此期间传输按钮不可用）。
                val transportLocked = uiState.connectionQualitySwitching
                val transportAlpha = if (transportLocked) 0.38f else 1f
                IconButton(
                    onClick = actions.onPlaybackPrevious.takeIf { !transportLocked } ?: {},
                    modifier = Modifier.alpha(transportAlpha),
                ) {
                    Icon(
                        imageVector = AppIcons.SkipPrevious,
                        contentDescription = stringResource(R.string.playback_previous),
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(
                    onClick = actions.onPlaybackPlayPause.takeIf { !transportLocked } ?: {},
                    modifier = Modifier.alpha(transportAlpha),
                ) {
                    Icon(
                        imageVector = if (playing) AppIcons.Pause else AppIcons.Play,
                        contentDescription = stringResource(R.string.playback_play_pause),
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(
                    onClick = actions.onPlaybackNext.takeIf { !transportLocked } ?: {},
                    modifier = Modifier.alpha(transportAlpha),
                ) {
                    Icon(
                        imageVector = AppIcons.SkipNext,
                        contentDescription = stringResource(R.string.playback_next),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // Official layout row 2: volume icon + slider.
            if (uiState.playbackMusicVolumeStep > 0) {
                PlaybackVolumeRow(
                    volume = uiState.playbackMusicVolume,
                    step = uiState.playbackMusicVolumeStep,
                    onVolumeChange = actions.onPlaybackVolumeChange,
                )
            }
        }
    }
}

@Composable
private fun PlaybackMetadata(
    uiState: SonyStateSnapshot,
    modifier: Modifier = Modifier,
) {
    val track = playbackName(uiState.playbackTrack, R.string.playback_unknown_track)
    val artist = playbackName(uiState.playbackArtist, R.string.playback_unknown_artist)
    val album = playbackName(uiState.playbackAlbum, R.string.playback_unknown_album)
    Column(modifier = modifier) {
        Text(
            text = track,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.basicMarquee(),
        )
        Text(
            text = artist,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = album,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Hard guarantee: every name slot draws either real text or the unknown
 * placeholder — null, empty and whitespace inputs all degrade to unknown,
 * so no upstream state can ever collapse a row into blank space. */
@Composable
private fun playbackName(value: String?, unknownRes: Int): String = when {
    value.isNullOrBlank() -> stringResource(unknownRes)
    else -> value
}

/**
 * Live sound-quality badges overlaid on the blank band below the headset
 * picture: the codec the link is using right now plus the DSEE mark while it
 * is actively processing. Both are hidden for anything the headset has not
 * confirmed — UNSETTLED/OTHER codecs, an unknown generation, or a DSEE status
 * that is not VALID (`u60.a`). The composable measures nothing when empty and,
 * when placed inside the picture item's Box, adds zero layout height — the
 * image-to-battery spacing stays identical whether badges show or not.
 */
@Composable
private fun SoundQualityBadges(uiState: SonyStateSnapshot, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val leaStreaming = uiState.leaStreamingStatusL == LE_AUDIO_UNICAST ||
        uiState.leaStreamingStatusR == LE_AUDIO_UNICAST
    val leaRes = if (leaStreaming) {
        if (dark) R.drawable.a_mdr_connection_leaudio_dark else R.drawable.a_mdr_connection_leaudio_light
    } else {
        null
    }
    val codecRes = uiState.soundQualityCodec?.let { codecBadgeRes(it, dark) }
    val dseeRes = uiState.dseeGeneration?.takeIf { uiState.dseeActive }?.let { dseeBadgeRes(it, dark) }
    if (leaRes == null && codecRes == null && dseeRes == null) {
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Official order (`big_header_view.xml`): LE Audio first, then codec, then DSEE.
        leaRes?.let { res -> BadgeImage(res, scale = BADGE_SCALE) }
        codecRes?.let { res -> BadgeImage(res, scale = BADGE_SCALE) }
        dseeRes?.let { res -> BadgeImage(res, scale = BADGE_SCALE) }
    }
}

/** 1f = the official 18dp badge height; bump this one number to scale all three. */
private const val BADGE_SCALE = 1.0f

/** Every badge draws at the official 18dp height (`big_header_view.xml`) times
 * [BADGE_SCALE], with the width from its own asset's pixel aspect ratio — that
 * reproduces the official sizes exactly (LE Audio's 112x36 asset lands on the
 * layout's fixed 56x18dp). `intrinsicSize` is raw pixels: using it as dp
 * directly rendered 3x-small on xxhdpi devices and row-filling on others. */
private val OFFICIAL_BADGE_HEIGHT = 18.dp

@Composable
private fun BadgeImage(res: Int, scale: Float) {
    val painter = painterResource(res)
    val intrinsic = painter.intrinsicSize
    val height = OFFICIAL_BADGE_HEIGHT * scale
    val width = if (intrinsic.width > 0f && intrinsic.height > 0f) {
        height * (intrinsic.width / intrinsic.height)
    } else {
        Dp.Unspecified
    }
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(width = width, height = height),
    )
}

/** StreamingStatus value the LE Audio badge lights up for (SC `LEAudioIcon`). */
private const val LE_AUDIO_UNICAST = "VIA_LE_AUDIO_UNICAST"

/** SC `a_mdr_codec_*`; UNSETTLED/OTHER have no official badge. */
private fun codecBadgeRes(codec: SoundQualityCodec, dark: Boolean): Int? =
    when (codec) {
        SoundQualityCodec.SBC ->
            if (dark) R.drawable.a_mdr_codec_sbc_dark else R.drawable.a_mdr_codec_sbc_light
        SoundQualityCodec.AAC ->
            if (dark) R.drawable.a_mdr_codec_aac_dark else R.drawable.a_mdr_codec_aac_light
        SoundQualityCodec.LDAC ->
            if (dark) R.drawable.a_mdr_codec_ldac_dark else R.drawable.a_mdr_codec_ldac_light
        SoundQualityCodec.APT_X ->
            if (dark) R.drawable.a_mdr_codec_aptx_dark else R.drawable.a_mdr_codec_aptx_light
        SoundQualityCodec.APT_X_HD ->
            if (dark) R.drawable.a_mdr_codec_aptxhd_dark else R.drawable.a_mdr_codec_aptxhd_light
        SoundQualityCodec.LC3 ->
            if (dark) R.drawable.a_mdr_codec_lc3_dark else R.drawable.a_mdr_codec_lc3_light
        SoundQualityCodec.UNSETTLED, SoundQualityCodec.OTHER -> null
    }

/** SC `a_mdr_dsee*` — one mark per DSEE generation. */
private fun dseeBadgeRes(generation: DseeGeneration, dark: Boolean): Int =
    when (generation) {
        DseeGeneration.DSEE_HX ->
            if (dark) R.drawable.a_mdr_dseehx_dark else R.drawable.a_mdr_dseehx_light
        DseeGeneration.DSEE ->
            if (dark) R.drawable.a_mdr_dsee_dark else R.drawable.a_mdr_dsee_light
        DseeGeneration.DSEE_HX_AI ->
            if (dark) R.drawable.a_mdr_dseehx_ai_dark else R.drawable.a_mdr_dseehx_ai_light
        DseeGeneration.DSEE_ULTIMATE ->
            if (dark) R.drawable.a_mdr_dsee_ult_dark else R.drawable.a_mdr_dsee_ult_light
    }

@Composable
private fun PlaybackVolumeRow(
    volume: Int?,
    step: Int,
    onVolumeChange: (Int) -> Unit,
) {
    val effective = (volume ?: 0).coerceIn(0, step - 1)
    var dragging by remember { mutableStateOf(false) }
    var draggingValue by remember { mutableFloatStateOf(effective.toFloat()) }
    // Follow device-reported volume unless the user is mid-drag; commit once on
    // drag end (same pattern as the ambient-level slider).
    LaunchedEffect(effective) {
        if (!dragging) draggingValue = effective.toFloat()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.VolumeUp,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(20.dp),
        )
        Slider(
            value = draggingValue,
            onValueChange = { newValue: Float ->
                dragging = true
                draggingValue = newValue
            },
            onValueChangeFinished = {
                dragging = false
                val rounded = draggingValue.toInt().coerceIn(0, step - 1)
                if (rounded != effective) {
                    onVolumeChange(rounded)
                }
            },
            valueRange = 0f..(step - 1).toFloat(),
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
    }
}

@Composable
private fun DeviceStatusCard(uiState: SonyStateSnapshot) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        BasicComponent(
            title = stringResource(R.string.firmware_version),
            endActions = {
                Text(
                    text = uiState.firmwareVersion ?: "—",
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            },
        )
    }
}
