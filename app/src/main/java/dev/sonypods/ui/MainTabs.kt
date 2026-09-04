package dev.sonypods.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.config.ConfigManager
import io.github.libxposed.service.XposedService
import com.mercury.sonypods.R
import dev.sonypods.ui.components.BarBackdropContent
import dev.sonypods.ui.components.BarBlurHost
import dev.sonypods.ui.components.BlurredBar
import dev.sonypods.ui.components.LocalBarBlurBackdrop
import dev.sonypods.ui.components.shouldExpandNavigationRail
import dev.sonypods.ui.components.shouldShowSplitPane
import dev.sonypods.ui.dialogs.RestartScope
import dev.sonypods.ui.dialogs.RestartScopeDialog
import dev.sonypods.ui.pages.AboutPage
import dev.sonypods.ui.pages.AboutTabPage
import dev.sonypods.ui.pages.DevicePickerPage
import dev.sonypods.ui.pages.HomePage
import dev.sonypods.ui.pages.SettingsPage
import dev.sonypods.ui.components.AppIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Months
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun MainTabsScaffold(
    modifier: Modifier = Modifier,
    tabs: List<MainTab>,
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    floatingBottomBar: Boolean,
    blurBottomBar: Boolean,
    iosBottomBar: Boolean,
    bottomBarVisible: Boolean,
    blurTopBar: MutableState<Boolean>,
    backgroundColor: Color,
    pageBottomContentPadding: Dp,
    xposedService: XposedService?,
    bluetoothServiceResponsive: Boolean,
    bluetoothEnabled: Boolean,
    bondedDeviceCount: Int,
    onBluetoothStatusClick: () -> Unit,
    onPairedBluetoothClick: () -> Unit,
    displayTitle: String,
    sonyState: SonyStateSnapshot,
    connectedDeviceAddress: String,
    connectingDeviceAddress: String?,
    showConnectErrorDialog: Boolean,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onConnectedDeviceClick: () -> Unit,
    onDeviceDisconnect: (BluetoothDevice) -> Unit,
    onDismissConnectError: () -> Unit,
    desktopIconHidden: MutableState<Boolean>,
    onDesktopIconHiddenChange: (Boolean) -> Unit,
    logLevel: MutableState<Int>,
    onLogLevelChange: (Int) -> Unit,
    islandMode: MutableState<Int>,
    onIslandModeChange: (Int) -> Unit,
    islandDurationSeconds: MutableState<Int>,
    onIslandDurationSecondsChange: (Int) -> Unit,
    ancCycleModes: MutableState<Set<String>>,
    onAncCycleModesChange: (Set<String>) -> Unit,
    startupTab: MutableState<Int>,
    onStartupTabChange: (Int) -> Unit,
    onOpenVisibility: () -> Unit,
    appLanguage: MutableState<Int>,
    onAppLanguageChange: (Int) -> Unit,
    notificationClickAction: MutableState<Int>,
    onNotificationClickActionChange: (Int) -> Unit,
    notificationEnabled: MutableState<Boolean>,
    onNotificationEnabledChange: (Boolean) -> Unit,
    popupOnConnect: MutableState<Boolean>,
    onPopupOnConnectChange: (Boolean) -> Unit,
    connectDialogMode: MutableState<Int>,
    onConnectDialogModeChange: (Int) -> Unit,
    popupAllowlist: MutableState<Set<String>>,
    onPopupAllowlistChange: (Set<String>) -> Unit,
    popupDenylist: MutableState<Set<String>>,
    onPopupDenylistChange: (Set<String>) -> Unit,
    suppressPopupInGameOrLandscape: MutableState<Boolean>,
    onSuppressPopupInGameOrLandscapeChange: (Boolean) -> Unit,
    moreClickAction: MutableState<Int>,
    onMoreClickActionChange: (Int) -> Unit,
    fusionMoreClickAction: MutableState<Int>,
    onFusionMoreClickActionChange: (Int) -> Unit,
    ignoreRandomLePairingRequests: MutableState<Boolean>,
    onIgnoreRandomLePairingRequestsChange: (Boolean) -> Unit,
    onOpenTandemDebug: () -> Unit,
    fakeDeviceId: MutableState<String>,
    onFakeDeviceIdChange: (String) -> Unit,
    onOpenTheme: () -> Unit,
    onOpenReferences: () -> Unit,
    showRestartScopeDialog: Boolean,
    restartingScopes: Boolean,
    onShowRestartScopeDialog: () -> Unit,
    onDismissRestartScopeDialog: () -> Unit,
    onRestartScopes: (List<String>) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = selectedTab.ordinal,
        pageCount = { tabs.size },
    )
    val coroutineScope = rememberCoroutineScope()
    val mainPagerState = remember(pagerState, coroutineScope) {
        MainTabsPagerState(pagerState, coroutineScope)
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

    val isWideScreen = shouldShowSplitPane()
    val expandRail = shouldExpandNavigationRail()
    val useRail = isWideScreen && !floatingBottomBar && !iosBottomBar

    // The record layer for the bottom bar's glass sampling lives in MainUI's root
    // host (shared with the predictive backdrop, like the reference); top bars get
    // their own per-page hosts inside the pager.
    val scaffoldContent: @Composable (PaddingValues) -> Unit = { padding ->
            // Full-bleed record layer: the content extends under the bar so the glass
            // sampling sees real page content mid-scroll. The bar-slot height flows
            // into the pages' scroll padding instead — it collapses when the bar
            // hides, which is the vertical give of the reference layout.
            val bottomBarSlot = padding.calculateBottomPadding()
            val pageBottomPadding = pageBottomContentPadding + bottomBarSlot
            BarBackdropContent(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                    key = { page -> tabs[page] },
                ) { page ->
                    when (tabs[page]) {
                        MainTab.Module -> ModuleTabPage(
                            xposedService = xposedService,
                            bluetoothServiceResponsive = bluetoothServiceResponsive,
                            bluetoothEnabled = bluetoothEnabled,
                            bondedDeviceCount = bondedDeviceCount,
                            onBluetoothStatusClick = onBluetoothStatusClick,
                            onPairedBluetoothClick = onPairedBluetoothClick,
                            onOpenTandemDebug = onOpenTandemDebug,
                            logLevel = logLevel.value,
                            pageBottomContentPadding = pageBottomPadding,
                            backgroundColor = backgroundColor,
                            blurTopBar = blurTopBar.value,
                            restartingScopes = restartingScopes,
                            onShowRestartScopeDialog = onShowRestartScopeDialog,
                        )

                        MainTab.Earphones -> EarphonesTabShell(
                            displayTitle = displayTitle,
                            sonyState = sonyState,
                            connectedDeviceAddress = connectedDeviceAddress,
                            connectingDeviceAddress = connectingDeviceAddress,
                            showConnectErrorDialog = showConnectErrorDialog,
                            pageBottomContentPadding = pageBottomPadding,
                            backgroundColor = backgroundColor,
                            blurTopBar = blurTopBar.value,
                            onDeviceSelected = onDeviceSelected,
                            onConnectedDeviceClick = onConnectedDeviceClick,
                            onDeviceDisconnect = onDeviceDisconnect,
                            onDismissConnectError = onDismissConnectError,
                        )

                        MainTab.Settings -> SettingsTabPage(
                            pageBottomContentPadding = pageBottomPadding,
                            backgroundColor = backgroundColor,
                            blurTopBar = blurTopBar.value,
                            desktopIconHidden = desktopIconHidden,
                            onDesktopIconHiddenChange = onDesktopIconHiddenChange,
                            logLevel = logLevel,
                            onLogLevelChange = onLogLevelChange,
                            islandMode = islandMode,
                            onIslandModeChange = onIslandModeChange,
                            islandDurationSeconds = islandDurationSeconds,
                            onIslandDurationSecondsChange = onIslandDurationSecondsChange,
                            ancCycleModes = ancCycleModes,
                            onAncCycleModesChange = onAncCycleModesChange,
                            startupTab = startupTab,
                            onStartupTabChange = onStartupTabChange,
                            onOpenVisibility = onOpenVisibility,
                            appLanguage = appLanguage,
                            onAppLanguageChange = onAppLanguageChange,
                            notificationClickAction = notificationClickAction,
                            onNotificationClickActionChange = onNotificationClickActionChange,
                            notificationEnabled = notificationEnabled,
                            onNotificationEnabledChange = onNotificationEnabledChange,
                            popupOnConnect = popupOnConnect,
                            onPopupOnConnectChange = onPopupOnConnectChange,
                            connectDialogMode = connectDialogMode,
                            onConnectDialogModeChange = onConnectDialogModeChange,
                            popupAllowlist = popupAllowlist,
                            onPopupAllowlistChange = onPopupAllowlistChange,
                            popupDenylist = popupDenylist,
                            onPopupDenylistChange = onPopupDenylistChange,
                            suppressPopupInGameOrLandscape = suppressPopupInGameOrLandscape,
                            onSuppressPopupInGameOrLandscapeChange = onSuppressPopupInGameOrLandscapeChange,
                            moreClickAction = moreClickAction,
                            onMoreClickActionChange = onMoreClickActionChange,
                            fusionMoreClickAction = fusionMoreClickAction,
                            onFusionMoreClickActionChange = onFusionMoreClickActionChange,
                            ignoreRandomLePairingRequests = ignoreRandomLePairingRequests,
                            onIgnoreRandomLePairingRequestsChange = onIgnoreRandomLePairingRequestsChange,
                            fakeDeviceId = fakeDeviceId,
                            onFakeDeviceIdChange = onFakeDeviceIdChange,
                            onOpenTheme = onOpenTheme,
                        )

                        MainTab.About -> AboutTabPage(
                            isActive = pagerState.currentPage == page,
                            pageBottomContentPadding = pageBottomPadding,
                            onOpenReferences = onOpenReferences,
                        )
                    }
                }
            }

            RestartScopeDialog(
                show = showRestartScopeDialog,
                scopes = restartScopeOptions,
                onDismissRequest = { if (!restartingScopes) onDismissRestartScopeDialog() },
                onConfirm = onRestartScopes,
            )
    }

    if (useRail) {
        Row(modifier = modifier.fillMaxSize()) {
            MainNavigationRail(
                tabs = tabs,
                selectedTab = tabs.getOrElse(mainPagerState.selectedPage) { selectedTab },
                expandRail = expandRail,
                onTabClick = {
                    mainPagerState.animateToPage(it.ordinal)
                    onTabSelected(it)
                },
            )
            Scaffold(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) { padding ->
                scaffoldContent(padding)
            }
        }
    } else {
        Scaffold(
            modifier = modifier,
            bottomBar = {
                AnimatedVisibility(
                    visible = bottomBarVisible,
                    enter = slideInVertically(tween(260)) { it } + fadeIn(tween(180)),
                    exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(140)),
                ) {
                    MainBottomNavigation(
                        tabs = tabs,
                        selectedTab = tabs.getOrElse(mainPagerState.selectedPage) { selectedTab },
                        floating = floatingBottomBar,
                        blur = blurBottomBar,
                        iosStyle = iosBottomBar,
                        backdrop = LocalBarBlurBackdrop.current,
                        onTabClick = {
                            mainPagerState.animateToPage(it.ordinal)
                            onTabSelected(it)
                        },
                    )
                }
            }
        ) { padding ->
            scaffoldContent(padding)
        }
    }
}

