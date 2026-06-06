package moe.chenxy.oppopods.ui

import android.content.res.Configuration
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
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import moe.chenxy.oppopods.MainActivity
import moe.chenxy.oppopods.OppoPodsApp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.pods.AppRfcommController
import moe.chenxy.oppopods.pods.NoiseControlMode
import moe.chenxy.oppopods.ui.components.AppIcons
import moe.chenxy.oppopods.ui.components.RestartScope
import moe.chenxy.oppopods.ui.components.RestartScopeDialog
import moe.chenxy.oppopods.ui.pages.AboutPage
import moe.chenxy.oppopods.ui.pages.DevicePickerPage
import moe.chenxy.oppopods.ui.pages.HomePage
import moe.chenxy.oppopods.ui.pages.PodDetailPage
import moe.chenxy.oppopods.ui.pages.SettingsPage
import moe.chenxy.oppopods.ui.pages.ThemeSettingsPage
import moe.chenxy.oppopods.utils.RootManager
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

sealed interface Screen : NavKey {
    data object Main : Screen
    data object About : Screen
    data object Theme : Screen
}

private enum class MainTab(val icon: ImageVector) {
    Module(AppIcons.Home),
    Earphones(AppIcons.Headphones),
    Settings(MiuixIcons.Settings),
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

    val mainTitle = remember { mutableStateOf("") }
    val batteryParams = remember { mutableStateOf(BatteryParams()) }
    val ancMode = remember { mutableStateOf(NoiseControlMode.OFF) }
    val hookConnected = remember { mutableStateOf(false) }
    val gameMode = remember { mutableStateOf(false) }
    val transparencyVocalEnhancement = remember { mutableStateOf(false) }
    val tabs = remember { MainTab.entries.toList() }
    var selectedTab by remember { mutableStateOf(MainTab.Module) }
    var hasAppliedDefaultTab by remember { mutableStateOf(false) }
    var bluetoothState by remember { mutableStateOf(readBluetoothState(context)) }
    var xposedService by remember { mutableStateOf(OppoPodsApp.xposedService) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showRestartScopeDialog by remember { mutableStateOf(false) }
    var restartingScopes by remember { mutableStateOf(false) }
    var connectingDeviceAddress by remember { mutableStateOf<String?>(null) }
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

    // Auto game mode preference (persisted)
    val prefs = remember { context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE) }
    val appConfig = remember { ConfigManager.refreshFromPrefs(prefs) }
    val autoGameMode = remember { mutableStateOf(prefs.getBoolean("auto_game_mode", false)) }
    val notificationClickAction = remember { mutableStateOf(appConfig.notificationClickAction) }
    val moreClickAction = remember { mutableStateOf(appConfig.moreClickAction) }
    val desktopIconHidden = remember { mutableStateOf(isLauncherIconHidden(context)) }
    val logLevel = remember { mutableStateOf(appConfig.logLevel) }
    val fakeDeviceId = remember { mutableStateOf(appConfig.fakeDeviceId) }
    val islandMode = remember { mutableStateOf(appConfig.islandMode) }
    // Adaptive模式偏好设置（持久化存储），默认开启
    val adaptiveMode = remember { mutableStateOf(prefs.getBoolean("adaptive_mode", true)) }

    val appController = remember { AppRfcommController() }
    val appConnState by appController.connectionState.collectAsState()
    val appBattery by appController.batteryParams.collectAsState()
    val appAnc by appController.ancMode.collectAsState()
    val appDeviceName by appController.deviceName.collectAsState()
    val appDeviceAddress by appController.deviceAddress.collectAsState()
    val appGameMode by appController.gameMode.collectAsState()
    val appTransparencyVocalEnhancement by appController.transparencyVocalEnhancement.collectAsState()

    val isStandaloneConnected = appConnState == AppRfcommController.ConnectionState.CONNECTED
    val isConnecting = appConnState == AppRfcommController.ConnectionState.CONNECTING
    val isError = appConnState == AppRfcommController.ConnectionState.ERROR
    val canShowDetailPage = hookConnected.value || isStandaloneConnected
    val showEarphoneDetail = canShowDetailPage && !showDevicePicker

    val displayBattery = if (isStandaloneConnected) appBattery else batteryParams.value
    val displayAnc = if (isStandaloneConnected) appAnc else ancMode.value
    val displayGameMode = if (isStandaloneConnected) appGameMode else gameMode.value
    val displayTransparencyVocalEnhancement = if (isStandaloneConnected) appTransparencyVocalEnhancement else transparencyVocalEnhancement.value
    val displayTitle = when {
        hookConnected.value -> mainTitle.value
        isStandaloneConnected -> appDeviceName
        else -> ""
    }

