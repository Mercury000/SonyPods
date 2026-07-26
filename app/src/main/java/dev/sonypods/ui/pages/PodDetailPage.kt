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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sonypods.data.SonyHeadphoneUiState
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.PlaybackStatus
import dev.sonypods.R
import dev.sonypods.ui.SonyDetailActions
import dev.sonypods.ui.components.AncSwitch
import dev.sonypods.ui.components.PodStatus
import dev.sonypods.ui.toBatteryParams
import dev.sonypods.ui.toSinglePodParams
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PodDetailPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    podName: String,
    uiState: SonyHeadphoneUiState,
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
                verticalArrangement = Arrangement.Center,
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
        horizontalAlignment = Alignment.CenterHorizontally
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
private fun rememberPodImagePainter(path: String?) = remember(path) {
    path?.let {
        runCatching { BitmapFactory.decodeFile(it) }
            .getOrNull()
            ?.let { bitmap -> BitmapPainter(bitmap.asImageBitmap()) }
    }
} ?: painterResource(R.drawable.img_box)

private fun LazyListScope.podControlItems(
    uiState: SonyHeadphoneUiState,
    actions: SonyDetailActions,
    bottomContentPadding: Dp
) {
    item {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            PodStatus(
                batteryParams = uiState.batteryState.toBatteryParams(),
                single = uiState.batteryState.toSinglePodParams(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
            )
        }
    }

    item {
        Card(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            AncSwitch(
                ancStatus = uiState.noiseControlState.controlMode,
                onAncModeChange = actions.onAncModeChange,
                ambientLevel = uiState.noiseControlState.ambientLevel,
                onAmbientLevelChange = actions.onAmbientLevelChange,
                ambientVoiceMode = uiState.noiseControlState.ambientVoiceMode,
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

    item {
        DeviceStatusCard(uiState = uiState)
    }

    item {
        Spacer(modifier = Modifier.height(bottomContentPadding))
    }
}

@Composable
private fun EqCard(uiState: SonyHeadphoneUiState, actions: SonyDetailActions) {
    val capability = uiState.eqUiCapability
    val presets = capability?.availablePresets?.takeIf { it.isNotEmpty() }
        ?: EqPresetId.entries.toList()
    val currentPreset = uiState.eqState.preset

    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        OverlayDropdownPreference(
            title = stringResource(R.string.sony_eq_title),
            summary = currentPreset?.displayName ?: stringResource(R.string.eq_unknown),
            items = presets.map { it.displayName },
            selectedIndex = presets.indexOf(currentPreset).coerceAtLeast(0),
            onSelectedIndexChange = { actions.onEqPresetChange(presets[it]) }
        )

        if (capability?.hasClearBass != false) {
            val clearBassRange = capability?.clearBassDisplayRange ?: (-10..10)
            LabeledLevelSlider(
                label = stringResource(R.string.eq_clear_bass),
                value = uiState.eqState.clearBass,
                range = clearBassRange,
                onValueChange = actions.onClearBassChange,
            )
        }

        val bandSteps = uiState.eqState.bandSteps
        if (bandSteps.isNotEmpty()) {
            val bandLabels = capability?.bandLabels.orEmpty()
            val bandRange = capability?.bandDisplayRange ?: (-10..10)
            bandSteps.forEachIndexed { index, step ->
                LabeledLevelSlider(
                    label = bandLabels.getOrNull(index) ?: "Band ${index + 1}",
                    value = step,
                    range = bandRange,
                    onValueChange = { actions.onCustomEqBandChange(index, it) },
                )
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
private fun PlaybackCard(uiState: SonyHeadphoneUiState, actions: SonyDetailActions) {
    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
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
private fun DeviceStatusCard(uiState: SonyHeadphoneUiState) {
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        BasicComponent(
            title = stringResource(R.string.firmware_version),
            summary = uiState.deviceInfo.firmwareVersion ?: "—",
        )
        BasicComponent(
            title = stringResource(R.string.wearing_status),
            summary = uiState.wearingState.status ?: "—",
        )
        BasicComponent(
            title = stringResource(R.string.lea_status),
            summary = uiState.leaState.enabled ?: "—",
        )
        BasicComponent(
            title = stringResource(R.string.quick_access),
            summary = listOfNotNull(
                uiState.quickAccessState.lrKeyFunction,
                uiState.quickAccessState.ncAmbKeyFunction,
            ).joinToString(" / ").ifEmpty { "—" },
        )
    }
}
