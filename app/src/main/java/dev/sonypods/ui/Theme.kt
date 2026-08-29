package dev.sonypods.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun AppTheme(
    colorSchemeMode: ColorSchemeMode = ColorSchemeMode.System,
    accentMode: Int = 0,
    content: @Composable () -> Unit
) {
    val actualMode = when (accentMode) {
        1 -> when (colorSchemeMode) {
            ColorSchemeMode.Light -> ColorSchemeMode.MonetLight
            ColorSchemeMode.Dark -> ColorSchemeMode.MonetDark
            else -> ColorSchemeMode.MonetSystem
        }
        else -> colorSchemeMode
    }
    val controller = remember(actualMode) { ThemeController(actualMode) }

    // One call site for [content], deliberately: branching between a provider-wrapped and a
    // bare MiuixTheme would move the whole subtree to a different composition slot every time
    // the mode crosses between "follow system" and an explicit light/dark, discarding all of
    // its remembered state — the UI would rebuild from scratch instead of just restyling.
    val currentConfig = LocalConfiguration.current
    val nightMode = when (actualMode) {
        ColorSchemeMode.Light -> Configuration.UI_MODE_NIGHT_NO
        ColorSchemeMode.Dark -> Configuration.UI_MODE_NIGHT_YES
        else -> null
    }
    val providedConfig = remember(currentConfig, nightMode) {
        if (nightMode == null) {
            currentConfig
        } else {
            Configuration(currentConfig).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            }
        }
    }
    CompositionLocalProvider(LocalConfiguration provides providedConfig) {
        MiuixTheme(controller = controller, content = content)
    }
}
