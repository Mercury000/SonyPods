package dev.sonypods.ui.pages

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import dev.sonypods.ui.localizedName
import dev.sonypods.ui.components.PodStatus
import dev.sonypods.ui.toBatteryParams
import dev.sonypods.ui.toSinglePodParams
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import java.io.File

@Composable
fun PodDetailPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    podName: String,
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions = SonyDetailActions(),
    boxImagePath: String? = null,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (isLandscape) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = rememberPodImagePainter(boxImagePath),
                    contentDescription = "Earphones",
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .widthIn(max = 360.dp),
                    contentScale = ContentScale.FillWidth
                )
                Text(
                    text = podName,
                    modifier = Modifier.padding(top = 12.dp),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            LazyColumn(
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
                    bottomContentPadding = bottomContentPadding
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Image(
                painter = rememberPodImagePainter(boxImagePath),
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
private fun rememberPodImagePainter(path: String?): Painter {
    // The downloader intentionally reuses the per-device path. Include the
    // file metadata so replacing that file invalidates a painter that may have
    // already fallen back to the stock image during the download.
    val fileKey = path?.let {
        val file = File(it)
        "$it:${file.lastModified()}:${file.length()}"
    }
    return remember(fileKey) {
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
    bottomContentPadding: Dp
) {
    item {
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

    item {
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
            )
        }
    }

    item {
        EqCard(uiState = uiState, actions = actions)
    }

    item {
        PlaybackCard(uiState = uiState, actions = actions)
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
                onOpen = actions.onOpenMultipointSettings,
            )
        }
    }

    item {
        DeviceStatusCard(uiState = uiState)
    }

    item {
        Spacer(modifier = Modifier.height(bottomContentPadding))
    }
}

@Composable
private fun MultipointEntryCard(
    state: MultipointSnapshot,
    onOpen: () -> Unit,
) {
    // Mirrors the official dashboard card (SC `so.q` /
    // multipoint_shortcut_layout.xml): title + disclosure chevron above a
    // fixed slot list numbered 1..maxConnected; the playback-right holder
    // carries the sound indicator and empty slots render greyed-out.
    val slotCount = maxOf(state.maxConnectedDevices, 2)
    Card(
        modifier = Modifier.padding(horizontal = 12.dp),
        showIndication = true,
        onClick = onOpen,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "同时连接2台设备",
                modifier = Modifier.weight(1f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.playback_previous),
                    onClick = actions.onPlaybackPrevious,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.playback_play_pause),
                    onClick = actions.onPlaybackPlayPause,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.playback_next),
                    onClick = actions.onPlaybackNext,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DeviceStatusCard(uiState: SonyStateSnapshot) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        BasicComponent(
            title = stringResource(R.string.firmware_version),
            summary = uiState.firmwareVersion ?: "—",
        )
        BasicComponent(
            title = stringResource(R.string.lea_status),
            summary = uiState.leaStatus ?: "—",
        )
    }
}
