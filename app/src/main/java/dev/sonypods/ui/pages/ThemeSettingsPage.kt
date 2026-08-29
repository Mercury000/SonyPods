package dev.sonypods.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mercury.sonypods.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun ThemeSettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {},
    accentMode: MutableState<Int> = mutableStateOf(0),
    onAccentModeChange: (Int) -> Unit = {},
    floatingBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onFloatingBottomBarChange: (Boolean) -> Unit = {},
    blurBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onBlurBottomBarChange: (Boolean) -> Unit = {},
    floatingBottomBarStyle: MutableState<Int> = mutableStateOf(0),
    onFloatingBottomBarStyleChange: (Int) -> Unit = {},
    blurTopBar: MutableState<Boolean> = mutableStateOf(false),
    onBlurTopBarChange: (Boolean) -> Unit = {},
) {
    val themeOptions = listOf(
        stringResource(R.string.theme_follow_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark),
    )
    val accentOptions = listOf(
        stringResource(R.string.color_default),
        stringResource(R.string.color_monet),
    )

    val floatingStyleOptions = listOf(
        stringResource(R.string.floating_bottom_bar_style_default),
        stringResource(R.string.floating_bottom_bar_style_ios),
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp,
        ),
    ) {
        item {
            Card {
                OverlayDropdownPreference(
                    title = stringResource(R.string.theme_title),
                    items = themeOptions,
                    selectedIndex = themeMode.value.coerceIn(themeOptions.indices),
                    onSelectedIndexChange = { onThemeModeChange(it) },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.theme_color),
                    summary = stringResource(R.string.theme_color_summary),
                    items = accentOptions,
                    selectedIndex = accentMode.value.coerceIn(accentOptions.indices),
                    onSelectedIndexChange = { onAccentModeChange(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.floating_bottom_bar),
                    summary = stringResource(R.string.floating_bottom_bar_summary),
                    checked = floatingBottomBar.value,
                    onCheckedChange = { onFloatingBottomBarChange(it) },
                )
                if (floatingBottomBar.value) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.floating_bottom_bar_style),
                        items = floatingStyleOptions,
                        selectedIndex = floatingBottomBarStyle.value.coerceIn(floatingStyleOptions.indices),
                        onSelectedIndexChange = { onFloatingBottomBarStyleChange(it) },
                    )
                }
                SwitchPreference(
                    title = stringResource(R.string.blur_bottom_bar),
                    summary = stringResource(R.string.blur_bottom_bar_summary),
                    checked = blurBottomBar.value,
                    onCheckedChange = { onBlurBottomBarChange(it) },
                )
                SwitchPreference(
                    title = stringResource(R.string.blur_top_bar),
                    summary = stringResource(R.string.blur_top_bar_summary),
                    checked = blurTopBar.value,
                    onCheckedChange = { onBlurTopBarChange(it) },
                )
            }
        }
    }
}
