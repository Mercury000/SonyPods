package dev.sonypods.ui

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
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
import android.content.res.Configuration
import android.provider.Settings
import android.os.SystemClock
import dev.sonypods.hook.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.key
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.SonyRemoteState
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.PlaybackStatus
import dev.sonypods.protocol.GestureNoiseControlMode
import dev.sonypods.SonyPodsApp
import com.mercury.sonypods.R
import dev.sonypods.config.ConfigManager
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.ui.components.AppIcons
import dev.sonypods.ui.components.BarBackdropContent
import dev.sonypods.ui.components.BarBlurHost
import dev.sonypods.ui.components.BlurredBar
import dev.sonypods.ui.nav.BACKGROUND_PARALLAX
import dev.sonypods.ui.nav.BACKGROUND_SCALE_REDUCTION
import dev.sonypods.ui.nav.EFFECT_VISIBILITY_THRESHOLD
import dev.sonypods.ui.nav.IslandLayerBackHandlers
import dev.sonypods.ui.nav.IslandLayerMotion
import dev.sonypods.ui.nav.IslandLevelHost
import dev.sonypods.ui.nav.isBoundaryEngaged
import dev.sonypods.ui.nav.LAYER_ENTER_DURATION
import dev.sonypods.ui.nav.LAYER_EXIT_DURATION
import dev.sonypods.ui.nav.MAX_LAYERS
import dev.sonypods.ui.nav.PredictiveBackBackdrop
import dev.sonypods.ui.pages.GestureOperationsPage
import dev.sonypods.ui.pages.MoreSettingsPage
import dev.sonypods.ui.pages.MultipointSettingsPage
import dev.sonypods.ui.pages.PodDetailPage
import dev.sonypods.ui.pages.ReferencesPage
import dev.sonypods.ui.pages.TandemDebugPage
import dev.sonypods.ui.pages.ThemeSettingsPage
import dev.sonypods.ui.pages.VisibilitySettingsPage
import dev.sonypods.ui.dialogs.MultipointAlertDialog
import dev.sonypods.ui.dialogs.LeAudioAlertDialog
import dev.sonypods.ui.dialogs.LeAudioPairingHelpDialog
import dev.sonypods.ui.dialogs.PowerOffDialog
import dev.sonypods.utils.RootManager
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private const val CONNECT_TIMEOUT_MS = 25_000L

sealed interface Screen {
    data object Main : Screen
    data object References : Screen
    data object Theme : Screen
    data object TandemDebug : Screen
    data object Visibility : Screen

