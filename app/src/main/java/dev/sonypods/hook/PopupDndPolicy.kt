package dev.sonypods.hook

import android.content.Context
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import dev.sonypods.config.ConfigManager

/**
 * Whether the connect popup may interrupt the user right now.
 *
 * The two ROM-derived rules reproduce Bluetooth Extension's own precondition:
 * `MiuiFastConnectService.preCheckStartProductActivity` refuses to show the official
 * dialog while a game is running, and — on phones only — while the display is rotated
 * to landscape. The module starts both the official dialog and its own popup through
 * paths that never reach that check, so without repeating it here the module
 * interrupts exactly the situations the system deliberately leaves alone.
 *
 * The ROM rules still preserve the original order: game is checked before tablet
 * orientation handling, so a tablet is not exempt from the game rule; only the
 * orientation test is skipped on tablets, whose natural orientation may itself be
 * landscape.
 *
 * On top of those sit two user-managed lists, keyed on which app holds the
 * foreground — see [suppressReason] for why the deny list outranks the switch that
 * owns the ROM rules.
 */
object PopupDndPolicy {
    private const val ACTION_GAME_START = "com.xiaomi.joyose.GAME_START"
    private const val EXTRA_GAME_START = "start"
    private const val TABLET_MIN_SMALLEST_WIDTH_DP = 600

    /** A short reason to suppress the popup, or null when it may be shown. */
    fun suppressReason(context: Context): String? {
        // The deny list is deliberately checked before the master switch: it carries
        // the module's own package by default, which used to be a switch of its own,
        // and turning the game/landscape rules off must not silently re-enable a
        // popup over the module's own UI.
        ForegroundQuery.firstForegroundIn(context, ConfigManager.popupDenylist())
            ?.let { return "denylist:$it" }
        if (!ConfigManager.suppressPopupInGameOrLandscape()) return null
        // The allow list only exempts from the two rules below, so it is evaluated
        // after the switch that owns them.
        if (ForegroundQuery.firstForegroundIn(context, ConfigManager.popupAllowlist()) != null) {
            return null
        }
        if (isGameRunning(context)) return "game"
        if (isPhoneLandscape(context)) return "landscape"
        return null
    }

    /**
     * Game state comes from the same sticky broadcast Bluetooth Extension listens to.
     * Registering a null receiver returns the last value synchronously, so reading it
     * costs neither a live receiver nor polling.
     */
    private fun isGameRunning(context: Context): Boolean = runCatching {
        context.registerReceiver(null, IntentFilter(ACTION_GAME_START))
            ?.getBooleanExtra(EXTRA_GAME_START, false) == true
    }.getOrDefault(false)

    private fun isPhoneLandscape(context: Context): Boolean = runCatching {
        if (isPad(context)) return@runCatching false
        val rotation = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation
            ?: return@runCatching false
        rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    }.getOrDefault(false)

    /**
     * The ROM's own tablet flag, with a screen-size fallback for builds where the
     * hidden FeatureParser is unavailable. Guessing "phone" on a tablet would suppress
     * the popup in its normal orientation, so the fallback errs towards tablet.
     */
    private fun isPad(context: Context): Boolean = runCatching {
        Class.forName("android.util.FeatureParser")
            .getMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
            .invoke(null, "is_pad", false) as Boolean
    }.getOrElse {
        context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP
    }
}
