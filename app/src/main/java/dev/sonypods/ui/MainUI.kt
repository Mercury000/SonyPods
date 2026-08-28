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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import androidx.compose.runtime.key
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
import dev.sonypods.ui.pages.VisibilitySettingsPage
import dev.sonypods.ui.dialogs.MultipointAlertDialog
import dev.sonypods.ui.dialogs.LeAudioAlertDialog
import dev.sonypods.ui.dialogs.LeAudioPairingHelpDialog
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
import top.yukonga.miuix.kmp.blur.textureBlur
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
    data object Visibility : Screen
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
    blurTopBar: MutableState<Boolean> = mutableStateOf(false),
    onBlurTopBarChange: (Boolean) -> Unit = {},
    appLanguage: MutableState<Int> = mutableStateOf(AppLocale.SYSTEM),
    onAppLanguageChange: (Int) -> Unit = {},
    openEarphoneDetailAddress: MutableState<String?> = mutableStateOf(null),
    onExternalDetailRequestConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

        // State authority lives in the bluetooth process; mirror it here.
    LaunchedEffect(Unit) { SonyRemoteState.start(context) }
    val sonyState by SonyRemoteState.state.collectAsState()

    val tabs = remember { MainTab.entries.toList() }
    var selectedTab by remember { mutableStateOf(MainTab.Module) }
    var hasAppliedDefaultTab by remember { mutableStateOf(false) }
    var mainTabsGeneration by remember { mutableIntStateOf(0) }
    var bluetoothState by remember { mutableStateOf(readBluetoothState(context)) }
    var xposedService by remember { mutableStateOf(SonyPodsApp.xposedService) }
    var remoteDataReady by remember {
        mutableStateOf(ConfigManager.isStoreAttached() && PodImagePrefs.isStoreAttached())
    }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showRestartScopeDialog by remember { mutableStateOf(false) }
    var restartingScopes by remember { mutableStateOf(false) }
    var connectingDeviceAddress by remember { mutableStateOf<String?>(null) }
    // Keep the navigation intent separate from the transport handshake. The
    // engine reports connected before capability probing and the connection-time
    // value burst finish, so clearing the connection marker at that point must
    // not lose the request to open the detail page once the session is operable.
    var pendingAutoOpenAddress by remember { mutableStateOf<String?>(null) }
    // Scope restart puts the UI on the picker, while Bluetooth normally
    // restores the previous Sony connection without a row click. Keep that
    // reconnect navigation intent separately from the address-based click
    // intent.
    var autoOpenAfterScopeRestart by remember { mutableStateOf(false) }
    var pendingExternalDetailAddress by remember { mutableStateOf<String?>(null) }
    var showConnectErrorDialog by remember { mutableStateOf(false) }
    var lastBluetoothServiceAliveMs by remember { mutableStateOf(0L) }
    var bluetoothServiceResponsive by remember { mutableStateOf(false) }
    var hasRequestedStartupConnection by remember { mutableStateOf(false) }
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

    val appConfig = remember { ConfigManager.current() }
    val notificationClickAction = remember { mutableStateOf(appConfig.notificationClickAction) }
    val notificationEnabled = remember { mutableStateOf(appConfig.notificationEnabled) }
    val popupOnConnect = remember { mutableStateOf(appConfig.popupOnConnect) }
    val connectDialogMode = remember { mutableStateOf(appConfig.connectDialogMode) }
    val popupAllowlist = remember { mutableStateOf(appConfig.popupAllowlist) }
    val popupDenylist = remember { mutableStateOf(appConfig.popupDenylist) }
    val suppressPopupInGameOrLandscape = remember { mutableStateOf(appConfig.suppressPopupInGameOrLandscape) }
    val moreClickAction = remember { mutableStateOf(appConfig.moreClickAction) }
    val fusionMoreClickAction = remember { mutableStateOf(appConfig.fusionMoreClickAction) }
    val desktopIconHidden = remember { mutableStateOf(isLauncherIconHidden(context)) }
    val logLevel = remember { mutableStateOf(appConfig.logLevel) }
    val fakeDeviceId = remember { mutableStateOf(appConfig.fakeDeviceId) }
    val islandMode = remember { mutableStateOf(appConfig.superIslandMode) }
    val islandDurationSeconds = remember { mutableStateOf(appConfig.islandDurationSeconds) }
    val ancCycleModes = remember { mutableStateOf(appConfig.ancCycleModes) }
    val startupTab = remember { mutableStateOf(appConfig.startupTab) }
    val visibility = remember { mutableStateOf(appConfig.visibility) }
    val earphonePrefs = remember { mutableStateOf(PodImagePrefs.loadCurrent()) }

    val sonyConnected = sonyState.connected
    val connectedDeviceAddress = sonyState.deviceAddress.orEmpty()
    val displayTitle = sonyState.displayName
    // The capability table is what turns the neutral profile into the real one
    // (battery layout, writable NC types, EQ support); gating the detail page on
    // it prevents opening against an empty half-probed profile while connecting.
    // "The probe finished" is not the same fact — it is also true when the probe
    // gave up without a table, and the page would then render the fallback guess.
    // The table only says which features exist, though — the values behind them
    // arrive later, over LE by several seconds. Waiting for the connection-time
    // value burst as well is what keeps the page from opening on defaults that
    // cannot be tapped.
    val canShowDetailPage = sonyConnected && sonyState.capabilitiesKnown && sonyState.initialValuesReady
    // A device selection is itself a navigation request. Do not make the
    // request depend solely on the effect below winning a particular
    // recomposition: the connection broadcast and the picker state can be
    // delivered in either order. While the selected session is probing, keep
    // the picker visible; as soon as the probe completes, the pending request
    // makes the detail page the target even before the effect clears the
    // picker flag.
    val externalDetailMatchesConnection = pendingExternalDetailAddress?.let { pending ->
        sonyConnected && connectedDeviceAddress.equals(pending, ignoreCase = true)
    } == true
    val showEarphoneDetail = canShowDetailPage && if (pendingExternalDetailAddress != null) {
        externalDetailMatchesConnection
    } else {
        !showDevicePicker || pendingAutoOpenAddress != null
    }

    val sonyActions = remember(context) {
        SonyDetailActions(
            onAncModeChange = { SonyBridge.setNoiseControl(context, it) },
            onWindNoiseReductionChange = { SonyBridge.setWindNoiseReduction(context, it) },
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
            onMultipointEnabledChange = { enabled -> SonyBridge.setMultipointEnabled(context, enabled) },
             onLeAudioEnabledChange = { enabled -> SonyBridge.setLeAudioEnabled(context, enabled) },
            onUpscalingEnabledChange = { enabled -> SonyBridge.setUpscalingEnabled(context, enabled) },
            onConnectionQualityChange = { mode -> SonyBridge.setConnectionQuality(context, mode.name) },
             onLeAudioAlertReply = { positive -> SonyBridge.replyLeAudioAlert(context, positive) },
             onLeAudioDevicePair = { SonyBridge.pairLeAudioDevice(context) },
             onLeAudioPairingGuide = { SonyBridge.showLeAudioPairingGuide(context) },
             onLeAudioPolicyChange = { allowed -> SonyBridge.setLeAudioPolicyAllowed(context, allowed) },
            onLdacEnabledChange = { enabled -> SonyBridge.setLdacEnabled(context, enabled) },
             onMultipointAlertReply = { positive -> SonyBridge.replyMultipointAlert(context, positive) },
            onFixedSourceChange = { address -> SonyBridge.setFixedSource(context, address) },
            onMusicHandOverChange = { enabled -> SonyBridge.setMusicHandOver(context, enabled) },
            onRefresh = { SonyBridge.sendCommand(context, SonyBridge.CMD_REFRESH) },
        )
    }

    LaunchedEffect(remoteDataReady, startupTab.value, openEarphoneDetailAddress.value) {
        if (remoteDataReady && !hasAppliedDefaultTab) {
            // remoteDataReady becomes true in the service callback, while the Compose
            // mirror below is updated by a later effect. Read the already-attached
            // authoritative store here so the in-memory default cannot win that race.
            val configuredStartupTab = ConfigManager.startupTab()
            startupTab.value = configuredStartupTab
            selectedTab = if (
                !openEarphoneDetailAddress.value.isNullOrBlank() ||
                configuredStartupTab == ConfigManager.STARTUP_TAB_EARPHONES
            ) {
                MainTab.Earphones
            } else {
                MainTab.Module
            }
            hasAppliedDefaultTab = true
            mainTabsGeneration++
            Log.i(
                "SonyPods",
                "startup page applied config=$configuredStartupTab selected=$selectedTab connected=$sonyConnected",
            )
        }
    }

    // The fusion device center can enter the module while the existing MainActivity
    // task is already alive. Keep this request separate from the normal device-picker
    // flow and consume it only after the requested Sony session has finished probing.
    LaunchedEffect(openEarphoneDetailAddress.value) {
        val target = openEarphoneDetailAddress.value?.trim()?.takeIf { it.isNotEmpty() }
        if (target != null) {
            pendingExternalDetailAddress = target
            pendingAutoOpenAddress = null
            autoOpenAfterScopeRestart = false
            selectedTab = MainTab.Earphones
            showDevicePicker = true
        }
    }

    LaunchedEffect(
        pendingExternalDetailAddress,
        sonyConnected,
        connectedDeviceAddress,
        sonyState.capabilitiesKnown,
        sonyState.initialValuesReady,
    ) {
        val pending = pendingExternalDetailAddress ?: return@LaunchedEffect
        if (!canShowDetailPage || !connectedDeviceAddress.equals(pending, ignoreCase = true)) return@LaunchedEffect
        selectedTab = MainTab.Earphones
        showDevicePicker = false
        pendingExternalDetailAddress = null
        onExternalDetailRequestConsumed()
    }

    // Re-read the device when the detail page becomes visible, the way every Sound Connect
    // card issues its GET on becoming visible instead of drawing whatever the last session
    // left behind. The connection-time burst is a single shot taken seconds earlier: the
    // headset's music info in particular is UNSETTLED until the phone's AVRCP metadata
    // reaches it, and a reply that says so leaves the song/artist/album rows empty with
    // nothing scheduled to ask again.
    LaunchedEffect(showEarphoneDetail, connectedDeviceAddress) {
        if (showEarphoneDetail && connectedDeviceAddress.isNotBlank()) {
            SonyBridge.sendCommand(context, SonyBridge.CMD_REFRESH)
        }
    }

    // Connection established: record the device so the automatic model image can be
    // associated with its Bluetooth address. Navigation waits for the capability table
    // and for the connection-time values, because the detail page is gated on
    // both — entering earlier would show untappable defaults.
    LaunchedEffect(
        sonyConnected,
        connectedDeviceAddress,
        sonyState.capabilitiesKnown,
        sonyState.initialValuesReady,
    ) {
        if (sonyConnected && connectedDeviceAddress.isNotBlank()) {
            // The selected Bluetooth address can be represented by different
            // GATT/SPP endpoint callbacks during one session. The pending marker
            // is therefore intentionally treated as a connection-session intent,
            // not compared byte-for-byte with the address in the latest snapshot.
            val shouldAutoOpen = pendingAutoOpenAddress != null ||
                autoOpenAfterScopeRestart

            // The transport is connected even while the capability probe is in
            // flight; stop showing the row-level spinner but retain the separate
            // pendingAutoOpenAddress navigation intent.
            connectingDeviceAddress = null
            showConnectErrorDialog = false
            // The state broadcast can arrive before the app has adopted the
            // framework-backed metadata store. Writing here in that window would
            // buffer a new record without autoImageUrl and overwrite the existing
            // image metadata when the store binds. ModelImageSync owns the
            // connection-time metadata update until the store is available.
            if (PodImagePrefs.isStoreAttached()) {
                earphonePrefs.value = PodImagePrefs.upsertConnected(
                    address = connectedDeviceAddress,
                    name = displayTitle,
                )
            }

            if (canShowDetailPage && shouldAutoOpen) {
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
                        // Opening the app always asks the engine to reconcile its control
                        // session. This is transport lifecycle, independent of which
                        // startup page the user selected. The liveness reply guarantees
                        // that the Bluetooth-side hook is present before the command.
                        if (!hasRequestedStartupConnection) {
                            hasRequestedStartupConnection = true
                            SonyBridge.sendCommand(context, SonyBridge.CMD_REFRESH)
                            Log.i("SonyPods", "startup control connection reconciliation requested")
                        }
                        // No config re-push here: the engine reads the framework-backed
                        // remote-pref store itself at startup and observes changes through
                        // its own OnSharedPreferenceChangeListener.
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
        var configStore: android.content.SharedPreferences? = null
        // The control service downloads the cloud model image asynchronously; reload
        // the cached metadata so the built-in catalog image appears without a restart.
        val storeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == PodImagePrefs.PREF_KEY_EARPHONES || key == null) {
                earphonePrefs.value = PodImagePrefs.load(changed)
            }
        }
        val serviceListener: (io.github.libxposed.service.XposedService?) -> Unit = { service ->
            xposedService = service
            val store = service?.let {
                runCatching { it.getRemotePreferences(ConfigManager.PREFS_NAME) }.getOrNull()
            }
            if (store !== configStore) {
                configStore?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(storeListener) } }
                configStore = store
                store?.let {
                    runCatching { it.registerOnSharedPreferenceChangeListener(storeListener) }
                    earphonePrefs.value = PodImagePrefs.load(it)
                }
            }
            remoteDataReady = ConfigManager.isStoreAttached() && PodImagePrefs.isStoreAttached()
        }
        SonyPodsApp.addServiceListener(serviceListener)

        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(SonyPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }, Context.RECEIVER_EXPORTED)

        sendBluetoothModuleBroadcast(context, SonyPodsAction.ACTION_PODS_UI_INIT)

        onDispose {
            sendBluetoothModuleBroadcast(context, SonyPodsAction.ACTION_PODS_UI_CLOSED)
            try {
                context.unregisterReceiver(broadcastReceiver)
            } catch (_: Exception) {}
            configStore?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(storeListener) } }
            SonyPodsApp.removeServiceListener(serviceListener)
        }
    }

    // The LSPosed service can bind after this composable was first composed (e.g. the
    // framework connected while the UI was already on screen). ConfigManager.attachStore
    // runs before listeners fire, so once the service is non-null the cache reflects the
    // persisted config — mirror it into the settings states here.
    LaunchedEffect(xposedService) {
        if (xposedService == null) return@LaunchedEffect
        val c = ConfigManager.current()
        notificationClickAction.value = c.notificationClickAction
        notificationEnabled.value = c.notificationEnabled
        popupOnConnect.value = c.popupOnConnect
        connectDialogMode.value = c.connectDialogMode
        popupAllowlist.value = c.popupAllowlist
        popupDenylist.value = c.popupDenylist
        suppressPopupInGameOrLandscape.value = c.suppressPopupInGameOrLandscape
        moreClickAction.value = c.moreClickAction
        fusionMoreClickAction.value = c.fusionMoreClickAction
        logLevel.value = c.logLevel
        fakeDeviceId.value = c.fakeDeviceId
        islandMode.value = c.superIslandMode
        islandDurationSeconds.value = c.islandDurationSeconds
        ancCycleModes.value = c.ancCycleModes
        startupTab.value = c.startupTab
        visibility.value = c.visibility
        earphonePrefs.value = PodImagePrefs.loadCurrent()
        remoteDataReady = ConfigManager.isStoreAttached() && PodImagePrefs.isStoreAttached()
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

    fun clearPodConnectionState() {
        connectingDeviceAddress = null
        pendingAutoOpenAddress = null
        autoOpenAfterScopeRestart = true
        showConnectErrorDialog = false
        showDevicePicker = true
        selectedTab = MainTab.Earphones
    }

    fun clearExternalDetailRequest() {
        pendingExternalDetailAddress = null
        onExternalDetailRequestConsumed()
    }

    fun onDeviceSelected(device: BluetoothDevice) {
        clearExternalDetailRequest()
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
        clearExternalDetailRequest()
        autoOpenAfterScopeRestart = false
        connectingDeviceAddress = null
        if (canShowDetailPage) {
            pendingAutoOpenAddress = null
            showDevicePicker = false
        } else {
            pendingAutoOpenAddress = connectedDeviceAddress
        }
        selectedTab = MainTab.Earphones
    }

    fun backToDevicePicker() {
        clearExternalDetailRequest()
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
        clearExternalDetailRequest()
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
            if (!remoteDataReady || !hasAppliedDefaultTab) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor),
                )
                return@entry
            }
            key(mainTabsGeneration) {
                MainTabsScaffold(
                    tabs = tabs,
                    selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                floatingBottomBar = floatingBottomBar.value,
                blurBottomBar = blurBottomBar.value,
                blurTopBar = blurTopBar,
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
                visibility = visibility.value,
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
                    ConfigManager.updateLogLevel(it)
                },
                islandMode = islandMode,
                onIslandModeChange = {
                    islandMode.value = it
                    ConfigManager.updateIslandMode(it)
                },
                islandDurationSeconds = islandDurationSeconds,
                onIslandDurationSecondsChange = {
                    islandDurationSeconds.value = it
                    ConfigManager.updateIslandDurationSeconds(it)
                },
                ancCycleModes = ancCycleModes,
                onAncCycleModesChange = {
                    ancCycleModes.value = it
                    ConfigManager.updateAncCycleModes(it)
                },
                startupTab = startupTab,
                onStartupTabChange = {
                    startupTab.value = it
                    ConfigManager.updateStartupTab(it)
                },
                onOpenVisibility = { backStack.add(Screen.Visibility) },
                appLanguage = appLanguage,
                onAppLanguageChange = {
                    appLanguage.value = it
                    onAppLanguageChange(it)
                },
                notificationClickAction = notificationClickAction,
                onNotificationClickActionChange = {
                    notificationClickAction.value = it
                    ConfigManager.updateNotificationClickAction(it)
                },
                notificationEnabled = notificationEnabled,
                onNotificationEnabledChange = {
                    notificationEnabled.value = it
                    ConfigManager.updateNotificationEnabled(it)
                },
                popupOnConnect = popupOnConnect,
                onPopupOnConnectChange = {
                    popupOnConnect.value = it
                    ConfigManager.updatePopupOnConnect(it)
                },
                connectDialogMode = connectDialogMode,
                onConnectDialogModeChange = {
                    connectDialogMode.value = it
                    ConfigManager.updateConnectDialogMode(it)
                },
                popupAllowlist = popupAllowlist,
                onPopupAllowlistChange = {
                    popupAllowlist.value = it
                    ConfigManager.updatePopupAllowlist(it)
                },
                popupDenylist = popupDenylist,
                onPopupDenylistChange = {
                    popupDenylist.value = it
                    ConfigManager.updatePopupDenylist(it)
                },
                suppressPopupInGameOrLandscape = suppressPopupInGameOrLandscape,
                onSuppressPopupInGameOrLandscapeChange = {
                    suppressPopupInGameOrLandscape.value = it
                    ConfigManager.updateSuppressPopupInGameOrLandscape(it)
                },
                moreClickAction = moreClickAction,
                onMoreClickActionChange = {
                    moreClickAction.value = it
                    ConfigManager.updateMoreClickAction(it)
                },
                fusionMoreClickAction = fusionMoreClickAction,
                onFusionMoreClickActionChange = {
                    fusionMoreClickAction.value = it
                    ConfigManager.updateFusionMoreClickAction(it)
                },
                onOpenTandemDebug = { backStack.add(Screen.TandemDebug) },
                fakeDeviceId = fakeDeviceId,
                onFakeDeviceIdChange = {
                    fakeDeviceId.value = it
                    ConfigManager.updateFakeDeviceId(it)
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
        }
        entry<Screen.About> {
            val aboutScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            val aboutTopBarBackdrop = if (blurTopBar.value) {
                rememberLayerBackdrop {
                    drawRect(backgroundColor)
                    drawContent()
                }
            } else {
                null
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.about),
                        largeTitle = stringResource(R.string.about),
                        modifier = if (aboutTopBarBackdrop != null) {
                            Modifier.textureBlur(
                                backdrop = aboutTopBarBackdrop,
                                shape = RectangleShape,
                            )
                        } else {
                            Modifier
                        },
                        color = if (aboutTopBarBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                        scrollBehavior = aboutScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.cd_back))
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .then(if (aboutTopBarBackdrop != null) Modifier.layerBackdrop(aboutTopBarBackdrop) else Modifier)
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
            val themeTopBarBackdrop = if (blurTopBar.value) {
                rememberLayerBackdrop {
                    drawRect(backgroundColor)
                    drawContent()
                }
            } else {
                null
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.theme_title),
                        largeTitle = stringResource(R.string.theme_title),
                        modifier = if (themeTopBarBackdrop != null) {
                            Modifier.textureBlur(
                                backdrop = themeTopBarBackdrop,
                                shape = RectangleShape,
                            )
                        } else {
                            Modifier
                        },
                        color = if (themeTopBarBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                        scrollBehavior = themeScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.cd_back))
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .then(if (themeTopBarBackdrop != null) Modifier.layerBackdrop(themeTopBarBackdrop) else Modifier)
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
                        blurTopBar = blurTopBar,
                        onBlurTopBarChange = onBlurTopBarChange,
                    )
                }
            }
        }
        entry<Screen.Visibility> {
            val visibilityScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            val visibilityTopBarBackdrop = if (blurTopBar.value) {
                rememberLayerBackdrop {
                    drawRect(backgroundColor)
                    drawContent()
                }
            } else {
                null
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.visibility_settings_title),
                        largeTitle = stringResource(R.string.visibility_settings_title),
                        modifier = if (visibilityTopBarBackdrop != null) {
                            Modifier.textureBlur(
                                backdrop = visibilityTopBarBackdrop,
                                shape = RectangleShape,
                            )
                        } else {
                            Modifier
                        },
                        color = if (visibilityTopBarBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                        scrollBehavior = visibilityScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.cd_back))
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .then(if (visibilityTopBarBackdrop != null) Modifier.layerBackdrop(visibilityTopBarBackdrop) else Modifier)
                        .padding(padding),
                ) {
                    VisibilitySettingsPage(
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(visibilityScrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(bottom = pageBottomContentPadding),
                        visibility = visibility.value,
                        onVisibilityChange = { newVisibility ->
                            visibility.value = newVisibility
                            ConfigManager.updateVisibility(newVisibility)
                        },
                    )
                }
            }
        }
        entry<Screen.TandemDebug> {
            val debugScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            val debugTopBarBackdrop = if (blurTopBar.value) {
                rememberLayerBackdrop {
                    drawRect(backgroundColor)
                    drawContent()
                }
            } else {
                null
            }
            var clearLogsRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = stringResource(R.string.tandem_debug_title),
                        largeTitle = stringResource(R.string.tandem_debug_title),
                        modifier = if (debugTopBarBackdrop != null) {
                            Modifier.textureBlur(
                                backdrop = debugTopBarBackdrop,
                                shape = RectangleShape,
                            )
                        } else {
                            Modifier
                        },
                        color = if (debugTopBarBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                        scrollBehavior = debugScrollBehavior,
                        navigationIcon = {
                            IconButton(onClick = { backStack.removeLast() }) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.cd_back))
                            }
                        },
                        actions = {
                            IconButton(onClick = { clearLogsRequest++ }) {
                                Icon(imageVector = MiuixIcons.Delete, contentDescription = stringResource(R.string.cd_clear_logs))
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .then(if (debugTopBarBackdrop != null) Modifier.layerBackdrop(debugTopBarBackdrop) else Modifier)
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

    // Outer transparent Scaffold: provides a root-level MiuixPopupHost so that
    // OverlayDialog-based composables (e.g. MultipointAlertDialog) render even
    // when invoked outside the per-screen Scaffolds. Inner Scaffolds propagate
    // LocalRootDialogStates up to this host. Zero contentWindowInsets so the
    // outer host does not steal insets the inner Scaffolds rely on.
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) {
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

        // Device-driven multipoint reconnection alert: shown globally once the engine
        // reports a pending FIXED_MESSAGE alert (V2 Table1 ALERT_NTFY_PARAM 0x99).
        val pendingAlertMsgType = sonyState.multipoint.pendingAlertMessageType
        LaunchedEffect(pendingAlertMsgType) {
            android.util.Log.i("OpenBuds", "UI pendingAlertMsgType=$pendingAlertMsgType show=${pendingAlertMsgType != null}")
        }
        MultipointAlertDialog(
            show = pendingAlertMsgType != null,
            messageType = pendingAlertMsgType ?: 7,
            onConfirm = { sonyActions.onMultipointAlertReply(true) },
            onCancel = { sonyActions.onMultipointAlertReply(false) },
        )
        LeAudioAlertDialog(
            show = sonyState.leAudioPending && sonyState.leAudioPendingInquiredType != null,
            targetEnabled = sonyState.leAudioPendingTargetEnabled,
            inquiredType = sonyState.leAudioPendingInquiredType,
            messageType = sonyState.leAudioPendingMessageType,
            itemCodes = sonyState.leAudioPendingItemCodes,
            deviceAlert = sonyState.leAudioPendingMessageType != null,
            onConfirm = { sonyActions.onLeAudioAlertReply(true) },
            onCancel = { sonyActions.onLeAudioAlertReply(false) },
        )
        LeAudioPairingHelpDialog(
            show = sonyState.leAudioPending && sonyState.leAudioPendingInquiredType == null,
            targetEnabled = sonyState.leAudioPendingTargetEnabled,
            formFactor = sonyState.formFactor,
            pairStage = sonyState.leAudioDevicePairStage,
            pairMessage = sonyState.leAudioDevicePairMessage,
            pairedAddress = sonyState.leAudioDevicePairedAddress,
            onPair = { sonyActions.onLeAudioDevicePair() },
            onDismiss = { sonyActions.onLeAudioAlertReply(true) },
        )
    }
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

