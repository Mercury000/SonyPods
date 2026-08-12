package dev.sonypods.utils.miuiStrongToast.data

/**
 * Cross-process broadcast actions for the SonyPods bridge (namespace dev.sonypods.action.*).
 *
 * Direction legend:
 *  - app -> hooks: state fan-out from the app-process Sony repository
 *  - hooks -> app: control requests and connection triggers
 */
object SonyPodsAction {
    // App UI <-> bluetooth-process liveness ping
    const val ACTION_PODS_UI_INIT = "dev.sonypods.action.ui_init"
    const val ACTION_PODS_UI_CLOSED = "dev.sonypods.action.ui_closed"
    const val ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE = "dev.sonypods.action.module_bluetooth_service_alive"

    // app -> hooks: state fan-out
    const val ACTION_PODS_CONNECTED = "dev.sonypods.action.pods_connected"
    const val ACTION_PODS_DISCONNECTED = "dev.sonypods.action.pods_disconnected"
    const val ACTION_PODS_BATTERY_CHANGED = "dev.sonypods.action.pods_battery_changed"
    const val ACTION_PODS_WEAR_STATUS_CHANGED = "dev.sonypods.action.pods_wear_status_changed"
    const val ACTION_PODS_ANC_CHANGED = "dev.sonypods.action.pods_anc_changed"
    const val ACTION_PODS_AMBIENT_VOICE_CHANGED = "dev.sonypods.action.pods_ambient_voice_changed"

    // hooks -> app: control requests (received by SystemIntegrationReceiver)
    const val ACTION_ANC_SELECT = "dev.sonypods.action.anc_select"
    const val ACTION_AMBIENT_VOICE_SET = "dev.sonypods.action.ambient_voice_set"
    const val ACTION_CYCLE_ANC = "dev.sonypods.action.cycle_anc"
    const val ACTION_REFRESH_STATUS = "dev.sonypods.action.refresh_status"

    // bluetooth process -> app: A2DP connection trigger for the Sony transport
    const val ACTION_HOOK_DEVICE_CONNECTED = "dev.sonypods.action.hook_device_connected"
    const val ACTION_HOOK_DEVICE_DISCONNECTED = "dev.sonypods.action.hook_device_disconnected"

    // app -> com.xiaomi.bluetooth: notification / island rendering
    const val ACTION_SEND_STRONG_TOAST = "dev.sonypods.action.send_strong_toast"
    const val ACTION_CANCEL_BATTERY_ISLAND = "dev.sonypods.action.cancel_battery_island"
    const val ACTION_UPDATE_PODS_NOTIFICATION = "dev.sonypods.action.update_pods_notification"
    const val ACTION_CANCEL_PODS_NOTIFICATION = "dev.sonypods.action.cancel_pods_notification"

    // Popup activity intent action (manifest intent-filter)
    const val ACTION_SHOW_PODS_UI = "dev.sonypods.action.show_pods_ui"
    /** True only when PopupActivity was opened by tapping the module island. */
    const val EXTRA_POPUP_FROM_ISLAND = "dev.sonypods.extra.POPUP_FROM_ISLAND"

    // Explicit entry from the fusion device center to the module's Sony detail page.
    // MainActivity is exported, but this action is intentionally not exposed through
    // an implicit manifest filter; the MiLink hook supplies the explicit component.
    const val ACTION_OPEN_EARPHONE_DETAIL = "dev.sonypods.action.open_earphone_detail"
    const val EXTRA_TARGET_DEVICE_ADDRESS = "target_device_address"

    // App config sync towards hook processes
    const val ACTION_CONFIG_CHANGED = "dev.sonypods.action.config_changed"
}
