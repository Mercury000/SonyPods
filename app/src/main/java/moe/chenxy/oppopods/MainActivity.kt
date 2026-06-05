package moe.chenxy.oppopods

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.ui.App
import moe.chenxy.oppopods.ui.AppLocale

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        AppLocale.rememberDeviceLocale(newBase)
        AppLocale.apply(newBase, newBase.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE).getInt("app_language", AppLocale.SYSTEM))
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val prefs = remember { getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
            val themeMode = remember { mutableStateOf(prefs.getInt("theme_mode", 0)) }
            val accentMode = remember { mutableStateOf(prefs.getInt("accent_mode", 0)) }
            val floatingBottomBar = remember { mutableStateOf(prefs.getBoolean("floating_bottom_bar", false)) }
            val blurBottomBar = remember { mutableStateOf(prefs.getBoolean("blur_bottom_bar", false)) }
            val appLanguage = remember { mutableStateOf(prefs.getInt("app_language", AppLocale.SYSTEM)) }
            val systemDark = isSystemInDarkTheme()
            val darkMode = when (themeMode.value) {
                1 -> false
                2 -> true
                else -> systemDark
            }

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )

                window.isNavigationBarContrastEnforced = false

                onDispose {}
            }

            App(
                themeMode = themeMode,
                onThemeModeChange = {
                    themeMode.value = it
                    prefs.edit().putInt("theme_mode", it).apply()
                },
                accentMode = accentMode,
                onAccentModeChange = {
                    accentMode.value = it
                    prefs.edit().putInt("accent_mode", it).apply()
                },
                floatingBottomBar = floatingBottomBar,
                onFloatingBottomBarChange = {
                    floatingBottomBar.value = it
                    prefs.edit().putBoolean("floating_bottom_bar", it).apply()
                },
                blurBottomBar = blurBottomBar,
                onBlurBottomBarChange = {
                    blurBottomBar.value = it
                    prefs.edit().putBoolean("blur_bottom_bar", it).apply()
                },
                appLanguage = appLanguage,
                onAppLanguageChange = {
                    appLanguage.value = it
                    prefs.edit().putInt("app_language", it).apply()
                },
            )
        }
    }
}