@Composable
private fun ModuleTabPage(
    xposedService: XposedService?,
    bluetoothServiceResponsive: Boolean,
    bluetoothEnabled: Boolean,
    bondedDeviceCount: Int,
    onBluetoothStatusClick: () -> Unit,
    onPairedBluetoothClick: () -> Unit,
    onOpenTandemDebug: () -> Unit,
    logLevel: Int,
    pageBottomContentPadding: Dp,
    backgroundColor: Color,
    blurTopBar: Boolean,
    restartingScopes: Boolean,
    onShowRestartScopeDialog: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    BarBlurHost(
        bottomBarBlurEnabled = false,
        topBarBlurEnabled = blurTopBar,
    ) {
        Scaffold(
            topBar = {
                BlurredBar(topGradient = true) {
                    TopAppBar(
                        title = stringResource(R.string.app_name),
                        largeTitle = stringResource(R.string.app_name),
                        color = Color.Transparent,
                        scrollBehavior = scrollBehavior,
                        actions = {
                            if (logLevel == ConfigManager.LOG_LEVEL_DEBUG) {
                                IconButton(onClick = onOpenTandemDebug) {
                                    Icon(imageVector = MiuixIcons.Months, contentDescription = stringResource(R.string.cd_tandem_debug))
                                }
                            }
                            IconButton(
                                onClick = {
                                    if (!restartingScopes) onShowRestartScopeDialog()
                                }
                            ) {
                                Icon(imageVector = MiuixIcons.Refresh, contentDescription = stringResource(R.string.cd_restart_scope))
                            }
                        },
                    )
                }
            },
        ) { pagePadding ->
            BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor),
                ) {
                    HomePage(
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        xposedService = xposedService,
                        bluetoothServiceResponsive = bluetoothServiceResponsive,
                        bluetoothEnabled = bluetoothEnabled,
                        bondedDeviceCount = bondedDeviceCount,
                        onBluetoothStatusClick = onBluetoothStatusClick,
                        onPairedBluetoothClick = onPairedBluetoothClick,
                        contentPadding = pagePadding,
                        bottomContentPadding = pageBottomContentPadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun EarphonesTabShell(
    displayTitle: String,
    sonyState: SonyStateSnapshot,
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
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pageTitle = stringResource(R.string.device)
    BarBlurHost(
        bottomBarBlurEnabled = false,
        topBarBlurEnabled = blurTopBar,
    ) {
        Scaffold(
            topBar = {
                BlurredBar(topGradient = true) {
                    TopAppBar(
                        title = pageTitle,
                        largeTitle = pageTitle,
                        color = Color.Transparent,
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
        ) { pagePadding ->
            BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor),
                ) {
                    DevicePickerPage(
                        isConnected = sonyState.connected,
                        connectedDeviceName = displayTitle,
                        connectedDeviceAddress = connectedDeviceAddress,
                        connectingDeviceAddress = connectingDeviceAddress,
                        // Connected is only the transport. The row stays in its connecting state until the
                        // control channel has answered, the device's capability table is in and the values
                        // behind the controls have come back — the same three facts the detail page waits
                        // on, so the spinner ends exactly when the page becomes operable.
                        controlChannelReady = sonyState.protocolReady &&
                            sonyState.capabilitiesKnown &&
                            sonyState.initialValuesReady,
                        identityPairs = sonyState.identityPairs,
                        showConnectError = showConnectErrorDialog,
                        contentPadding = pagePadding,
                        bottomContentPadding = pageBottomContentPadding,
                        onDeviceSelected = onDeviceSelected,
                        onConnectedDeviceClick = onConnectedDeviceClick,
                        onDeviceDisconnect = onDeviceDisconnect,
                        onDismissConnectError = onDismissConnectError,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTabPage(
    pageBottomContentPadding: Dp,
    backgroundColor: Color,
    blurTopBar: Boolean,
    desktopIconHidden: MutableState<Boolean>,
    onDesktopIconHiddenChange: (Boolean) -> Unit,
    logLevel: MutableState<Int>,
    onLogLevelChange: (Int) -> Unit,
    islandMode: MutableState<Int>,
    onIslandModeChange: (Int) -> Unit,
    islandDurationSeconds: MutableState<Int>,
    onIslandDurationSecondsChange: (Int) -> Unit,
    ancCycleModes: MutableState<Set<String>>,
    onAncCycleModesChange: (Set<String>) -> Unit,
    startupTab: MutableState<Int>,
    onStartupTabChange: (Int) -> Unit,
    onOpenVisibility: () -> Unit,
    appLanguage: MutableState<Int>,
    onAppLanguageChange: (Int) -> Unit,
    notificationClickAction: MutableState<Int>,
    onNotificationClickActionChange: (Int) -> Unit,
    notificationEnabled: MutableState<Boolean>,
    onNotificationEnabledChange: (Boolean) -> Unit,
    popupOnConnect: MutableState<Boolean>,
    onPopupOnConnectChange: (Boolean) -> Unit,
    connectDialogMode: MutableState<Int>,
    onConnectDialogModeChange: (Int) -> Unit,
    popupAllowlist: MutableState<Set<String>>,
    onPopupAllowlistChange: (Set<String>) -> Unit,
    popupDenylist: MutableState<Set<String>>,
    onPopupDenylistChange: (Set<String>) -> Unit,
    suppressPopupInGameOrLandscape: MutableState<Boolean>,
    onSuppressPopupInGameOrLandscapeChange: (Boolean) -> Unit,
    moreClickAction: MutableState<Int>,
    onMoreClickActionChange: (Int) -> Unit,
    fusionMoreClickAction: MutableState<Int>,
    onFusionMoreClickActionChange: (Int) -> Unit,
    ignoreRandomLePairingRequests: MutableState<Boolean>,
    onIgnoreRandomLePairingRequestsChange: (Boolean) -> Unit,
    fakeDeviceId: MutableState<String>,
    onFakeDeviceIdChange: (String) -> Unit,
    onOpenTheme: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    BarBlurHost(
        bottomBarBlurEnabled = false,
        topBarBlurEnabled = blurTopBar,
    ) {
        Scaffold(
            topBar = {
                BlurredBar(topGradient = true) {
                    TopAppBar(
                        title = stringResource(R.string.settings),
                        largeTitle = stringResource(R.string.settings),
                        color = Color.Transparent,
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
        ) { pagePadding ->
            BarBackdropContent(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor),
                ) {
                    SettingsPage(
                        modifier = Modifier
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            top = pagePadding.calculateTopPadding(),
                            bottom = pageBottomContentPadding,
                        ),
                        desktopIconHidden = desktopIconHidden,
                        onDesktopIconHiddenChange = onDesktopIconHiddenChange,
                        logLevel = logLevel,
                        onLogLevelChange = onLogLevelChange,
                        islandMode = islandMode,
                        onIslandModeChange = onIslandModeChange,
                        islandDurationSeconds = islandDurationSeconds,
                        onIslandDurationSecondsChange = onIslandDurationSecondsChange,
                        ancCycleModes = ancCycleModes,
                        onAncCycleModesChange = onAncCycleModesChange,
                        startupTab = startupTab,
                        onStartupTabChange = onStartupTabChange,
                        onOpenVisibility = onOpenVisibility,
                        appLanguage = appLanguage,
                        onAppLanguageChange = onAppLanguageChange,
                        notificationClickAction = notificationClickAction,
                        onNotificationClickActionChange = onNotificationClickActionChange,
                        notificationEnabled = notificationEnabled,
                        onNotificationEnabledChange = onNotificationEnabledChange,
                        popupOnConnect = popupOnConnect,
                        onPopupOnConnectChange = onPopupOnConnectChange,
                        connectDialogMode = connectDialogMode,
                        onConnectDialogModeChange = onConnectDialogModeChange,
                        popupAllowlist = popupAllowlist,
                        onPopupAllowlistChange = onPopupAllowlistChange,
                        popupDenylist = popupDenylist,
                        onPopupDenylistChange = onPopupDenylistChange,
                        suppressPopupInGameOrLandscape = suppressPopupInGameOrLandscape,
                        onSuppressPopupInGameOrLandscapeChange = onSuppressPopupInGameOrLandscapeChange,
                        moreClickAction = moreClickAction,
                        onMoreClickActionChange = onMoreClickActionChange,
                        fusionMoreClickAction = fusionMoreClickAction,
                        onFusionMoreClickActionChange = onFusionMoreClickActionChange,
                        ignoreRandomLePairingRequests = ignoreRandomLePairingRequests,
                        onIgnoreRandomLePairingRequestsChange = onIgnoreRandomLePairingRequestsChange,
                        fakeDeviceId = fakeDeviceId,
                        onFakeDeviceIdChange = onFakeDeviceIdChange,
                        onOpenTheme = onOpenTheme,
                    )
                }
            }
        }
    }
}

private val restartScopeOptions = listOf(
    RestartScope("com.android.bluetooth", R.string.scope_bluetooth),
    RestartScope("com.android.settings", R.string.scope_settings),
    RestartScope("com.milink.service", R.string.scope_milink),
    RestartScope("com.xiaomi.bluetooth", R.string.scope_mi_bluetooth),
    RestartScope("com.sony.songpal.mdr", R.string.scope_sound_connect),
)
