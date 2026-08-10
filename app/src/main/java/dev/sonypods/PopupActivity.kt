package dev.sonypods
import com.mercury.sonypods.R

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.SonyRemoteState
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.protocol.NoiseAdaptiveSensitivity
import dev.sonypods.protocol.NoiseControlMode
import kotlinx.coroutines.delay
import dev.sonypods.config.ConfigManager
import dev.sonypods.ui.AppLocale
import dev.sonypods.ui.AppTheme
import dev.sonypods.ui.components.AncSwitch
import dev.sonypods.ui.components.PodStatus
import dev.sonypods.ui.displayName
import dev.sonypods.ui.noiseAdaptiveSensitivityValue
import dev.sonypods.ui.toBatteryParams
import dev.sonypods.ui.toSinglePodParams
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class PopupActivity : ComponentActivity() {
    private var surfacesReplayScheduled = false

    override fun attachBaseContext(newBase: Context) {
        AppLocale.rememberDeviceLocale(newBase)
        val language = newBase.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("app_language", AppLocale.SYSTEM)
        super.attachBaseContext(AppLocale.apply(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val appConfig = ConfigManager.refreshFromPrefs(prefs)
        val bluetoothDevice = intent.parcelableDevice("android.bluetooth.device.extra.DEVICE")
        if (appConfig.notificationClickAction != ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP) {
            openNotificationTarget(appConfig.notificationClickAction, bluetoothDevice)
            finish()
            return
        }

        setContent {
            val colorSchemeMode = when (prefs.getInt("theme_mode", 0)) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
            AppTheme(colorSchemeMode = colorSchemeMode, accentMode = prefs.getInt("accent_mode", 0)) {
                PopupContent(
                    onMore = {
                        val latestConfig = ConfigManager.refreshFromPrefs(prefs)
                        openMoreTarget(latestConfig.moreClickAction, bluetoothDevice)
                        finish()
                    },
                    onDone = { finish() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scheduleSurfacesReplay()
    }

    override fun onStop() {
        super.onStop()
        // Pulling the island down puts this activity in HyperOS' mini-window.  In
        // that path the activity can become stopped without being marked finishing
        // (and sometimes without a useful onDestroy callback).  The island has
        // already been consumed by the time the activity leaves the foreground, so
        // this is the earliest lifecycle point at which it is safe to restore it.
        scheduleSurfacesReplay()
    }

    private fun scheduleSurfacesReplay() {
        if (isChangingConfigurations || surfacesReplayScheduled) return
        surfacesReplayScheduled = true
        // HyperOS consumes the island notification when its content action launches
        // this activity. Re-publish after the mini-window transition has completed;
        // FocusIslandUtil then performs the required remove -> add notification
        // cycle instead of merely updating the hidden notification record.
        Handler(Looper.getMainLooper()).postDelayed({
            SonyBridge.sendCommand(applicationContext, SonyBridge.CMD_SURFACES_READY) {
                putExtra(SonyBridge.EXTRA_ISLAND_FIRST_FLOAT, false)
            }
        }, 350L)
    }

    private fun openNotificationTarget(action: Int, bluetoothDevice: BluetoothDevice?) {
        when (action) {
            ConfigManager.NOTIFICATION_CLICK_SYSTEM_SETTINGS -> openSystemSettings(bluetoothDevice)
            else -> openModule()
        }
    }

    private fun openMoreTarget(action: Int, bluetoothDevice: BluetoothDevice?) {
        when (action) {
            ConfigManager.MORE_CLICK_SYSTEM_SETTINGS -> openSystemSettings(bluetoothDevice)
            else -> openModule()
        }
    }

    private fun openModule() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    @SuppressLint("MissingPermission")
    private fun openSystemSettings(bluetoothDevice: BluetoothDevice?) {
        if (bluetoothDevice == null) {
            openModule()
            return
        }
        val intent = Intent().apply {
            setClassName("com.android.settings", "com.android.settings.bluetooth.MiuiHeadsetActivity")
            putExtra("android.bluetooth.device.extra.DEVICE", bluetoothDevice)
            putExtra("bluetoothaddress", bluetoothDevice.address)
            putExtra("MIUI_HEADSET_SUPPORT", ConfigManager.fakeSupport())
            putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
            putExtra("DEVICE_ID", ConfigManager.fakeDeviceId())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure { openModule() }
    }

    private fun Intent.parcelableDevice(key: String): BluetoothDevice? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }
}

@Composable
private fun PopupContent(onMore: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val showDialog = remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val themeMode = remember { prefs.getInt("theme_mode", 0) }
    val systemDark = isSystemInDarkTheme()
    val isDarkMode = when (themeMode) {
        1 -> false
        2 -> true
        else -> systemDark
    }

        LaunchedEffect(Unit) { SonyRemoteState.start(context) }
    val sonyState by SonyRemoteState.state.collectAsState()

    LaunchedEffect(sonyState.connected) {
        if (sonyState.connected && !showDialog.value) {
            showDialog.value = true
        }
    }

    // Timeout fallback: show dialog even if not connected within 500ms.
    // Periodic refresh: poll the headphones every 15s while the popup is open.
    LaunchedEffect(Unit) {
        delay(500)
        if (!showDialog.value) showDialog.value = true

        while (true) {
            delay(15_000)
            SonyBridge.sendCommand(context, SonyBridge.CMD_REFRESH)
        }
    }

    val dialogBgColor = if (isDarkMode) Color(0xFF1A1A1A) else Color(0xFFF7F7F7)
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(containerColor = Color.Transparent) { _ ->
        OverlayDialog(
            title = sonyState.displayName.ifEmpty { stringResource(R.string.app_name) },
            show = showDialog.value,
            backgroundColor = dialogBgColor,
            onDismissRequest = {
                showDialog.value = false
            },
            onDismissFinished = {
                onDone()
            }
        ) {
            if (isLandscape) {
                LandscapePopupBody(
                    sonyState = sonyState,
                    onAncModeChange = { SonyBridge.setNoiseControl(context, it) },
                    onAmbientLevelChange = { SonyBridge.setAmbientLevel(context, it) },
                    onAmbientVoiceModeChange = { SonyBridge.setAmbientVoice(context, it) },
                    onNoiseAdaptiveChange = { SonyBridge.setNoiseAdaptive(context, it) },
                    onNoiseAdaptiveSensitivityChange = { SonyBridge.setNoiseAdaptiveSensitivity(context, it.name) },
                    onMore = onMore,
                    onDone = { showDialog.value = false },
                )
            } else {
                PortraitPopupBody(
                    sonyState = sonyState,
                    onAncModeChange = { SonyBridge.setNoiseControl(context, it) },
                    onAmbientLevelChange = { SonyBridge.setAmbientLevel(context, it) },
                    onAmbientVoiceModeChange = { SonyBridge.setAmbientVoice(context, it) },
                    onNoiseAdaptiveChange = { SonyBridge.setNoiseAdaptive(context, it) },
                    onNoiseAdaptiveSensitivityChange = { SonyBridge.setNoiseAdaptiveSensitivity(context, it.name) },
                    onMore = onMore,
                    onDone = { showDialog.value = false },
                )
            }
        }
    }
}

@Composable
private fun PortraitPopupBody(
    sonyState: SonyStateSnapshot,
    onAncModeChange: (NoiseControlMode) -> Unit,
    onAmbientLevelChange: (Int) -> Unit,
    onAmbientVoiceModeChange: (Boolean) -> Unit,
    onNoiseAdaptiveChange: (Boolean) -> Unit,
    onNoiseAdaptiveSensitivityChange: (NoiseAdaptiveSensitivity) -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            PodStatus(
                batteryParams = sonyState.toBatteryParams(),
                single = sonyState.toSinglePodParams(),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            AncSwitch(
                ancStatus = sonyState.noiseControlMode,
                onAncModeChange = onAncModeChange,
                ambientLevel = sonyState.ambientLevel,
                onAmbientLevelChange = onAmbientLevelChange,
                ambientVoiceMode = sonyState.ambientVoiceMode,
                onAmbientVoiceModeChange = onAmbientVoiceModeChange,
                noiseAdaptiveSupported = sonyState.supportsNoiseAdaptive,
                noiseAdaptiveEnabled = sonyState.noiseAdaptiveEnabled,
                onNoiseAdaptiveChange = onNoiseAdaptiveChange,
                noiseAdaptiveSensitivity = sonyState.noiseAdaptiveSensitivityValue(),
                onNoiseAdaptiveSensitivityChange = onNoiseAdaptiveSensitivityChange,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                text = stringResource(R.string.more),
                onClick = onMore,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                text = stringResource(R.string.done),
                onClick = onDone,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LandscapePopupBody(
    sonyState: SonyStateSnapshot,
    onAncModeChange: (NoiseControlMode) -> Unit,
    onAmbientLevelChange: (Int) -> Unit,
    onAmbientVoiceModeChange: (Boolean) -> Unit,
    onNoiseAdaptiveChange: (Boolean) -> Unit,
    onNoiseAdaptiveSensitivityChange: (NoiseAdaptiveSensitivity) -> Unit,
    onMore: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(min = 560.dp)
            .height(240.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(0.60f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                PodStatus(
                    batteryParams = sonyState.toBatteryParams(),
                    single = sonyState.toSinglePodParams(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    compact = true
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                AncSwitch(
                    ancStatus = sonyState.noiseControlMode,
                    onAncModeChange = onAncModeChange,
                    ambientLevel = sonyState.ambientLevel,
                    onAmbientLevelChange = onAmbientLevelChange,
                    ambientVoiceMode = sonyState.ambientVoiceMode,
                    onAmbientVoiceModeChange = onAmbientVoiceModeChange,
                    noiseAdaptiveSupported = sonyState.supportsNoiseAdaptive,
                    noiseAdaptiveEnabled = sonyState.noiseAdaptiveEnabled,
                    onNoiseAdaptiveChange = onNoiseAdaptiveChange,
                    noiseAdaptiveSensitivity = sonyState.noiseAdaptiveSensitivityValue(),
                    onNoiseAdaptiveSensitivityChange = onNoiseAdaptiveSensitivityChange,
                    compact = true,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(0.40f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            TextButton(
                text = stringResource(R.string.more),
                onClick = onMore,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(
                text = stringResource(R.string.done),
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
