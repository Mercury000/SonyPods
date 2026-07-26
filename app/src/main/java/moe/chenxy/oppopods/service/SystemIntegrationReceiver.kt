package moe.chenxy.oppopods.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.sonypods.data.SonyHeadphoneRepository
import dev.sonypods.protocol.NoiseControlMode
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction

/** Numeric ANC coding shared with the HyperOS hooks: 1=Off, 2=NC, 3=Ambient. */
internal fun ancStatusFromMode(mode: NoiseControlMode?): Int = when (mode) {
    NoiseControlMode.NOISE_CANCELLING -> 2
    NoiseControlMode.AMBIENT_SOUND -> 3
    else -> 1
}

internal fun ancModeFromStatus(status: Int): NoiseControlMode = when (status) {
    2 -> NoiseControlMode.NOISE_CANCELLING
    3 -> NoiseControlMode.AMBIENT_SOUND
    else -> NoiseControlMode.OFF
}

/**
 * Manifest-registered bridge receiver (app process). Receives control broadcasts
 * from the HyperOS hooks and drives the Sony repository:
 *  - A2DP connect/disconnect triggers from the bluetooth-process hook
 *  - ANC selection from system settings / fusion center / notification actions
 *  - ANC cycle from the notification / island button
 *  - Ambient voice toggle and status refresh requests
 */
class SystemIntegrationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val repository = SonyHeadphoneRepository.getInstance(context.applicationContext)
        Log.d(TAG, "control action=${intent.action}")
        when (intent.action) {
            OppoPodsAction.ACTION_HOOK_DEVICE_CONNECTED -> {
                SonyControlService.ensureRunning(context)
                val address = intent.getStringExtra("address") ?: return
                val name = intent.getStringExtra("device_name").orEmpty().ifEmpty { "Sony audio device" }
                val current = repository.state.value.connectedDevice?.address
                if (!address.equals(current, ignoreCase = true)) {
                    repository.connect(address, name)
                }
            }

            OppoPodsAction.ACTION_HOOK_DEVICE_DISCONNECTED -> {
                val address = intent.getStringExtra("address") ?: return
                val current = repository.state.value.connectedDevice?.address
                if (address.equals(current, ignoreCase = true)) {
                    repository.disconnect()
                }
            }

            OppoPodsAction.ACTION_ANC_SELECT -> {
                repository.setNoiseControlMode(ancModeFromStatus(intent.getIntExtra("status", 1)))
            }

            OppoPodsAction.ACTION_AMBIENT_VOICE_SET -> {
                repository.setAmbientVoiceMode(intent.getBooleanExtra("enabled", false))
            }

            OppoPodsAction.ACTION_CYCLE_ANC -> {
                val next = when (repository.state.value.noiseControlState.controlMode) {
                    NoiseControlMode.NOISE_CANCELLING -> NoiseControlMode.AMBIENT_SOUND
                    NoiseControlMode.AMBIENT_SOUND -> NoiseControlMode.OFF
                    else -> NoiseControlMode.NOISE_CANCELLING
                }
                repository.setNoiseControlMode(next)
            }

            OppoPodsAction.ACTION_REFRESH_STATUS -> {
                if (repository.state.value.deviceInfo.protocolReady) {
                    repository.refreshBasics()
                }
            }
        }
    }

    companion object {
        private const val TAG = "SonyPods-Bridge"
    }
}
