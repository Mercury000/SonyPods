package dev.sonypods.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mercury.sonypods.R
import dev.sonypods.config.ConfigManager
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Duration units selectable in [IslandDurationDialog]; multipliers to seconds. */
private val UNIT_MULTIPLIERS = intArrayOf(1, 60, 3600)

private fun rangeForUnit(unitIndex: Int): IntRange = when (unitIndex) {
    2 -> 1..24
    else -> 1..59
}

/** (amount, unitIndex) for a stored seconds value; largest exact unit wins. */
fun decomposeIslandDuration(seconds: Int): Pair<Int, Int> = when {
    seconds >= 3600 && seconds % 3600 == 0 -> (seconds / 3600).coerceIn(1, 24) to 2
    seconds >= 60 && seconds % 60 == 0 -> (seconds / 60).coerceIn(1, 59) to 1
    else -> seconds.coerceIn(1, 59) to 0
}

/**
 * islandTimeout is seconds on the wire (system requirement); the picker lets the
 * user choose 秒/分/时 and the module converts on confirm.
 */
@Composable
fun IslandDurationDialog(
    show: Boolean,
    currentSeconds: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initial = decomposeIslandDuration(currentSeconds)
    var amount by remember(show) { mutableIntStateOf(initial.first) }
    var unitIndex by remember(show) { mutableIntStateOf(initial.second) }
    val unitLabels = listOf(
        stringResource(R.string.duration_unit_second),
        stringResource(R.string.duration_unit_minute),
        stringResource(R.string.duration_unit_hour),
    )
    OverlayDialog(
        title = stringResource(R.string.island_duration_title),
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberPicker(
                value = amount,
                onValueChange = { amount = it },
                range = rangeForUnit(unitIndex),
                modifier = Modifier.weight(1f),
            )
            NumberPicker(
                value = unitIndex,
                onValueChange = { selected ->
                    unitIndex = selected
                    amount = amount.coerceIn(rangeForUnit(selected))
                },
                range = 0..2,
                label = { unitLabels[it] },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.confirm),
                onClick = {
                    onConfirm(
                        (amount * UNIT_MULTIPLIERS[unitIndex])
                            .coerceIn(1, ConfigManager.MAX_ISLAND_DURATION_SECONDS)
                    )
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