    /** The earphone detail flow, layered above [Main]. */
    data object EarphoneDetail : Screen
    data object EarphoneMoreSettings : Screen
    data object EarphoneGestureOperations : Screen
    data object EarphoneMultipointSettings : Screen
}

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainUI(
    themeMode: MutableState<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {},
    accentMode: MutableState<Int> = mutableStateOf(0),
    onAccentModeChange: (Int) -> Unit = {},
    floatingBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onFloatingBottomBarChange: (Boolean) -> Unit = {},
    blurBottomBar: MutableState<Boolean> = mutableStateOf(false),
    onBlurBottomBarChange: (Boolean) -> Unit = {},
    floatingBottomBarStyle: MutableState<Int> = mutableStateOf(0),
    onFloatingBottomBarStyleChange: (Int) -> Unit = {},
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
    // The floating bar's style: 0 = miuix default pill, 1 = iOS liquid glass. Only
    // meaningful while the floating bar itself is on.
    val useIosBottomBar = floatingBottomBar.value && floatingBottomBarStyle.value == 1
    // Constant page bottom padding like the reference (28dp); the vertical give comes
    // from the bottom-bar slot itself collapsing when the bar hides, not extra padding.
    val pageBottomContentPadding = 28.dp

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
    // The layered stack above the root — the reference's architecture: pushing adds a
    // layer; the device picker is the Earphones tab with no earphone layer pushed.
    var layers by remember { mutableStateOf<List<Screen>>(emptyList()) }
    val motion = remember { List(MAX_LAYERS) { IslandLayerMotion() } }
    // A popped level keeps rendering its last screen so its exit animation can play —
    // the reference keeps its `visibleDetail` set after closing for the same reason.
    // Only ever overwritten while a screen occupies the level, never cleared.
    val lastScreens = remember { mutableStateListOf<Screen?>(null, null, null) }
    SideEffect {
        repeat(MAX_LAYERS) { k ->
            // Guarded: SnapshotStateList.set records a write even for an equal value, and
            // this list is read during composition — an unconditional write would keep
            // scheduling recompositions forever.
            val screen = layers.getOrNull(k)
            if (screen != null && lastScreens[k] != screen) lastScreens[k] = screen
        }
    }
    val renderScreens: List<Screen?> = List(MAX_LAYERS) { k ->
        layers.getOrNull(k) ?: lastScreens[k]
    }

    val earphoneScreens = remember {
        setOf(
            Screen.EarphoneDetail,
            Screen.EarphoneMoreSettings,
            Screen.EarphoneGestureOperations,
            Screen.EarphoneMultipointSettings,
        )
    }
    val earphoneDetailOpen = layers.isNotEmpty() && layers.first() in earphoneScreens

    // Set while the user is deliberately on the device picker, so the auto-open effect
    // below does not drag them back into the detail page.
    var pickerRequested by remember { mutableStateOf(false) }

    fun popToEarphonePicker() {
        pickerRequested = true
        layers = emptyList()
    }

    fun openEarphoneDetail() {
        pickerRequested = false
        if (layers.firstOrNull() != Screen.EarphoneDetail) {
            layers = listOf(Screen.EarphoneDetail)
        }
    }

    fun openScreen(screen: Screen) {
        if (layers.lastOrNull() != screen && layers.size < MAX_LAYERS) {
            layers = layers + screen
        }
    }

    fun closeLayer() {
        if (layers.isNotEmpty()) layers = layers.dropLast(1)
    }

    val sonyActions = remember(context) {
        SonyDetailActions(
            onAncModeChange = { SonyBridge.setNoiseControl(context, it) },
            onWindNoiseReductionChange = { SonyBridge.setWindNoiseReduction(context, it) },
            onAmbientLevelChange = { SonyBridge.setAmbientLevel(context, it) },
            onAmbientVoiceModeChange = { SonyBridge.setAmbientVoice(context, it) },
            onSpeakToChatEnabledChange = { SonyBridge.setSpeakToChatEnabled(context, it) },
            onSpeakToChatSensitivityChange = { SonyBridge.setSpeakToChatSensitivity(context, it.name) },
            onSpeakToChatModeOutTimeChange = { SonyBridge.setSpeakToChatModeOutTime(context, it.name) },
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
            Log.d(
                "SonyPods-App",
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
            popToEarphonePicker()
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
        openEarphoneDetail()
        pendingExternalDetailAddress = null
        onExternalDetailRequestConsumed()
    }

    // Connection lost while the earphone flow is open: the detail page is gated on a
    // live, fully-probed session, so drop the flow back to the device picker.
    LaunchedEffect(canShowDetailPage) {
        if (!canShowDetailPage && earphoneDetailOpen) {
            popToEarphonePicker()
        }
    }

    // A live, fully-probed session opens its detail page on its own — the picker is only
    // shown while the user asked for it (or nothing is connected).
    LaunchedEffect(canShowDetailPage, pickerRequested, pendingExternalDetailAddress) {
        if (canShowDetailPage && !pickerRequested && pendingExternalDetailAddress == null &&
            layers.isEmpty()
        ) {
            openEarphoneDetail()
        }
    }

    // The only documented gap in the NTFY-maintained state is playback metadata
    // right after connect: the connection-time burst can be answered before the
    // phone's AVRCP data reaches the headset, and AVRCP settling does not
    // reliably produce an invalidation NTFY, so nothing would re-ask. That gap
    // has a visible symptom — ask again only when that symptom is present.
    // Otherwise opening the page is a read of the mirrored state: no commands.
    LaunchedEffect(earphoneDetailOpen, connectedDeviceAddress) {
        if (!earphoneDetailOpen || connectedDeviceAddress.isBlank()) return@LaunchedEffect
        if (!sonyState.supportsPlaybackControl) return@LaunchedEffect
        val metadataEmpty = sonyState.playbackTrack.isNullOrBlank() &&
            sonyState.playbackArtist.isNullOrBlank() &&
            sonyState.playbackAlbum.isNullOrBlank()
        if (metadataEmpty || sonyState.playbackStatus == PlaybackStatus.UNKNOWN) {
            SonyBridge.sendCommand(context, SonyBridge.CMD_REFRESH) {
                putExtra(SonyBridge.EXTRA_FORCE_REFRESH, true)
            }
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
                openEarphoneDetail()
                pendingAutoOpenAddress = null
                autoOpenAfterScopeRestart = false
            }
            Log.d("SonyPods-App", "Sony device connected: $displayTitle ($connectedDeviceAddress)")
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
            popToEarphonePicker()
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
                            Log.d("SonyPods-App", "startup control connection reconciliation requested")
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
        popToEarphonePicker()
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
        popToEarphonePicker()
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
            openEarphoneDetail()
        } else {
            pendingAutoOpenAddress = connectedDeviceAddress
        }
        selectedTab = MainTab.Earphones
    }

    fun backToDevicePicker() {
        clearExternalDetailRequest()
        pendingAutoOpenAddress = null
        autoOpenAfterScopeRestart = false
        popToEarphonePicker()
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
        popToEarphonePicker()
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

    // The reference's layered navigation runtime: per-boundary enter/exit effects and
    // predictive handlers, verbatim structure generalized to the bounded stack.
    val predictiveDistance: () -> Long = { predictiveBackDistance.value.coerceIn(25, 100).toLong() }

    repeat(MAX_LAYERS) { k ->
        val shown = layers.size > k
        LaunchedEffect(shown, motion[k].predictiveActive) {
            if (!motion[k].predictiveActive) {
                val target = if (shown) 1f else 0f
                val duration = if (shown) LAYER_ENTER_DURATION else LAYER_EXIT_DURATION
                coroutineScope {
                    launch {
                        motion[k].backdropIntensity.animateTo(
                            target,
                            tween(duration, easing = FastOutSlowInEasing),
                        )
                    }
                    launch {
                        motion[k].coveredDepth.animateTo(
                            target,
                            tween(duration, easing = FastOutSlowInEasing),
                        )
                    }
                }
            }
        }
        IslandLayerBackHandlers(
            enabled = predictiveBack.value && layers.size == k + 1,
            motion = motion[k],
            maxTranslationPercent = predictiveDistance,
            onDismissed = { layers = layers.take(k) },
        )
    }
    if (!predictiveBack.value) {
        BackHandler(enabled = layers.isNotEmpty()) { closeLayer() }
    }

    @Composable
    fun LayerScreenContent(screen: Screen) {
        when (screen) {
            Screen.References -> {
            val referencesScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            BarBlurHost(
                bottomBarBlurEnabled = false,
                topBarBlurEnabled = blurTopBar.value,
            ) {
                Scaffold(
                    topBar = {
                        BlurredBar(topGradient = true) {
                            TopAppBar(
                                title = stringResource(R.string.about_references),
                                largeTitle = stringResource(R.string.about_references),
                                color = Color.Transparent,
                                scrollBehavior = referencesScrollBehavior,
                                navigationIcon = {
                                    IconButton(onClick = { closeLayer() }) {
                                        Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.cd_back))
                                    }
                                }
                            )
                        }
                    }
                ) { padding ->
                    // See Screen.About: the page scrolls under the top bar.
                    BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(backgroundColor),
                        ) {
                            ReferencesPage(
                                modifier = Modifier
                                    .overScrollVertical()
                                    .nestedScroll(referencesScrollBehavior.nestedScrollConnection),
                                contentPadding = PaddingValues(
                                    top = padding.calculateTopPadding(),
                                    bottom = pageBottomContentPadding,
                                ),
                            )
                        }
                    }
                }
            }
            }
            Screen.Theme -> {
            val themeScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            BarBlurHost(
                bottomBarBlurEnabled = false,
                topBarBlurEnabled = blurTopBar.value,
            ) {
                Scaffold(
                    topBar = {
                        BlurredBar(topGradient = true) {
                            TopAppBar(
                                title = stringResource(R.string.theme_title),
                                largeTitle = stringResource(R.string.theme_title),
                                color = Color.Transparent,
                                scrollBehavior = themeScrollBehavior,
                                navigationIcon = {
                                    IconButton(onClick = { closeLayer() }) {
                                        Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.cd_back))
                                    }
                                }
                            )
                        }
                    }
                ) { padding ->
                    // See Screen.About: the page scrolls under the top bar.
                    BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(backgroundColor),
                        ) {
                            ThemeSettingsPage(
                                modifier = Modifier
                                    .overScrollVertical()
                                    .nestedScroll(themeScrollBehavior.nestedScrollConnection),
                                contentPadding = PaddingValues(
                                    top = padding.calculateTopPadding(),
                                    bottom = pageBottomContentPadding,
                                ),
                                themeMode = themeMode,
                                onThemeModeChange = onThemeModeChange,
                                accentMode = accentMode,
                                onAccentModeChange = onAccentModeChange,
                                floatingBottomBar = floatingBottomBar,
                                onFloatingBottomBarChange = onFloatingBottomBarChange,
                                blurBottomBar = blurBottomBar,
                                onBlurBottomBarChange = onBlurBottomBarChange,
                                floatingBottomBarStyle = floatingBottomBarStyle,
                                onFloatingBottomBarStyleChange = onFloatingBottomBarStyleChange,
                                blurTopBar = blurTopBar,
                                onBlurTopBarChange = onBlurTopBarChange,
                                predictiveBack = predictiveBack,
                                onPredictiveBackChange = onPredictiveBackChange,
                                predictiveBackDistance = predictiveBackDistance,
                                onPredictiveBackDistanceChange = onPredictiveBackDistanceChange,
                            )
                        }
                    }
                }
            }
            }
            Screen.Visibility -> {
            val visibilityScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            BarBlurHost(
                bottomBarBlurEnabled = false,
                topBarBlurEnabled = blurTopBar.value,
            ) {
                Scaffold(
                    topBar = {
                        BlurredBar(topGradient = true) {
                            TopAppBar(
                                title = stringResource(R.string.visibility_settings_title),
                                largeTitle = stringResource(R.string.visibility_settings_title),
                                color = Color.Transparent,
                                scrollBehavior = visibilityScrollBehavior,
                                navigationIcon = {
                                    IconButton(onClick = { closeLayer() }) {
                                        Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.cd_back))
                                    }
                                }
                            )
                        }
                    }
                ) { padding ->
                    // See Screen.About: the page scrolls under the top bar.
                    BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(backgroundColor),
                        ) {
                            VisibilitySettingsPage(
                                modifier = Modifier
                                    .overScrollVertical()
                                    .nestedScroll(visibilityScrollBehavior.nestedScrollConnection),
                                contentPadding = PaddingValues(
                                    top = padding.calculateTopPadding(),
                                    bottom = pageBottomContentPadding,
                                ),
                                visibility = visibility.value,
                                onVisibilityChange = { newVisibility ->
                                    visibility.value = newVisibility
                                    ConfigManager.updateVisibility(newVisibility)
                                },
                            )
                        }
                    }
                }
            }
            }
            Screen.TandemDebug -> {
            val debugScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            var clearLogsRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            BarBlurHost(
                bottomBarBlurEnabled = false,
                topBarBlurEnabled = blurTopBar.value,
            ) {
                Scaffold(
                    topBar = {
                        BlurredBar(topGradient = true) {
                            TopAppBar(
                                title = stringResource(R.string.tandem_debug_title),
                                largeTitle = stringResource(R.string.tandem_debug_title),
                                color = Color.Transparent,
                                scrollBehavior = debugScrollBehavior,
                                navigationIcon = {
                                    IconButton(onClick = { closeLayer() }) {
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
                    }
                ) { padding ->
                    // See Screen.About: the page scrolls under the top bar.
                    BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(backgroundColor),
                        ) {
                            TandemDebugPage(
                                modifier = Modifier.nestedScroll(debugScrollBehavior.nestedScrollConnection),
                                contentPadding = PaddingValues(
                                    top = padding.calculateTopPadding(),
                                ),
                                clearRequest = clearLogsRequest,
                            )
                        }
                    }
                }
            }
            }
            Screen.EarphoneDetail -> {
            val detailScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val detailTitle = displayTitle.ifEmpty { stringResource(R.string.pod_info) }
            // Own the list state here so returning from a sub-page restores the scroll
            // position; a new detail session/device still starts from the top.
            val detailListState = remember(connectedDeviceAddress) { LazyListState() }
            // The hero image belongs to the device, not the connection. The snapshot
            // drops the address the moment the link drops, and resolving by an empty
            // address would swap the user's own headset picture for the generic
            // placeholder mid-view — so resolve against the last known address instead.
            var lastKnownImageAddress by remember { mutableStateOf(connectedDeviceAddress) }
            if (connectedDeviceAddress.isNotBlank()) {
                lastKnownImageAddress = connectedDeviceAddress
            }
            val imageLookupAddress = connectedDeviceAddress.ifBlank { lastKnownImageAddress }
            val currentEarphonePref = earphonePrefs.value.firstOrNull {
                it.address.equals(imageLookupAddress, ignoreCase = true)
            }
            var showPowerOffDialog by remember { mutableStateOf(false) }
            BarBlurHost(
                bottomBarBlurEnabled = false,
                topBarBlurEnabled = blurTopBar.value,
            ) {
                Scaffold(
                    topBar = {
                        BlurredBar(topGradient = true) {
                            val navigationIcon: @Composable () -> Unit = {
                                IconButton(onClick = { backToDevicePicker() }) {
                                    Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.cd_back))
                                }
                            }
                            val actions: @Composable RowScope.() -> Unit = {
                                if (sonyState.supportsPowerOff) {
                                    IconButton(onClick = { showPowerOffDialog = true }) {
                                        Icon(
                                            imageVector = AppIcons.Power,
                                            modifier = Modifier.size(23.dp),
                                            contentDescription = stringResource(R.string.power_off),
                                        )
                                    }
                                }
                                IconButton(onClick = { openSystemHeadsetSettings() }) {
                                    Icon(
                                        imageVector = MiuixIcons.Settings,
                                        contentDescription = stringResource(R.string.click_action_system_settings),
                                    )
                                }
                            }
                            if (isLandscape) {
                                SmallTopAppBar(
                                    title = detailTitle,
                                    color = Color.Transparent,
                                    scrollBehavior = detailScrollBehavior,
                                    navigationIcon = navigationIcon,
                                    actions = actions,
                                )
                            } else {
                                TopAppBar(
                                    title = detailTitle,
                                    largeTitle = detailTitle,
                                    color = Color.Transparent,
                                    scrollBehavior = detailScrollBehavior,
                                    navigationIcon = navigationIcon,
                                    actions = actions,
                                )
                            }
                        }
                    },
                ) { padding ->
                    BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(backgroundColor),
                        ) {
                            PodDetailPage(
                                modifier = Modifier
                                    .overScrollVertical()
                                    .nestedScroll(detailScrollBehavior.nestedScrollConnection),
                                contentPadding = padding,
                                bottomContentPadding = pageBottomContentPadding,
                                podName = detailTitle,
                                uiState = sonyState,
                                actions = sonyActions.copy(
                                    onOpenMoreSettings = { openScreen(Screen.EarphoneMoreSettings) },
                                    onOpenGestureOperations = { openScreen(Screen.EarphoneGestureOperations) },
                                    onOpenMultipointSettings = { openScreen(Screen.EarphoneMultipointSettings) },
                                ),
                                visibility = visibility.value,
                                listState = detailListState,
                                boxImagePath = currentEarphonePref?.boxImagePath,
                                boxImageRevision = currentEarphonePref?.imageRevision ?: 0L,
                            )
                            PowerOffDialog(
                                show = showPowerOffDialog,
                                deviceName = displayTitle,
                                onDismissRequest = { showPowerOffDialog = false },
                                onConfirm = {
                                    showPowerOffDialog = false
                                    sonyActions.onPowerOff()
                                },
                            )
                        }
                    }
                }
            }
            }
            Screen.EarphoneMoreSettings -> {
            val moreScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            BarBlurHost(
                bottomBarBlurEnabled = false,
                topBarBlurEnabled = blurTopBar.value,
            ) {
                Scaffold(
                    topBar = {
                        BlurredBar(topGradient = true) {
                            TopAppBar(
                                title = stringResource(R.string.more_settings_title),
                                largeTitle = stringResource(R.string.more_settings_title),
                                color = Color.Transparent,
                                scrollBehavior = moreScrollBehavior,
                                navigationIcon = {
                                    IconButton(onClick = { closeLayer() }) {
                                        Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.cd_back))
                                    }
                                },
                            )
                        }
                    },
                ) { padding ->
                    BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(backgroundColor),
                        ) {
                            MoreSettingsPage(
                                modifier = Modifier
                                    .overScrollVertical()
                                    .nestedScroll(moreScrollBehavior.nestedScrollConnection),
                                contentPadding = padding,
                                bottomContentPadding = pageBottomContentPadding,
                                uiState = sonyState,
                                actions = sonyActions.copy(
                                    onOpenGestureOperations = { openScreen(Screen.EarphoneGestureOperations) },
                                    onOpenMultipointSettings = { openScreen(Screen.EarphoneMultipointSettings) },
                                ),
                                visibility = visibility.value,
                            )
                        }
                    }
                }
            }
            }
            Screen.EarphoneGestureOperations -> {
            val gestureScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            BarBlurHost(
                bottomBarBlurEnabled = false,
                topBarBlurEnabled = blurTopBar.value,
            ) {
                Scaffold(
                    topBar = {
                        BlurredBar(topGradient = true) {
                            TopAppBar(
                                title = stringResource(R.string.card_gesture_title),
                                largeTitle = stringResource(R.string.card_gesture_title),
                                color = Color.Transparent,
                                scrollBehavior = gestureScrollBehavior,
                                navigationIcon = {
                                    IconButton(onClick = { closeLayer() }) {
                                        Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.cd_back))
                                    }
                                },
                            )
                        }
                    },
                ) { padding ->
                    BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(backgroundColor),
                        ) {
                            GestureOperationsPage(
                                modifier = Modifier
                                    .overScrollVertical()
                                    .nestedScroll(gestureScrollBehavior.nestedScrollConnection),
                                contentPadding = padding,
                                bottomContentPadding = pageBottomContentPadding,
                                uiState = sonyState,
                                actions = sonyActions,
                                visibility = visibility.value,
                            )
                        }
                    }
                }
            }
            }
            Screen.EarphoneMultipointSettings -> {
            val multipointScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            // Official behaviour: leaving the add-device flow drops the headset back
            // to NORMAL_MODE (SC sends the cancel when the waiting screen closes).
            val pairingMode by rememberUpdatedState(sonyState.multipoint.pairingMode)
            val cancelPairingMode by rememberUpdatedState(sonyActions.onMultipointPairingModeChange)
            DisposableEffect(Unit) {
                onDispose {
                    if (pairingMode) cancelPairingMode(false)
                }
            }
            BarBlurHost(
                bottomBarBlurEnabled = false,
                topBarBlurEnabled = blurTopBar.value,
            ) {
                Scaffold(
                    topBar = {
                        BlurredBar(topGradient = true) {
                            TopAppBar(
                                title = stringResource(R.string.mp_connect_two_title),
                                largeTitle = stringResource(R.string.mp_connect_two_title),
                                color = Color.Transparent,
                                scrollBehavior = multipointScrollBehavior,
                                navigationIcon = {
                                    IconButton(onClick = { closeLayer() }) {
                                        Icon(imageVector = MiuixIcons.Back, contentDescription = stringResource(R.string.cd_back))
                                    }
                                },
                            )
                        }
                    },
                ) { padding ->
                    BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(backgroundColor),
                        ) {
                            MultipointSettingsPage(
                                modifier = Modifier
                                    .overScrollVertical()
                                    .nestedScroll(multipointScrollBehavior.nestedScrollConnection),
                                contentPadding = padding,
                                bottomContentPadding = pageBottomContentPadding,
                                uiState = sonyState,
                                actions = sonyActions,
                            )
                        }
                    }
                }
            }
            }
            Screen.Main -> Unit
        }
    }

    // Outer transparent Scaffold: provides a root-level MiuixPopupHost so that
    // OverlayDialog-based composables (e.g. MultipointAlertDialog) render even
    // when invoked outside the per-screen Scaffolds. Inner Scaffolds propagate
    // LocalRootDialogStates up to this host. Zero contentWindowInsets so the
    // outer host does not steal insets the inner Scaffolds rely on.
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        // The reference's root host: captures the root for the bottom bar's glass and
        // the predictive backdrop, exactly like AppShell's root BarBlurHost.
        val rootEngaged = layers.isNotEmpty() || motion[0].predictiveActive ||
            motion[0].backdropIntensity.value > EFFECT_VISIBILITY_THRESHOLD
        BarBlurHost(
            bottomBarBlurEnabled = blurBottomBar.value,
            topBarBlurEnabled = false,
            captureForEffects = rootEngaged,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!remoteDataReady || !hasAppliedDefaultTab) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(backgroundColor),
                    )
                } else {
                    key(mainTabsGeneration) {
                        MainTabsScaffold(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val depth = motion[0].coveredDepth.value.coerceIn(0f, 1f)
                                    scaleX = 1f - depth * BACKGROUND_SCALE_REDUCTION
                                    scaleY = scaleX
                                    translationX = -size.width * depth * BACKGROUND_PARALLAX
                                },
                    tabs = tabs,
                    selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                floatingBottomBar = floatingBottomBar.value,
                blurBottomBar = blurBottomBar.value,
                iosBottomBar = useIosBottomBar,
                bottomBarVisible = layers.isEmpty() && !motion[0].predictiveActive &&
                    motion[0].coveredDepth.value < EFFECT_VISIBILITY_THRESHOLD,
                blurTopBar = blurTopBar,
                backgroundColor = backgroundColor,
                pageBottomContentPadding = pageBottomContentPadding,
                xposedService = xposedService,
                bluetoothServiceResponsive = bluetoothServiceResponsive,
                bluetoothEnabled = bluetoothState.enabled,
                bondedDeviceCount = bluetoothState.bondedCount,
                onBluetoothStatusClick = { openBluetoothSettings() },
                onPairedBluetoothClick = { openDevicePicker() },
                displayTitle = displayTitle,
                sonyState = sonyState,
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
                onOpenVisibility = { openScreen(Screen.Visibility) },
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
                onOpenTandemDebug = { openScreen(Screen.TandemDebug) },
                fakeDeviceId = fakeDeviceId,
                onFakeDeviceIdChange = {
                    fakeDeviceId.value = it
                    ConfigManager.updateFakeDeviceId(it)
                },
                onOpenTheme = { openScreen(Screen.Theme) },
                onOpenReferences = { openScreen(Screen.References) },
                showRestartScopeDialog = showRestartScopeDialog,
                restartingScopes = restartingScopes,
                onShowRestartScopeDialog = { showRestartScopeDialog = true },
                onDismissRestartScopeDialog = { showRestartScopeDialog = false },
                onRestartScopes = { restartScopes(it) },
                )
            }
                }

                PredictiveBackBackdrop(
                    intensity = motion[0].backdropIntensity.value,
                    visible = motion[0].backdropIntensity.value > EFFECT_VISIBILITY_THRESHOLD,
                    modifier = Modifier.fillMaxSize(),
                )
                BarBlurHost(
                    bottomBarBlurEnabled = false,
                    topBarBlurEnabled = false,
                    captureForEffects = motion.isBoundaryEngaged(1, layers.size),
                ) {
                    IslandLevelHost(
                        level = 0,
                        layerCount = layers.size,
                        renderScreens = renderScreens,
                        motion = motion,
                        maxTranslationPercent = predictiveDistance,
                        screenContent = { LayerScreenContent(it) },
                    )
                }
            }
        }

        // Device-driven multipoint reconnection alert: shown globally once the engine
        // reports a pending FIXED_MESSAGE alert (V2 Table1 ALERT_NTFY_PARAM 0x99).
        val pendingAlertMsgType = sonyState.multipoint.pendingAlertMessageType
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

