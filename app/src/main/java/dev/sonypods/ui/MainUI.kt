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
import androidx.compose.ui.Alignment
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sonypods.bridge.SonyRemoteState
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.PlaybackStatus
import dev.sonypods.protocol.GestureNoiseControlMode
import dev.sonypods.SonyPodsApp
import com.mercury.sonypods.R
import dev.sonypods.config.CardLocation
import dev.sonypods.config.ConfigManager
import dev.sonypods.config.LegacyConfigMigrator
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.ui.components.AppIcons
import dev.sonypods.ui.components.BarBackdropContent
import dev.sonypods.ui.components.BarBlurHost
import dev.sonypods.ui.components.BlurredBar
import dev.sonypods.ui.components.shouldShowSplitPane
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
import dev.sonypods.ui.pages.EqDetailPage
import dev.sonypods.ui.pages.GestureOperationsPage
import dev.sonypods.ui.pages.MoreSettingsPage
import dev.sonypods.ui.pages.MultipointSettingsPage
import dev.sonypods.ui.pages.PodDetailPage
import dev.sonypods.ui.pages.earphonePageReadiness
import dev.sonypods.ui.pages.ReferencesPage
import dev.sonypods.ui.pages.TandemDebugPage
import dev.sonypods.ui.pages.ThemeSettingsPage
import dev.sonypods.ui.pages.VisibilitySettingsPage
import dev.sonypods.ui.dialogs.MultipointAlertDialog
import dev.sonypods.ui.dialogs.LeAudioAlertDialog
import dev.sonypods.ui.dialogs.LeAudioPairingHelpDialog
import dev.sonypods.ui.dialogs.PowerOffDialog
import dev.sonypods.utils.RootManager
import dev.sonypods.utils.SoundConnectPrefs
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

sealed interface Screen {
    data object Main : Screen
    data object References : Screen
    data object Theme : Screen
    data object TandemDebug : Screen
    data object Visibility : Screen

