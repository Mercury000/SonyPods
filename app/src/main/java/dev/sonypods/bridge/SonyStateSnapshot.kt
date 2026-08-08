package dev.sonypods.bridge

import android.os.Bundle
import dev.sonypods.data.GestureOperationAction
import dev.sonypods.data.GestureOperationKey
import dev.sonypods.data.QuickAccessActionState
import dev.sonypods.data.SonyHeadphoneUiState
import dev.sonypods.protocol.AssignableSettingsAction
import dev.sonypods.protocol.AssignableSettingsFunction
import dev.sonypods.protocol.AssignableSettingsKey
import dev.sonypods.protocol.AssignableSettingsPreset
import dev.sonypods.protocol.AssignableSettingsType
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.NoiseControlMode
import dev.sonypods.protocol.PlaybackStatus

/**
 * The full headphone state as carried across processes.
 *
 * The Sony Tandem engine lives in the `com.android.bluetooth` hook process so that
 * system-surface controls keep working when the module app is not running. Every
 * other process (module UI, com.xiaomi.bluetooth, com.milink.service,
 * com.android.settings) is a consumer that learns the state from this snapshot.
 *
 * Kept as a flat [Bundle] rather than a Parcelable: the receiving processes load our
 * classes through LSPosed, and a Parcelable would have to be unmarshalled by a class
 * loader that may not be the one that wrote it.
 */
