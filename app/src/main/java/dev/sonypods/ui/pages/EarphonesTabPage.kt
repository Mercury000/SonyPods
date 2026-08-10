package dev.sonypods.ui.pages

import android.bluetooth.BluetoothDevice
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.sonypods.bridge.SonyStateSnapshot
import com.mercury.sonypods.R
import dev.sonypods.ui.SonyDetailActions
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun EarphonesTabPage(
    showEarphoneDetail: Boolean,
    showGestureOperations: Boolean,
    showMultipointSettings: Boolean,
    displayTitle: String,
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions,
    boxImagePath: String?,
    boxImageRevision: Long,
    connectedDeviceAddress: String,
    connectingDeviceAddress: String?,
    showConnectErrorDialog: Boolean,
    contentPadding: PaddingValues,
    pageBottomContentPadding: Dp,
    nestedScrollConnection: NestedScrollConnection,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onConnectedDeviceClick: () -> Unit,
    onDeviceDisconnect: (BluetoothDevice) -> Unit,
    onDismissConnectError: () -> Unit,
) {
    val page = when {
        !showEarphoneDetail -> EarphonesPage.DEVICE_PICKER
        showGestureOperations -> EarphonesPage.GESTURE_OPERATIONS
        showMultipointSettings -> EarphonesPage.MULTIPOINT_SETTINGS
        else -> EarphonesPage.DETAIL
    }
    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            ContentTransform(
                targetContentEnter = slideInHorizontally { it / 3 } + fadeIn(),
                initialContentExit = slideOutHorizontally { -it / 3 } + fadeOut(),
            )
        },
        label = "EarphonesPageAnim",
    ) { currentPage ->
        when (currentPage) {
            EarphonesPage.GESTURE_OPERATIONS -> GestureOperationsPage(
                modifier = Modifier
                    .overScrollVertical()
                    .nestedScroll(nestedScrollConnection),
                contentPadding = contentPadding,
                bottomContentPadding = pageBottomContentPadding,
                uiState = uiState,
                actions = actions,
            )

            EarphonesPage.MULTIPOINT_SETTINGS -> MultipointSettingsPage(
                modifier = Modifier
                    .overScrollVertical()
                    .nestedScroll(nestedScrollConnection),
                contentPadding = contentPadding,
                bottomContentPadding = pageBottomContentPadding,
                uiState = uiState,
                actions = actions,
            )

            EarphonesPage.DETAIL -> PodDetailPage(
                modifier = Modifier
                    .overScrollVertical()
                    .nestedScroll(nestedScrollConnection),
                contentPadding = contentPadding,
                bottomContentPadding = pageBottomContentPadding,
                podName = displayTitle.ifEmpty { stringResource(R.string.pod_info) },
                uiState = uiState,
                actions = actions,
                boxImagePath = boxImagePath,
                boxImageRevision = boxImageRevision,
            )

            EarphonesPage.DEVICE_PICKER -> DevicePickerPage(
                isConnected = uiState.connected,
                connectedDeviceName = displayTitle,
                connectedDeviceAddress = connectedDeviceAddress,
                connectingDeviceAddress = connectingDeviceAddress,
                showConnectError = showConnectErrorDialog,
                contentPadding = contentPadding,
                bottomContentPadding = pageBottomContentPadding,
                onDeviceSelected = onDeviceSelected,
                onConnectedDeviceClick = onConnectedDeviceClick,
                onDeviceDisconnect = onDeviceDisconnect,
                onDismissConnectError = onDismissConnectError,
            )
        }
    }
}

private enum class EarphonesPage {
    DEVICE_PICKER,
    DETAIL,
    GESTURE_OPERATIONS,
    MULTIPOINT_SETTINGS,
}
