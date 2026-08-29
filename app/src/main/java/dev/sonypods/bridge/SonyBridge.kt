package dev.sonypods.bridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import dev.sonypods.protocol.NoiseControlMode

/**
 * Cross-process contract between the Sony engine (hosted in the `com.android.bluetooth`
 * hook process) and every consumer: the module UI and the HyperOS surfaces.
 *
 * State flows engine -> consumers as a [SonyStateSnapshot]; commands flow
 * consumers -> engine as [ACTION_COMMAND] broadcasts.
 */
object SonyBridge {
    /** Engine -> consumers: full state snapshot. */
    const val ACTION_STATE = "dev.sonypods.action.state"

    /** Consumers -> engine: a control command, see [EXTRA_COMMAND]. */
    const val ACTION_COMMAND = "dev.sonypods.action.command"

    /**
     * Engine -> official app hook: reassert a live lease after bluetooth-process restart.
     */
    const val ACTION_ENGINE_READY = "dev.sonypods.action.engine_ready"

    const val ACTION_DEBUG_LOG = "dev.sonypods.action.debug_log"

    const val EXTRA_COMMAND = "command"
    const val EXTRA_INT = "value_int"
    const val EXTRA_BOOL = "value_bool"
    const val EXTRA_STRING = "value_string"
    const val EXTRA_INDEX = "index"
    const val EXTRA_KEY_CODE = "gesture_key_code"
    const val EXTRA_ACTION_CODE = "gesture_action_code"
    const val EXTRA_FUNCTION_CODE = "gesture_function_code"
    const val EXTRA_QUICK_ACCESS_ACTION_INDEX = "quick_access_action_index"
    const val EXTRA_QUICK_ACCESS_FUNCTION_CODE = "quick_access_function_code"
    const val EXTRA_PRESET_CODE = "gesture_preset_code"
    const val EXTRA_OFFICIAL_LEASE_ID = "official_lease_id"
    const val EXTRA_OFFICIAL_LEASE_TOKEN = "official_lease_token"
    const val EXTRA_OFFICIAL_SENDER_PACKAGE = "official_sender_package"
    const val EXTRA_OFFICIAL_SENDER_UID = "official_sender_uid"
    /** Optional island animation override for a surface replay after an action click. */
    const val EXTRA_ISLAND_FIRST_FLOAT = "island_first_float"
    /** State-only flag: Sound Connect handoff reconnect must not open a connect popup. */
    const val EXTRA_SUPPRESS_CONNECT_POPUP = "suppress_connect_popup"
    /** Address of the terminal A2DP disconnect which caused this state transition. */
    const val EXTRA_PHYSICAL_DISCONNECT_ADDRESS = "physical_a2dp_disconnect_address"
    /** CMD_REFRESH: run a real query burst even when the engine's cache is young. */
    const val EXTRA_FORCE_REFRESH = "force_refresh"

