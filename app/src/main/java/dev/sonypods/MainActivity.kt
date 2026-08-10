package dev.sonypods

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import dev.sonypods.config.ConfigManager
import dev.sonypods.ui.App
import dev.sonypods.ui.AppLocale
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction

class MainActivity : ComponentActivity() {
    private val openEarphoneDetailAddress = mutableStateOf<String?>(null)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Permissions only gate the UI; the engine runs in the bluetooth process.
        }

    override fun attachBaseContext(newBase: Context) {
        AppLocale.rememberDeviceLocale(newBase)
        AppLocale.apply(newBase, newBase.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE).getInt("app_language", AppLocale.SYSTEM))
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleNavigationIntent(intent)

        val requiredPermissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }

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
                openEarphoneDetailAddress = openEarphoneDetailAddress,
                onExternalDetailRequestConsumed = { openEarphoneDetailAddress.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent?) {
        if (intent?.action != SonyPodsAction.ACTION_OPEN_EARPHONE_DETAIL) return
        openEarphoneDetailAddress.value = intent.getStringExtra(SonyPodsAction.EXTRA_TARGET_DEVICE_ADDRESS)
    }
}
