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

    when (actualMode) {
        ColorSchemeMode.Light, ColorSchemeMode.Dark -> {
            val nightMode = if (actualMode == ColorSchemeMode.Dark)
                Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            val currentConfig = LocalConfiguration.current
            val overrideConfig = remember(currentConfig, nightMode) {
                Configuration(currentConfig).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                }
            }
            CompositionLocalProvider(LocalConfiguration provides overrideConfig) {
                MiuixTheme(controller = controller, content = content)
            }
        }
        else -> {
            MiuixTheme(controller = controller, content = content)
        }
    }
}
