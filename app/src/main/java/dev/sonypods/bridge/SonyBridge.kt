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

    /** Engine -> official app hook: reassert a live lease after bluetooth-process restart. */
    const val ACTION_ENGINE_READY = "dev.sonypods.action.engine_ready"

    /**
     * Engine -> app (durable persistence) and app -> engine (value push):
     * the encoded capability-probe cache. The payload is [EXTRA_CAPABILITY_JSON].
     * The engine broadcasts it to the app, which persists it into the shared
     * remote-prefs store (the only side where that store is writable) and echoes
     * it back so the engine's in-process overlay stays current even when the
     * hook-side remote-prefs read comes back empty.
     */
    const val ACTION_CAPABILITY_CACHE = "dev.sonypods.action.capability_cache"

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
    const val EXTRA_CAPABILITY_JSON = "capability_cache_json"
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

    // Commands understood by the engine.
    const val CMD_SET_NOISE_CONTROL = "set_noise_control"
    const val CMD_CYCLE_NOISE_CONTROL = "cycle_noise_control"
    const val CMD_SET_AMBIENT_LEVEL = "set_ambient_level"
    const val CMD_SET_AMBIENT_VOICE = "set_ambient_voice"
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
    const val CMD_REPLY_MULTIPOINT_ALERT = "reply_multipoint_alert"
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

    /** Reply to the device's pending reconnection alert; true = confirm (execute), false = cancel. */
    fun replyMultipointAlert(context: Context, positive: Boolean) =
        sendCommand(context, CMD_REPLY_MULTIPOINT_ALERT) { putExtra(EXTRA_BOOL, positive) }

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

    /**
     * Push the encoded capability-probe cache to the engine (app -> engine value
     * push, mirroring the config broadcast; used by the app receiver to echo the
     * engine's own write back so the in-process overlay stays current).
     */
    fun sendCapabilityCache(context: Context, json: String) {
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_CAPABILITY_CACHE).apply {
                    putExtra(EXTRA_CAPABILITY_JSON, json)
                    setPackage(ENGINE_PACKAGE)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            )
        }
    }
}
