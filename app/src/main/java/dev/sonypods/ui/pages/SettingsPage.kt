package dev.sonypods.ui.pages

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mercury.sonypods.R
import dev.sonypods.config.ConfigManager
import dev.sonypods.ui.AppLocale
import dev.sonypods.ui.dialogs.AncCycleModesDialog
import dev.sonypods.ui.dialogs.AppPickerDialog
import dev.sonypods.ui.dialogs.IslandDurationDialog
import dev.sonypods.ui.dialogs.decomposeIslandDuration
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class PopupAppList { Allow, Deny }

@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    desktopIconHidden: MutableState<Boolean> = mutableStateOf(false),
    onDesktopIconHiddenChange: (Boolean) -> Unit = {},
    logLevel: MutableState<Int> = mutableStateOf(ConfigManager.LOG_LEVEL_BASIC),
    onLogLevelChange: (Int) -> Unit = {},
    islandMode: MutableState<Int> = mutableStateOf(ConfigManager.ISLAND_MODE_MODULE),
    onIslandModeChange: (Int) -> Unit = {},
    islandDurationSeconds: MutableState<Int> = mutableStateOf(ConfigManager.DEFAULT_ISLAND_DURATION_SECONDS),
    onIslandDurationSecondsChange: (Int) -> Unit = {},
    ancCycleModes: MutableState<Set<String>> = mutableStateOf(ConfigManager.DEFAULT_ANC_CYCLE_MODES),
    onAncCycleModesChange: (Set<String>) -> Unit = {},
    startupTab: MutableState<Int> = mutableStateOf(ConfigManager.STARTUP_TAB_MODULE),
    onStartupTabChange: (Int) -> Unit = {},
    appLanguage: MutableState<Int> = mutableStateOf(AppLocale.SYSTEM),
    onAppLanguageChange: (Int) -> Unit = {},
    notificationClickAction: MutableState<Int> = mutableStateOf(ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP),
    onNotificationClickActionChange: (Int) -> Unit = {},
    popupOnConnect: MutableState<Boolean> = mutableStateOf(false),
    onPopupOnConnectChange: (Boolean) -> Unit = {},
    connectDialogMode: MutableState<Int> = mutableStateOf(ConfigManager.CONNECT_DIALOG_MODE_MODULE),
    onConnectDialogModeChange: (Int) -> Unit = {},
    popupAllowlist: MutableState<Set<String>> = mutableStateOf(emptySet()),
    onPopupAllowlistChange: (Set<String>) -> Unit = {},
    popupDenylist: MutableState<Set<String>> = mutableStateOf(ConfigManager.DEFAULT_POPUP_DENYLIST),
    onPopupDenylistChange: (Set<String>) -> Unit = {},
    suppressPopupInGameOrLandscape: MutableState<Boolean> = mutableStateOf(true),
    onSuppressPopupInGameOrLandscapeChange: (Boolean) -> Unit = {},
    moreClickAction: MutableState<Int> = mutableStateOf(ConfigManager.MORE_CLICK_MODULE),
    onMoreClickActionChange: (Int) -> Unit = {},
    fusionMoreClickAction: MutableState<Int> = mutableStateOf(ConfigManager.FUSION_MORE_CLICK_SYSTEM_SETTINGS),
    onFusionMoreClickActionChange: (Int) -> Unit = {},
    fakeDeviceId: MutableState<String> = mutableStateOf(ConfigManager.DEFAULT_FAKE_DEVICE_ID),
    onFakeDeviceIdChange: (String) -> Unit = {},
    onOpenTheme: () -> Unit = {},
    onOpenAbout: () -> Unit = {}
) {
    val languageOptions = listOf(
        stringResource(R.string.language_system),
        stringResource(R.string.language_chinese),
        stringResource(R.string.language_english),
    )
    val logLevelValues = listOf(ConfigManager.LOG_LEVEL_OFF, ConfigManager.LOG_LEVEL_BASIC, ConfigManager.LOG_LEVEL_DEBUG)
    val logLevelOptions = listOf(
        stringResource(R.string.log_level_off),
        stringResource(R.string.log_level_basic),
        stringResource(R.string.log_level_debug),
    )
    val islandModeValues = listOf(ConfigManager.ISLAND_MODE_NONE, ConfigManager.ISLAND_MODE_OFFICIAL, ConfigManager.ISLAND_MODE_MODULE)
    val islandModeOptions = listOf(
        stringResource(R.string.island_mode_none),
        stringResource(R.string.island_mode_official),
        stringResource(R.string.island_mode_module),
    )
    // Stored as seconds (islandTimeout is seconds on the wire); shown as the
    // largest exact unit the user picked, e.g. "10 秒" / "5 分" / "1 时".
    val showIslandDurationDialog = remember { mutableStateOf(false) }
    val islandDurationParts = decomposeIslandDuration(islandDurationSeconds.value)
    val islandDurationLabel = "${islandDurationParts.first} " + stringResource(
        when (islandDurationParts.second) {
            2 -> R.string.duration_unit_hour
            1 -> R.string.duration_unit_minute
            else -> R.string.duration_unit_second
        }
    )
    val notificationClickActionValues = listOf(
        ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP,
        ConfigManager.NOTIFICATION_CLICK_SYSTEM_SETTINGS,
    )
    val notificationClickActionOptions = listOf(
        stringResource(R.string.notification_click_module_popup),
        stringResource(R.string.click_action_system_settings),
    )
    val connectDialogModeValues = listOf(
        ConfigManager.CONNECT_DIALOG_MODE_MODULE,
        ConfigManager.CONNECT_DIALOG_MODE_OFFICIAL,
    )
    val connectDialogModeOptions = listOf(
        stringResource(R.string.connect_dialog_mode_module),
        stringResource(R.string.connect_dialog_mode_official),
    )
    val startupTabValues = listOf(ConfigManager.STARTUP_TAB_MODULE, ConfigManager.STARTUP_TAB_EARPHONES)
    val startupTabOptions = listOf(
        stringResource(R.string.module),
        stringResource(R.string.earphones),
    )
    val ancCycleModeLabels = listOf(
        "NOISE_CANCELLING" to stringResource(R.string.anc_cycle_mode_noise_cancelling),
        "AMBIENT_SOUND" to stringResource(R.string.anc_cycle_mode_ambient),
        "OFF" to stringResource(R.string.anc_cycle_mode_off),
    )
    val ancCycleModesSummary = ancCycleModeLabels
        .filter { (value, _) -> value in ancCycleModes.value }
        .joinToString(" → ") { (_, label) -> label }
        .ifEmpty { stringResource(R.string.anc_cycle_modes_summary) }
    val showAncCycleModesDialog = remember { mutableStateOf(false) }
    val popupAppList = remember { mutableStateOf<PopupAppList?>(null) }
    val moreClickActionValues = listOf(
        ConfigManager.MORE_CLICK_SYSTEM_SETTINGS,
        ConfigManager.MORE_CLICK_MODULE,
    )
    val moreClickActionOptions = listOf(
        stringResource(R.string.click_action_system_settings),
        stringResource(R.string.click_action_module),
    )
    val fusionMoreClickActionValues = listOf(
        ConfigManager.FUSION_MORE_CLICK_SYSTEM_SETTINGS,
        ConfigManager.FUSION_MORE_CLICK_MODULE,
    )
    val fusionMoreClickActionOptions = listOf(
        stringResource(R.string.click_action_system_settings),
        stringResource(R.string.click_action_module),
    )
    val sectionTitleInsideMargin = PaddingValues(start = 14.dp, top = 14.dp, end = 28.dp, bottom = 6.dp)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
    ) {
        item {
            SmallTitle(
                text = stringResource(R.string.settings_section_module),
                modifier = Modifier.fillMaxWidth(),
                insideMargin = sectionTitleInsideMargin,
            )
            Card {
                BasicComponent(
                    title = stringResource(R.string.theme_title),
                    summary = stringResource(R.string.theme_color_summary),
                    onClick = onOpenTheme,
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.language),
                    summary = stringResource(R.string.language_summary),
                    items = languageOptions,
                    selectedIndex = appLanguage.value.coerceIn(languageOptions.indices),
                    onSelectedIndexChange = { onAppLanguageChange(it) }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.log_level),
                    summary = stringResource(R.string.log_level_summary),
                    items = logLevelOptions,
                    selectedIndex = logLevelValues.indexOf(logLevel.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onLogLevelChange(logLevelValues[it]) }
                )
                SwitchPreference(
                    title = stringResource(R.string.hide_desktop_icon),
                    summary = stringResource(R.string.hide_desktop_icon_summary),
                    checked = desktopIconHidden.value,
                    onCheckedChange = { onDesktopIconHiddenChange(it) }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.startup_tab),
                    summary = stringResource(R.string.startup_tab_summary),
                    items = startupTabOptions,
                    selectedIndex = startupTabValues.indexOf(startupTab.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onStartupTabChange(startupTabValues[it]) }
                )
            }
        }

        item {
            SmallTitle(
                text = stringResource(R.string.settings_section_island),
                modifier = Modifier.fillMaxWidth(),
                insideMargin = sectionTitleInsideMargin,
            )
            Card {
                OverlayDropdownPreference(
                    title = stringResource(R.string.island_mode),
                    summary = stringResource(R.string.island_mode_summary),
                    items = islandModeOptions,
                    selectedIndex = islandModeValues.indexOf(islandMode.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onIslandModeChange(islandModeValues[it]) }
                )
                if (islandMode.value == ConfigManager.ISLAND_MODE_MODULE) {
                    BasicComponent(
                        title = stringResource(R.string.island_duration_title),
                        summary = stringResource(R.string.island_duration_summary),
                        endActions = {
                            Text(
                                text = islandDurationLabel,
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = { showIslandDurationDialog.value = true },
                    )
                }
            }
        }

        item {
            SmallTitle(
                text = stringResource(R.string.settings_section_notification),
                modifier = Modifier.fillMaxWidth(),
                insideMargin = sectionTitleInsideMargin,
            )
            Card {
                BasicComponent(
                    title = stringResource(R.string.anc_cycle_modes_title),
                    summary = stringResource(R.string.anc_cycle_modes_summary),
                    endActions = {
                        Text(
                            text = ancCycleModesSummary,
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    },
                    onClick = { showAncCycleModesDialog.value = true },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.notification_click_action),
                    summary = stringResource(R.string.notification_click_action_summary),
                    items = notificationClickActionOptions,
                    selectedIndex = notificationClickActionValues.indexOf(notificationClickAction.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onNotificationClickActionChange(notificationClickActionValues[it]) }
                )
            }
        }

        item {
            SmallTitle(
                text = stringResource(R.string.settings_section_popup),
                modifier = Modifier.fillMaxWidth(),
                insideMargin = sectionTitleInsideMargin,
            )
            Card {
                SwitchPreference(
                    title = stringResource(R.string.popup_on_connect),
                    summary = stringResource(R.string.popup_on_connect_summary),
                    checked = popupOnConnect.value,
                    onCheckedChange = { onPopupOnConnectChange(it) }
                )
                if (popupOnConnect.value) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.connect_dialog_mode),
                        summary = stringResource(R.string.connect_dialog_mode_summary),
                        items = connectDialogModeOptions,
                        selectedIndex = connectDialogModeValues.indexOf(connectDialogMode.value).coerceAtLeast(0),
                        onSelectedIndexChange = { onConnectDialogModeChange(connectDialogModeValues[it]) },
                    )
                    BasicComponent(
                        title = stringResource(R.string.popup_denylist_title),
                        summary = stringResource(R.string.popup_denylist_summary, popupDenylist.value.size),
                        onClick = { popupAppList.value = PopupAppList.Deny },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.suppress_popup_in_game_or_landscape),
                        summary = stringResource(R.string.suppress_popup_in_game_or_landscape_summary),
                        checked = suppressPopupInGameOrLandscape.value,
                        onCheckedChange = { onSuppressPopupInGameOrLandscapeChange(it) }
                    )
                    if (suppressPopupInGameOrLandscape.value) {
                        BasicComponent(
                            title = stringResource(R.string.popup_allowlist_title),
                            summary = stringResource(R.string.popup_allowlist_summary, popupAllowlist.value.size),
                            onClick = { popupAppList.value = PopupAppList.Allow },
                        )
                    }
                }
            }
        }

        item {
            SmallTitle(
                text = stringResource(R.string.settings_section_click),
                modifier = Modifier.fillMaxWidth(),
                insideMargin = sectionTitleInsideMargin,
            )
            Card {
                if (notificationClickAction.value == ConfigManager.NOTIFICATION_CLICK_MODULE_POPUP) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.more_click_action),
                        items = moreClickActionOptions,
                        selectedIndex = moreClickActionValues.indexOf(moreClickAction.value).coerceAtLeast(0),
                        onSelectedIndexChange = { onMoreClickActionChange(moreClickActionValues[it]) }
                    )
                }
                OverlayDropdownPreference(
                    title = stringResource(R.string.fusion_more_click_action),
                    items = fusionMoreClickActionOptions,
                    selectedIndex = fusionMoreClickActionValues.indexOf(fusionMoreClickAction.value).coerceAtLeast(0),
                    onSelectedIndexChange = { onFusionMoreClickActionChange(fusionMoreClickActionValues[it]) },
                )
                BasicComponent(
                    title = stringResource(R.string.fake_device_id),
                    summary = stringResource(R.string.fake_device_id_summary)
                )
                TextField(
                    value = fakeDeviceId.value,
                    onValueChange = { onFakeDeviceIdChange(it.trim()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                )
            }
        }

        item {
            Card(modifier = Modifier.padding(top = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.about),
                    summary = "SonyPods",
                    onClick = onOpenAbout
                )
            }
        }
    }

    AncCycleModesDialog(
        show = showAncCycleModesDialog.value,
        selected = ancCycleModes.value,
        onDismissRequest = { showAncCycleModesDialog.value = false },
        onConfirm = {
            onAncCycleModesChange(it)
            showAncCycleModesDialog.value = false
        },
    )

    AppPickerDialog(
        show = popupAppList.value != null,
        title = stringResource(
            if (popupAppList.value == PopupAppList.Allow) {
                R.string.popup_allowlist_title
            } else {
                R.string.popup_denylist_title
            },
        ),
        selectedPackages = if (popupAppList.value == PopupAppList.Allow) {
            popupAllowlist.value
        } else {
            popupDenylist.value
        },
        onDismissRequest = { popupAppList.value = null },
        onConfirm = { selected ->
            if (popupAppList.value == PopupAppList.Allow) onPopupAllowlistChange(selected)
            else onPopupDenylistChange(selected)
            popupAppList.value = null
        },
    )

    IslandDurationDialog(
        show = showIslandDurationDialog.value,
        currentSeconds = islandDurationSeconds.value,
        onDismissRequest = { showIslandDurationDialog.value = false },
        onConfirm = { seconds ->
            showIslandDurationDialog.value = false
            onIslandDurationSecondsChange(seconds)
        },
    )
}
