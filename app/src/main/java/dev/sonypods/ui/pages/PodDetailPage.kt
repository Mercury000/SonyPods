package dev.sonypods.ui.pages

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.PlaybackStatus
import com.mercury.sonypods.R
import dev.sonypods.ui.SonyDetailActions
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            painter = rememberPodImagePainter(boxImagePath, boxImageRevision),
                            contentDescription = "Earphones",
                            modifier = Modifier
                                .fillMaxWidth(0.62f)
                                .widthIn(max = 280.dp),
                            contentScale = ContentScale.FillWidth
                        )
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
            Image(
                painter = rememberPodImagePainter(boxImagePath, boxImageRevision),
                contentDescription = "Earphones",
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .padding(vertical = 16.dp),
                contentScale = ContentScale.FillWidth
            )
        }

        podControlItems(
            uiState = uiState,
            actions = actions,
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
    } ?: painterResource(R.drawable.img_box)
}

private fun LazyListScope.podControlItems(
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions,
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

    if (includeAnc) {
        item {
            AncControlCard(uiState = uiState, actions = actions)
        }
    }

    item {
        EqCard(uiState = uiState, actions = actions)
    }

    item {
        PlaybackCard(uiState = uiState, actions = actions)
    }

    if (uiState.supportsLeAudio) {
        item {
            LeAudioCard(uiState = uiState, actions = actions)
        }
    }

    if (uiState.supportsGestureOperations) {
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                BasicComponent(
                    title = "手势操作",
                    summary = if (uiState.gestureOperationKeys.isEmpty()) "读取耳机支持的触控与按键操作" else "自定义触控、按键和面部轻击操作",
                    onClick = actions.onOpenGestureOperations,
                )
            }
        }
    }

    if (uiState.supportsMultipoint) {
        item {
            MultipointEntryCard(
                state = uiState.multipoint,
                leAudioRestricted = uiState.connectedViaLeAudio,
                onOpen = actions.onOpenMultipointSettings,
            )
        }
    }

    if (includeDeviceStatus) {
        item {
            DeviceStatusCard(uiState = uiState)
        }
    }

    item {
        Spacer(modifier = Modifier.height(bottomContentPadding))
    }
}

@Composable
private fun LeAudioCard(
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions,
) {
    val enabled = uiState.leaStatus == "ENABLE"
    // Two witnesses, either conclusive: the stack routing this headset's LE Audio group, or the
    // headset reporting a bud streaming unicast. The headset's own report only arrives with a LEA
    // status notification, so waiting for it alone left this summary stuck on "等待系统建立 LC3".
    val usingLc3 = uiState.usingLeAudio


    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        SwitchPreference(
            title = "LE Audio",
            summary = when {
                uiState.leAudioSwitchPending -> "正在更改 LE Audio"
                usingLc3 -> "LE Audio优先，当前使用 LE Audio（LC3）"
                enabled -> "LE Audio优先，等待手机连接"
                else -> "仅经典音频"
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
        if (enabled && !uiState.leAudioSwitchPending) {
            val pairing = uiState.leAudioDevicePairStage == "SCANNING" ||
                uiState.leAudioDevicePairStage == "PAIRING"
            val policy = uiState.leAudioPolicyAllowed
            // Nothing bonded for the permission to apply to, so there is nothing to toggle yet.
            val needsPairing = uiState.leAudioIdentityAddress == null
            SwitchPreference(
                title = "低功耗音频",
                summary = when {
                    pairing -> uiState.leAudioDevicePairMessage.ifEmpty { "正在配对…" }
                    uiState.leAudioDevicePairStage == "FAILED" ->
                        uiState.leAudioDevicePairMessage.ifEmpty { "配对失败，点击重试" }
                    policy == true && usingLc3 -> "已开启，音频通过 LC3 传输"
                    policy == true -> "已开启，等待系统建立 LC3"
                    policy == false -> "已关闭，音频回落到经典蓝牙"
                    needsPairing -> "需要重置耳机进入配对模式，点击查看步骤"
                    else -> "未读取到系统开关状态"
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
            text = "同时连接2台设备",
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
                text = if (leAudioRestricted) "在通过 LE Audio 连接时，此功能不可用" else "关闭",
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
                    text = "$slot.",
                    modifier = Modifier.widthIn(min = 22.dp),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (holdsPlayback) {
                    Icon(
                        imageVector = MiuixIcons.VolumeUp,
                        contentDescription = "正在播放",
                        modifier = Modifier.size(18.dp).padding(end = 2.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.widthIn(min = 4.dp))
                }
                Text(
                    text = device?.name?.ifBlank { device.address } ?: "未连接",
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
        ?: EqPresetId.entries.toList()
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
                        label = uiState.eqBandLabels.getOrNull(index) ?: "Band ${index + 1}",
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
            // Metadata only while PLAY/PAUSE; a Spacer keeps the buttons pinned
            // right when it is hidden (STOP/UNSETTLED or fallback mode).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val showMetadata = tandemMode && uiState.playbackStatus in
                    setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED)
                if (showMetadata) {
                    PlaybackMetadata(
                        uiState = uiState,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                IconButton(onClick = actions.onPlaybackPrevious) {
                    Icon(
                        imageVector = AppIcons.SkipPrevious,
                        contentDescription = stringResource(R.string.playback_previous),
                        modifier = Modifier.size(24.dp),
                    )
                }
                IconButton(onClick = actions.onPlaybackPlayPause) {
                    Icon(
                        imageVector = if (playing) AppIcons.Pause else AppIcons.Play,
                        contentDescription = stringResource(R.string.playback_play_pause),
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(onClick = actions.onPlaybackNext) {
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
    if (track == null && artist == null && album == null) {
        Spacer(modifier = modifier)
        return
    }
    Column(modifier = modifier) {
        track?.let {
            Text(
                text = it,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.basicMarquee(),
            )
        }
        artist?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        album?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Official copy rules: SETTLED→text, NOTHING("")→"unknown" placeholder, null→row hidden. */
@Composable
private fun playbackName(value: String?, unknownRes: Int): String? = when {
    value == null -> null
    value.isEmpty() -> stringResource(unknownRes)
    else -> value
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