    // Commands understood by the engine.
    const val CMD_SET_NOISE_CONTROL = "set_noise_control"
    const val CMD_CYCLE_NOISE_CONTROL = "cycle_noise_control"
    const val CMD_SET_AMBIENT_LEVEL = "set_ambient_level"
    const val CMD_SET_AMBIENT_VOICE = "set_ambient_voice"
    const val CMD_SET_WIND_NOISE_REDUCTION = "set_wind_noise_reduction"
    const val CMD_SET_SPEAK_TO_CHAT_ENABLED = "set_speak_to_chat_enabled"
    const val CMD_SET_SPEAK_TO_CHAT_SENSITIVITY = "set_speak_to_chat_sensitivity"
    const val CMD_SET_SPEAK_TO_CHAT_MODE_OUT_TIME = "set_speak_to_chat_mode_out_time"
    const val CMD_SET_NOISE_ADAPTIVE = "set_noise_adaptive"
    const val CMD_SET_NOISE_ADAPTIVE_SENSITIVITY = "set_noise_adaptive_sensitivity"
    const val CMD_SET_EQ_PRESET = "set_eq_preset"
    const val CMD_SET_CLEAR_BASS = "set_clear_bass"
    const val CMD_POWER_OFF = "power_off"
    const val CMD_SET_EQ_BAND = "set_eq_band"
    const val CMD_SET_GESTURE_PRESET = "set_gesture_preset"
    const val CMD_SET_GESTURE_FUNCTION = "set_gesture_function"
    const val CMD_SET_QUICK_ACCESS_FUNCTION = "set_quick_access_function"
    const val CMD_SET_GESTURE_AMBIENT_MODES = "set_gesture_ambient_modes"
    const val CMD_SET_MULTIPOINT_PAIRING_MODE = "set_multipoint_pairing_mode"
    const val CMD_CONNECT_MULTIPOINT_DEVICE = "connect_multipoint_device"
    const val CMD_DISCONNECT_MULTIPOINT_DEVICE = "disconnect_multipoint_device"
    const val CMD_UNPAIR_MULTIPOINT_DEVICE = "unpair_multipoint_device"
    const val CMD_SET_SOURCE_SWITCH_ENABLED = "set_source_switch_enabled"
    const val CMD_SET_MULTIPOINT_ENABLED = "set_multipoint_enabled"
    const val CMD_SET_LE_AUDIO_ENABLED = "set_le_audio_enabled"
    const val CMD_SET_UPSCALING_ENABLED = "set_upscaling_enabled"
    const val CMD_REPLY_MULTIPOINT_ALERT = "reply_multipoint_alert"
    const val CMD_REPLY_LE_AUDIO_ALERT = "reply_le_audio_alert"
    /** Bond the headset's LE-only identity so the phone can actually route LC3. */
    const val CMD_LE_AUDIO_DEVICE_PAIR = "le_audio_device_pair"
    /** Raise the LE Audio pairing guide from the device detail page. */
    const val CMD_LE_AUDIO_PAIRING_GUIDE = "le_audio_pairing_guide"
    /** Remove a LE identity bond created by [CMD_LE_AUDIO_DEVICE_PAIR]. */
    const val CMD_LE_AUDIO_DEVICE_UNPAIR = "le_audio_device_unpair"
    /** Allow or forbid the LE Audio profile on the bonded LE identity — the system's own switch. */
    const val CMD_SET_LE_AUDIO_POLICY = "set_le_audio_policy"
    /** Turn this device's LDAC on or off through the A2DP profile service — the system's own switch. */
    const val CMD_SET_LDAC_ENABLED = "set_ldac_enabled"
    const val CMD_SET_FIXED_SOURCE = "set_fixed_source"
    const val CMD_SET_MUSIC_HAND_OVER = "set_music_hand_over"
    const val CMD_PLAYBACK_PREVIOUS = "playback_previous"
    const val CMD_PLAYBACK_PLAY_PAUSE = "playback_play_pause"
    const val CMD_PLAYBACK_NEXT = "playback_next"
    const val CMD_SET_PLAYBACK_VOLUME = "set_playback_volume"
    const val CMD_CONNECT = "connect"
    const val CMD_DISCONNECT = "disconnect"
    const val CMD_OFFICIAL_APP_ACQUIRE = "official_app_acquire"
    const val CMD_OFFICIAL_APP_RELEASE = "official_app_release"
    const val CMD_REFRESH = "refresh"
    /** The app finished writing a model image; refresh system surfaces immediately. */
    const val CMD_IMAGE_READY = "image_ready"
    /** The app finished publishing the cloud model catalog to Remote Files. */
    const val CMD_CLOUD_MODEL_INFO_READY = "cloud_model_info_ready"
    /** Ask the engine to re-broadcast its current state (for late-starting consumers). */
    const val CMD_REPUBLISH = "republish"

    /**
     * Sent by the process that renders the HyperOS notification and island once its
     * receiver is up. The engine then re-renders, because anything it pushed before
     * that point was dropped on the floor.
     */
    const val CMD_SURFACES_READY = "surfaces_ready"
    const val CMD_DEBUG_RAW = "debug_raw"

    /** The process that hosts the engine; all commands are addressed to it. */
    const val ENGINE_PACKAGE = "com.android.bluetooth"
    const val OFFICIAL_APP_PACKAGE = "com.sony.songpal.mdr"

    /** Processes that render headphone state in system surfaces, plus the module app. */
    val STATE_CONSUMERS = listOf(
        "com.mercury.sonypods",
        "com.xiaomi.bluetooth",
        "com.milink.service",
        "com.android.settings",
    )

