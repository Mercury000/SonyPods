package dev.sonypods.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.sonypods.config.ConfigManager
import dev.sonypods.data.SonyHeadphoneRepository
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.utils.miuiStrongToast.data.SonyPodsAction

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
            SonyPodsAction.ACTION_HOOK_DEVICE_CONNECTED -> {
                SonyControlService.ensureRunning(context)
                val address = intent.getStringExtra("address") ?: return
                val name = intent.getStringExtra("device_name").orEmpty().ifEmpty { "Sony audio device" }
                val current = repository.state.value.connectedDevice?.address
                if (!address.equals(current, ignoreCase = true)) {
                    repository.connect(address, name)
                }
            }

            SonyPodsAction.ACTION_HOOK_DEVICE_DISCONNECTED -> {
                val address = intent.getStringExtra("address") ?: return
                val current = repository.state.value.connectedDevice?.address
                if (address.equals(current, ignoreCase = true)) {
                    repository.disconnect()
                }
            }

            SonyPodsAction.ACTION_ANC_SELECT -> {
                if (ensureSession(context, repository)) {
                    repository.setNoiseControlMode(ancModeFromStatus(intent.getIntExtra("status", 1)))
                }
            }

            SonyPodsAction.ACTION_AMBIENT_VOICE_SET -> {
                if (ensureSession(context, repository)) {
                    repository.setAmbientVoiceMode(intent.getBooleanExtra("enabled", false))
                }
            }

            SonyPodsAction.ACTION_CYCLE_ANC -> {
                if (ensureSession(context, repository)) {
                    val next = when (repository.state.value.noiseControlState.controlMode) {
                        NoiseControlMode.NOISE_CANCELLING -> NoiseControlMode.AMBIENT_SOUND
                        NoiseControlMode.AMBIENT_SOUND -> NoiseControlMode.OFF
                        else -> NoiseControlMode.NOISE_CANCELLING
                    }
                    repository.setNoiseControlMode(next)
                }
            }

            SonyPodsAction.ACTION_REFRESH_STATUS -> {
                // A hook process asking for a refresh has usually just (re)registered its
                // receiver and holds no state at all, so replay what we have as well.
                SonyControlService.requestRepublish(context)
                if (repository.state.value.deviceInfo.protocolReady) {
                    repository.refreshBasics()
                } else {
                    ensureSession(context, repository)
                }
            }
        }
    }

    /**
     * Controls only work while the app-process Tandem session is alive. If the
     * app process was restarted (repository fresh, nothing connected), revive
     * the session towards the last known device; the triggering command is lost
     * but the session comes back for the next press.
     */
    private fun ensureSession(context: Context, repository: SonyHeadphoneRepository): Boolean {
        if (repository.state.value.connectedDevice != null) return true
        SonyControlService.ensureRunning(context)
        val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val address = prefs.getString(SonyControlService.PREF_LAST_DEVICE_ADDRESS, null)
        if (address.isNullOrBlank()) {
            Log.w(TAG, "control dropped: no active session and no remembered device")
            return false
        }
        val name = prefs.getString(SonyControlService.PREF_LAST_DEVICE_NAME, null) ?: "Sony audio device"
        Log.i(TAG, "no active session; reconnecting to $name ($address)")
        repository.connect(address, name)
        return false
    }

    companion object {
        private const val TAG = "SonyPods-Bridge"
    }
}
