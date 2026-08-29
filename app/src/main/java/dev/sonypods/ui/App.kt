package dev.sonypods.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

@Composable
fun App(
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {},
    accentMode: MutableState<Int> = mutableStateOf(0),
    onAccentModeChange: (Int) -> Unit = {},
    floatingBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onFloatingBottomBarChange: (Boolean) -> Unit = {},
    floatingBottomBarStyle: MutableState<Int> = mutableStateOf(0),
    onFloatingBottomBarStyleChange: (Int) -> Unit = {},
    blurBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onBlurBottomBarChange: (Boolean) -> Unit = {},
    blurTopBar: MutableState<Boolean> = mutableStateOf(false),
    onBlurTopBarChange: (Boolean) -> Unit = {},
    predictiveBack: MutableState<Boolean> = mutableStateOf(true),
    onPredictiveBackChange: (Boolean) -> Unit = {},
    predictiveBackDistance: MutableState<Int> = mutableStateOf(50),
    onPredictiveBackDistanceChange: (Int) -> Unit = {},
    appLanguage: MutableState<Int> = mutableStateOf(AppLocale.SYSTEM),
    onAppLanguageChange: (Int) -> Unit = {},
    openEarphoneDetailAddress: MutableState<String?> = mutableStateOf(null),
    onExternalDetailRequestConsumed: () -> Unit = {},
) {
    val colorSchemeMode = when (themeMode.value) {
        1 -> ColorSchemeMode.Light
        2 -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }

    AppLocale.Provider(language = appLanguage.value) {
        AppTheme(colorSchemeMode = colorSchemeMode, accentMode = accentMode.value) {
            MainUI(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                accentMode = accentMode,
                onAccentModeChange = onAccentModeChange,
                floatingBottomBar = floatingBottomBar,
                onFloatingBottomBarChange = onFloatingBottomBarChange,
                floatingBottomBarStyle = floatingBottomBarStyle,
                onFloatingBottomBarStyleChange = onFloatingBottomBarStyleChange,
                blurBottomBar = blurBottomBar,
                onBlurBottomBarChange = onBlurBottomBarChange,
                blurTopBar = blurTopBar,
                onBlurTopBarChange = onBlurTopBarChange,
                predictiveBack = predictiveBack,
                onPredictiveBackChange = onPredictiveBackChange,
                predictiveBackDistance = predictiveBackDistance,
                onPredictiveBackDistanceChange = onPredictiveBackDistanceChange,
                appLanguage = appLanguage,
                onAppLanguageChange = onAppLanguageChange,
                openEarphoneDetailAddress = openEarphoneDetailAddress,
                onExternalDetailRequestConsumed = onExternalDetailRequestConsumed,
            )
        }
    }
}