    fun sendCommand(context: Context, command: String, fill: Intent.() -> Unit = {}) {
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_COMMAND).apply {
                    putExtra(EXTRA_COMMAND, command)
                    fill()
                    setPackage(ENGINE_PACKAGE)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            )
        }
    }

    fun imageReady(context: Context, address: String) =
        sendCommand(context, CMD_IMAGE_READY) { putExtra(EXTRA_STRING, address) }

    fun setNoiseControl(context: Context, mode: NoiseControlMode) =
        sendCommand(context, CMD_SET_NOISE_CONTROL) { putExtra(EXTRA_STRING, mode.name) }

    fun setAmbientLevel(context: Context, level: Int) =
        sendCommand(context, CMD_SET_AMBIENT_LEVEL) { putExtra(EXTRA_INT, level) }

    fun setAmbientVoice(context: Context, enabled: Boolean) =
        sendCommand(context, CMD_SET_AMBIENT_VOICE) { putExtra(EXTRA_BOOL, enabled) }

    fun setWindNoiseReduction(context: Context, enabled: Boolean) =
        sendCommand(context, CMD_SET_WIND_NOISE_REDUCTION) { putExtra(EXTRA_BOOL, enabled) }

    fun setSpeakToChatEnabled(context: Context, enabled: Boolean) =
        sendCommand(context, CMD_SET_SPEAK_TO_CHAT_ENABLED) { putExtra(EXTRA_BOOL, enabled) }

    fun setSpeakToChatSensitivity(context: Context, sensitivityName: String) =
        sendCommand(context, CMD_SET_SPEAK_TO_CHAT_SENSITIVITY) { putExtra(EXTRA_STRING, sensitivityName) }

    fun setSpeakToChatModeOutTime(context: Context, modeOutTimeName: String) =
        sendCommand(context, CMD_SET_SPEAK_TO_CHAT_MODE_OUT_TIME) { putExtra(EXTRA_STRING, modeOutTimeName) }

    fun setNoiseAdaptive(context: Context, enabled: Boolean) =
        sendCommand(context, CMD_SET_NOISE_ADAPTIVE) { putExtra(EXTRA_BOOL, enabled) }

    /** [sensitivityName] is a [dev.sonypods.protocol.NoiseAdaptiveSensitivity] name. */
    fun setNoiseAdaptiveSensitivity(context: Context, sensitivityName: String) =
        sendCommand(context, CMD_SET_NOISE_ADAPTIVE_SENSITIVITY) { putExtra(EXTRA_STRING, sensitivityName) }

    fun setEqPreset(context: Context, presetName: String) =
        sendCommand(context, CMD_SET_EQ_PRESET) { putExtra(EXTRA_STRING, presetName) }

    fun setClearBass(context: Context, level: Int) =
        sendCommand(context, CMD_SET_CLEAR_BASS) { putExtra(EXTRA_INT, level) }

    fun setEqBand(context: Context, index: Int, level: Int) =
        sendCommand(context, CMD_SET_EQ_BAND) {
            putExtra(EXTRA_INDEX, index)
            putExtra(EXTRA_INT, level)
        }

    fun setGesturePreset(context: Context, keyCode: Int, presetCode: Int) =
        sendCommand(context, CMD_SET_GESTURE_PRESET) {
            putExtra(EXTRA_KEY_CODE, keyCode)
            putExtra(EXTRA_PRESET_CODE, presetCode)
        }

    fun setGestureFunction(context: Context, keyCode: Int, actionCode: Int, functionCode: Int) =
        sendCommand(context, CMD_SET_GESTURE_FUNCTION) {
            putExtra(EXTRA_KEY_CODE, keyCode)
            putExtra(EXTRA_ACTION_CODE, actionCode)
            putExtra(EXTRA_FUNCTION_CODE, functionCode)
        }

    fun setQuickAccessFunction(context: Context, actionIndex: Int, functionCode: Int) =
        sendCommand(context, CMD_SET_QUICK_ACCESS_FUNCTION) {
            putExtra(EXTRA_QUICK_ACCESS_ACTION_INDEX, actionIndex)
            putExtra(EXTRA_QUICK_ACCESS_FUNCTION_CODE, functionCode)
        }

    fun setGestureAmbientModes(context: Context, modeCodes: IntArray) =
        sendCommand(context, CMD_SET_GESTURE_AMBIENT_MODES) {
            putExtra(EXTRA_FUNCTION_CODE, modeCodes)
        }

    fun setMultipointPairingMode(context: Context, enabled: Boolean) =
        sendCommand(context, CMD_SET_MULTIPOINT_PAIRING_MODE) { putExtra(EXTRA_BOOL, enabled) }

    fun connectMultipointDevice(context: Context, address: String) =
        sendCommand(context, CMD_CONNECT_MULTIPOINT_DEVICE) { putExtra(EXTRA_STRING, address) }

    fun disconnectMultipointDevice(context: Context, address: String) =
        sendCommand(context, CMD_DISCONNECT_MULTIPOINT_DEVICE) { putExtra(EXTRA_STRING, address) }

    fun unpairMultipointDevice(context: Context, address: String) =
        sendCommand(context, CMD_UNPAIR_MULTIPOINT_DEVICE) { putExtra(EXTRA_STRING, address) }

    fun setSourceSwitchEnabled(context: Context, enabled: Boolean) =
        sendCommand(context, CMD_SET_SOURCE_SWITCH_ENABLED) { putExtra(EXTRA_BOOL, enabled) }

    fun setMultipointEnabled(context: Context, enabled: Boolean) =
        sendCommand(context, CMD_SET_MULTIPOINT_ENABLED) { putExtra(EXTRA_BOOL, enabled) }

    fun setLeAudioEnabled(context: Context, enabled: Boolean) =
        sendCommand(context, CMD_SET_LE_AUDIO_ENABLED) { putExtra(EXTRA_BOOL, enabled) }

    fun setUpscalingEnabled(context: Context, enabled: Boolean) =
        sendCommand(context, CMD_SET_UPSCALING_ENABLED) { putExtra(EXTRA_BOOL, enabled) }

    const val CMD_SET_CONNECTION_QUALITY = "set_connection_quality"

    /** [modeName] is a [dev.sonypods.protocol.ConnectionQualityMode] name. */
    fun setConnectionQuality(context: Context, modeName: String) =
        sendCommand(context, CMD_SET_CONNECTION_QUALITY) { putExtra(EXTRA_STRING, modeName) }

    /** Reply to the device's pending reconnection alert; true = confirm (execute), false = cancel. */
    fun replyMultipointAlert(context: Context, positive: Boolean) =
        sendCommand(context, CMD_REPLY_MULTIPOINT_ALERT) { putExtra(EXTRA_BOOL, positive) }

    fun replyLeAudioAlert(context: Context, positive: Boolean) =
        sendCommand(context, CMD_REPLY_LE_AUDIO_ALERT) { putExtra(EXTRA_BOOL, positive) }

    /**
     * Asks the engine to find and bond the headset's LE-only identity. Telling the headset
     * to switch is only half of the hand-over; without this the phone keeps its
     * BR/EDR-only bond and stays on A2DP.
     */
    fun pairLeAudioDevice(context: Context) = sendCommand(context, CMD_LE_AUDIO_DEVICE_PAIR)

    fun showLeAudioPairingGuide(context: Context) =
        sendCommand(context, CMD_LE_AUDIO_PAIRING_GUIDE)

    /** [address] defaults to whichever LE identity the engine bonded itself. */
    fun unpairLeAudioDevice(context: Context, address: String? = null) =
        sendCommand(context, CMD_LE_AUDIO_DEVICE_UNPAIR) {
            if (address != null) putExtra(EXTRA_STRING, address)
        }

    /**
     * Flips the system's per-device LE Audio permission on the bonded LE identity, which is
     * what HyperOS surfaces as "低功耗音频". The bond is left alone either way.
     */
    fun setLeAudioPolicyAllowed(context: Context, allowed: Boolean) =
        sendCommand(context, CMD_SET_LE_AUDIO_POLICY) { putExtra(EXTRA_BOOL, allowed) }

    /**
     * Flips this device's LDAC the way the system's own codec switch does: the stored per-device
     * user preference plus the A2DP codec priority.
     */
    fun setLdacEnabled(context: Context, enabled: Boolean) =
        sendCommand(context, CMD_SET_LDAC_ENABLED) { putExtra(EXTRA_BOOL, enabled) }

    fun setFixedSource(context: Context, address: String) =
        sendCommand(context, CMD_SET_FIXED_SOURCE) { putExtra(EXTRA_STRING, address) }

    fun setMusicHandOver(context: Context, enabled: Boolean) =
        sendCommand(context, CMD_SET_MUSIC_HAND_OVER) { putExtra(EXTRA_BOOL, enabled) }

    fun setPlaybackVolume(context: Context, volume: Int) =
        sendCommand(context, CMD_SET_PLAYBACK_VOLUME) { putExtra(EXTRA_INT, volume) }

    fun connect(context: Context, address: String, name: String) =
        sendCommand(context, CMD_CONNECT) {
            putExtra(EXTRA_STRING, address)
            putExtra("device_name", name)
        }

    fun acquireOfficialAppLease(context: Context, leaseId: String, token: IBinder): Boolean =
        sendOfficialAppLease(context, CMD_OFFICIAL_APP_ACQUIRE, leaseId, token)

    fun releaseOfficialAppLease(context: Context, leaseId: String, token: IBinder): Boolean =
        sendOfficialAppLease(context, CMD_OFFICIAL_APP_RELEASE, leaseId, token)

    private fun sendOfficialAppLease(
        context: Context,
        command: String,
        leaseId: String,
        token: IBinder,
    ): Boolean = runCatching {
        context.sendBroadcast(
            Intent(ACTION_COMMAND).apply {
                putExtra(EXTRA_COMMAND, command)
                putExtra(EXTRA_OFFICIAL_LEASE_ID, leaseId)
                putExtra(EXTRA_OFFICIAL_SENDER_PACKAGE, OFFICIAL_APP_PACKAGE)
                putExtra(EXTRA_OFFICIAL_SENDER_UID, Process.myUid())
                putExtras(Bundle().apply { putBinder(EXTRA_OFFICIAL_LEASE_TOKEN, token) })
                setPackage(ENGINE_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }.isSuccess
}