data class SonyStateSnapshot(
    val connected: Boolean = false,
    val protocolReady: Boolean = false,
    /** True once the connection-time capability probe finished (cache restore or
     * full probe). The app detail UI is gated on it so it never opens against an
     * empty half-probed profile. */
    val probeComplete: Boolean = false,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val firmwareVersion: String? = null,
    /** Physical form: "HEADSET" (over-ear, single battery) / "TRUE_WIRELESS" /
     * "UNKNOWN". Carried from the connected profile so system surfaces (fusion
     * device center, settings injection) can present a single-battery over-ear
     * headphone instead of projecting it onto a TWS case/left/right layout. */
    val formFactor: String? = null,
    /** Cloud catalog image URL for this model and colour. */
    val modelImageUrl: String? = null,
    /** Catalog accent colour (ARGB hex) for system surfaces. */
    val modelImageSourceColor: String? = null,
    val supportsPowerOff: Boolean = false,
    val supportsGestureOperations: Boolean = false,
    val batterySingle: Int? = null,
    val batteryLeft: Int? = null,
    val batteryRight: Int? = null,
    val batteryCradle: Int? = null,

    val noiseControlMode: NoiseControlMode? = null,
    val ambientLevel: Int? = null,
    val ambientVoiceMode: Boolean = false,

    val eqPreset: EqPresetId? = null,
    val eqAvailablePresets: List<EqPresetId> = emptyList(),
    val eqClearBass: Int? = null,
    val eqHasClearBass: Boolean = true,
    val eqBandSteps: List<Int> = emptyList(),
    val eqBandLabels: List<String> = emptyList(),
    val eqBandMin: Int = -10,
    val eqBandMax: Int = 10,
    val eqClearBassMin: Int = -10,
    val eqClearBassMax: Int = 10,

    val leaStatus: String? = null,
    val quickAccessLeftRight: String? = null,
    val quickAccessNcAmb: String? = null,
    val quickAccessKeyCode: Int? = null,
    val quickAccessTypeCode: Int? = null,
    val quickAccessEnabled: Boolean? = null,
    val quickAccessFunctionCodes: List<Int> = emptyList(),
    val quickAccessActions: List<QuickAccessActionSnapshot> = emptyList(),
    val gestureOperationKeys: List<GestureOperationKey> = emptyList(),
    val wearingStatus: String? = null,
    val playbackStatus: PlaybackStatus = PlaybackStatus.UNKNOWN,
    val scanState: String? = null,
) {
    /** Aggregated level fed to the system bluetooth stack and the Xiaomi surfaces. */
    val systemBatteryLevel: Int?
        get() = listOfNotNull(batterySingle, batteryLeft, batteryRight).minOrNull()

    fun toBundle(): Bundle = Bundle().apply {
        putBoolean(KEY_CONNECTED, connected)
        putBoolean(KEY_PROTOCOL_READY, protocolReady)
        putBoolean(KEY_PROBE_COMPLETE, probeComplete)
        deviceName?.let { putString(KEY_DEVICE_NAME, it) }
        deviceAddress?.let { putString(KEY_DEVICE_ADDRESS, it) }
        firmwareVersion?.let { putString(KEY_FIRMWARE, it) }
        formFactor?.let { putString(KEY_FORM_FACTOR, it) }
        modelImageUrl?.let { putString(KEY_MODEL_IMAGE, it) }
        modelImageSourceColor?.let { putString(KEY_MODEL_IMAGE_COLOR, it) }
        putBoolean(KEY_SUPPORTS_POWER_OFF, supportsPowerOff)
        putBoolean(KEY_SUPPORTS_GESTURES, supportsGestureOperations)
        putParcelableArrayList(KEY_GESTURES, ArrayList(gestureOperationKeys.map { it.toBundle() }))

        batterySingle?.let { putInt(KEY_BATTERY_SINGLE, it) }
        batteryLeft?.let { putInt(KEY_BATTERY_LEFT, it) }
        batteryRight?.let { putInt(KEY_BATTERY_RIGHT, it) }
        batteryCradle?.let { putInt(KEY_BATTERY_CRADLE, it) }

        noiseControlMode?.let { putString(KEY_NC_MODE, it.name) }
        ambientLevel?.let { putInt(KEY_AMBIENT_LEVEL, it) }
        putBoolean(KEY_AMBIENT_VOICE, ambientVoiceMode)

        eqPreset?.let { putString(KEY_EQ_PRESET, it.name) }
        putStringArray(KEY_EQ_PRESETS, eqAvailablePresets.map { it.name }.toTypedArray())
        eqClearBass?.let { putInt(KEY_EQ_CLEAR_BASS, it) }
        putBoolean(KEY_EQ_HAS_CLEAR_BASS, eqHasClearBass)
        putIntArray(KEY_EQ_BANDS, eqBandSteps.toIntArray())
        putStringArray(KEY_EQ_BAND_LABELS, eqBandLabels.toTypedArray())
        putInt(KEY_EQ_BAND_MIN, eqBandMin)
        putInt(KEY_EQ_BAND_MAX, eqBandMax)
        putInt(KEY_EQ_CB_MIN, eqClearBassMin)
        putInt(KEY_EQ_CB_MAX, eqClearBassMax)

        leaStatus?.let { putString(KEY_LEA, it) }
        quickAccessLeftRight?.let { putString(KEY_QA_LR, it) }
        quickAccessNcAmb?.let { putString(KEY_QA_NC, it) }
        quickAccessKeyCode?.let { putInt(KEY_QA_KEY, it) }
        quickAccessTypeCode?.let { putInt(KEY_QA_TYPE, it) }
        quickAccessEnabled?.let {
            putBoolean(KEY_QA_ENABLED_KNOWN, true)
            putBoolean(KEY_QA_ENABLED, it)
        }
        putIntArray(KEY_QA_FUNCTION_CODES, quickAccessFunctionCodes.toIntArray())
        putParcelableArrayList(KEY_QA_ACTIONS, ArrayList(quickAccessActions.map { it.toBundle() }))
        wearingStatus?.let { putString(KEY_WEARING, it) }
        putString(KEY_PLAYBACK, playbackStatus.name)
        scanState?.let { putString(KEY_SCAN_STATE, it) }
    }

    companion object {
        const val EXTRA_SNAPSHOT = "sony_state"

        private const val KEY_CONNECTED = "connected"
        private const val KEY_PROTOCOL_READY = "protocol_ready"
        private const val KEY_PROBE_COMPLETE = "probe_complete"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ADDRESS = "device_address"
        private const val KEY_FIRMWARE = "firmware"
        private const val KEY_FORM_FACTOR = "form_factor"
        private const val KEY_MODEL_IMAGE = "model_image_url"
        private const val KEY_MODEL_IMAGE_COLOR = "model_image_source_color"
        private const val KEY_SUPPORTS_POWER_OFF = "supports_power_off"
        private const val KEY_SUPPORTS_GESTURES = "supports_gesture_operations"
        private const val KEY_GESTURES = "gesture_operations"
        private const val KEY_ACTIONS = "actions"
        private const val KEY_KEY = "key"
        private const val KEY_TYPE = "type"
        private const val KEY_ENABLED_KNOWN = "enabled_known"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PRESET = "preset"
        private const val KEY_PRESETS = "presets"
        private const val KEY_ACTION = "action"
        private const val KEY_FUNCTION = "function"
        private const val KEY_FUNCTIONS = "functions"
        private const val KEY_BATTERY_SINGLE = "battery_single"
        private const val KEY_BATTERY_LEFT = "battery_left"
        private const val KEY_BATTERY_RIGHT = "battery_right"
        private const val KEY_BATTERY_CRADLE = "battery_cradle"
        private const val KEY_NC_MODE = "nc_mode"
        private const val KEY_AMBIENT_LEVEL = "ambient_level"
        private const val KEY_AMBIENT_VOICE = "ambient_voice"
        private const val KEY_EQ_PRESET = "eq_preset"
        private const val KEY_EQ_PRESETS = "eq_presets"
        private const val KEY_EQ_CLEAR_BASS = "eq_clear_bass"
        private const val KEY_EQ_HAS_CLEAR_BASS = "eq_has_clear_bass"
        private const val KEY_EQ_BANDS = "eq_bands"
        private const val KEY_EQ_BAND_LABELS = "eq_band_labels"
        private const val KEY_EQ_BAND_MIN = "eq_band_min"
        private const val KEY_EQ_BAND_MAX = "eq_band_max"
        private const val KEY_EQ_CB_MIN = "eq_cb_min"
        private const val KEY_EQ_CB_MAX = "eq_cb_max"
        private const val KEY_LEA = "lea"
        private const val KEY_QA_LR = "qa_lr"
        private const val KEY_QA_NC = "qa_nc"
        private const val KEY_QA_KEY = "qa_key"
        private const val KEY_QA_TYPE = "qa_type"
        private const val KEY_QA_ENABLED_KNOWN = "qa_enabled_known"
        private const val KEY_QA_ENABLED = "qa_enabled"
        private const val KEY_QA_FUNCTION_CODES = "qa_function_codes"
        private const val KEY_QA_ACTIONS = "qa_actions"
        private const val KEY_QA_ACTION = "qa_action"
        private const val KEY_QA_FUNCTION = "qa_function"
        private const val KEY_QA_DEFAULT = "qa_default"
        private const val KEY_QA_FUNCTIONS = "qa_functions"
        private const val KEY_WEARING = "wearing"
        private const val KEY_PLAYBACK = "playback"
        private const val KEY_SCAN_STATE = "scan_state"

        fun fromBundle(bundle: Bundle): SonyStateSnapshot = SonyStateSnapshot(
            connected = bundle.getBoolean(KEY_CONNECTED, false),
            protocolReady = bundle.getBoolean(KEY_PROTOCOL_READY, false),
            probeComplete = bundle.getBoolean(KEY_PROBE_COMPLETE, false),
            deviceName = bundle.getString(KEY_DEVICE_NAME),
            deviceAddress = bundle.getString(KEY_DEVICE_ADDRESS),
            firmwareVersion = bundle.getString(KEY_FIRMWARE),
            formFactor = bundle.getString(KEY_FORM_FACTOR),
            modelImageUrl = bundle.getString(KEY_MODEL_IMAGE),
            modelImageSourceColor = bundle.getString(KEY_MODEL_IMAGE_COLOR),
            supportsPowerOff = bundle.getBoolean(KEY_SUPPORTS_POWER_OFF, false),
            supportsGestureOperations = bundle.getBoolean(KEY_SUPPORTS_GESTURES, false),
            batterySingle = bundle.optInt(KEY_BATTERY_SINGLE),
            batteryLeft = bundle.optInt(KEY_BATTERY_LEFT),
            batteryRight = bundle.optInt(KEY_BATTERY_RIGHT),
            batteryCradle = bundle.optInt(KEY_BATTERY_CRADLE),
            noiseControlMode = bundle.getString(KEY_NC_MODE)?.let { name ->
                NoiseControlMode.entries.firstOrNull { it.name == name }
            },
            ambientLevel = bundle.optInt(KEY_AMBIENT_LEVEL),
            ambientVoiceMode = bundle.getBoolean(KEY_AMBIENT_VOICE, false),
            eqPreset = bundle.getString(KEY_EQ_PRESET)?.let { name ->
                EqPresetId.entries.firstOrNull { it.name == name }
            },
            eqAvailablePresets = bundle.getStringArray(KEY_EQ_PRESETS).orEmpty().mapNotNull { name ->
                EqPresetId.entries.firstOrNull { it.name == name }
            },
            eqClearBass = bundle.optInt(KEY_EQ_CLEAR_BASS),
            eqHasClearBass = bundle.getBoolean(KEY_EQ_HAS_CLEAR_BASS, true),
            eqBandSteps = bundle.getIntArray(KEY_EQ_BANDS)?.toList().orEmpty(),
            eqBandLabels = bundle.getStringArray(KEY_EQ_BAND_LABELS)?.toList().orEmpty(),
            eqBandMin = bundle.getInt(KEY_EQ_BAND_MIN, -10),
            eqBandMax = bundle.getInt(KEY_EQ_BAND_MAX, 10),
            eqClearBassMin = bundle.getInt(KEY_EQ_CB_MIN, -10),
            eqClearBassMax = bundle.getInt(KEY_EQ_CB_MAX, 10),
            leaStatus = bundle.getString(KEY_LEA),
            quickAccessLeftRight = bundle.getString(KEY_QA_LR),
            quickAccessNcAmb = bundle.getString(KEY_QA_NC),
            quickAccessKeyCode = bundle.optInt(KEY_QA_KEY),
            quickAccessTypeCode = bundle.optInt(KEY_QA_TYPE),
            quickAccessEnabled = if (bundle.getBoolean(KEY_QA_ENABLED_KNOWN, false)) {
                bundle.getBoolean(KEY_QA_ENABLED)
            } else null,
            quickAccessFunctionCodes = bundle.getIntArray(KEY_QA_FUNCTION_CODES)?.toList().orEmpty(),
            quickAccessActions = bundle.quickAccessActions(),
            gestureOperationKeys = bundle.gestureKeys(),
            wearingStatus = bundle.getString(KEY_WEARING),
            playbackStatus = bundle.getString(KEY_PLAYBACK)?.let { name ->
                PlaybackStatus.entries.firstOrNull { it.name == name }
            } ?: PlaybackStatus.UNKNOWN,
            scanState = bundle.getString(KEY_SCAN_STATE),
        )

        fun fromUiState(state: SonyHeadphoneUiState): SonyStateSnapshot {
            val capability = state.eqUiCapability
            return SonyStateSnapshot(
                connected = state.connectedDevice != null,
                protocolReady = state.deviceInfo.protocolReady,
                probeComplete = state.probeComplete,
                deviceName = state.deviceInfo.modelName ?: state.connectedDevice?.name,
                deviceAddress = state.connectedDevice?.address,
                firmwareVersion = state.deviceInfo.firmwareVersion,
                formFactor = state.connectedProfile?.capabilities?.formFactor?.name,
                modelImageUrl = state.deviceInfo.modelImageUrl,
                modelImageSourceColor = state.deviceInfo.modelImageSourceColor,
                supportsPowerOff = state.connectedProfile?.supports(dev.sonypods.headphones.HeadphoneFeature.POWER_OFF) == true,
                supportsGestureOperations = state.connectedProfile?.supports(dev.sonypods.headphones.HeadphoneFeature.GESTURE_OPERATIONS) == true,
                batterySingle = state.batteryState.single,
                batteryLeft = state.batteryState.left,
                batteryRight = state.batteryState.right,
                batteryCradle = state.batteryState.cradle,
                noiseControlMode = state.noiseControlState.controlMode,
                ambientLevel = state.noiseControlState.ambientLevel,
                ambientVoiceMode = state.noiseControlState.ambientVoiceMode,
                eqPreset = state.eqState.preset,
                eqAvailablePresets = capability?.availablePresets.orEmpty(),
                eqClearBass = state.eqState.clearBass,
                eqHasClearBass = capability?.hasClearBass ?: true,
                eqBandSteps = state.eqState.bandSteps,
                eqBandLabels = capability?.bandLabels.orEmpty(),
                eqBandMin = capability?.bandDisplayRange?.first ?: -10,
                eqBandMax = capability?.bandDisplayRange?.last ?: 10,
                eqClearBassMin = capability?.clearBassDisplayRange?.first ?: -10,
                eqClearBassMax = capability?.clearBassDisplayRange?.last ?: 10,
                leaStatus = state.leaState.enabled,
                quickAccessLeftRight = state.quickAccessState.lrKeyFunction,
                quickAccessNcAmb = state.quickAccessState.ncAmbKeyFunction,
                quickAccessKeyCode = state.quickAccessState.key?.code?.toInt()?.and(0xFF),
                quickAccessTypeCode = state.quickAccessState.type?.code?.toInt()?.and(0xFF),
                quickAccessEnabled = state.quickAccessState.enabled,
                quickAccessFunctionCodes = state.quickAccessState.functionCodes,
                quickAccessActions = state.quickAccessState.actions.map { it.toSnapshot() },
                gestureOperationKeys = state.gestureOperationsState.uiKeys(),
                wearingStatus = state.wearingState.status,
                playbackStatus = state.playbackStatus,
                scanState = state.scanState,
            )
        }

        @Suppress("DEPRECATION")
        private fun Bundle.gestureKeys(): List<GestureOperationKey> =
            getParcelableArrayList<Bundle>(KEY_GESTURES).orEmpty().map { keyBundle ->
                val actions = keyBundle.getParcelableArrayList<Bundle>(KEY_ACTIONS).orEmpty().map { actionBundle ->
                    GestureOperationAction(
                        action = AssignableSettingsAction.entries.firstOrNull {
                            it.code.toInt() and 0xFF == actionBundle.getInt(KEY_ACTION)
                        } ?: AssignableSettingsAction.OUT_OF_RANGE,
                        function = AssignableSettingsFunction.entries.firstOrNull {
                            it.code.toInt() and 0xFF == actionBundle.getInt(KEY_FUNCTION)
                        } ?: AssignableSettingsFunction.OUT_OF_RANGE,
                        availableFunctions = (actionBundle.getIntArray(KEY_FUNCTIONS) ?: intArrayOf()).toList().mapNotNull { code ->
                            AssignableSettingsFunction.entries.firstOrNull {
                                it.code.toInt() and 0xFF == code
                            }?.takeIf { it != AssignableSettingsFunction.OUT_OF_RANGE }
                        },
                    )
                }
                GestureOperationKey(
                    key = AssignableSettingsKey.entries.firstOrNull {
                        it.code.toInt() and 0xFF == keyBundle.getInt(KEY_KEY)
                    } ?: AssignableSettingsKey.OUT_OF_RANGE,
                    type = AssignableSettingsType.entries.firstOrNull {
                        it.code.toInt() and 0xFF == keyBundle.getInt(KEY_TYPE)
                    } ?: AssignableSettingsType.OUT_OF_RANGE,
                    enabled = if (keyBundle.getBoolean(KEY_ENABLED_KNOWN, false)) {
                        keyBundle.getBoolean(KEY_ENABLED)
                    } else null,
                    currentPreset = AssignableSettingsPreset.entries.firstOrNull {
                        it.code.toInt() and 0xFF == keyBundle.getInt(KEY_PRESET)
                    } ?: AssignableSettingsPreset.NO_FUNCTION,
                    availablePresets = (keyBundle.getIntArray(KEY_PRESETS) ?: intArrayOf()).toList().mapNotNull { code ->
                        AssignableSettingsPreset.entries.firstOrNull {
                            it.code.toInt() and 0xFF == code
                        }?.takeIf { it != AssignableSettingsPreset.OUT_OF_RANGE }
                    },
                    actions = actions,
                )
            }

        private fun GestureOperationKey.toBundle(): Bundle = Bundle().apply {
            putInt(KEY_KEY, key.code.toInt() and 0xFF)
            putInt(KEY_TYPE, type.code.toInt() and 0xFF)
            enabled?.let {
                putBoolean(KEY_ENABLED_KNOWN, true)
                putBoolean(KEY_ENABLED, it)
            }
            putInt(KEY_PRESET, currentPreset.code.toInt() and 0xFF)
            putIntArray(KEY_PRESETS, availablePresets.map { it.code.toInt() and 0xFF }.toIntArray())
            putParcelableArrayList(KEY_ACTIONS, ArrayList(actions.map { it.toBundle() }))
        }

        private fun GestureOperationAction.toBundle(): Bundle = Bundle().apply {
            putInt(KEY_ACTION, action.code.toInt() and 0xFF)
            putInt(KEY_FUNCTION, function.code.toInt() and 0xFF)
            putIntArray(KEY_FUNCTIONS, availableFunctions.map { it.code.toInt() and 0xFF }.toIntArray())
        }

        @Suppress("DEPRECATION")
        private fun Bundle.quickAccessActions(): List<QuickAccessActionSnapshot> =
            getParcelableArrayList<Bundle>(KEY_QA_ACTIONS).orEmpty().map { actionBundle ->
                QuickAccessActionSnapshot(
                    actionCode = actionBundle.getInt(KEY_QA_ACTION),
                    currentFunctionCode = if (actionBundle.getBoolean("${KEY_QA_FUNCTION}_known", false)) {
                        actionBundle.getInt(KEY_QA_FUNCTION)
                    } else null,
                    defaultFunctionCode = actionBundle.getInt(KEY_QA_DEFAULT),
                    availableFunctionCodes = (actionBundle.getIntArray(KEY_QA_FUNCTIONS) ?: intArrayOf()).toList(),
                )
            }

        private fun QuickAccessActionState.toSnapshot(): QuickAccessActionSnapshot =
            QuickAccessActionSnapshot(
                actionCode = action.code.toInt() and 0xFF,
                currentFunctionCode = currentFunctionCode,
                defaultFunctionCode = defaultFunctionCode,
                availableFunctionCodes = availableFunctionCodes,
            )

        private fun QuickAccessActionSnapshot.toBundle(): Bundle = Bundle().apply {
            putInt(KEY_QA_ACTION, actionCode)
            currentFunctionCode?.let {
                putBoolean("${KEY_QA_FUNCTION}_known", true)
                putInt(KEY_QA_FUNCTION, it)
            }
            putInt(KEY_QA_DEFAULT, defaultFunctionCode)
            putIntArray(KEY_QA_FUNCTIONS, availableFunctionCodes.toIntArray())
        }

        private fun Bundle.optInt(key: String): Int? = if (containsKey(key)) getInt(key) else null
    }
}

/** Flat cross-process representation of a Quick Access capability action. */
data class QuickAccessActionSnapshot(
    val actionCode: Int,
    val currentFunctionCode: Int?,
    val defaultFunctionCode: Int,
    val availableFunctionCodes: List<Int>,
)
