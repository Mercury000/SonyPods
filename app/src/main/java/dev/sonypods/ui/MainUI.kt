package dev.sonypods.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.SonyRemoteState
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.GestureNoiseControlMode
import com.mercury.sonypods.R
import dev.sonypods.config.ConfigManager
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.ui.pages.AboutPage
import dev.sonypods.ui.pages.ThemeSettingsPage
import dev.sonypods.ui.dialogs.MultipointAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        // State authority lives in the bluetooth process; mirror it here.
    LaunchedEffect(Unit) { SonyRemoteState.start(context) }
    val sonyState by SonyRemoteState.state.collectAsState()

    val tabs = remember { MainTab.entries.toList() }
    var selectedTab by remember { mutableStateOf(MainTab.Earphones) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var connectingDeviceAddress by remember { mutableStateOf<String?>(null) }
    // Keep the navigation intent separate from the transport handshake. The
    // engine reports connected before capability probing finishes, so clearing
    // the connection marker at that point must not lose the request to open the
    // detail page once probeComplete becomes true.
    var pendingAutoOpenAddress by remember { mutableStateOf<String?>(null) }
    var pendingExternalDetailAddress by remember { mutableStateOf<String?>(null) }
    var showConnectErrorDialog by remember { mutableStateOf(false) }
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
    val logLevel = remember { mutableStateOf(appConfig.logLevel) }
    val earphonePrefs = remember { mutableStateOf(PodImagePrefs.load(prefs)) }
    val imageCacheRevision by PodImagePrefs.cacheRevision.collectAsState()

    // Model artwork is downloaded asynchronously after the Bluetooth snapshot.
    // Reload the metadata whenever the engine publishes a new connection/model
    // state; this complements the preference listener and covers a download that
    // completed before the listener was attached.
    LaunchedEffect(imageCacheRevision) {
        earphonePrefs.value = withContext(Dispatchers.IO) { PodImagePrefs.load(prefs) }
    }

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
            onMultipointAlertReply = { positive -> SonyBridge.replyMultipointAlert(context, positive) },
            onFixedSourceChange = { address -> SonyBridge.setFixedSource(context, address) },
            onMusicHandOverChange = { enabled -> SonyBridge.setMusicHandOver(context, enabled) },
            onRefresh = { SonyBridge.sendCommand(context, SonyBridge.CMD_REFRESH) },
        )
    }

    // The fusion device center can enter the module while the existing MainActivity
    // task is already alive. Keep this request separate from the normal device-picker
    // flow and consume it only after the requested Sony session has finished probing.
    LaunchedEffect(openEarphoneDetailAddress.value) {
        val target = openEarphoneDetailAddress.value?.trim()?.takeIf { it.isNotEmpty() }
        if (target != null) {
            pendingExternalDetailAddress = target
            pendingAutoOpenAddress = null
            selectedTab = MainTab.Earphones
            showDevicePicker = true
        }
    }

    LaunchedEffect(
        pendingExternalDetailAddress,
        sonyConnected,
        connectedDeviceAddress,
        sonyState.probeComplete,
    ) {
        val pending = pendingExternalDetailAddress ?: return@LaunchedEffect
        if (!canShowDetailPage || !connectedDeviceAddress.equals(pending, ignoreCase = true)) return@LaunchedEffect
        selectedTab = MainTab.Earphones
        showDevicePicker = false
        pendingExternalDetailAddress = null
        onExternalDetailRequestConsumed()
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
            val shouldAutoOpen = pendingAutoOpenAddress != null

            // The transport is connected even while the capability probe is in
            // flight; stop showing the row-level spinner but retain the separate
            // pendingAutoOpenAddress navigation intent.
            connectingDeviceAddress = null
            showConnectErrorDialog = false
            earphonePrefs.value = withContext(Dispatchers.IO) {
                PodImagePrefs.upsertConnected(
                    prefs = prefs,
                    address = connectedDeviceAddress,
                    name = displayTitle,
                )
            }

            if (sonyState.probeComplete && shouldAutoOpen) {
                selectedTab = MainTab.Earphones
                showDevicePicker = false
                pendingAutoOpenAddress = null
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
            showConnectErrorDialog = true
            showDevicePicker = true
            SonyBridge.sendCommand(context, SonyBridge.CMD_DISCONNECT)
        }
    }

    fun clearExternalDetailRequest() {
        pendingExternalDetailAddress = null
        onExternalDetailRequestConsumed()
    }

    fun onDeviceSelected(device: BluetoothDevice) {
        clearExternalDetailRequest()
        connectingDeviceAddress = device.address
        pendingAutoOpenAddress = device.address
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
        if (device.address.equals(connectedDeviceAddress, ignoreCase = true) || connectedDeviceAddress.isBlank()) {
            SonyBridge.sendCommand(context, SonyBridge.CMD_DISCONNECT)
        }
    }

    fun onConnectedDeviceClick() {
        if (!sonyConnected) return
        clearExternalDetailRequest()
        pendingAutoOpenAddress = null
        connectingDeviceAddress = null
        showDevicePicker = false
        selectedTab = MainTab.Earphones
    }

    fun backToDevicePicker() {
        clearExternalDetailRequest()
        pendingAutoOpenAddress = null
        showDevicePicker = true
    }

    val entryProvider = entryProvider<Screen> {
        entry<Screen.Main> {
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
                logLevel = logLevel,
                onLogLevelChange = {
                    logLevel.value = it
                    ConfigManager.updateLogLevel(prefs, it)
                },
                appLanguage = appLanguage,
                onAppLanguageChange = {
                    appLanguage.value = it
                    onAppLanguageChange(it)
                },
                onOpenTheme = { backStack.add(Screen.Theme) },
                onOpenAbout = { backStack.add(Screen.About) },
                onBackToDevicePicker = { backToDevicePicker() },
            )
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
    }
}

@Composable
fun appBackground(): Color = MiuixTheme.colorScheme.surface