    data object EarphoneMoreSettings : Screen
    data object EarphoneGestureOperations : Screen
    data object EarphoneMultipointSettings : Screen
    data object EarphoneEqDetail : Screen
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
    // 声压轮询门控的一部分:模块是否在前台(后台时 composition 仍在,须显式观察)。
    val lifecycleOwner = LocalLifecycleOwner.current
    var appForeground by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            appForeground = when (event) {
                Lifecycle.Event.ON_START -> true
                Lifecycle.Event.ON_STOP -> false
                else -> appForeground
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Sound Connect owns the persistent Safe Listening switch and the headphone has
    // no readable copy of it, so mirror SC's own preference into the config the
    // bluetooth process reads. Refreshing on every foreground also covers the return
    // from Sound Connect, after its Tandem lease is released.
    LaunchedEffect(appForeground) {
        if (!appForeground) return@LaunchedEffect
        val mode = withContext(Dispatchers.IO) { SoundConnectPrefs.readSafeListeningMode() }
        ConfigManager.updateScSafeListeningMode(
            when (mode) {
                true -> ConfigManager.SC_SL_MODE_ON
                false -> ConfigManager.SC_SL_MODE_OFF
                null -> ConfigManager.SC_SL_MODE_UNKNOWN
            }
        )
    }

        // State authority lives in the bluetooth process; mirror it here.
    LaunchedEffect(Unit) { SonyRemoteState.start(context) }
    val sonyState by SonyRemoteState.state.collectAsState()

    val tabs = remember { MainTab.entries.toList() }
    var selectedTab by remember { mutableStateOf(MainTab.Module) }
    var hasAppliedDefaultTab by remember { mutableStateOf(false) }
    var mainTabsGeneration by remember { mutableIntStateOf(0) }
    var bluetoothState by remember { mutableStateOf(readBluetoothState(context)) }
    var xposedService by remember { mutableStateOf(SonyPodsApp.xposedService) }
    var showRestartScopeDialog by remember { mutableStateOf(false) }
    var restartingScopes by remember { mutableStateOf(false) }
    var pendingExternalDetailAddress by remember { mutableStateOf<String?>(null) }
    var lastBluetoothServiceAliveMs by remember { mutableStateOf(0L) }
    var bluetoothServiceResponsive by remember { mutableStateOf(false) }
    var hasRequestedStartupConnection by remember { mutableStateOf(false) }
    val backgroundColor = appBackground()
    // The floating bar's style: 0 = miuix default pill, 1 = iOS liquid glass. Only
    // meaningful while the floating bar itself is on.
    val useIosBottomBar = floatingBottomBar.value && floatingBottomBarStyle.value == 1
    val useRail = shouldShowSplitPane() && !floatingBottomBar.value && !useIosBottomBar
    // Constant page bottom padding like the reference (28dp); the vertical give comes
    // from the bottom-bar slot itself collapsing when the bar hides, not extra padding.
    val pageBottomContentPadding = 28.dp

    val appConfig = remember { ConfigManager.current() }
    val notificationClickAction = remember { mutableStateOf(LegacyConfigMigrator.readNotificationClickAction(context)) }
    val notificationEnabled = remember { mutableStateOf(appConfig.notificationEnabled) }
    val popupOnConnect = remember { mutableStateOf(appConfig.popupOnConnect) }
    val connectDialogMode = remember { mutableStateOf(appConfig.connectDialogMode) }
    val popupAllowlist = remember { mutableStateOf(appConfig.popupAllowlist) }
    val popupDenylist = remember { mutableStateOf(appConfig.popupDenylist) }
    val suppressPopupInGameOrLandscape = remember { mutableStateOf(appConfig.suppressPopupInGameOrLandscape) }
    val moreClickAction = remember { mutableStateOf(LegacyConfigMigrator.readMoreClickAction(context)) }
    val fusionMoreClickAction = remember { mutableStateOf(appConfig.fusionMoreClickAction) }
    val ignoreRandomLePairingRequests = remember { mutableStateOf(appConfig.ignoreRandomLePairingRequests) }
    val desktopIconHidden = remember { mutableStateOf(isLauncherIconHidden(context)) }
    val logLevel = remember { mutableStateOf(appConfig.logLevel) }
    val fakeDeviceId = remember { mutableStateOf(appConfig.fakeDeviceId) }
    val islandMode = remember { mutableStateOf(appConfig.superIslandMode) }
    val islandDurationSeconds = remember { mutableStateOf(appConfig.islandDurationSeconds) }
    val ancCycleModes = remember { mutableStateOf(appConfig.ancCycleModes) }
    val startupTab = remember { mutableStateOf(LegacyConfigMigrator.readStartupTab(context)) }
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
    // layer. The headset's own page is the Earphones tab itself, so an empty stack means
    // that page; only its sub-pages are layers.
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
            Screen.EarphoneMoreSettings,
            Screen.EarphoneGestureOperations,
            Screen.EarphoneMultipointSettings,
            Screen.EarphoneEqDetail,
        )
    }
    val earphoneDetailOpen = layers.isNotEmpty() && layers.first() in earphoneScreens
    // 声压卡片实际可见 = 它所在的页(详情页/更多设置页,取决于显隐配置)是栈顶、
    // 未被上层覆盖。仅这时才轮询。
    //
    // 详情页现在是耳机 Tab 本身而不是一层,所以"在栈顶"等价于层栈为空且选中耳机 Tab。
    // 这里必须补上 canShowDetailPage:以前详情页只在会话探完(自动进入的门槛)才被压栈,
    // 层栈判断顺带把"还在探能力"这段窗口排除掉了;换成 Tab 之后层栈一直是空的,少了这一条
    // 就会在初始值突发还没跑完时开始发 0x5A/0x5B,和连接突发抢同一条串行 ACK 管道——
    // 而初始值就绪本身是靠"没有未完成写"判定的。
    val slCardOnTop = canShowDetailPage && when (visibility.value.safeListening) {
        CardLocation.DETAIL -> layers.isEmpty() && selectedTab == MainTab.Earphones
        CardLocation.MORE -> layers.lastOrNull() == Screen.EarphoneMoreSettings
        CardLocation.HIDDEN -> false
    }

    /**
     * Close every sub-page stacked over the earphone tab.
     *
     * There is no device list to fall back to any more: the earphone tab *is* the headset's control
     * page when a session is live and the "no headset" notice when it is not, so the deepest the
     * flow can be popped is the tab itself.
     */
    fun popToEarphoneTab() {
        layers = emptyList()
    }

    fun focusEarphoneTab() {
        selectedTab = MainTab.Earphones
        layers = emptyList()
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

    // The startup page is an app-local preference now, read synchronously at
    // composition: the default tab applies on the first frame without waiting for the
    // LSPosed remote-pref store, so the module UI stays usable while the module is
    // disabled. The first launch after the app-only split can compose before the
    // migration lands the saved value locally; the bind re-application below closes
    // that window.
    fun applyStartupTabSelection() {
        val configuredStartupTab = startupTab.value
        val target = if (
            !openEarphoneDetailAddress.value.isNullOrBlank() ||
            configuredStartupTab == ConfigManager.STARTUP_TAB_EARPHONES
        ) {
            MainTab.Earphones
        } else {
            MainTab.Module
        }
        if (selectedTab != target) {
            selectedTab = target
            mainTabsGeneration++
        }
    }

    LaunchedEffect(Unit) {
        if (hasAppliedDefaultTab) return@LaunchedEffect
        applyStartupTabSelection()
        hasAppliedDefaultTab = true
        Log.d(
            "SonyPods-App",
            "startup page applied config=${startupTab.value} selected=$selectedTab connected=$sonyConnected",
        )
    }

    // The fusion device center can enter the module while the existing MainActivity
    // task is already alive. It asks for one specific headset, so hold the request until
    // that session has finished probing rather than showing whatever is live now.
    LaunchedEffect(openEarphoneDetailAddress.value) {
        val target = openEarphoneDetailAddress.value?.trim()?.takeIf { it.isNotEmpty() }
        if (target != null) {
            pendingExternalDetailAddress = target
            focusEarphoneTab()
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
        focusEarphoneTab()
        pendingExternalDetailAddress = null
        onExternalDetailRequestConsumed()
    }

    // The session died while a sub-page of the earphone tab was open. Those pages are gated
    // on a live, fully-probed session, so close them; the tab underneath stays and switches
    // itself to the not-ready presentation.
    LaunchedEffect(canShowDetailPage) {
        if (!canShowDetailPage && earphoneDetailOpen) {
            layers = emptyList()
        }
    }

    // 声压轮询仅在声压卡片实际可见时进行:模块前台 + 卡片所在页是栈顶 + 控制连接就绪。
    LaunchedEffect(appForeground, slCardOnTop, sonyConnected, sonyState.supportsSafeListening) {
        SonyBridge.setSafeListeningPollActive(
            context,
            appForeground && slCardOnTop && sonyConnected && sonyState.supportsSafeListening,
        )
    }
    DisposableEffect(Unit) {
        onDispose { SonyBridge.setSafeListeningPollActive(context, false) }
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
    // associated with its Bluetooth address. No navigation happens here any more — the
    // earphone tab renders the live session wherever the user already is.
    LaunchedEffect(
        sonyConnected,
        connectedDeviceAddress,
        sonyState.capabilitiesKnown,
        sonyState.initialValuesReady,
    ) {
        if (sonyConnected && connectedDeviceAddress.isNotBlank()) {
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
            Log.d("SonyPods-App", "Sony device connected: $displayTitle ($connectedDeviceAddress)")
        }
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
        notificationEnabled.value = c.notificationEnabled
        popupOnConnect.value = c.popupOnConnect
        connectDialogMode.value = c.connectDialogMode
        popupAllowlist.value = c.popupAllowlist
        popupDenylist.value = c.popupDenylist
        suppressPopupInGameOrLandscape.value = c.suppressPopupInGameOrLandscape
        fusionMoreClickAction.value = c.fusionMoreClickAction
        ignoreRandomLePairingRequests.value = c.ignoreRandomLePairingRequests
        logLevel.value = c.logLevel
        fakeDeviceId.value = c.fakeDeviceId
        islandMode.value = c.superIslandMode
        islandDurationSeconds.value = c.islandDurationSeconds
        ancCycleModes.value = c.ancCycleModes
        visibility.value = c.visibility
        earphonePrefs.value = PodImagePrefs.loadCurrent()
        // First launch after the app-only split: migrateAppOnlyPrefsToUi ran during
        // bind (before this listener fired), so local prefs now holds the startup tab
        // the user saved in the old remote config. Apply it if composition already
        // read a default; skip while a detail flow is open so a fast manual
        // navigation is never yanked.
        val migratedStartupTab = LegacyConfigMigrator.readStartupTab(context)
        if (migratedStartupTab != startupTab.value) {
            startupTab.value = migratedStartupTab
            if (layers.isEmpty()) applyStartupTabSelection()
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

    fun clearPodConnectionState() {
        popToEarphoneTab()
        selectedTab = MainTab.Earphones
    }

    fun clearExternalDetailRequest() {
        pendingExternalDetailAddress = null
        onExternalDetailRequestConsumed()
    }

    fun openBluetoothSettings() {
        val action = if (bluetoothState.enabled) Settings.ACTION_BLUETOOTH_SETTINGS else BluetoothAdapter.ACTION_REQUEST_ENABLE
        Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(this) }
                .onFailure { Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    fun showEarphoneTab() {
        clearExternalDetailRequest()
        focusEarphoneTab()
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
            onDismissed = {
                // A predictive commit already slid the layer off-screen; drop its retained
                // content so the post-commit recomposition cannot replay an exit animation.
                lastScreens[k] = null
                layers = layers.take(k)
            },
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
            var debugPaused by remember { mutableStateOf(false) }
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
                                    IconButton(onClick = { debugPaused = !debugPaused }) {
                                        Icon(
                                            imageVector = if (debugPaused) MiuixIcons.Play else MiuixIcons.Pause,
                                            contentDescription = if (debugPaused) "Resume" else "Pause",
                                        )
                                    }
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
                                paused = debugPaused,
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
                                    onOpenEqDetail = { openScreen(Screen.EarphoneEqDetail) },
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
            Screen.EarphoneEqDetail -> {
            val eqScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            BarBlurHost(
                bottomBarBlurEnabled = false,
                topBarBlurEnabled = blurTopBar.value,
            ) {
                Scaffold(
                    topBar = {
                        BlurredBar(topGradient = true) {
                            TopAppBar(
                                title = stringResource(R.string.sony_eq_title),
                                largeTitle = stringResource(R.string.sony_eq_title),
                                color = Color.Transparent,
                                scrollBehavior = eqScrollBehavior,
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
                            EqDetailPage(
                                modifier = Modifier
                                    .overScrollVertical()
                                    .nestedScroll(eqScrollBehavior.nestedScrollConnection),
                                uiState = sonyState,
                                actions = sonyActions,
                                contentPadding = padding,
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
                if (!hasAppliedDefaultTab) {
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
                                    if (useRail) {
                                        // miuix-wide-screen: the covered page (Rail + content)
                                        // parallaxes at 25 % width so the entire previous
                                        // page slides as one visible unit.
                                        translationX = -size.width * depth * 0.25f
                                        alpha = 1f - 0.1f * depth
                                    } else {
                                        scaleX = 1f - depth * BACKGROUND_SCALE_REDUCTION
                                        scaleY = scaleX
                                        translationX = -size.width * depth * BACKGROUND_PARALLAX
                                    }
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
                onPairedBluetoothClick = { showEarphoneTab() },
                earphoneTab = { earphoneBottomPadding ->
                    val detailScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
                    val isLandscape =
                        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    // The headset is the page's subject, so its identity outlives the link — and
                    // outlives the process. `remember` alone only survives while this composition
                    // does, which is why a fresh launch with nothing connected showed the generic
                    // placeholder: the fallback has to come from the persisted per-device record.
                    // PodImagePrefs.upsertConnected stamps `lastConnectedAt` on every connect, so the
                    // newest of those is the headset the user last used.
                    val lastUsedPref = remember(earphonePrefs.value) {
                        earphonePrefs.value.maxByOrNull { it.lastConnectedAt }
                    }
                    var lastKnownTitle by remember { mutableStateOf("") }
                    // Only an operable session may set or supply the title. A half-probed one reports
                    // the neutral placeholder until DEVICE_INFO answers, and a teardown opens exactly
                    // such a session for a moment — following it flashed "Sony headphones" over the
                    // real name, and worse, latched it as the remembered one.
                    if (canShowDetailPage && displayTitle.isNotEmpty()) lastKnownTitle = displayTitle
                    val detailTitle = displayTitle
                        .takeIf { canShowDetailPage }
                        .orEmpty()
                        .ifEmpty { lastKnownTitle }
                        .ifEmpty { lastUsedPref?.name.orEmpty() }
                        .ifEmpty { stringResource(R.string.pod_info) }
                    var lastKnownImageAddress by remember { mutableStateOf(connectedDeviceAddress) }
                    if (connectedDeviceAddress.isNotBlank()) {
                        lastKnownImageAddress = connectedDeviceAddress
                    }
                    // Own the list state here so returning from a sub-page restores the scroll
                    // position; a new headset still starts from the top.
                    val detailListState = remember(lastKnownImageAddress) { LazyListState() }
                    val imageLookupAddress = connectedDeviceAddress
                        .ifBlank { lastKnownImageAddress }
                        .ifBlank { lastUsedPref?.address.orEmpty() }
                    val currentEarphonePref = earphonePrefs.value.firstOrNull {
                        it.address.equals(imageLookupAddress, ignoreCase = true)
                    }
                    var showPowerOffDialog by remember { mutableStateOf(false) }
                    // What the page renders. While the control channel is unusable it is frozen on
                    // the last operable snapshot, because a teardown does not go straight to empty:
                    // the stack battery lands a single level before Tandem's L/R and case arrive, and
                    // a Tandem-only loss can even open a short recovery session on the other identity
                    // (`phase=RECOVERING` in the engine log). Following those repaints the layout two
                    // or three times on the way down — default → single battery → three battery — for
                    // a page the user cannot touch anyway. Greyed means "this is what the headset last
                    // told us", so hold that.
                    var lastReadyState by remember { mutableStateOf(sonyState) }
                    if (canShowDetailPage) lastReadyState = sonyState
                    val pageState = if (canShowDetailPage) sonyState else lastReadyState
                    // Losing readiness also freezes the scroll: the page swallows pointer events, so
                    // whatever position and collapse fraction it was left at can no longer be undone
                    // by the user. miuix collapses the large title into a centred small one as you
                    // scroll, and being stuck there while the page cannot scroll reads as a glitch.
                    LaunchedEffect(canShowDetailPage) {
                        if (!canShowDetailPage) {
                            detailScrollBehavior.state.heightOffset = 0f
                            detailScrollBehavior.state.contentOffset = 0f
                            runCatching { detailListState.scrollToItem(0) }
                        }
                    }
                    BarBlurHost(
                        bottomBarBlurEnabled = false,
                        topBarBlurEnabled = blurTopBar.value,
                    ) {
                        Scaffold(
                            topBar = {
                                BlurredBar(topGradient = true) {
                                    val title = detailTitle
                                    val actions: @Composable RowScope.() -> Unit = {
                                        if (canShowDetailPage && sonyState.supportsPowerOff) {
                                            IconButton(onClick = { showPowerOffDialog = true }) {
                                                Icon(
                                                    imageVector = AppIcons.Power,
                                                    modifier = Modifier.size(23.dp),
                                                    contentDescription = stringResource(R.string.power_off),
                                                )
                                            }
                                        }
                                        // With nothing connected there is no per-device page to open,
                                        // so the same button goes to the Bluetooth list instead — the
                                        // one place the user can do something about it.
                                        IconButton(
                                            onClick = {
                                                if (sonyConnected && connectedDeviceAddress.isNotBlank()) {
                                                    openSystemHeadsetSettings()
                                                } else {
                                                    openBluetoothSettings()
                                                }
                                            },
                                        ) {
                                            Icon(
                                                imageVector = MiuixIcons.Settings,
                                                contentDescription = stringResource(R.string.click_action_system_settings),
                                            )
                                        }
                                    }
                                    if (isLandscape) {
                                        SmallTopAppBar(
                                            title = title,
                                            color = Color.Transparent,
                                            scrollBehavior = detailScrollBehavior,
                                            actions = actions,
                                        )
                                    } else {
                                        TopAppBar(
                                            title = title,
                                            largeTitle = title,
                                            color = Color.Transparent,
                                            scrollBehavior = detailScrollBehavior,
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
                                    // Always the headset page, never a separate empty state: with
                                    // nothing connected it renders the last headset's name and image
                                    // over empty battery/control blocks, which is the same shape as
                                    // the first moments of a session. One layout, greyed or not.
                                    PodDetailPage(
                                        modifier = Modifier
                                            .overScrollVertical()
                                            .nestedScroll(detailScrollBehavior.nestedScrollConnection)
                                            .earphonePageReadiness(canShowDetailPage),
                                        contentPadding = padding,
                                        bottomContentPadding = earphoneBottomPadding,
                                        podName = detailTitle,
                                        uiState = pageState,
                                        actions = sonyActions.copy(
                                            onOpenMoreSettings = { openScreen(Screen.EarphoneMoreSettings) },
                                            onOpenGestureOperations = { openScreen(Screen.EarphoneGestureOperations) },
                                            onOpenMultipointSettings = { openScreen(Screen.EarphoneMultipointSettings) },
                                            onOpenEqDetail = { openScreen(Screen.EarphoneEqDetail) },
                                        ),
                                        visibility = visibility.value,
                                        listState = detailListState,
                                        boxImagePath = currentEarphonePref?.boxImagePath,
                                        boxImageRevision = currentEarphonePref?.imageRevision ?: 0L,
                                    )
                                    // Only when there is nothing to show yet — a first-ever connect,
                                    // or a cold start. Once the page holds a real state, a session
                                    // coming back is a recovery: the greyed content already says
                                    // "not controllable right now", and spinning on top of it turns
                                    // every LE Audio teardown (which briefly opens a session on the
                                    // other identity) into a fake reconnect animation.
                                    if (sonyConnected && !canShowDetailPage && !pageState.capabilitiesKnown) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopCenter)
                                                .padding(top = padding.calculateTopPadding() + 8.dp),
                                        ) {
                                            InfiniteProgressIndicator()
                                        }
                                    }
                                    PowerOffDialog(
                                        show = showPowerOffDialog,
                                        deviceName = detailTitle,
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
                },
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
                    LegacyConfigMigrator.writeStartupTab(context, it)
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
                    LegacyConfigMigrator.writeNotificationClickAction(context, it)
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
                    LegacyConfigMigrator.writeMoreClickAction(context, it)
                },
                fusionMoreClickAction = fusionMoreClickAction,
                onFusionMoreClickActionChange = {
                    fusionMoreClickAction.value = it
                    ConfigManager.updateFusionMoreClickAction(it)
                },
                ignoreRandomLePairingRequests = ignoreRandomLePairingRequests,
                onIgnoreRandomLePairingRequestsChange = {
                    ignoreRandomLePairingRequests.value = it
                    ConfigManager.updateIgnoreRandomLePairingRequests(it)
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
            identityAddress = sonyState.leAudioIdentityAddress,
            pairStage = sonyState.leAudioDevicePairStage,
            pairing = sonyState.leAudioDevicePairing,
            pairMessage = sonyState.leAudioDevicePairMessage,
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

