package dev.sonypods.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.sonypods.R
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.ui.SonyDetailActions
import dev.sonypods.ui.localizedName
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun EqDetailPage(
    modifier: Modifier = Modifier,
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val presets = uiState.eqAvailablePresets.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(uiState.eqPreset).ifEmpty { listOf(EqPresetId.OFF) }
    val currentPreset = uiState.eqPreset

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.sony_eq_title),
                    items = presets.map { it.localizedName() },
                    selectedIndex = presets.indexOf(currentPreset).coerceAtLeast(0),
                    onSelectedIndexChange = { actions.onEqPresetChange(presets[it]) },
                )
            }
        }

        if (uiState.eqHasClearBass) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    EqSliderItem(
                        label = stringResource(R.string.eq_clear_bass),
                        value = uiState.eqClearBass,
                        range = uiState.eqClearBassMin..uiState.eqClearBassMax,
                        onValueChange = actions.onClearBassChange,
                    )
                }
            }
        }

        val bandSteps = uiState.eqBandSteps
        if (bandSteps.isNotEmpty()) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    bandSteps.forEachIndexed { index, step ->
                        EqSliderItem(
                            label = uiState.eqBandLabels.getOrNull(index)
                                ?: stringResource(R.string.eq_band_fmt, index + 1),
                            value = step,
                            range = uiState.eqBandMin..uiState.eqBandMax,
                            onValueChange = { actions.onCustomEqBandChange(index, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EqSliderItem(
    label: String,
    value: Int?,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    val effective = (value ?: 0).coerceIn(range.first, range.last)
    var dragging by remember { mutableStateOf(false) }
    var draggingValue by remember { mutableFloatStateOf(effective.toFloat()) }

    LaunchedEffect(effective) {
        if (!dragging) draggingValue = effective.toFloat()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
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
