package dev.sonypods.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.SonyRemoteState
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.GestureNoiseControlMode
import dev.sonypods.SonyPodsApp
import com.mercury.sonypods.R
import dev.sonypods.config.ConfigManager
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.ui.pages.AboutPage
import dev.sonypods.ui.pages.TandemDebugPage
import dev.sonypods.ui.pages.ThemeSettingsPage
import dev.sonypods.utils.RootManager
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val CONNECT_TIMEOUT_MS = 25_000L

sealed interface Screen : NavKey {
    data object Main : Screen
    data object About : Screen
    data object Theme : Screen
    data object TandemDebug : Screen
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainUI(
    backStack: SnapshotStateList<Screen>,
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {},
    accentMode: MutableState<Int> = mutableStateOf(0),
    onAccentModeChange: (Int) -> Unit = {},
    floatingBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onFloatingBottomBarChange: (Boolean) -> Unit = {},
    blurBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onBlurBottomBarChange: (Boolean) -> Unit = {},
    appLanguage: MutableState<Int> = mutableStateOf(AppLocale.SYSTEM),
    onAppLanguageChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

        // State authority lives in the bluetooth process; mirror it here.
    LaunchedEffect(Unit) { SonyRemoteState.start(context) }
    val sonyState by SonyRemoteState.state.collectAsState()

    val tabs = remember { MainTab.entries.toList() }
    var selectedTab by remember { mutableStateOf(MainTab.Module) }
    var hasAppliedDefaultTab by remember { mutableStateOf(false) }
    var bluetoothState by remember { mutableStateOf(readBluetoothState(context)) }
    var xposedService by remember { mutableStateOf(SonyPodsApp.xposedService) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showRestartScopeDialog by remember { mutableStateOf(false) }
    var restartingScopes by remember { mutableStateOf(false) }
    var connectingDeviceAddress by remember { mutableStateOf<String?>(null) }
    // Keep the navigation intent separate from the transport handshake. The
    // engine reports connected before capability probing finishes, so clearing
    // the connection marker at that point must not lose the request to open the
    // detail page once probeComplete becomes true.
    var pendingAutoOpenAddress by remember { mutableStateOf<String?>(null) }
    // Scope restart puts the UI on the picker, while Bluetooth normally
    // restores the previous Sony connection without a row click. Keep that
    // reconnect navigation intent separately from the address-based click
    // intent.
    var autoOpenAfterScopeRestart by remember { mutableStateOf(false) }
    var showConnectErrorDialog by remember { mutableStateOf(false) }
    var lastBluetoothServiceAliveMs by remember { mutableStateOf(0L) }
    var bluetoothServiceResponsive by remember { mutableStateOf(false) }
    val backgroundColor = appBackground()
    val overlayBottomBar = floatingBottomBar.value || blurBottomBar.value
    val pageBottomContentPadding = if (overlayBottomBar) 104.dp else 28.dp
    val backdrop = if (blurBottomBar.value) {
        rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }
    } else {
        null
    }

    val prefs = remember { context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val appConfig = remember { ConfigManager.refreshFromPrefs(prefs) }
    val notificationClickAction = remember { mutableStateOf(appConfig.notificationClickAction) }
    val moreClickAction = remember { mutableStateOf(appConfig.moreClickAction) }
    val desktopIconHidden = remember { mutableStateOf(isLauncherIconHidden(context)) }
    val logLevel = remember { mutableStateOf(appConfig.logLevel) }
    val fakeDeviceId = remember { mutableStateOf(appConfig.fakeDeviceId) }
    val islandMode = remember { mutableStateOf(appConfig.superIslandMode) }
    val islandDurationSeconds = remember { mutableStateOf(appConfig.islandDurationSeconds) }
    val ancCycleModes = remember { mutableStateOf(appConfig.ancCycleModes) }
    val startupTab = remember { mutableStateOf(appConfig.startupTab) }
    val earphonePrefs = remember { mutableStateOf(PodImagePrefs.load(prefs)) }

    val sonyConnected = sonyState.connected
    val connectedDeviceAddress = sonyState.deviceAddress.orEmpty()
    val displayTitle = sonyState.displayName
    // The capability probe is what turns the neutral profile into the real one
    // (battery layout, writable NC types, EQ support); gating the detail page on
    // it prevents opening against an empty half-probed profile while connecting.
    val canShowDetailPage = sonyConnected && sonyState.probeComplete
    // A device selection is itself a navigation request. Do not make the
    // request depend solely on the effect below winning a particular
    // recomposition: the connection broadcast and the picker state can be
    // delivered in either order. While the selected session is probing, keep
    // the picker visible; as soon as the probe completes, the pending request
    // makes the detail page the target even before the effect clears the
    // picker flag.
    val showEarphoneDetail = canShowDetailPage &&
        (!showDevicePicker || pendingAutoOpenAddress != null)

    val sonyActions = remember(context) {
        SonyDetailActions(
            onAncModeChange = { SonyBridge.setNoiseControl(context, it) },
            onAmbientLevelChange = { SonyBridge.setAmbientLevel(context, it) },
            onAmbientVoiceModeChange = { SonyBridge.setAmbientVoice(context, it) },
            onNoiseAdaptiveChange = { SonyBridge.setNoiseAdaptive(context, it) },
            onNoiseAdaptiveSensitivityChange = { SonyBridge.setNoiseAdaptiveSensitivity(context, it.name) },
            onEqPresetChange = { SonyBridge.setEqPreset(context, it.name) },
            onClearBassChange = { SonyBridge.setClearBass(context, it) },
            onCustomEqBandChange = { index, level -> SonyBridge.setEqBand(context, index, level) },
            onPlaybackPrevious = { SonyBridge.sendCommand(context, SonyBridge.CMD_PLAYBACK_PREVIOUS) },
            onPlaybackPlayPause = { SonyBridge.sendCommand(context, SonyBridge.CMD_PLAYBACK_PLAY_PAUSE) },
            onPlaybackNext = { SonyBridge.sendCommand(context, SonyBridge.CMD_PLAYBACK_NEXT) },
            onPlaybackVolumeChange = { volume -> SonyBridge.setPlaybackVolume(context, volume) },
            onPowerOff = { SonyBridge.sendCommand(context, SonyBridge.CMD_POWER_OFF) },
            onGesturePresetChange = { key, preset ->
                SonyBridge.setGesturePreset(context, key.code.toInt() and 0xFF, preset.code.toInt() and 0xFF)
            },
            onGestureFunctionChange = { key, action, function ->
                SonyBridge.setGestureFunction(
                    context,
                    key.code.toInt() and 0xFF,
                    action.code.toInt() and 0xFF,
                    function.code.toInt() and 0xFF,
                )
            },
            onQuickAccessFunctionChange = { actionIndex, functionCode ->
                SonyBridge.setQuickAccessFunction(context, actionIndex, functionCode)
            },
            onGestureAmbientModesChange = { modes ->
                SonyBridge.setGestureAmbientModes(context, modes.map { it.ordinal }.toIntArray())
            },
            onMultipointPairingModeChange = { enabled -> SonyBridge.setMultipointPairingMode(context, enabled) },
            onMultipointConnect = { address -> SonyBridge.connectMultipointDevice(context, address) },
            onMultipointDisconnect = { address -> SonyBridge.disconnectMultipointDevice(context, address) },
            onMultipointUnpair = { address -> SonyBridge.unpairMultipointDevice(context, address) },
            onSourceSwitchEnabledChange = { enabled -> SonyBridge.setSourceSwitchEnabled(context, enabled) },
            onFixedSourceChange = { address -> SonyBridge.setFixedSource(context, address) },
            onMusicHandOverChange = { enabled -> SonyBridge.setMusicHandOver(context, enabled) },
            onRefresh = { SonyBridge.sendCommand(context, SonyBridge.CMD_REFRESH) },
        )
    }

    LaunchedEffect(Unit) {
        if (!hasAppliedDefaultTab) {
            selectedTab = if (startupTab.value == ConfigManager.STARTUP_TAB_EARPHONES) {
                MainTab.Earphones
            } else {
                MainTab.Module
            }
            hasAppliedDefaultTab = true
        }
    }

    // Connection established: record the device so the automatic model image can be
    // associated with its Bluetooth address. Navigation waits for the capability
    // probe, because the detail page is intentionally gated on probeComplete.
    LaunchedEffect(
        sonyConnected,
        connectedDeviceAddress,
        sonyState.probeComplete,
    ) {
        if (sonyConnected && connectedDeviceAddress.isNotBlank()) {
            // The selected Bluetooth address can be represented by different
            // GATT/SPP endpoint callbacks during one session. The pending marker
            // is therefore intentionally treated as a connection-session intent,
            // not compared byte-for-byte with the address in the latest snapshot.
            val shouldAutoOpen = pendingAutoOpenAddress != null ||
                autoOpenAfterScopeRestart ||
                !hasAppliedDefaultTab

            // The transport is connected even while the capability probe is in
            // flight; stop showing the row-level spinner but retain the separate
            // pendingAutoOpenAddress navigation intent.
            connectingDeviceAddress = null
            showConnectErrorDialog = false
            earphonePrefs.value = PodImagePrefs.upsertConnected(
                prefs = prefs,
                service = xposedService,
                address = connectedDeviceAddress,
                name = displayTitle,
            )

            if (sonyState.probeComplete && shouldAutoOpen) {
                selectedTab = MainTab.Earphones
                hasAppliedDefaultTab = true
                showDevicePicker = false
                pendingAutoOpenAddress = null
                autoOpenAfterScopeRestart = false
            }
            Log.i("SonyPods", "Sony device connected: $displayTitle ($connectedDeviceAddress)")
        }
    }

    // Connect timeout -> error dialog.
    LaunchedEffect(connectingDeviceAddress) {
        val address = connectingDeviceAddress ?: return@LaunchedEffect
        delay(CONNECT_TIMEOUT_MS)
        if (connectingDeviceAddress == address && !sonyConnected) {
            connectingDeviceAddress = null
            pendingAutoOpenAddress = null
            autoOpenAfterScopeRestart = false
            showConnectErrorDialog = true
            showDevicePicker = true
            SonyBridge.sendCommand(context, SonyBridge.CMD_DISCONNECT)
        }
    }

    // If the Bluetooth scope does not restore its connection, do not let the
    // restart intent affect a later unrelated connection.
    LaunchedEffect(autoOpenAfterScopeRestart) {
        if (!autoOpenAfterScopeRestart) return@LaunchedEffect
        delay(60_000L)
        autoOpenAfterScopeRestart = false
    }

    val broadcastReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                when (p1?.action) {
                    SonyPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE -> {
                        lastBluetoothServiceAliveMs = SystemClock.elapsedRealtime()
                        bluetoothServiceResponsive = true
                        // The engine (re)started — e.g. after a scope restart. Re-push the
                        // current config so its authoritative cache is current even if the push
                        // that accompanied the original change was missed. Prefer the
                        // framework-backed remote-preferences store (the cross-process source of
                        // truth that survives a scope restart); fall back to the local store only
                        // if the LSPosed service is unavailable. Remote prefs remain the persistent
                        // source of truth the engine reads at startup; this just guarantees delivery.
                        val remote = xposedService
                        val source = if (remote != null) {
                            runCatching { remote.getRemotePreferences(ConfigManager.PREFS_NAME) }.getOrNull() ?: prefs
                        } else {
                            prefs
                        }
                        ConfigManager.refreshFromPrefs(source)
                        broadcastConfigChanged(context, "com.android.bluetooth")
                    }

                    BluetoothAdapter.ACTION_STATE_CHANGED,
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        bluetoothState = readBluetoothState(context)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        val serviceListener: (io.github.libxposed.service.XposedService?) -> Unit = { service ->
            xposedService = service
        }
        SonyPodsApp.addServiceListener(serviceListener)

        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(SonyPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }, Context.RECEIVER_EXPORTED)

        // The control service downloads the cloud model image asynchronously; reload
        // the cached path so the built-in catalog image appears without a restart.
        val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == PodImagePrefs.PREF_KEY_EARPHONES) {
                earphonePrefs.value = PodImagePrefs.load(changed)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        sendBluetoothModuleBroadcast(context, SonyPodsAction.ACTION_PODS_UI_INIT)

        onDispose {
            sendBluetoothModuleBroadcast(context, SonyPodsAction.ACTION_PODS_UI_CLOSED)
            try {
                context.unregisterReceiver(broadcastReceiver)
            } catch (_: Exception) {}
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            SonyPodsApp.removeServiceListener(serviceListener)
        }
    }

    // Hook liveness ping: the bluetooth-process hook answers UI_INIT with SERVICE_ALIVE.
    LaunchedEffect(Unit) {
        while (true) {
            sendBluetoothModuleBroadcast(context, SonyPodsAction.ACTION_PODS_UI_INIT)
            delay(30_000L)
        }
    }

    LaunchedEffect(lastBluetoothServiceAliveMs) {
        while (true) {
            bluetoothServiceResponsive = lastBluetoothServiceAliveMs > 0L &&
                    SystemClock.elapsedRealtime() - lastBluetoothServiceAliveMs <= 75_000L
            delay(5_000L)
        }
    }

    // Periodic status refresh while connected.
    LaunchedEffect(sonyConnected) {
        while (sonyConnected) {
            SonyBridge.sendCommand(context, SonyBridge.CMD_REFRESH)
            delay(30_000L)
        }
    }

    fun clearPodConnectionState() {
        connectingDeviceAddress = null
        pendingAutoOpenAddress = null
        autoOpenAfterScopeRestart = true
        showConnectErrorDialog = false
        showDevicePicker = true
        selectedTab = MainTab.Earphones
    }

    fun onDeviceSelected(device: BluetoothDevice) {
        connectingDeviceAddress = device.address
        pendingAutoOpenAddress = device.address
        autoOpenAfterScopeRestart = false
        showConnectErrorDialog = false
        showDevicePicker = true
        selectedTab = MainTab.Earphones
        val name = runCatching { device.name }.getOrNull() ?: "Sony audio device"
        SonyBridge.connect(context, device.address, name)
    }

    fun onDeviceDisconnect(device: BluetoothDevice) {
        if (device.address == connectingDeviceAddress) {
            connectingDeviceAddress = null
        }
        if (device.address.equals(pendingAutoOpenAddress, ignoreCase = true)) {
            pendingAutoOpenAddress = null
        }
        autoOpenAfterScopeRestart = false
        if (device.address.equals(connectedDeviceAddress, ignoreCase = true) || connectedDeviceAddress.isBlank()) {
            SonyBridge.sendCommand(context, SonyBridge.CMD_DISCONNECT)
        }
    }

    fun onConnectedDeviceClick() {
        if (!sonyConnected) return
        pendingAutoOpenAddress = null
        autoOpenAfterScopeRestart = false
        connectingDeviceAddress = null
        showDevicePicker = false
        selectedTab = MainTab.Earphones
    }

    fun backToDevicePicker() {
        pendingAutoOpenAddress = null
        autoOpenAfterScopeRestart = false
        showDevicePicker = true
    }

    fun openBluetoothSettings() {
        val action = if (bluetoothState.enabled) Settings.ACTION_BLUETOOTH_SETTINGS else BluetoothAdapter.ACTION_REQUEST_ENABLE
        Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(this) }
                .onFailure { Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    fun openDevicePicker() {
        pendingAutoOpenAddress = null
        autoOpenAfterScopeRestart = false
        showDevicePicker = true
        selectedTab = MainTab.Earphones
    }

    @SuppressLint("MissingPermission")
    fun openSystemHeadsetSettings() {
        val address = connectedDeviceAddress
        if (address.isBlank()) {
            Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val device = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter?.getRemoteDevice(address)
        }.getOrNull()
        if (device == null) {
            Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Intent().apply {
            setClassName("com.android.settings", "com.android.settings.bluetooth.MiuiHeadsetActivity")
            putExtra("android.bluetooth.device.extra.DEVICE", device)
            putExtra("bluetoothaddress", device.address)
            putExtra("MIUI_HEADSET_SUPPORT", ConfigManager.fakeSupport())
            putExtra("COME_FROM", "MIUI_BLUETOOTH_SETTINGS")
            putExtra("DEVICE_ID", ConfigManager.fakeDeviceId())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(this) }
                .onFailure { Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    fun restartScopes(packages: List<String>) {
        if (packages.isEmpty() || restartingScopes) return
        restartingScopes = true
        coroutineScope.launch {
            val success = withContext(Dispatchers.IO) {
                RootManager.restartPackages(packages)
            }
            restartingScopes = false
            showRestartScopeDialog = false
            if (success && "com.android.bluetooth" in packages) {
                clearPodConnectionState()
            }
            Toast.makeText(
                context,
                if (success) R.string.restart_scope_success else R.string.restart_scope_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val entryProvider = entryProvider<Screen> {
        entry<Screen.Main> {
            MainTabsScaffold(
                tabs = tabs,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                floatingBottomBar = floatingBottomBar.value,
                blurBottomBar = blurBottomBar.value,
                backdrop = backdrop,
                backgroundColor = backgroundColor,
                overlayBottomBar = overlayBottomBar,
                pageBottomContentPadding = pageBottomContentPadding,
                xposedService = xposedService,
                bluetoothServiceResponsive = bluetoothServiceResponsive,
                bluetoothEnabled = bluetoothState.enabled,
                bondedDeviceCount = bluetoothState.bondedCount,
                onBluetoothStatusClick = { openBluetoothSettings() },
                onPairedBluetoothClick = { openDevicePicker() },
                showEarphoneDetail = showEarphoneDetail,
                mainTitle = displayTitle,
                displayTitle = displayTitle,
                sonyState = sonyState,
                sonyActions = sonyActions,
                earphonePrefs = earphonePrefs.value,
                connectedDeviceAddress = connectedDeviceAddress,
                connectingDeviceAddress = connectingDeviceAddress,
                showConnectErrorDialog = showConnectErrorDialog,
                onDeviceSelected = { onDeviceSelected(it) },
                onConnectedDeviceClick = { onConnectedDeviceClick() },
                onDeviceDisconnect = { onDeviceDisconnect(it) },
                onDismissConnectError = { showConnectErrorDialog = false },
                desktopIconHidden = desktopIconHidden,
                onDesktopIconHiddenChange = {
                    desktopIconHidden.value = it
                    setLauncherIconHidden(context, it)
                },
                logLevel = logLevel,
                onLogLevelChange = {
                    logLevel.value = it
                    ConfigManager.updateLogLevel(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                    broadcastConfigChanged(context, "com.milink.service")
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                islandMode = islandMode,
                onIslandModeChange = {
                    islandMode.value = it
                    ConfigManager.updateIslandMode(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                islandDurationSeconds = islandDurationSeconds,
                onIslandDurationSecondsChange = {
                    islandDurationSeconds.value = it
                    ConfigManager.updateIslandDurationSeconds(prefs, xposedService, it)
                    // The island renderer lives in com.xiaomi.bluetooth; the engine
                    // (com.android.bluetooth) reads the same config for surfaces.
                    broadcastConfigChanged(context, "com.android.bluetooth")
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                ancCycleModes = ancCycleModes,
                onAncCycleModesChange = {
                    ancCycleModes.value = it
                    ConfigManager.updateAncCycleModes(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                },
                startupTab = startupTab,
                onStartupTabChange = {
                    startupTab.value = it
                    ConfigManager.updateStartupTab(prefs, xposedService, it)
                },
                appLanguage = appLanguage,
                onAppLanguageChange = {
                    appLanguage.value = it
                    onAppLanguageChange(it)
                },
                notificationClickAction = notificationClickAction,
                onNotificationClickActionChange = {
                    notificationClickAction.value = it
                    ConfigManager.updateNotificationClickAction(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                moreClickAction = moreClickAction,
                onMoreClickActionChange = {
                    moreClickAction.value = it
                    ConfigManager.updateMoreClickAction(prefs, xposedService, it)
                },
                onOpenTandemDebug = { backStack.add(Screen.TandemDebug) },
                fakeDeviceId = fakeDeviceId,
                onFakeDeviceIdChange = {
                    fakeDeviceId.value = it
                    ConfigManager.updateFakeDeviceId(prefs, xposedService, it)
                    broadcastConfigChanged(context, "com.android.bluetooth")
                    broadcastConfigChanged(context, "com.android.settings")
                    broadcastConfigChanged(context, "com.milink.service")
                    broadcastConfigChanged(context, "com.xiaomi.bluetooth")
                },
                onOpenTheme = { backStack.add(Screen.Theme) },
                onOpenAbout = { backStack.add(Screen.About) },
                showRestartScopeDialog = showRestartScopeDialog,
                restartingScopes = restartingScopes,
                onShowRestartScopeDialog = { showRestartScopeDialog = true },
                onDismissRestartScopeDialog = { showRestartScopeDialog = false },
                onRestartScopes = { restartScopes(it) },
                onBackToDevicePicker = { backToDevicePicker() },
                onOpenSystemHeadsetSettings = { openSystemHeadsetSettings() },
            )
        }
        entry<Screen.About> {
            val aboutScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.about),
                        largeTitle = stringResource(R.string.about),
                        scrollBehavior = aboutScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    AboutPage(
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(aboutScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(bottom = pageBottomContentPadding),
                    )
                }
            }
        }
        entry<Screen.Theme> {
            val themeScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.theme_title),
                        largeTitle = stringResource(R.string.theme_title),
                        scrollBehavior = themeScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    ThemeSettingsPage(
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(themeScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(bottom = pageBottomContentPadding),
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        accentMode = accentMode,
                        onAccentModeChange = onAccentModeChange,
                        floatingBottomBar = floatingBottomBar,
                        onFloatingBottomBarChange = onFloatingBottomBarChange,
                        blurBottomBar = blurBottomBar,
                        onBlurBottomBarChange = onBlurBottomBarChange,
                    )
                }
            }
        }
        entry<Screen.TandemDebug> {
            val debugScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            var clearLogsRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.tandem_debug_title),
                        largeTitle = stringResource(R.string.tandem_debug_title),
                        scrollBehavior = debugScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = { clearLogsRequest++ }) {
                                Icon(imageVector = MiuixIcons.Delete, contentDescription = "Clear logs")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .padding(padding),
                ) {
                    TandemDebugPage(
                        modifier = Modifier.nestedScroll(debugScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(0.dp),
                        clearRequest = clearLogsRequest,
                    )
                }
            }
        }
    }

    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryProvider = entryProvider
    )

    NavDisplay(
        entries = entries,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLast()
            } else {
                (context as? Activity)?.finish()
            }
        }
    )
}

@Composable
fun appBackground(): Color = MiuixTheme.colorScheme.surface

private data class BluetoothSummary(
    val enabled: Boolean,
    val bondedCount: Int,
)

private fun readBluetoothState(context: Context): BluetoothSummary {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    return runCatching {
        BluetoothSummary(
            enabled = adapter?.isEnabled == true,
            bondedCount = adapter?.bondedDevices?.size ?: 0,
        )
    }.getOrDefault(BluetoothSummary(enabled = false, bondedCount = 0))
}

private fun sendBluetoothModuleBroadcast(context: Context, action: String) {
    listOf("com.android.bluetooth", "com.xiaomi.bluetooth").forEach { packageName ->
        Intent(action).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            context.sendBroadcast(this)
        }
    }
}

private fun isLauncherIconHidden(context: Context): Boolean {
    val component = ComponentName(context, "dev.sonypods.LauncherActivity")
    val state = context.packageManager.getComponentEnabledSetting(component)
    return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}

private fun setLauncherIconHidden(context: Context, hidden: Boolean) {
    val component = ComponentName(context, "dev.sonypods.LauncherActivity")
    val state = if (hidden) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
    context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
}

private fun broadcastConfigChanged(context: Context, packageName: String) {
    Intent(SonyPodsAction.ACTION_CONFIG_CHANGED).apply {
        setPackage(packageName)
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        // Carry the authoritative config by value so the engine process applies it
        // directly, independent of remote-preferences propagation.
        putExtra(ConfigManager.PREF_KEY_CONFIG_JSON, ConfigManager.currentAsJson())
        context.sendBroadcast(this)
    }
}