    LaunchedEffect(displayTitle) {
        if (displayTitle.isNotEmpty()) {
            mainTitle.value = displayTitle
        }
    }

    LaunchedEffect(canShowDetailPage) {
        if (!hasAppliedDefaultTab) {
            selectedTab = if (canShowDetailPage) MainTab.Earphones else MainTab.Module
            hasAppliedDefaultTab = true
        }
    }

    LaunchedEffect(isStandaloneConnected) {
        if (isStandaloneConnected) {
            connectingDeviceAddress = null
            showConnectErrorDialog = false
            showDevicePicker = false
            selectedTab = MainTab.Earphones
        }
    }

    LaunchedEffect(isError) {
        if (isError) {
            connectingDeviceAddress = null
            showConnectErrorDialog = true
            showDevicePicker = true
        }
    }

    val broadcastReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                when (p1?.action) {
                    OppoPodsAction.ACTION_PODS_ANC_CHANGED -> {
                        val status = p1.getIntExtra("status", 1)
                        ancMode.value = when (status) {
                            1 -> NoiseControlMode.OFF
                            2 -> NoiseControlMode.NOISE_CANCELLATION
                            3 -> NoiseControlMode.TRANSPARENCY
                            4 -> NoiseControlMode.ADAPTIVE
                            5 -> NoiseControlMode.NOISE_CANCELLATION_SMART
                            6 -> NoiseControlMode.NOISE_CANCELLATION_LIGHT
                            7 -> NoiseControlMode.NOISE_CANCELLATION_MEDIUM
                            8 -> NoiseControlMode.NOISE_CANCELLATION_DEEP
                            else -> NoiseControlMode.OFF
                        }
                    }

                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        batteryParams.value =
                            p1.getParcelableExtra("status", BatteryParams::class.java)!!
                    }

                    OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED -> {
                        transparencyVocalEnhancement.value = p1.getBooleanExtra("enabled", false)
                    }

                    OppoPodsAction.ACTION_PODS_CONNECTED -> {
                        val deviceName = p1.getStringExtra("device_name")
                        mainTitle.value = deviceName ?: ""
                        hookConnected.value = true
                        showDevicePicker = false
                        selectedTab = MainTab.Earphones
                        Log.i("OppoPods", "pod connected via hook: $deviceName")
                    }

