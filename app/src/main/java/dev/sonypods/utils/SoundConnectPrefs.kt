package dev.sonypods.utils

/**
 * Reads Sound Connect's own Safe Listening preference.
 *
 * SC keeps the persistent listening-log switch in a single app-global boolean
 * (`KEY_SL_MODE`, default false) and writes it to every headphone it connects to:
 * its state machine starts by dispatching ON or OFF from that value, which maps to
 * `setParamOn` / `setParamOff`. The headphone itself has no readable copy — the
 * only frame that reports the two switches is the SET_PARAM confirmation — so
 * mirroring SC's preference is the one way to know which branch to follow without
 * writing first and risking the user's own setting.
 */
object SoundConnectPrefs {
    private const val SL_PREF_PATH =
        "/data/data/com.sony.songpal.mdr/shared_prefs/safe_listening_settings_preference.xml"

    private val slModeRegex = Regex("""name="KEY_SL_MODE"\s+value="(true|false)"""")

    /**
     * True/false when SC's switch could be read, null when it could not.
     *
     * A missing file with root working means SC is absent or has never written the
     * setting, and its own default is off.
     */
    fun readSafeListeningMode(): Boolean? {
        RootManager.readTextAsRoot(SL_PREF_PATH)?.let { xml ->
            return slModeRegex.find(xml)?.groupValues?.get(1) == "true"
        }
        return if (RootManager.hasRootAccess()) false else null
    }
}
