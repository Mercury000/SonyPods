package dev.sonypods.ui.pages

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import com.mercury.sonypods.R
import dev.sonypods.device.SonyDeviceService
import dev.sonypods.ui.isLikelySonyAudioDevice
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@SuppressLint("MissingPermission")
@Composable
fun DevicePickerPage(
    isConnected: Boolean = false,
    connectedDeviceName: String = "",
    connectedDeviceAddress: String = "",
    connectingDeviceAddress: String? = null,
    /**
     * Whether the connected headset's Tandem control channel is up and its capabilities known.
     *
     * A Bluetooth link is not yet a control channel: over LE the headset can take some ten seconds
     * to answer the protocol handshake, and until it has, every control the page could reach is
     * inert. The connected row keeps its spinner for that whole window rather than offering a
     * disconnect button for a session that is still coming up.
     */
    controlChannelReady: Boolean = true,
    showConnectError: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onConnectedDeviceClick: () -> Unit = {},
    onDeviceDisconnect: (BluetoothDevice) -> Unit = {},
    onDismissConnectError: () -> Unit = {},
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    var bluetoothRefreshToken by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED ||
                    intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED
                ) {
                    bluetoothRefreshToken++
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }, Context.RECEIVER_EXPORTED)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.bt_permission_required))
                Spacer(Modifier.height(16.dp))
                TextButton(
                    text = stringResource(R.string.grant_permission),
                    onClick = {
                        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                )
            }
        }
        return
    }

    val btManager = context.getSystemService(BluetoothManager::class.java)
    val adapter = btManager?.adapter
    val bluetoothEnabled = adapter?.isEnabled == true
    val foldResult = remember(hasPermission, bluetoothEnabled, bluetoothRefreshToken) {
        if (!bluetoothEnabled) {
            Pair(emptyList(), emptyMap())
        } else {
            val bonded = adapter.bondedDevices.toList()
            // A headset on LE Audio is bonded twice: once for LC3 audio and once for the
            // Tandem control channel. Listing both would offer the user an entry that cannot
            // be controlled, so the LE identity is folded into its control counterpart.
            // UUID classification needs the stack's service cache to be warm; until it is,
            // the engine-seeded alias map answers instead.
            SonyDeviceService.linkLeAudioIdentities(bonded)
            val aliases = SonyDeviceService.leAudioAliasSnapshot()
            val byAddress = bonded.associateBy { it.address.uppercase() }
            val paired = bonded
                .filterNot { SonyDeviceService.isLeAudioIdentity(it) }
                .filterNot { device ->
                    aliases[device.address.uppercase()]
                        ?.let { control -> byAddress.containsKey(control.uppercase()) } == true
                }
                .sortedByDescending { isLikelySonyAudioDevice(it.name) }
            val leByControl = aliases.entries
                .mapNotNull { (le, control) ->
                    val controlDevice = byAddress[control.uppercase()] ?: return@mapNotNull null
                    if (!byAddress.containsKey(le.uppercase())) return@mapNotNull null
                    controlDevice.address.uppercase() to le
                }
                .groupBy({ it.first }, { it.second })
            Pair(paired, leByControl)
        }
    }
    val pairedDevices = foldResult.first
    val leAddressesByControl = foldResult.second

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                end = 12.dp,
                bottom = bottomContentPadding + 12.dp,
            ),
        ) {
            if (pairedDevices.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(
                                if (bluetoothEnabled) R.string.no_paired_devices else R.string.bluetooth_disabled
                            ),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            if (bluetoothEnabled) {
                items(pairedDevices, key = { it.address }) { device ->
                    val connected = isConnected && (device.address == connectedDeviceAddress || (
                        connectedDeviceAddress.isBlank() &&
                            connectedDeviceName.isNotBlank() &&
                            device.name == connectedDeviceName
                    ))
                    DeviceRow(
                        title = device.name ?: stringResource(R.string.unknown_device),
                        summary = device.address,
                        secondarySummaries = leAddressesByControl[
                            device.address.uppercase()
                        ].orEmpty(),
                        connected = connected,
                        connecting = device.address == connectingDeviceAddress ||
                            (connected && !controlChannelReady),
                        onClick = { if (connected) onConnectedDeviceClick() else onDeviceSelected(device) },
                        onDisconnect = { onDeviceDisconnect(device) },
                    )
                }
            }
        }

    }

    WindowDialog(
        title = stringResource(R.string.connect_failed),
        show = showConnectError,
        onDismissRequest = onDismissConnectError,
    ) {
        val dismiss = LocalDismissState.current
        TextButton(
            text = stringResource(R.string.confirm),
            onClick = { dismiss?.invoke() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }

}

/**
 * One paired device. [connecting] takes the place of the disconnect button, because a session that
 * is still being established has nothing to disconnect from yet.
 */
@Composable
private fun DeviceRow(
    title: String,
    summary: String,
    connected: Boolean,
    connecting: Boolean,
    onClick: () -> Unit,
    onDisconnect: () -> Unit,
    secondarySummaries: List<String> = emptyList(),
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (connected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.headline1,
                )
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // The folded-in LE identity keeps its address visible: some flows (pairing
                // prompts, stack dumps) only ever name that address.
                secondarySummaries.forEach { leAddress ->
                    Text(
                        text = leAddress,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (connecting) {
                InfiniteProgressIndicator()
            } else if (connected) {
                IconButton(
                    modifier = Modifier.size(32.dp),
                    onClick = onDisconnect,
                ) {
                    Icon(
                        modifier = Modifier.size(18.dp),
                        imageVector = MiuixIcons.Close,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}