                    OppoPodsAction.ACTION_PODS_DISCONNECTED -> {
                        mainTitle.value = ""
                        hookConnected.value = false
                        if (p0 is MainActivity) {
                            p0.finish()
                        }
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
        OppoPodsApp.addServiceListener(serviceListener)

        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_ANC_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }, Context.RECEIVER_EXPORTED)

        context.sendBroadcast(Intent(OppoPodsAction.ACTION_PODS_UI_INIT))

        onDispose {
            try {
                context.unregisterReceiver(broadcastReceiver)
            } catch (_: Exception) {}
            OppoPodsApp.removeServiceListener(serviceListener)
            appController.disconnect()
        }
    }

    fun setAncMode(mode: NoiseControlMode) {
        if (isStandaloneConnected) {
            appController.setANCMode(mode)
            return
        }
        ancMode.value = mode
        val status = when (mode) {
            NoiseControlMode.OFF -> 1
            NoiseControlMode.NOISE_CANCELLATION -> 2
            NoiseControlMode.TRANSPARENCY -> 3
            NoiseControlMode.ADAPTIVE -> 4
            NoiseControlMode.NOISE_CANCELLATION_SMART -> 5
            NoiseControlMode.NOISE_CANCELLATION_LIGHT -> 6
            NoiseControlMode.NOISE_CANCELLATION_MEDIUM -> 7
            NoiseControlMode.NOISE_CANCELLATION_DEEP -> 8
        }
        Intent(OppoPodsAction.ACTION_ANC_SELECT).apply {
            this.putExtra("status", status)
            context.sendBroadcast(this)
        }
    }

    fun setGameMode(enabled: Boolean) {
        if (isStandaloneConnected) {
            appController.setGameMode(enabled)
            return
        }
        gameMode.value = enabled
        Intent(OppoPodsAction.ACTION_GAME_MODE_SET).apply {
            this.putExtra("enabled", enabled)
            context.sendBroadcast(this)
        }
    }

    fun setTransparencyVocalEnhancement(enabled: Boolean) {
        if (isStandaloneConnected) {
            appController.setTransparencyVocalEnhancement(enabled)
            return
        }
        transparencyVocalEnhancement.value = enabled
        Intent(OppoPodsAction.ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET).apply {
            this.putExtra("enabled", enabled)
            context.sendBroadcast(this)
        }
    }

    fun onDeviceSelected(device: BluetoothDevice) {
        connectingDeviceAddress = device.address
        showConnectErrorDialog = false
        showDevicePicker = true
        appController.connect(device, autoGameMode = autoGameMode.value)
    }

    fun backToDevicePicker() {
        showDevicePicker = true
        appController.disconnect()
        hookConnected.value = false
        mainTitle.value = ""
    }

    fun refreshStatus() {
        if (isStandaloneConnected) {
            appController.refreshStatus()
        } else if (hookConnected.value) {
            context.sendBroadcast(Intent(OppoPodsAction.ACTION_REFRESH_STATUS))
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
            Toast.makeText(
                context,
                if (success) R.string.restart_scope_success else R.string.restart_scope_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val entryProvider = entryProvider<Screen> {
        entry<Screen.Main> {
            val topAppBarScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
            val isLandscapeDetail = selectedTab == MainTab.Earphones &&
                    showEarphoneDetail &&
                    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val title = when (selectedTab) {
                MainTab.Module -> stringResource(R.string.app_name)
                MainTab.Earphones -> mainTitle.value.ifEmpty { stringResource(R.string.pod_info) }
                MainTab.Settings -> stringResource(R.string.settings)
            }

            Scaffold(
                topBar = {
                    if (!isLandscapeDetail) {
                        TopAppBar(
                            title = title,
                            largeTitle = title,
                            scrollBehavior = topAppBarScrollBehavior,
                            navigationIcon = {
                                if (selectedTab == MainTab.Earphones && showEarphoneDetail) {
                                    IconButton(onClick = { backToDevicePicker() }) {
                                        Icon(
                                            imageVector = MiuixIcons.Back,
                                            contentDescription = "Back"
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (selectedTab == MainTab.Module) {
                                    IconButton(
                                        onClick = {
                                            if (!restartingScopes) {
                                                showRestartScopeDialog = true
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = MiuixIcons.Refresh,
                                            contentDescription = "Restart scope"
                                        )
                                    }
                                }
                            }
                        )
                    }
                },
                bottomBar = {
                    BottomNavigation(
                        tabs = tabs,
                        selectedTab = selectedTab,
                        floating = floatingBottomBar.value,
                        blur = blurBottomBar.value,
                        backdrop = backdrop,
                        onTabClick = { selectedTab = it },
                    )
                }
            ) { padding ->
                val contentPadding = if (overlayBottomBar) padding.withoutBottom() else padding
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                        .padding(contentPadding),
                ) {
                    AnimatedContent(
                        targetState = selectedTab,
                        label = "MainTabAnim"
                    ) { tab ->
                        when (tab) {
                            MainTab.Module -> HomePage(
                                modifier = Modifier
                                    .overScrollVertical()
                                    .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
                                xposedService = xposedService,
                                bluetoothEnabled = bluetoothState.enabled,
                                bondedDeviceCount = bluetoothState.bondedCount,
                                bottomContentPadding = pageBottomContentPadding,
                            )

                            MainTab.Earphones -> AnimatedContent(
                                targetState = when {
                                    showEarphoneDetail -> "detail"
                                    else -> "picker"
                                },
                                label = "EarphonesPageAnim"
                            ) { state ->
                                when (state) {
                                    "detail" -> PodDetailPage(
                                        modifier = Modifier
                                            .overScrollVertical()
                                            .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
                                        contentPadding = PaddingValues(0.dp),
                                        bottomContentPadding = pageBottomContentPadding,
                                        podName = displayTitle.ifEmpty { stringResource(R.string.pod_info) },
                                        batteryParams = displayBattery,
                                        ancMode = displayAnc,
                                        onAncModeChange = { setAncMode(it) },
                                        transparencyVocalEnhancement = displayTransparencyVocalEnhancement,
                                        onTransparencyVocalEnhancementChange = { setTransparencyVocalEnhancement(it) },
                                        gameMode = displayGameMode,
                                        onGameModeChange = { setGameMode(it) },
                                        adaptiveModeEnabled = adaptiveMode.value
                                    )

                                    else -> DevicePickerPage(
                                        connectedDeviceName = displayTitle,
                                        connectedDeviceAddress = if (isStandaloneConnected) appDeviceAddress else "",
                                        connectingDeviceAddress = connectingDeviceAddress,
                                        showConnectError = showConnectErrorDialog,
                                        bottomContentPadding = pageBottomContentPadding,
                                        onDeviceSelected = { onDeviceSelected(it) },
                                        onDismissConnectError = {
                                            showConnectErrorDialog = false
                                            appController.disconnect()
                                        },
                                    )
                                }
                            }

                            MainTab.Settings -> SettingsPage(
                                modifier = Modifier
                                    .overScrollVertical()
                                    .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
                                contentPadding = PaddingValues(bottom = pageBottomContentPadding),
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
                                appLanguage = appLanguage,
                                onAppLanguageChange = {
                                    appLanguage.value = it
                                    onAppLanguageChange(it)
                                },
                                autoGameMode = autoGameMode,
                                onAutoGameModeChange = {
                                    autoGameMode.value = it
                                    prefs.edit().putBoolean("auto_game_mode", it).apply()
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
                                adaptiveMode = adaptiveMode,
                                onAdaptiveModeChange = {
                                    adaptiveMode.value = it
                                    prefs.edit().putBoolean("adaptive_mode", it).apply()
                                    Intent(OppoPodsAction.ACTION_ADAPTIVE_MODE_CHANGED).apply {
                                        putExtra("enabled", it)
                                        context.sendBroadcast(this)
                                    }
                                    if (!it && displayAnc == NoiseControlMode.ADAPTIVE) {
                                        setAncMode(NoiseControlMode.NOISE_CANCELLATION)
                                    }
                                },
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
                            )
                        }
                    }

                    if (isLandscapeDetail) {
                        IconButton(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 8.dp, end = 8.dp),
                            onClick = { backToDevicePicker() }
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "Back"
                            )
                        }
                    }
                }

                RestartScopeDialog(
                    show = showRestartScopeDialog,
                    scopes = restartScopeOptions,
                    onDismissRequest = { if (!restartingScopes) showRestartScopeDialog = false },
                    onConfirm = { restartScopes(it) },
                )
            }
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

private val restartScopeOptions = listOf(
    RestartScope("com.android.bluetooth", "Bluetooth"),
    //RestartScope("com.android.settings", "Settings"),
    RestartScope("com.milink.service", "MiLink Service"),
    RestartScope("com.xiaomi.bluetooth", "Mi Bluetooth"),
)

private fun PaddingValues.withoutBottom(): PaddingValues {
    return PaddingValues(
        start = calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        top = calculateTopPadding(),
        end = calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        bottom = 0.dp,
    )
}

@Composable
private fun BottomNavigation(
    tabs: List<MainTab>,
    selectedTab: MainTab,
    floating: Boolean,
    blur: Boolean,
    backdrop: LayerBackdrop?,
    onTabClick: (MainTab) -> Unit,
) {
    val barModifier = if (blur && backdrop != null) {
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = RoundedCornerShape(if (floating) 50.dp else 0.dp),
        )
    } else {
        Modifier
    }

    if (floating) {
        FloatingNavigationBar(
            modifier = barModifier,
            color = if (blur) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
        ) {
            tabs.forEach { tab ->
                FloatingNavigationBarItem(
                    selected = selectedTab == tab,
                    onClick = { onTabClick(tab) },
                    icon = tab.icon,
                    label = tab.title(),
                )
            }
        }
    } else {
        NavigationBar(
            modifier = barModifier,
            color = if (blur) Color.Transparent else MiuixTheme.colorScheme.surface,
            showDivider = false,
        ) {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = selectedTab == tab,
                    onClick = { onTabClick(tab) },
                    icon = tab.icon,
                    label = tab.title(),
                )
            }
        }
    }
}

@Composable
private fun MainTab.title(): String = when (this) {
    MainTab.Module -> stringResource(R.string.module)
    MainTab.Earphones -> stringResource(R.string.earphones)
    MainTab.Settings -> stringResource(R.string.settings)
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

private fun isLauncherIconHidden(context: Context): Boolean {
    val component = ComponentName(context, "moe.chenxy.oppopods.LauncherActivity")
    val state = context.packageManager.getComponentEnabledSetting(component)
    return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}

private fun setLauncherIconHidden(context: Context, hidden: Boolean) {
    val component = ComponentName(context, "moe.chenxy.oppopods.LauncherActivity")
    val state = if (hidden) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
    context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
}

private fun broadcastConfigChanged(context: Context, packageName: String) {
    Intent(OppoPodsAction.ACTION_CONFIG_CHANGED).apply {
        setPackage(packageName)
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        context.sendBroadcast(this)
    }
}
