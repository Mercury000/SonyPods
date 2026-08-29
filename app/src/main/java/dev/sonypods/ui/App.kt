package dev.sonypods.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
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
    val backStack = rememberNavBackStack<Screen>(Screen.Main)

    AppLocale.Provider(language = appLanguage.value) {
        AppTheme(colorSchemeMode = colorSchemeMode, accentMode = accentMode.value) {
            MainUI(
                backStack = backStack,
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
                appLanguage = appLanguage,
                onAppLanguageChange = onAppLanguageChange,
                openEarphoneDetailAddress = openEarphoneDetailAddress,
                onExternalDetailRequestConsumed = onExternalDetailRequestConsumed,
            )
        }
    }
}
