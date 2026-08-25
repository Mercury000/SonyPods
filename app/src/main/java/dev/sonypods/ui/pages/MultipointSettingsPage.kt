package dev.sonypods.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.sonypods.R
import dev.sonypods.bridge.MultipointDeviceSnapshot
import dev.sonypods.bridge.MultipointSnapshot
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.ui.SonyDetailActions
import dev.sonypods.ui.displayName
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The dual-device (multipoint) management page, mirroring Sound Connect's
 * `MultipointDeviceSettingsFragment`: description text, a fixed slot list of
 * connected devices, the paired history list, and the "connect new device"
 * pairing entry. The shell owns the title/back bar.
 *
 * Semantics follow the official app exactly:
 * - The playback-right holder is the connected device whose connectedStatus
 *   equals the trailing playbackright byte (SC `so.f`: `kVar.d() == i11`).
 * - Tapping a non-holder row switches playback to it (SET_EXTENDED_PARAM with
 *   the row's address); when the source is fixed, it is unfixed first.
 * - SOURCE_SWITCH_CONTROL's on/off is the "fix playback device" setting, not a
 *   feature toggle; it surfaces in each row's menu.
 */
@Composable
internal fun MultipointSettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions,
) {
    val state = uiState.multipoint
    val headphoneName = uiState.displayName.ifBlank { stringResource(R.string.mp_default_headphone_name) }
    val sourceSwitchSupported = state.sourceSwitchEnabled != null
    val sourceKeepEnabled = state.sourceSwitchEnabled == true
    val multipointToggleSupported = state.multipointEnabled != null
    // The target value is used only by the switch while a write is pending.
    // Keep the rest of the page on the pre-tap state until the device settles.
    val multipointDisabled = if (state.multipointTogglePending) {
        state.multipointEnabled == true
    } else {
        state.multipointEnabled == false
    }
    val slotCount = maxOf(state.maxConnectedDevices, 2)
    // Sony's LC3 links carry no second device. The dashboard card stops being a link then, so this
    // page is normally out of reach — but the connection can hand over to LE Audio while it is
    // already open, and the switch must not stay live into a write the headset would refuse.
    val leAudioRestricted = uiState.connectedViaLeAudio

    var pendingDisconnect by remember { mutableStateOf<MultipointDeviceSnapshot?>(null) }
    var pendingUnpair by remember { mutableStateOf<MultipointDeviceSnapshot?>(null) }
    var showMaxReached by remember { mutableStateOf(false) }
    var showSwitchUnsupported by remember { mutableStateOf(false) }

    fun switchPlaybackTo(address: String) {
        // Official `so.f.v()`: unfix first when the source is fixed, then
        // hand the playback right to the tapped device.
        if (sourceKeepEnabled) actions.onSourceSwitchEnabledChange(false)
        actions.onFixedSourceChange(address)
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item("multipoint-toggle") {
            if (multipointToggleSupported) {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPreference(
                        title = stringResource(R.string.mp_connect_two_title),
                        summary = if (leAudioRestricted) stringResource(R.string.feature_unavailable_over_le_audio) else null,
                        checked = state.multipointEnabled == true,
                        onCheckedChange = { enabled ->
                            if (!state.multipointTogglePending && !leAudioRestricted) {
                                actions.onMultipointEnabledChange(enabled)
                            }
                        },
                        enabled = !leAudioRestricted,
                    )
                }
            }
        }

        item("description") {
            Text(
                text = if (!multipointDisabled && sourceSwitchSupported) {
                    stringResource(R.string.mp_intro_body_1)
                } else {
                    stringResource(R.string.mp_intro_body_2)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 6.dp),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        if (!multipointDisabled) {
            item("connected-header") {
                SmallTitle(text = stringResource(R.string.mp_connected), modifier = Modifier.fillMaxWidth())
            }

            item("connected-card") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    (1..slotCount).forEach { slot ->
                        val device = state.connectedDevices.firstOrNull { it.connectedStatus == slot }
                        ConnectedSlotRow(
                            slot = slot,
                            device = device,
                            state = state,
                            sourceSwitchSupported = sourceSwitchSupported,
                            sourceKeepEnabled = sourceKeepEnabled,
                            onRowClick = { snapshot ->
                                if (sourceSwitchSupported) {
                                    switchPlaybackTo(snapshot.address)
                                } else {
                                    // Official Msg_MultiPoint_change_player dialog.
                                    showSwitchUnsupported = true
                                }
                            },
                            onFixPlayback = { actions.onSourceSwitchEnabledChange(true) },
                            onSwitchAndFix = { snapshot ->
                                if (!sourceKeepEnabled) actions.onSourceSwitchEnabledChange(true)
                                actions.onFixedSourceChange(snapshot.address)
                            },
                            onUnfix = { actions.onSourceSwitchEnabledChange(false) },
                            onDisconnect = { pendingDisconnect = it },
                            onUnpair = { pendingUnpair = it },
                        )
                    }
                }
            }
        }

        if (!multipointDisabled && state.historyDevices.isNotEmpty()) {
            item("history-header") {
                SmallTitle(text = stringResource(R.string.mp_paired), modifier = Modifier.fillMaxWidth())
            }
            item("history-card") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    state.historyDevices.forEach { device ->
                        HistoryDeviceRow(
                            device = device,
                            state = state,
                            onConnect = {
                                if (state.connectedDevices.size >= slotCount) {
                                    showMaxReached = true
                                } else {
                                    actions.onMultipointConnect(device.address)
                                }
                            },
                            onUnpair = { pendingUnpair = device },
                        )
                    }
                }
            }
        }

        if (!multipointDisabled) {
            item("add-device") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    if (state.pairingMode) {
                        Text(
                            text = stringResource(R.string.mp_scan_hint, headphoneName),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        TextButton(
                            text = stringResource(R.string.mp_stop_scan),
                            onClick = { actions.onMultipointPairingModeChange(false) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 12.dp),
                        )
                    } else {
                        BasicComponent(
                            title = stringResource(R.string.mp_connect_new),
                            titleColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(
                                color = MiuixTheme.colorScheme.primary,
                            ),
                            endActions = {
                                Icon(
                                    imageVector = MiuixIcons.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                            },
                            onClick = {
                                if (state.connectedDevices.size >= slotCount) {
                                    showMaxReached = true
                                } else {
                                    actions.onMultipointPairingModeChange(true)
                                }
                            },
                        )
                    }
                }
            }
        }

        item("bottom-spacer") { Spacer(modifier = Modifier.height(bottomContentPadding)) }
    }

    // Official Msg_MultiPoint_ConfirmToDisconnect: "Disconnect %s?".
    OverlayDialog(
        title = stringResource(R.string.mp_disconnect),
        summary = pendingDisconnect?.let { stringResource(R.string.mp_disconnect_confirm_name, it.name.ifBlank { it.address }) },
        show = pendingDisconnect != null,
        onDismissRequest = { pendingDisconnect = null },
    ) {
        DialogButtons(
            confirmText = stringResource(R.string.mp_disconnect_action),
            onCancel = { pendingDisconnect = null },
            onConfirm = {
                pendingDisconnect?.let { actions.onMultipointDisconnect(it.address) }
                pendingDisconnect = null
            },
        )
    }

    // Official Msg_MultiPoint_DeviceRemoveConfirmation.
    OverlayDialog(
        title = stringResource(R.string.mp_unpair),
        summary = pendingUnpair?.let { device ->
            val label = device.name.ifBlank { device.address }
            stringResource(R.string.mp_unpair_confirm_body, label, headphoneName)
        },
        show = pendingUnpair != null,
        onDismissRequest = { pendingUnpair = null },
    ) {
        DialogButtons(
            confirmText = stringResource(R.string.mp_unpair),
            onCancel = { pendingUnpair = null },
            onConfirm = {
                pendingUnpair?.let { actions.onMultipointUnpair(it.address) }
                pendingUnpair = null
            },
        )
    }

    // Official Msg_MultiPoint_CannotEnterPairngMode_Title/_Description.
    OverlayDialog(
        title = stringResource(R.string.mp_disconnect_required_title),
        summary = stringResource(R.string.mp_disconnect_required_body),
        show = showMaxReached,
        onDismissRequest = { showMaxReached = false },
    ) {
        TextButton(
            text = stringResource(R.string.mp_got_it),
            onClick = { showMaxReached = false },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }

    // Official Msg_MultiPoint_change_player (models without source switch).
    OverlayDialog(
        title = stringResource(R.string.mp_switch_playback_title),
        summary = stringResource(R.string.mp_switch_playback_body),
        show = showSwitchUnsupported,
        onDismissRequest = { showSwitchUnsupported = false },
    ) {
        TextButton(
            text = stringResource(R.string.mp_got_it),
            onClick = { showSwitchUnsupported = false },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun ConnectedSlotRow(
    slot: Int,
    device: MultipointDeviceSnapshot?,
    state: MultipointSnapshot,
    sourceSwitchSupported: Boolean,
    sourceKeepEnabled: Boolean,
    onRowClick: (MultipointDeviceSnapshot) -> Unit,
    onFixPlayback: () -> Unit,
    onSwitchAndFix: (MultipointDeviceSnapshot) -> Unit,
    onUnfix: () -> Unit,
    onDisconnect: (MultipointDeviceSnapshot) -> Unit,
    onUnpair: (MultipointDeviceSnapshot) -> Unit,
) {
    val holdsPlayback = device != null && state.playbackRight > 0 &&
        device.connectedStatus == state.playbackRight
    BasicComponent(
        title = device?.name?.ifBlank { device.address } ?: stringResource(R.string.mp_not_connected),
        summary = when {
            device == null -> null
            inProgressLabel(state, device) != null -> inProgressLabel(state, device)
            holdsPlayback && sourceKeepEnabled -> stringResource(R.string.mp_fixed_as_playback)
            holdsPlayback -> stringResource(R.string.cd_now_playing)
            else -> null
        },
        startAction = {
            Text(
                text = stringResource(R.string.mp_slot_number, slot),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        },
        endActions = {
            if (holdsPlayback) {
                // endActions 的 Row 默认顶部对齐，菜单按钮带 40dp 最小高度，
                // 裸 Icon 需要显式垂直居中才能与其对齐。
                Icon(
                    imageVector = MiuixIcons.VolumeUp,
                    contentDescription = stringResource(R.string.cd_now_playing),
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .size(20.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
            }
            if (device != null) {
                OverlayIconDropdownMenu(
                    entry = DropdownEntry(
                        items = buildList {
                            if (sourceSwitchSupported) {
                                if (holdsPlayback && !sourceKeepEnabled) {
                                    add(DropdownItem(text = stringResource(R.string.mp_fix_playback), onClick = onFixPlayback))
                                }
                                if (!holdsPlayback) {
                                    add(DropdownItem(text = stringResource(R.string.mp_switch_and_fix), onClick = { onSwitchAndFix(device) }))
                                }
                                if (holdsPlayback && sourceKeepEnabled) {
                                    add(DropdownItem(text = stringResource(R.string.mp_unfix), onClick = onUnfix))
                                }
                            }
                            add(DropdownItem(text = stringResource(R.string.mp_disconnect), onClick = { onDisconnect(device) }))
                            add(DropdownItem(text = stringResource(R.string.mp_unpair), onClick = { onUnpair(device) }))
                        },
                    ),
                ) {
                    Icon(
                        imageVector = MiuixIcons.More,
                        contentDescription = stringResource(R.string.cd_more_options),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        },
        onClick = when {
            device == null -> null
            holdsPlayback -> null // Official: tapping the holder is a no-op.
            else -> ({ onRowClick(device) })
        },
        enabled = device != null,
    )
}

@Composable
private fun HistoryDeviceRow(
    device: MultipointDeviceSnapshot,
    state: MultipointSnapshot,
    onConnect: () -> Unit,
    onUnpair: () -> Unit,
) {
    BasicComponent(
        title = device.name.ifBlank { device.address },
        summary = inProgressLabel(state, device),
        endActions = {
            OverlayIconDropdownMenu(
                entry = DropdownEntry(
                    items = listOf(DropdownItem(text = stringResource(R.string.mp_unpair), onClick = onUnpair)),
                ),
            ) {
                Icon(
                    imageVector = MiuixIcons.More,
                    contentDescription = stringResource(R.string.cd_more_options),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        },
        // Official `so.j`: tapping a history row connects it, no confirmation.
        onClick = onConnect,
    )
}

/**
 * Per-row in-progress text from the latest connectivity NTFY. The engine only
 * carries the raw result code; the localized copy lives in string resources and
 * the in-flight set is decided by code, never by sniffing display text.
 */
private val MP_RESULT_IN_PROGRESS_CODES = setOf(0x02, 0x12, 0x22, 0x32)

@Composable
private fun multipointResultLabel(code: Int): String {
    val res = when (code) {
        0x00 -> R.string.mp_result_disconnect_ok
        0x01 -> R.string.mp_result_disconnect_failed
        0x02 -> R.string.mp_result_disconnect_in_progress
        0x03 -> R.string.mp_result_disconnect_busy
        0x10 -> R.string.mp_result_connect_ok
        0x11 -> R.string.mp_result_connect_failed
        0x12 -> R.string.mp_result_connect_in_progress
        0x13 -> R.string.mp_result_connect_busy
        0x20 -> R.string.mp_result_unregister_ok
        0x21 -> R.string.mp_result_unregister_failed
        0x22 -> R.string.mp_result_unregister_in_progress
        0x23 -> R.string.mp_result_unregister_busy
        0x30 -> R.string.mp_result_pair_ok
        0x31 -> R.string.mp_result_pair_failed
        0x32 -> R.string.mp_result_pair_in_progress
        0x33 -> R.string.mp_result_pair_busy
        else -> return stringResource(R.string.mp_result_unknown_fmt, code)
    }
    return stringResource(res)
}

@Composable
private fun inProgressLabel(state: MultipointSnapshot, device: MultipointDeviceSnapshot): String? =
    state.resultCode
        ?.takeIf { state.resultAddress.equals(device.address, ignoreCase = true) && it in MP_RESULT_IN_PROGRESS_CODES }
        ?.let { "${multipointResultLabel(it)}…" }

@Composable
private fun DialogButtons(
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(
            text = stringResource(R.string.cancel),
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}
