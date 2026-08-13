package dev.sonypods.ui

import android.bluetooth.BluetoothDevice
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.config.EarphonePref
import com.mercury.sonypods.R
import dev.sonypods.ui.dialogs.PowerOffDialog
import dev.sonypods.ui.pages.EarphonesTabPage
import dev.sonypods.ui.pages.SettingsPage
import dev.sonypods.ui.components.AppIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun MainTabsScaffold(
    tabs: List<MainTab>,
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    floatingBottomBar: Boolean,
    blurBottomBar: Boolean,
    blurTopBar: MutableState<Boolean>,
    backdrop: LayerBackdrop?,
    backgroundColor: Color,
    overlayBottomBar: Boolean,
    pageBottomContentPadding: Dp,
    showEarphoneDetail: Boolean,
    mainTitle: String,
    displayTitle: String,
    sonyState: SonyStateSnapshot,
    sonyActions: SonyDetailActions,
    earphonePrefs: List<EarphonePref>,
    connectedDeviceAddress: String,
    connectingDeviceAddress: String?,
    showConnectErrorDialog: Boolean,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onConnectedDeviceClick: () -> Unit,
    onDeviceDisconnect: (BluetoothDevice) -> Unit,
    onDismissConnectError: () -> Unit,
    logLevel: MutableState<Int>,
    onLogLevelChange: (Int) -> Unit,
    appLanguage: MutableState<Int>,
    onAppLanguageChange: (Int) -> Unit,
    onOpenTheme: () -> Unit,
    onOpenAbout: () -> Unit,
    onBackToDevicePicker: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = selectedTab.ordinal,
        pageCount = { tabs.size },
    )
    val coroutineScope = rememberCoroutineScope()
    val mainPagerState = remember(pagerState, coroutineScope) {
        MainTabsPagerState(pagerState, coroutineScope)
    }
    var showGestureOperations by remember { mutableStateOf(false) }
    var showMultipointSettings by remember { mutableStateOf(false) }
    val isLandscapeDetail = selectedTab == MainTab.Earphones &&
            showEarphoneDetail &&
            !showGestureOperations &&
            !showMultipointSettings &&
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val currentEarphonePref = earphonePrefs.firstOrNull {
        it.address.equals(connectedDeviceAddress, ignoreCase = true)
    }
    var showPowerOffDialog by remember { mutableStateOf(false) }

    // Official behaviour: leaving the add-device flow drops the headset back
    // to NORMAL_MODE (SC sends the cancel when the waiting screen closes).
    fun closeSubPages() {
        if (showMultipointSettings && sonyState.multipoint.pairingMode) {
            sonyActions.onMultipointPairingModeChange(false)
        }
        showGestureOperations = false
        showMultipointSettings = false
    }

    LaunchedEffect(showEarphoneDetail) {
        if (!showEarphoneDetail) {
            showGestureOperations = false
            showMultipointSettings = false
        }
    }

    // 手势返回按层级回退：三级子页 → 详细页 → 蓝牙设备列表，
    // 与顶栏返回箭头保持一致，而不是直接退出应用。
    BackHandler(
        enabled = selectedTab == MainTab.Earphones &&
            (showGestureOperations || showMultipointSettings || showEarphoneDetail)
    ) {
        if (showGestureOperations || showMultipointSettings) {
            closeSubPages()
        } else {
            onBackToDevicePicker()
        }
    }

    LaunchedEffect(selectedTab) {
        val targetPage = selectedTab.ordinal
        if (mainPagerState.selectedPage != targetPage) {
            mainPagerState.animateToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        mainPagerState.syncPage()
    }

    LaunchedEffect(mainPagerState.selectedPage, tabs) {
        val page = mainPagerState.selectedPage
        if (page in tabs.indices && selectedTab != tabs[page]) {
            onTabSelected(tabs[page])
        }
    }

    Scaffold(
        bottomBar = {
            MainBottomNavigation(
                tabs = tabs,
                selectedTab = tabs.getOrElse(mainPagerState.selectedPage) { selectedTab },
                floating = floatingBottomBar,
                blur = blurBottomBar,
                backdrop = backdrop,
                onTabClick = {
                    mainPagerState.animateToPage(it.ordinal)
                    onTabSelected(it)
                },
            )
        }
    ) { padding ->
        val contentPadding = if (overlayBottomBar) PaddingValues(0.dp) else PaddingValues(bottom = padding.calculateBottomPadding())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .padding(contentPadding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { page -> tabs[page] },
            ) { page ->
                when (tabs[page]) {
                    MainTab.Earphones -> EarphonesTabShell(
                        isLandscapeDetail = isLandscapeDetail,
                        showEarphoneDetail = showEarphoneDetail,
                        showGestureOperations = showGestureOperations,
                        showMultipointSettings = showMultipointSettings,
                        onOpenGestureOperations = { showGestureOperations = true },
                        onOpenMultipointSettings = { showMultipointSettings = true },
                        onBackFromSubPage = { closeSubPages() },
                        mainTitle = mainTitle,
                        displayTitle = displayTitle,
                        sonyState = sonyState,
                        sonyActions = sonyActions,
                        boxImagePath = currentEarphonePref?.boxImagePath,
                        boxImageRevision = currentEarphonePref?.imageRevision ?: 0L,
                        connectedDeviceAddress = connectedDeviceAddress,
                        connectingDeviceAddress = connectingDeviceAddress,
                        showConnectErrorDialog = showConnectErrorDialog,
                        pageBottomContentPadding = pageBottomContentPadding,
                        backgroundColor = backgroundColor,
                         blurTopBar = blurTopBar.value,
                        onDeviceSelected = onDeviceSelected,
                        onConnectedDeviceClick = onConnectedDeviceClick,
                        onDeviceDisconnect = onDeviceDisconnect,
                        onDismissConnectError = onDismissConnectError,
                        onBackToDevicePicker = onBackToDevicePicker,
                        onPowerOff = { showPowerOffDialog = true },
                        powerOffEnabled = sonyState.supportsPowerOff,
                    )

                    MainTab.Settings -> SettingsTabPage(
                        pageBottomContentPadding = pageBottomContentPadding,
                        backgroundColor = backgroundColor,
                         blurTopBar = blurTopBar.value,
                        logLevel = logLevel,
                        onLogLevelChange = onLogLevelChange,
                         appLanguage = appLanguage,
                         onAppLanguageChange = onAppLanguageChange,
                         onOpenTheme = onOpenTheme,
                         onOpenAbout = onOpenAbout,
                     )
                }
            }

        }

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

@Composable
private fun EarphonesTabShell(
    isLandscapeDetail: Boolean,
    showEarphoneDetail: Boolean,
    showGestureOperations: Boolean,
    showMultipointSettings: Boolean,
    onOpenGestureOperations: () -> Unit,
    onOpenMultipointSettings: () -> Unit,
    onBackFromSubPage: () -> Unit,
    mainTitle: String,
    displayTitle: String,
    sonyState: SonyStateSnapshot,
    sonyActions: SonyDetailActions,
    boxImagePath: String?,
    boxImageRevision: Long,
    connectedDeviceAddress: String,
    connectingDeviceAddress: String?,
    showConnectErrorDialog: Boolean,
    pageBottomContentPadding: Dp,
    backgroundColor: Color,
    blurTopBar: Boolean,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onConnectedDeviceClick: () -> Unit,
    onDeviceDisconnect: (BluetoothDevice) -> Unit,
    onDismissConnectError: () -> Unit,
    onBackToDevicePicker: () -> Unit,
    onPowerOff: () -> Unit,
    powerOffEnabled: Boolean,
) {
    val topBarBackdrop = if (blurTopBar) {
        rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }
    } else {
        null
    }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val onSubPage = showGestureOperations || showMultipointSettings
    val pageTitle = when {
        showGestureOperations -> "手势操作"
        showMultipointSettings -> "同时连接2台设备"
        else -> mainTitle.ifEmpty { stringResource(R.string.pod_info) }
    }
    Scaffold(
        topBar = {
            if (isLandscapeDetail) {
                SmallTopAppBar(
                    title = pageTitle,
                    modifier = if (topBarBackdrop != null) {
                        Modifier.textureBlur(
                            backdrop = topBarBackdrop,
                            shape = RectangleShape,
                        )
                    } else {
                        Modifier
                    },
                    color = if (topBarBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBackToDevicePicker) {
                            Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                        }
                    },
                    actions = {
                        EarphoneDetailActions(
                            onPowerOff = onPowerOff,
                            powerOffEnabled = powerOffEnabled,
                        )
                    },
                )
            } else {
                TopAppBar(
                    title = pageTitle,
                    largeTitle = pageTitle,
                    modifier = if (topBarBackdrop != null) {
                        Modifier.textureBlur(
                            backdrop = topBarBackdrop,
                            shape = RectangleShape,
                        )
                    } else {
                        Modifier
                    },
                    color = if (topBarBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        if (onSubPage) {
                            IconButton(onClick = onBackFromSubPage) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        } else if (showEarphoneDetail) {
                            IconButton(onClick = onBackToDevicePicker) {
                                Icon(imageVector = MiuixIcons.Back, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        if (showEarphoneDetail && !onSubPage) {
                            EarphoneDetailActions(
                                onPowerOff = onPowerOff,
                                powerOffEnabled = powerOffEnabled,
                            )
                        }
                    },
                )
            }
        },
    ) { pagePadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .then(if (topBarBackdrop != null) Modifier.layerBackdrop(topBarBackdrop) else Modifier),
        ) {
            EarphonesTabPage(
                showEarphoneDetail = showEarphoneDetail,
                showGestureOperations = showGestureOperations,
                showMultipointSettings = showMultipointSettings,
                displayTitle = displayTitle,
                uiState = sonyState,
                actions = sonyActions.copy(
                    onOpenGestureOperations = onOpenGestureOperations,
                    onOpenMultipointSettings = onOpenMultipointSettings,
                ),
                boxImagePath = boxImagePath,
                boxImageRevision = boxImageRevision,
                connectedDeviceAddress = connectedDeviceAddress,
                connectingDeviceAddress = connectingDeviceAddress,
                showConnectErrorDialog = showConnectErrorDialog,
                contentPadding = pagePadding,
                pageBottomContentPadding = pageBottomContentPadding,
                nestedScrollConnection = scrollBehavior.nestedScrollConnection,
                onDeviceSelected = onDeviceSelected,
                onConnectedDeviceClick = onConnectedDeviceClick,
                onDeviceDisconnect = onDeviceDisconnect,
                onDismissConnectError = onDismissConnectError,
            )
        }
    }
}

@Composable
private fun SettingsTabPage(
    pageBottomContentPadding: Dp,
    backgroundColor: Color,
    blurTopBar: Boolean,
    logLevel: MutableState<Int>,
    onLogLevelChange: (Int) -> Unit,
    appLanguage: MutableState<Int>,
    onAppLanguageChange: (Int) -> Unit,
    onOpenTheme: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val topBarBackdrop = if (blurTopBar) {
        rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }
    } else {
        null
    }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings),
                largeTitle = stringResource(R.string.settings),
                modifier = if (topBarBackdrop != null) {
                    Modifier.textureBlur(
                        backdrop = topBarBackdrop,
                        shape = RectangleShape,
                    )
                } else {
                    Modifier
                },
                color = if (topBarBackdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { pagePadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .then(if (topBarBackdrop != null) Modifier.layerBackdrop(topBarBackdrop) else Modifier),
        ) {
            SettingsPage(
                modifier = Modifier
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = pagePadding.calculateTopPadding(),
                    bottom = pageBottomContentPadding,
                ),
                logLevel = logLevel,
                onLogLevelChange = onLogLevelChange,
                appLanguage = appLanguage,
                onAppLanguageChange = onAppLanguageChange,
                onOpenTheme = onOpenTheme,
                onOpenAbout = onOpenAbout,
            )
        }
    }
}

@Composable
private fun EarphoneDetailActions(
    onPowerOff: () -> Unit,
    powerOffEnabled: Boolean,
) {
    if (powerOffEnabled) {
        IconButton(onClick = onPowerOff) {
            Icon(
                imageVector = AppIcons.Power,
                modifier = Modifier.size(23.dp),
                contentDescription = stringResource(R.string.power_off),
            )
        }
    }
}
