package dev.sonypods.leaudio
/** Mirrors Sound Connect's C12259c0 capability-change sequence. */
class LeAudioSwitchCoordinator(
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun requestPairedHistory(): Boolean
        /** Mirrors Sound Connect's BtProfileGateway.mo61779l queue. */
        fun requestLeAudioProfileReady(onReady: (platform: LeAudioProfileGateway.Platform) -> Unit): Boolean
        fun sendHeadsetCommand(enabled: Boolean, changeConnectionMethod: Boolean): Boolean
        fun onPairingGuideRequired(enabled: Boolean, pairedHistory: String?)
        fun onFinished(success: Boolean, message: String)
        fun onLog(message: String)
        fun shouldSkipPairingGuide(): Boolean
    }

    enum class Stage {
        IDLE,
        WAITING_FOR_PAIRED_HISTORY,
        WAITING_FOR_PROFILE_READY,
        WAITING_FOR_SETTING_NOTIFICATION,
        SUCCESS,
        FAILED,
    }

    private var stage = Stage.IDLE
    private var requestedEnabled: Boolean? = null
    private var pairedHistory: String? = null
    private var platform = LeAudioProfileGateway.Platform.UNSUPPORTED

    fun start(currentPairedHistory: String?) = begin(true, currentPairedHistory)

    fun disable() = begin(false, null)

    fun cancel() {
        if (!isRunning()) return
        log("LE Audio switch cancelled")
        stage = Stage.IDLE
        requestedEnabled = null
        pairedHistory = null
        platform = LeAudioProfileGateway.Platform.UNSUPPORTED
    }

    fun isRunning(): Boolean = stage !in setOf(Stage.IDLE, Stage.SUCCESS, Stage.FAILED)

    /** Called for a real Sony paired-history response. */
    fun onPairedHistory(pairedHistory: String?) {
        if (stage != Stage.WAITING_FOR_PAIRED_HISTORY || pairedHistory == null) return
        this.pairedHistory = pairedHistory
        log("Headset paired history=$pairedHistory; continuing official hand-over")
        // C12259c0's Qualcomm path records the history for the pairing guide,
        // but all three sender variants still issue SETTING_AND_CONNECTION_METHOD_CHANGE.
        // The history changes the follow-up guide, not the 0x48 change type.
        sendHeadsetSettingCommand(enabled = true, platformSupportsLeAudio = true)
    }

    /** Compatibility entry point for older callers. Device Alert replies are
     * handled by the repository and do not advance this setting transaction. */
    fun confirm(positive: Boolean) {
        if (!positive) cancel()
    }

    /** Called for the real 0x49/0x0c setting notification. */
    fun onHeadsetSetting(enabled: String?) {
        if (stage != Stage.WAITING_FOR_SETTING_NOTIFICATION) return
        val expected = if (requestedEnabled == true) "ENABLE" else "DISABLE"
        if (enabled != expected) {
            log("Ignoring headset setting=$enabled while waiting for $expected")
            return
        }
        val guideHistory = pairedHistory
        val targetEnabled = requestedEnabled == true
        val skipPairingGuide = callbacks.shouldSkipPairingGuide()
        val showGuide = when (platform) {
            LeAudioProfileGateway.Platform.UNSUPPORTED -> false
            LeAudioProfileGateway.Platform.STANDARD -> true
            LeAudioProfileGateway.Platform.SONY_QUALCOMM ->
                targetEnabled && pairedHistory == "ONLY_CLASSIC_BT" && !skipPairingGuide
        }
        succeed("Headset confirmed LE Audio ${if (targetEnabled) "enabled" else "disabled"}")
        if (showGuide) callbacks.onPairingGuideRequired(targetEnabled, guideHistory)
    }

    /** Streaming is informational only. It is not the setting transaction's
     * completion signal: the official app waits for its Classic-only observer. */
    fun onHeadsetStreaming(streamingStatusL: String?, streamingStatusR: String?) {
        if (stage == Stage.WAITING_FOR_SETTING_NOTIFICATION) {
            log("Observed LE Audio streaming status L=$streamingStatusL R=$streamingStatusR; waiting for setting observer")
        }
    }

    private fun begin(enabled: Boolean, initialPairedHistory: String?) {
        if (isRunning()) {
            log("LE Audio switch already running")
            return
        }
        requestedEnabled = enabled
        pairedHistory = initialPairedHistory
        stage = Stage.WAITING_FOR_PROFILE_READY
        log("LE Audio ${if (enabled) "enable" else "disable"} started")
        awaitLeAudioProfileReady()
    }

    private fun awaitLeAudioProfileReady() {
        stage = Stage.WAITING_FOR_PROFILE_READY
        log("Waiting for Android LE Audio profile service")
        if (!callbacks.requestLeAudioProfileReady { readyPlatform ->
                platform = readyPlatform
                val platformSupportsLeAudio = readyPlatform != LeAudioProfileGateway.Platform.UNSUPPORTED
                if (requestedEnabled == true && platformSupportsLeAudio) {
                    stage = Stage.WAITING_FOR_PAIRED_HISTORY
                    log("Android LE Audio profile ready; requesting live paired history")
                    if (!callbacks.requestPairedHistory()) {
                        log("Paired-history request unavailable; continuing without guide data")
                        sendHeadsetSettingCommand(enabled = true, platformSupportsLeAudio = true)
                    }
                } else {
                    if (requestedEnabled == true) {
                        log("Using paired history=${pairedHistory.orEmpty()} for LE Audio hand-over")
                    }
                    sendHeadsetSettingCommand(requestedEnabled == true, platformSupportsLeAudio)
                }
            }) {
            fail("Android LE Audio profile service is unavailable")
        }
    }

    private fun sendHeadsetSettingCommand(enabled: Boolean, platformSupportsLeAudio: Boolean) {
        stage = Stage.WAITING_FOR_SETTING_NOTIFICATION
        log("Sending Sony LE Audio ${if (enabled) "ENABLE" else "DISABLE"} " +
            "changeConnectionMethod=$platformSupportsLeAudio")
        if (!callbacks.sendHeadsetCommand(enabled, platformSupportsLeAudio)) {
            fail("The current Sony protocol did not produce an LE Audio setting command")
            return
        }
    }

    private fun succeed(message: String) {
        if (!isRunning()) return
        log(message)
        stage = Stage.SUCCESS
        callbacks.onFinished(true, message)
        stage = Stage.IDLE
        requestedEnabled = null
        pairedHistory = null
        platform = LeAudioProfileGateway.Platform.UNSUPPORTED
    }

    private fun fail(message: String) {
        if (!isRunning()) return
        log(message)
        stage = Stage.FAILED
        callbacks.onFinished(false, message)
        stage = Stage.IDLE
        requestedEnabled = null
        pairedHistory = null
        platform = LeAudioProfileGateway.Platform.UNSUPPORTED
    }

    private fun log(message: String) = callbacks.onLog("LEA[$stage] $message")

}
