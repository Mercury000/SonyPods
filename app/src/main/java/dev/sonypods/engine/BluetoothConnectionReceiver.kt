package dev.sonypods.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Wakes the no-root engine when Android reports a headset/profile transition. */
class BluetoothConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val receivedIntent = intent ?: return
        val action = receivedIntent.action ?: return
        when (action) {
            android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED,
            android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED,
            android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
            -> {
                runCatching { AppEngineHost.start(context) }
                val profileConnected = action.endsWith("CONNECTION_STATE_CHANGED") &&
                    receivedIntent.getIntExtra(
                        android.bluetooth.BluetoothProfile.EXTRA_STATE,
                        android.bluetooth.BluetoothProfile.STATE_DISCONNECTED,
                    ) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
                val bondCompleted = action == android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED &&
                    receivedIntent.getIntExtra(
                        android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE,
                        android.bluetooth.BluetoothDevice.BOND_NONE,
                    ) == android.bluetooth.BluetoothDevice.BOND_BONDED
                if (action == android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED ||
                    profileConnected ||
                    bondCompleted
                ) {
                    AppEngineHost.rearmBackgroundConnection()
                } else {
                    AppEngineHost.ensureBackgroundConnection()
                }
            }
        }
    }
}
