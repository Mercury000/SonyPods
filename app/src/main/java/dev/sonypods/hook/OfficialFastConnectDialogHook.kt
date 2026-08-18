package dev.sonypods.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.config.ConfigManager
import dev.sonypods.utils.PodImageLoader

/**
 * Reuses Bluetooth Extension's real MiuiFastConnectActivity/PairingDialog.
 *
 * The launcher lives in com.xiaomi.bluetooth (the process that owns the Xiaomi
 * notification). The Activity and its controller callback live in
 * com.xiaomi.bluetooth:ui, so this hook is deliberately installed in both
 * processes. No module PopupActivity fallback is performed here.
 */
@SuppressLint("MissingPermission")
object OfficialFastConnectDialogHook : HookContext() {
    private const val TAG = "SonyPods-OfficialDialog"
    private const val XIAOMI_PACKAGE = "com.xiaomi.bluetooth"
    private const val UI_PROCESS_SUFFIX = ":ui"
    private const val FAST_CONNECT_ACTIVITY =
        "com.android.bluetooth.ble.app.MiuiFastConnectActivity"
    private const val FAST_CONNECT_ACTIVITY_VARIANT =
        "com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectActivity"
    private const val FAST_CONTROLLER_CLASS =
        "com.android.bluetooth.ble.app.fastconnect.MiuiFastConnectController"
    private const val FAST_CONNECT_ACTION = "com.android.bluetooth.FAST_CONNECT_DEVICE"
    /**
     * The Activity class is shared by Xiaomi's own fast-connect flow and the
     * synthetic Sony flow.  Class-name matching alone therefore hijacks every
     * official earphone popup.  This marker is written only by
     * [launchOfficialActivity] and is required at every mutation entry point.
     */
    private const val EXTRA_MODULE_DIALOG_MARKER =
        "dev.sonypods.extra.OFFICIAL_FAST_CONNECT_DIALOG"
    private const val EXTRA_MODULE_DIALOG_ADDRESS =
        "dev.sonypods.extra.OFFICIAL_FAST_CONNECT_ADDRESS"
    private const val MODULE_DIALOG_MARKER = "sony_pods_official_fast_connect"
    private const val SINGLE_IMAGE_SCALE = 1.4f
    private const val RELOAD_LAST_LAUNCHED_ADDRESS =
        "sonypods.reload.official_dialog.last_launched_address"
    private const val RELOAD_RECOVERY_SUPPRESSION_ADDRESS =
        "sonypods.reload.official_dialog.recovery_suppression_address"

    private var mainContext: Context? = null
    private var mainStateReceiver: BroadcastReceiver? = null
    private var uiContext: Context? = null
    private var uiStateReceiver: BroadcastReceiver? = null
    private var activeActivity: Activity? = null
    private var activeController: Any? = null
    private var activeView: View? = null
    private var activeAddress: String? = null
    private var connectingSent = false
    private var successSent = false
    private var latestSnapshot: SonyStateSnapshot? = null
    private var batteryViewDumped = false
    private var batteryTextHookInstalled = false
    private val batteryTextRewriteDepth = ThreadLocal.withInitial { false }
    private var officialHandler: Handler? = null
    private var handlerDispatchGuardInstalled = false
    private var lastLaunchedAddress: String? = null
    private var latestPhysicalDisconnectAddress: String? = null
    /**
     * Address of a Tandem recovery for which the stock Bluetooth process must not
     * show a second fast-connect dialog. This is deliberately separate from
     * [lastLaunchedAddress]: the Xiaomi process may launch its own unmarked
     * Activity long after the module-owned Activity has gone away.
     */
    private var recoverySuppressionAddress: String? = null
    private var uiCloudFallback: HookCloudModelFallback? = null
    private val fastControllerHookedClasses = mutableSetOf<String>()
    private val fastSuccessHookedClasses = mutableSetOf<String>()
    private var frameworkActivityHookInstalled = false
    private var uiApplicationHookInstalled = false
    private var activityLaunchGuardInstalled = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private val isUiProcess: Boolean
        get() = runCatching { android.app.Application.getProcessName() }
            .getOrNull()
            ?.endsWith(UI_PROCESS_SUFFIX) == true

    override fun onHook() {
        if (isUiProcess) {
            installUiHooks()
        } else {
            installMainHooks()
        }
    }

    override fun onBeforeReload() {
        unregisterReceiverForReload(mainContext, mainStateReceiver)
        unregisterReceiverForReload(uiContext, uiStateReceiver)
        mainStateReceiver = null
        uiStateReceiver = null
        mainContext = null
        uiContext = null
        activeActivity = null
        activeController = null
        activeView = null
        officialHandler = null
        activeAddress = null
        batteryViewDumped = false
        batteryTextHookInstalled = false
        handlerDispatchGuardInstalled = false
        connectingSent = false
        successSent = false
        latestSnapshot = null
        lastLaunchedAddress = null
        latestPhysicalDisconnectAddress = null
        recoverySuppressionAddress = null
        fastControllerHookedClasses.clear()
        fastSuccessHookedClasses.clear()
        frameworkActivityHookInstalled = false
        uiApplicationHookInstalled = false
        activityLaunchGuardInstalled = false
        uiCloudFallback?.close()
        uiCloudFallback = null
        uiHandler.removeCallbacksAndMessages(null)
        PodImageLoader.temporaryImageReader = null
    }

    override fun saveReloadState(state: Bundle) {
        lastLaunchedAddress?.let { state.putString(RELOAD_LAST_LAUNCHED_ADDRESS, it) }
        recoverySuppressionAddress?.let {
            state.putString(RELOAD_RECOVERY_SUPPRESSION_ADDRESS, it)
        }
    }

    override fun restoreReloadState(state: Bundle) {
        lastLaunchedAddress = state.getString(RELOAD_LAST_LAUNCHED_ADDRESS)
        recoverySuppressionAddress = state.getString(RELOAD_RECOVERY_SUPPRESSION_ADDRESS)
    }

    override fun onReloadRejected(snapshot: SonyStateSnapshot) {
        if (isUiProcess) {
            currentApplicationContext()?.let { registerUiStateReceiver(it) }
            findExistingManagedActivity()?.let(::onOfficialActivityCreated)
        } else {
            currentApplicationContext()?.let { registerMainStateReceiver(it) }
        }
    }

    internal fun startAfterReload(context: Context) {
        if (isUiProcess) {
            registerUiStateReceiver(context)
            findExistingManagedActivity()?.let(::onOfficialActivityCreated)
        } else {
            registerMainStateReceiver(context)
        }
    }

    private fun installMainHooks() {
        installActivityLaunchGuard()
        currentApplicationContext()?.let { registerMainStateReceiver(it) }
        // MiuiBluetoothNotification is constructed after package-ready on some
        // builds. Its context is a reliable second chance for registering the
        // receiver and requesting a state replay.
        runCatching {
            hookConstructorAfterAll(
                findConstructorsByParamCount(
                    "com.android.bluetooth.ble.app.MiuiBluetoothNotification",
                    2,
                ),
                // MiBluetoothToastHook also observes these constructors to bring
                // up the notification/island receiver.  Constructor identity
                // alone is therefore not a sufficient stable ID: without a
                // distinct role HookRegistry rejects the second spec and the
                // Xiaomi scope loses the notification hook entirely.
                logicalRole = "official-dialog-notification-constructor",
            ) {
                val context = runCatching { getObjectField(instance, "mContext") as? Context }
                    .getOrNull()
                    ?: return@hookConstructorAfterAll
                registerMainStateReceiver(context)
            }
        }.onFailure { Log.w(TAG, "MiuiBluetoothNotification hook unavailable", it) }
    }

    private fun installUiHooks() {
        installUiImageFallback()
        installActivityLaunchGuard()
        installUiApplicationHook()
        currentApplicationContext()?.let { context ->
            registerUiStateReceiver(context)
            SonyBridge.sendCommand(context, SonyBridge.CMD_REPUBLISH)
        }
        // The modern activity is loaded from a Qigsaw feature after the
        // package classloader is created. Hook the framework lifecycle as a
        // fallback so we can observe it even when its class is not visible at
        // package-ready time.
        installFrameworkActivityHooks()
        installBatteryTextGuard(appClassLoader)
        installHandlerDispatchGuard(appClassLoader)
        installActivityHooks(FAST_CONNECT_ACTIVITY, "root")
        installActivityHooks(FAST_CONNECT_ACTIVITY_VARIANT, "fast")
        // Hot reload does not replay Activity.onCreate. Rebind the currently
        // visible module-owned official dialog so its controller, view and
        // concrete feature-class hooks are restored immediately.
        findExistingManagedActivity()?.let(::onOfficialActivityCreated)
    }

    /**
     * The UI process can be started by Xiaomi's own unmarked Activity, before
     * our module-created Activity has ever registered the state receiver. Hook
     * Application creation so the recovery marker is available before the
     * first Activity lifecycle callback in that process.
     */
    private fun installUiApplicationHook() {
        if (uiApplicationHookInstalled) return
        runCatching {
            hookAfter(
                findMethodWithLoader(
                    "android.app.Instrumentation",
                    appClassLoader,
                    "callApplicationOnCreate",
                    android.app.Application::class.java,
                ),
                logicalRole = "official-dialog-ui-application-ready",
            ) {
                val application = args.firstOrNull() as? android.app.Application ?: return@hookAfter
                registerUiStateReceiver(application)
                SonyBridge.sendCommand(application, SonyBridge.CMD_REPUBLISH)
            }
            uiApplicationHookInstalled = true
            Log.i(TAG, "official dialog UI application receiver hook installed")
        }.onFailure { Log.w(TAG, "official dialog UI application hook unavailable", it) }
    }

    /**
     * Cancel Xiaomi's native fast-connect launch before Activity.onCreate. There
     * is intentionally no post-create fallback: if this hook is unavailable,
     * the problem must remain visible in the log and be fixed at this boundary.
     */
    private fun installActivityLaunchGuard() {
        if (activityLaunchGuardInstalled) return
        runCatching {
            val instrumentation = Class.forName(
                "android.app.Instrumentation",
                false,
                appClassLoader,
            )
            val method = instrumentation.declaredMethods
                .filter { it.name == "execStartActivity" }
                .filter { method -> method.parameterTypes.any { it == Intent::class.java } }
                .sortedBy { it.parameterTypes.size }
                .firstOrNull()
                ?.apply { isAccessible = true }
                ?: throw NoSuchMethodException("Instrumentation.execStartActivity(Intent)")
            hookBefore(
                method,
                logicalRole = "official-dialog-start-before-create",
                required = true,
            ) {
                val intent = args.filterIsInstance<Intent>().firstOrNull() ?: return@hookBefore
                if (!shouldSuppressExternalOfficialIntent(intent)) return@hookBefore
                Log.i(
                    TAG,
                    "blocked delayed unmarked official Activity before launch " +
                        "class=${intent.component?.className} " +
                        "address=$recoverySuppressionAddress",
                )
                // Instrumentation callers accept a null ActivityResult for a
                // start that was consumed by a hook. No Activity is constructed.
                result = null
            }
            activityLaunchGuardInstalled = true
            Log.i(TAG, "official dialog pre-launch guard installed method=${method.name}")
        }.onFailure {
            Log.e(
                TAG,
                "official dialog pre-launch guard unavailable; no suppression installed",
                it,
            )
        }
    }

    private fun installFrameworkActivityHooks() {
        if (frameworkActivityHookInstalled) return
        runCatching {
            hookAfter(
                findMethodWithLoader(
                    "android.app.Activity",
                    appClassLoader,
                    "onCreate",
                    Bundle::class.java,
                ),
                logicalRole = "official-dialog-framework-activity-create",
            ) {
                val activity = instance as? Activity ?: return@hookAfter
                if (isManagedOfficialActivity(activity)) onOfficialActivityCreated(activity)
            }
            hookAfter(
                findMethodWithLoader("android.app.Activity", appClassLoader, "onDestroy"),
                logicalRole = "official-dialog-framework-activity-destroy",
            ) {
                val activity = instance as? Activity ?: return@hookAfter
                if (isManagedOfficialActivity(activity)) onOfficialActivityDestroyed(activity)
            }
            frameworkActivityHookInstalled = true
            Log.i(TAG, "official framework Activity fallback hook installed")
        }.onFailure { Log.w(TAG, "official framework Activity fallback unavailable", it) }
    }

    private fun isOfficialActivity(activity: Activity): Boolean =
        activity.javaClass.name == FAST_CONNECT_ACTIVITY ||
            activity.javaClass.name == FAST_CONNECT_ACTIVITY_VARIANT

    /**
     * Returns the address carried by a module-owned synthetic dialog.  The
     * marker is deliberately checked together with all address carriers: an
     * official Xiaomi Intent must not be treated as ours merely because it
     * targets the same Activity class.
     */
    private fun managedOfficialAddress(activity: Activity): String? {
        if (!isOfficialActivity(activity)) return null
        val intent = activity.intent ?: return null
        if (intent.getStringExtra(EXTRA_MODULE_DIALOG_MARKER) != MODULE_DIALOG_MARKER) {
            return null
        }

        val taggedAddress = intent.getStringExtra(EXTRA_MODULE_DIALOG_ADDRESS)
            ?.trim()
            ?.takeIf(::isBluetoothAddress)
            ?: return null
        val headsetAddress = intent.getStringArrayExtra("headset_addresses")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf(::isBluetoothAddress)
            ?: return null
        if (!taggedAddress.equals(headsetAddress, ignoreCase = true)) return null

        val deviceAddress = runCatching {
            intent.getParcelableExtra(
                "android.bluetooth.device.extra.DEVICE",
                BluetoothDevice::class.java,
            )
        }.getOrNull()
        if (deviceAddress != null && !taggedAddress.equals(deviceAddress.address, ignoreCase = true)) {
            return null
        }

        return taggedAddress
    }

    /**
     * Extracts the address carried by Xiaomi's own fast-connect Intent without
     * relying on any JADX-generated class, method, or field name. Native and
     * module-generated flows use the same stable Android Bluetooth extras.
     */
    private fun officialIntentAddresses(intent: Intent?): List<String> {
        intent ?: return emptyList()
        val values = ArrayList<String>()
        listOf(
            "device_address",
            "deviceAddress",
            "bluetooth_address",
            "bluetoothAddress",
            "headset_address",
            "address",
            "mac_address",
            "mac",
        ).forEach { key ->
            intent.getStringExtra(key)?.let(values::add)
        }
        intent.getStringArrayExtra("headset_addresses")?.let { values.addAll(it) }
        intent.getStringArrayListExtra("headset_addresses")?.let { values.addAll(it) }
        runCatching {
            intent.getParcelableExtra(
                "android.bluetooth.device.extra.DEVICE",
                BluetoothDevice::class.java,
            )
        }.getOrNull()?.address?.let(values::add)
        return values
            .map(String::trim)
            .filter(::isBluetoothAddress)
            .distinct()
    }

    private fun isOfficialActivityIntent(intent: Intent): Boolean =
        intent.component?.className == FAST_CONNECT_ACTIVITY ||
            intent.component?.className == FAST_CONNECT_ACTIVITY_VARIANT

    /**
     * Native Xiaomi fast-connect launches the same Activity class as the module
     * but without our marker. Only suppress it when both the bridge and the
     * Intent identify the exact address previously reported as a Tandem recovery.
     */
    private fun shouldSuppressExternalOfficialIntent(intent: Intent?): Boolean {
        if (intent == null || !isOfficialActivityIntent(intent)) return false
        if (intent.getStringExtra(EXTRA_MODULE_DIALOG_MARKER) == MODULE_DIALOG_MARKER) return false
        val recoveryAddress = recoverySuppressionAddress ?: return false
        val snapshot = latestSnapshot ?: return false
        if (!snapshot.connected || !snapshot.deviceAddress.equals(recoveryAddress, ignoreCase = true)) {
            return false
        }
        val addresses = officialIntentAddresses(intent)
        val matches = addresses.any { it.equals(recoveryAddress, ignoreCase = true) }
        Log.d(
            TAG,
            "unmarked official Activity candidate class=${intent.component?.className} " +
                "addresses=$addresses recovery=$recoveryAddress matches=$matches",
        )
        return matches
    }

    /** True only for the Activity instance launched by this module for its Sony address. */
    private fun isManagedOfficialActivity(activity: Activity?): Boolean {
        val address = activity?.let(::managedOfficialAddress) ?: return false
        val active = activeAddress
        return active == null || active.equals(address, ignoreCase = true)
    }

    /**
     * Mutation-time gate.  A stale snapshot for another Sony device may still
     * be in this process while a new Activity is being created; that must not
     * prevent the Activity from being registered, but it must prevent a global
     * Controller/TextView/Handler callback from applying the stale device's
     * data to this dialog.
     */
    private fun isManagedOfficialTarget(activity: Activity?): Boolean {
        val address = activity?.let(::managedOfficialAddress) ?: return false
        if (activeAddress != null && !activeAddress.equals(address, ignoreCase = true)) return false
        val snapshotAddress = latestSnapshot?.deviceAddress
        return snapshotAddress == null || snapshotAddress.equals(address, ignoreCase = true)
    }

    private fun isBluetoothAddress(value: String): Boolean =
        value.matches(Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$"))

    private fun installActivityHooks(className: String, role: String) {
        runCatching {
            hookAfter(
                findMethod(className, "onCreate", Bundle::class.java),
                logicalRole = "official-dialog-$role-activity-create",
                required = true,
            ) {
                val activity = instance as? Activity ?: return@hookAfter
                if (isManagedOfficialActivity(activity)) onOfficialActivityCreated(activity)
            }
            hookAfter(
                findMethod(className, "onDestroy"),
                logicalRole = "official-dialog-$role-activity-destroy",
            ) {
                val activity = instance as? Activity ?: return@hookAfter
                if (isManagedOfficialActivity(activity)) onOfficialActivityDestroyed(activity)
            }
            Log.i(TAG, "official Activity hooks installed class=$className")
        }.onFailure { Log.w(TAG, "official Activity hooks unavailable class=$className", it) }
    }

    private fun onOfficialActivityCreated(activity: Activity) {
        val dialogAddress = managedOfficialAddress(activity) ?: run {
            Log.d(TAG, "bypass official Activity without SonyPods marker/address class=${activity.javaClass.name}")
            return
        }
        if (activeActivity === activity && activeController != null) return
        activeActivity = activity
        uiContext = activity
        // Application lookup can still be null during package-ready; the
        // Activity is the first guaranteed Context in :ui.
        installUiImageFallback(activity)
        registerUiStateReceiver(activity)
        installBatteryTextGuard(activity.classLoader)
        activeController = findActivityController(activity)
        activeView = activeController?.let { controller -> findControllerView(controller) }
        officialHandler = activeController?.let(::findControllerHandler)
        installFastControllerHooks(activeController, activity.classLoader)
        activeAddress = dialogAddress
        connectingSent = false
        successSent = false
        // The main process may have sent the connected snapshot before :ui
        // registered. Replaying from the engine closes that race.
        SonyBridge.sendCommand(activity, SonyBridge.CMD_REPUBLISH)
        latestSnapshot?.let { applySnapshot(it) }
        // When this callback comes from android.app.Activity.onCreate, the
        // subclass has not yet assigned its controller field. Rebind after the
        // full subclass onCreate so the feature-module controller and its view
        // are available for the success/battery refresh hook.
        uiHandler.post {
            if (activeActivity !== activity) return@post
            findActivityController(activity)?.let { controller ->
                activeController = controller
                activeView = findControllerView(controller)
                officialHandler = findControllerHandler(controller)
                installFastControllerHooks(controller, activity.classLoader)
                applyOfficialIdentity(latestSnapshot)
                activeView?.let { view ->
                    replaceOfficialImages(view)
                    latestSnapshot?.let {
                        refreshBatteryText(view, it)
                        refreshBatteryIcons(view, it)
                    }
                }
                latestSnapshot?.let { applySnapshot(it) }
                Log.d(TAG, "official Activity controller rebound class=${controller.javaClass.name}")
            }
        }
        Log.i(
            TAG,
            "official Activity created class=${activity.javaClass.name} " +
                "address=$activeAddress controller=${activeController?.javaClass?.name}",
        )
    }

    private fun onOfficialActivityDestroyed(activity: Activity?) {
        if (activity == null || activity === activeActivity) {
            activeActivity = null
            activeController = null
            activeView = null
            officialHandler = null
            activeAddress = null
            connectingSent = false
            successSent = false
        }
    }

    /**
     * The stock controller posts a delayed failure check after its success
     * callback. Our Sony bridge is already the authoritative connection
     * source, so that check is a false negative for the synthetic fast-connect
     * payload and closes the official Activity about one second later.
     *
     * Identify the controller's Handler by its runtime type instead of using
     * JADX-generated class or field names. Only message 3 on that Handler is
     * suppressed, and only while the bridge still reports the device as
     * connected; user dismissal and every other official message remain intact.
     */
    private fun installHandlerDispatchGuard(classLoader: ClassLoader) {
        if (handlerDispatchGuardInstalled) return
        runCatching {
            hookBefore(
                Class.forName("android.os.Handler", false, classLoader)
                    .getDeclaredMethod("dispatchMessage", Message::class.java)
                    .apply { isAccessible = true },
                logicalRole = "official-dialog-failure-check-guard",
            ) {
                val handler = instance as? Handler ?: return@hookBefore
                val message = args.firstOrNull() as? Message ?: return@hookBefore
                val snapshot = latestSnapshot ?: return@hookBefore
                if (!isManagedOfficialTarget(activeActivity) ||
                    handler !== officialHandler ||
                    message.what !in setOf(3, 6) ||
                    !snapshot.connected ||
                    !snapshot.deviceAddress.equals(activeAddress, ignoreCase = true)
                ) {
                    return@hookBefore
                }
                Log.i(
                    TAG,
                    "official dialog ignored automatic close/check " +
                        "what=${message.what} address=${snapshot.deviceAddress}",
                )
                result = null
            }
            handlerDispatchGuardInstalled = true
            Log.i(TAG, "official dialog failure-check Handler guard installed")
        }.onFailure { Log.w(TAG, "official dialog failure-check guard unavailable", it) }
    }

    private fun installFastControllerHooks(controller: Any?, fallbackLoader: ClassLoader) {
        val loader = controller?.javaClass?.classLoader ?: fallbackLoader
        // Hook both the stable modern base class and the concrete feature
        // controller. The latter may override modifyView, in which case a
        // hook on the base implementation is never reached.
        installFastControllerHook(FAST_CONTROLLER_CLASS, loader)
        installFastSuccessHook(FAST_CONTROLLER_CLASS, loader)
        controller?.javaClass?.name
            ?.takeIf { it != FAST_CONTROLLER_CLASS }
            ?.let {
                installFastControllerHook(it, loader)
                installFastSuccessHook(it, loader)
            }
    }

    private fun installFastControllerHook(className: String, classLoader: ClassLoader) {
        if (className in fastControllerHookedClasses) return
        runCatching {
            hookAfter(
                findMethodWithLoader(
                    className,
                    classLoader,
                    "modifyView",
                    View::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                    String::class.java,
                ),
                logicalRole = "official-dialog-fast-image-refresh-${className.hashCode()}",
            ) {
                onOfficialViewRefreshed(args.firstOrNull() as? View, instance)
            }
            fastControllerHookedClasses += className
            Log.i(TAG, "official fast controller hook installed class=$className")
        }.onFailure { Log.w(TAG, "official fast controller hook unavailable class=$className", it) }
    }

    private fun installFastSuccessHook(className: String, classLoader: ClassLoader) {
        if (className in fastSuccessHookedClasses) return
        runCatching {
            val method = Class.forName(className, false, classLoader)
                .declaredMethods
                .first { it.name == "updateConnectSuccessDilog" }
                .apply { isAccessible = true }
            hookAfter(
                method,
                logicalRole = "official-dialog-fast-success-refresh-${className.hashCode()}",
            ) {
                val view = args.filterIsInstance<View>().firstOrNull() ?: activeView
                    ?: activeController?.let(::findControllerView)
                onOfficialViewRefreshed(view, instance)
            }
            fastSuccessHookedClasses += className
            Log.i(
                TAG,
                "official fast success hook installed class=$className " +
                    "method=${method.name} params=${method.parameterTypes.size}",
            )
        }.onFailure {
            Log.w(TAG, "official fast success hook unavailable class=$className", it)
        }
    }

    private fun onOfficialViewRefreshed(view: View?, controller: Any? = null) {
        view ?: return
        if (!isManagedOfficialTarget(activeActivity) ||
            (controller != null && controller !== activeController)
        ) {
            return
        }
        activeView = view
        applyOfficialIdentity(latestSnapshot)
        replaceOfficialImages(view)
        latestSnapshot?.let {
            refreshBatteryText(view, it)
            refreshBatteryIcons(view, it)
        }
    }

    private fun findMethodWithLoader(
        className: String,
        classLoader: ClassLoader,
        methodName: String,
        vararg parameterTypes: Class<*>,
    ) = Class.forName(className, false, classLoader)
        .getDeclaredMethod(methodName, *parameterTypes)
        .apply { isAccessible = true }

    private fun findActivityController(activity: Activity): Any? {
        var type: Class<*>? = activity.javaClass
        val candidates = ArrayList<Any>()
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(activity)
                }.getOrNull()?.let { value ->
                    val name = value.javaClass.name.lowercase()
                    if (name.contains("controller") && name.contains("fastconnect")) {
                        candidates += value
                    }
                }
            }
            type = type.superclass
        }
        return candidates.firstOrNull()
    }

    /** Find a still-running module-owned fast-connect Activity after hot reload. */
    private fun findExistingManagedActivity(): Activity? = runCatching {
        val thread = Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentActivityThread")
            .apply { isAccessible = true }
            .invoke(null)
            ?: return@runCatching null
        var type: Class<*>? = thread.javaClass
        var activities: Any? = null
        while (type != null && activities == null) {
            activities = runCatching {
                type.getDeclaredField("mActivities").apply { isAccessible = true }.get(thread)
            }.getOrNull()
            type = type.superclass
        }
        val records = (activities as? Map<*, *>)?.values.orEmpty()
        records.asSequence()
            .mapNotNull { record ->
                var recordType: Class<*>? = record?.javaClass
                var activity: Any? = null
                while (recordType != null && activity == null) {
                    activity = runCatching {
                        recordType.getDeclaredField("activity").apply { isAccessible = true }.get(record)
                    }.getOrNull()
                    recordType = recordType.superclass
                }
                activity as? Activity
            }
            .firstOrNull(::isManagedOfficialActivity)
    }.getOrNull()

    private fun findControllerView(controller: Any): View? {
        var type: Class<*>? = controller.javaClass
        val candidates = ArrayList<View>()
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(controller)
                }.getOrNull()?.let { value ->
                    if (value is View) candidates += value
                }
            }
            type = type.superclass
        }
        return candidates.firstOrNull()
    }

    private fun findControllerHandler(controller: Any): Handler? {
        var type: Class<*>? = controller.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                if (!Handler::class.java.isAssignableFrom(field.type)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(controller) as? Handler
                }.getOrNull()?.let { handler ->
                    return handler
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun installUiImageFallback(context: Context? = currentApplicationContext()) {
        if (uiCloudFallback != null) return
        context ?: return
        runCatching {
            val fallback = HookCloudModelFallback(
                context = context,
                remoteFileReader = remoteFileReader,
                onCatalogReady = {},
                onImageReady = { address ->
                    // The temporary image is written on IO. Re-enter the
                    // official controller on its UI thread so the same
                    // PairingDialog is refreshed in place.
                    uiHandler.post {
                        latestSnapshot?.let { snapshot ->
                            if (snapshot.deviceAddress.equals(address, ignoreCase = true)) {
                                applySnapshot(snapshot)
                            }
                        }
                    }
                },
            )
            uiCloudFallback = fallback
            PodImageLoader.temporaryImageReader = { address -> fallback.temporaryBitmap(address) }
        }.onFailure { Log.w(TAG, "ui-process temporary image fallback unavailable", it) }
    }

    private fun registerMainStateReceiver(context: Context) {
        if (mainStateReceiver != null) return
        val appContext = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != SonyBridge.ACTION_STATE) return
                val bundle = intent.getBundleExtra(SonyStateSnapshot.EXTRA_SNAPSHOT) ?: return
                val snapshot = SonyStateSnapshot.fromBundle(bundle)
                latestSnapshot = snapshot
                val suppressPopup = intent.getBooleanExtra(SonyBridge.EXTRA_SUPPRESS_CONNECT_POPUP, false)
                val physicalDisconnectAddress = intent.getStringExtra(
                    SonyBridge.EXTRA_PHYSICAL_DISCONNECT_ADDRESS,
                )
                latestPhysicalDisconnectAddress = physicalDisconnectAddress
                updateRecoverySuppression(snapshot, suppressPopup, physicalDisconnectAddress)
                if (!snapshot.connected || snapshot.deviceAddress.isNullOrBlank()) {
                    // Only the terminal A2DP callback is a real disconnect. A
                    // Tandem/GATT loss, Sound Connect handoff, or reload snapshot
                    // must retain the key so recovery is not treated as new UI.
                    if (!physicalDisconnectAddress.isNullOrBlank()) {
                        lastLaunchedAddress = null
                    } else {
                        Log.d(TAG, "official dialog key retained during transient transport loss")
                    }
                    return
                }
                // A same-address state carrying the terminal marker is a genuine
                // physical reconnect; allow its next connect popup once.
                if (physicalDisconnectAddress.equals(snapshot.deviceAddress, ignoreCase = true)) {
                    lastLaunchedAddress = null
                }
                if (suppressPopup) {
                    // Sound Connect has just handed the existing session back.
                    // This is not a new user connection, so consume the address
                    // without launching a second official dialog.
                    lastLaunchedAddress = snapshot.deviceAddress
                    Log.d(
                        TAG,
                        "official dialog suppressed for Sound Connect handoff " +
                            "address=${snapshot.deviceAddress}",
                    )
                    return
                }
                if (!shouldLaunchOfficialDialog(appContext, snapshot)) return
                val address = snapshot.deviceAddress
                if (address.equals(lastLaunchedAddress, ignoreCase = true)) return
                if (launchOfficialActivity(appContext, snapshot)) {
                    lastLaunchedAddress = address
                }
            }
        }
        runCatching {
            appContext.registerReceiver(
                receiver,
                IntentFilter(SonyBridge.ACTION_STATE),
                Context.RECEIVER_EXPORTED,
            )
            mainContext = appContext
            mainStateReceiver = receiver
            SonyBridge.sendCommand(appContext, SonyBridge.CMD_REPUBLISH)
            Log.d(TAG, "main-process official dialog receiver registered")
        }.onFailure { Log.w(TAG, "main-process receiver registration failed", it) }
    }

    private fun registerUiStateReceiver(context: Context) {
        if (uiStateReceiver != null) return
        val appContext = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != SonyBridge.ACTION_STATE) return
                val bundle = intent.getBundleExtra(SonyStateSnapshot.EXTRA_SNAPSHOT) ?: return
                val snapshot = SonyStateSnapshot.fromBundle(bundle)
                latestSnapshot = snapshot
                val physicalDisconnectAddress = intent.getStringExtra(
                    SonyBridge.EXTRA_PHYSICAL_DISCONNECT_ADDRESS,
                )
                latestPhysicalDisconnectAddress = physicalDisconnectAddress
                updateRecoverySuppression(
                    snapshot,
                    intent.getBooleanExtra(SonyBridge.EXTRA_SUPPRESS_CONNECT_POPUP, false),
                    physicalDisconnectAddress,
                )
                if (activeActivity == null) findExistingManagedActivity()?.let(::onOfficialActivityCreated)
                uiCloudFallback?.onState(snapshot)
                applySnapshot(snapshot, physicalDisconnectAddress)
            }
        }
        runCatching {
            appContext.registerReceiver(
                receiver,
                IntentFilter(SonyBridge.ACTION_STATE),
                Context.RECEIVER_EXPORTED,
            )
            uiContext = appContext
            uiStateReceiver = receiver
        }.onFailure { Log.w(TAG, "ui-process receiver registration failed", it) }
    }

    private fun updateRecoverySuppression(
        snapshot: SonyStateSnapshot,
        suppressPopup: Boolean,
        physicalDisconnectAddress: String?,
    ) {
        val address = snapshot.deviceAddress?.takeIf(::isBluetoothAddress)
        when {
            suppressPopup && snapshot.connected && address != null -> {
                recoverySuppressionAddress = address
                Log.d(TAG, "official dialog suppression armed for Tandem recovery address=$address")
            }
            !snapshot.connected && !physicalDisconnectAddress.isNullOrBlank() -> {
                if (recoverySuppressionAddress != null) {
                    Log.d(
                        TAG,
                        "official dialog suppression cleared after physical disconnect " +
                            "address=$recoverySuppressionAddress",
                    )
                }
                recoverySuppressionAddress = null
            }
            physicalDisconnectAddress != null -> {
                recoverySuppressionAddress = null
            }
            snapshot.connected && address != null &&
                recoverySuppressionAddress != null &&
                !address.equals(recoverySuppressionAddress, ignoreCase = true) -> {
                Log.d(
                    TAG,
                    "official dialog suppression moved to new connected address=$address",
                )
                recoverySuppressionAddress = null
            }
        }
    }

    private fun shouldLaunchOfficialDialog(context: Context, snapshot: SonyStateSnapshot): Boolean {
        if (!ConfigManager.popupOnConnect() ||
            ConfigManager.connectDialogMode() != ConfigManager.CONNECT_DIALOG_MODE_OFFICIAL
        ) {
            return false
        }
        // Bluetooth Extension gates its own dialog on this; the module launches the
        // dialog through a path that skips that gate, so it has to repeat the check.
        if (ConfigManager.suppressPopupInGameOrLandscape()) {
            PopupDndPolicy.suppressReason(context)?.let { reason ->
                Log.d(TAG, "official dialog skipped: $reason")
                return false
            }
        }
        return !(ConfigManager.suppressPopupOnConnectWhenForeground() && isModuleUiForeground(context))
    }

    private fun launchOfficialActivity(context: Context, snapshot: SonyStateSnapshot): Boolean {
        val address = snapshot.deviceAddress ?: return false
        val device = runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
        }.getOrNull() ?: run {
            Log.w(TAG, "cannot create BluetoothDevice for official dialog address=$address")
            return false
        }
        // The snapshot/address comes from the Sony engine and is authoritative.
        // Do not use the Bluetooth name as a hard gate: users can rename a Sony
        // device to an arbitrary name.  Keep the name only for diagnostics.
        val deviceName = runCatching { device.name ?: device.alias }.getOrNull()
        Log.d(
            TAG,
            "launching Sony official dialog address=$address " +
                "name=${deviceName ?: snapshot.deviceName.orEmpty()}",
        )
        val intent = Intent().apply {
            // The current stock dialog is the feature-module activity. The
            // root activity is a legacy implementation and has a different
            // controller/layout; launching it leaves the modern :ui hook with
            // no activity to observe.
            setClassName(XIAOMI_PACKAGE, FAST_CONNECT_ACTIVITY_VARIANT)
            action = FAST_CONNECT_ACTION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("android.bluetooth.device.extra.DEVICE", device)
            // The feature implementation reads this key before it has a
            // Bluetooth profile name available. Keep the third address entry
            // as well: the base implementation uses that entry as its local
            // dialog-name override.
            snapshot.deviceName?.takeIf { it.isNotBlank() }?.let {
                putExtra("device_name_over_write", it)
            }
            // 01010200 is the built-in Redmi AirDots controller. It gives us
            // the stable official TWS layout while Sony remains the actual
            // BluetoothDevice and state source.
            putExtra("headset_miui_data", fakeAirDotsData(snapshot))
            putExtra("headset_adv_row_bytes", fakeAdvRowData(snapshot))
            putExtra(
                "headset_addresses",
                arrayOf(
                    address,
                    "00:00:00:00:00:00",
                    snapshot.deviceName?.takeIf { it.isNotBlank() } ?: address,
                ),
            )
            putExtra("type_layout_hid_fastconnect", 1)
            // C3323r4.Z() treats bits 0x02 and 0x08 as the right/left
            // battery-valid flags.  Leaving them clear makes the official
            // success renderer deliberately show disabled/0% values.
            putExtra("headset_extra_data", intArrayOf(0, 0x0F, 0, 1, 0))
            putExtra("current_a2dp_devices", 0)
            // This Activity class is also used by Xiaomi's own earphone flow.
            // Keep a module-only marker and bind it to the exact Sony address
            // so every UI-process hook can fail closed for official popups.
            putExtra(EXTRA_MODULE_DIALOG_MARKER, MODULE_DIALOG_MARKER)
            putExtra(EXTRA_MODULE_DIALOG_ADDRESS, address)
        }
        return runCatching {
            context.startActivity(intent)
            Log.i(TAG, "official PairingDialog Activity launched address=$address")
            true
        }.onFailure {
            // This is intentionally a hard official-mode failure. The caller
            // must not silently replace it with PopupActivity.
            Log.e(TAG, "official PairingDialog Activity launch failed", it)
        }.getOrDefault(false)
    }

    private fun fakeAirDotsData(snapshot: SonyStateSnapshot): ByteArray {
        fun level(value: Int?): Int = value?.coerceIn(0, 100) ?: 0
        return ByteArray(24).apply {
            this[0] = 0x16
            this[1] = 0x01
            this[2] = 0x02
            this[3] = 0x00
            this[4] = 0x00
            this[5] = level(snapshot.batteryLeft ?: snapshot.batterySingle).toByte()
            this[6] = level(snapshot.batteryRight).toByte()
            this[7] = level(snapshot.batteryCradle).toByte()
            // Keep the address bytes non-zero so C3287o5's optional local
            // address parsing never treats the payload as an empty device.
            this[8] = 0x01
            this[9] = 0x02
            this[10] = 0x03
            this[11] = 0x04
            this[12] = 0x05
            this[13] = 0x06
            this[14] = 0x07
            this[15] = 0x08
            this[16] = 0x09
        }
    }

    /**
     * The stock service passes the original BLE scan record as
     * headset_adv_row_bytes. An empty array is accepted by some old builds,
     * but the feature Controller parses it as a ScanRecord and immediately
     * finishes the Activity when parsing fails. This is a minimal valid
     * manufacturer-data record; the actual battery/name values remain in the
     * module snapshot and are applied to the already-created dialog below.
     */
    private fun fakeAdvRowData(snapshot: SonyStateSnapshot): ByteArray {
        fun level(value: Int?): Int = value?.coerceIn(0, 100) ?: 0
        val name = snapshot.deviceName?.takeIf { it.isNotBlank() } ?: "SonyPods"
        val nameBytes = name.toByteArray(Charsets.UTF_8).take(20).toByteArray()
        val manufacturerPayload = byteArrayOf(
            0x16,
            0x01,
            0x02,
            0x00,
            0x00,
            level(snapshot.batteryLeft ?: snapshot.batterySingle).toByte(),
            level(snapshot.batteryRight).toByte(),
            level(snapshot.batteryCradle).toByte(),
        )
        return byteArrayOf(
            (manufacturerPayload.size + 3).toByte(),
            0xFF.toByte(),
            0x01,
            0x02,
        ) + manufacturerPayload + byteArrayOf(
            (nameBytes.size + 1).toByte(),
            0x09,
        ) + nameBytes
    }

    private fun applySnapshot(
        snapshot: SonyStateSnapshot,
        physicalDisconnectAddress: String? = latestPhysicalDisconnectAddress,
    ) {
        val activity = activeActivity ?: return
        if (!isManagedOfficialActivity(activity)) return
        val address = snapshot.deviceAddress ?: activeAddress ?: return
        if (activeAddress != null && !address.equals(activeAddress, ignoreCase = true)) return

        applyOfficialIdentity(snapshot)

        if (!snapshot.connected) {
            // Tandem can briefly report false while A2DP remains connected. Keep
            // the dialog alive in that case; only the explicit terminal A2DP
            // marker is allowed to dismiss this synthetic official Activity.
            if (physicalDisconnectAddress.equals(address, ignoreCase = true) ||
                (!physicalDisconnectAddress.isNullOrBlank() && activeAddress == null)
            ) {
                Log.d(TAG, "official dialog dismissed after real A2DP disconnect address=$address")
                if (!activity.isFinishing) activity.finish()
            } else {
                Log.d(TAG, "official dialog retained during Tandem transport loss address=$address")
            }
            return
        }
        if (!connectingSent || !address.equals(activeAddress, ignoreCase = true)) {
            activeAddress = address
            connectingSent = true
            successSent = false
            Log.d(TAG, "official dialog state=connecting address=$address")
        }

        if (!snapshot.probeComplete) return
        activeView?.let { view ->
            refreshBatteryText(view, snapshot)
            refreshBatteryIcons(view, snapshot)
            replaceOfficialImages(view)
        }
        successSent = true
        Log.d(TAG, "official dialog state=success address=$address battery=${snapshot.batteryLeft}/${snapshot.batteryRight}/${snapshot.batteryCradle}")
    }

    private fun applyOfficialIdentity(snapshot: SonyStateSnapshot?) {
        val activity = activeActivity ?: return
        val name = snapshot?.deviceName?.trim()?.takeIf { it.isNotBlank() }
            ?: activity.intent?.getStringArrayExtra("headset_addresses")?.getOrNull(2)
                ?.takeIf { it.isNotBlank() && !it.equals(activeAddress, ignoreCase = true) }
            ?: return

        // Names differ between the base implementation and the Qigsaw feature.
        // Set the known semantic fields when present, then update the actual
        // PairingDialog title and title TextView so the visible result does not
        // depend on which obfuscation generation is active.
        val controller = activeController
        listOf(
            "mDeviceNameOverWrite",
            "deviceNameOverWrite",
            "mDeviceNameForDialog",
            "mDeviceNameForDialogLocal",
            "deviceNameForDialog",
        ).forEach { field ->
            runCatching { setObjectField(controller, field, name) }
        }

        var titleViews = 0
        findPairingDialog(controller)?.let { dialog ->
            runCatching { (callMethod(dialog, "getTitleView") as? TextView)?.let { it.text = name; titleViews++ } }
            runCatching { callMethod(dialog, "setTitle", name) }
        }

        val roots = listOfNotNull(activeView, activity.window?.decorView).distinct()
        roots.flatMap(::allViews)
            .filterIsInstance<TextView>()
            .filter { textView ->
                val resourceName = resourceEntryName(activity, textView.id).lowercase()
                resourceName.contains("title") ||
                    resourceName.contains("pairing") ||
                    textView.text?.toString() == "Air 2s"
            }
            .forEach { textView ->
                textView.text = name
                titleViews++
            }
        Log.d(TAG, "official dialog name applied name=$name titleViews=$titleViews")
    }

    private fun findPairingDialog(controller: Any?): Any? {
        controller ?: return null
        var type: Class<*>? = controller.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    val value = field.get(controller) ?: return@runCatching
                    if (value.javaClass.name.contains("PairingDialog")) return value
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun allViews(root: View): List<View> {
        val result = ArrayList<View>()
        fun visit(view: View) {
            result += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return result
    }

    private fun resourceEntryName(activity: Activity, id: Int): String =
        if (id == View.NO_ID) "" else runCatching {
            activity.resources.getResourceEntryName(id)
        }.getOrDefault("")

    private fun refreshBatteryText(view: View, snapshot: SonyStateSnapshot) {
        val activity = activeActivity ?: return
        var updated = 0
        val roots = listOfNotNull(view, activity.window?.decorView).distinct()
        hideOfficialChargingIndicators(roots, activity)

        fun resourceId(resourceName: String): Int {
            val packages = listOf<String?>(XIAOMI_PACKAGE, activity.packageName, null)
            return packages.asSequence()
                .map { packageName ->
                    runCatching {
                        activity.resources.getIdentifier(resourceName, "id", packageName)
                    }.getOrDefault(0)
                }
                .firstOrNull { it != 0 }
                ?: 0
        }

        fun showPathToRoot(view: View) {
            var current: View? = view
            while (current != null) {
                current.visibility = View.VISIBLE
                if (roots.any { it === current }) break
                current = current.parent as? View
            }
        }

        fun setPercent(resourceName: String, value: Int?) {
            val id = resourceId(resourceName)
            val targets = roots.flatMap(::allViews)
                .filterIsInstance<TextView>()
                .filter { textView ->
                    textView.id == id ||
                        resourceEntryName(activity, textView.id)
                            .equals(resourceName, ignoreCase = true)
                }
                .distinct()
            targets.forEach { textView ->
                if (value == null) {
                    // Null is the repository's disconnected-side state. Do
                    // not leave the previous percentage visible after one bud
                    // goes off-link.
                    textView.visibility = View.GONE
                    (textView.parent as? View)?.visibility = View.GONE
                } else {
                    textView.text = "${value.coerceIn(0, 100)}%"
                    when (resourceName) {
                        "textViewHeadsetLBatteryPercent" -> showHeadsetSideRow(roots, activity, "L")
                        "textViewHeadsetRBatteryPercent" -> showHeadsetSideRow(roots, activity, "R")
                    }
                    showPathToRoot(textView)
                    updated++
                }
            }
            if (value == null) {
                when (resourceName) {
                    "textViewHeadsetLBatteryPercent" -> hideHeadsetSideRow(roots, activity, "L")
                    "textViewHeadsetRBatteryPercent" -> hideHeadsetSideRow(roots, activity, "R")
                }
            }
            if (id == 0) {
                Log.d(TAG, "official dialog battery id missing name=$resourceName")
            } else if (targets.isEmpty()) {
                Log.d(TAG, "official dialog battery view missing name=$resourceName id=$id")
            }
        }

        fun dumpBatteryTextViews() {
            if (batteryViewDumped) return
            batteryViewDumped = true
            roots.flatMap(::allViews)
                .filterIsInstance<TextView>()
                .distinct()
                .forEach { textView ->
                    Log.d(
                        TAG,
                        "official dialog TextView id=${textView.id} " +
                            "name=${resourceEntryName(activity, textView.id)} " +
                            "text=${textView.text}",
                    )
                }
        }

        if (isHeadsetSnapshot(snapshot)) {
            // Reuse the stock cradle battery row as the single battery row.
            setPercent("textViewBoxBatteryPercent", snapshot.batterySingle)
        } else {
            setPercent("textViewHeadsetLBatteryPercent", snapshot.batteryLeft ?: snapshot.batterySingle)
            setPercent("textViewHeadsetRBatteryPercent", snapshot.batteryRight)
            setPercent("textViewBoxBatteryPercent", snapshot.batteryCradle)

            // Keep a single-battery value visible in the official TWS layout.
            if (snapshot.batterySingle != null &&
                snapshot.batteryLeft == null && snapshot.batteryRight == null
            ) {
                setPercent("textViewHeadsetRBatteryPercent", snapshot.batterySingle)
            }
        }
        if (updated == 0) dumpBatteryTextViews()
        Log.d(
            TAG,
            "official dialog battery text applied updated=$updated " +
                "values=${snapshot.batteryLeft ?: snapshot.batterySingle}/" +
                "${snapshot.batteryRight ?: snapshot.batterySingle}/${snapshot.batteryCradle}",
        )
    }

    /**
     * Re-apply the stock battery drawable after the stock controller has
     * rendered its synthetic model. The model currently contains 0, so the
     * stock renderer selects its empty/disabled glyph even though the bridge
     * already has the real battery values. We still use the stock arrays and
     * the stock drawable loader; only the selected bucket comes from the
     * bridge snapshot.
     */
    private fun refreshBatteryIcons(view: View, snapshot: SonyStateSnapshot) {
        val activity = activeActivity ?: return
        val roots = listOfNotNull(view, activity.window?.decorView).distinct()
        hideOfficialChargingIndicators(roots, activity)
        val controller = activeController
        val arrays = controller?.let { findOfficialBatteryArrays(it, activity) }
        var updated = 0

        fun setIcon(resourceName: String, value: Int?) {
            val targets = roots.flatMap(::allViews)
                .filterIsInstance<ImageView>()
                .filter { imageView ->
                    resourceEntryName(activity, imageView.id)
                        .equals(resourceName, ignoreCase = true)
                }
                .distinct()
            if (value == null) {
                targets.forEach { imageView ->
                    imageView.visibility = View.GONE
                    (imageView.parent as? View)?.visibility = View.GONE
                }
                return
            }
            when (resourceName) {
                "imageViewHeadsetLBattery" -> showHeadsetSideRow(roots, activity, "L")
                "imageViewHeadsetRBattery" -> showHeadsetSideRow(roots, activity, "R")
            }
            val drawable = if (controller != null && arrays != null) {
                officialBatteryDrawable(controller, arrays, value)
            } else {
                null
            } ?: return
            targets.forEach { imageView ->
                // A Drawable instance must not be shared between ImageViews;
                // use the official constant state when the loader provides it.
                imageView.setImageDrawable(drawable.constantState?.newDrawable() ?: drawable)
                imageView.visibility = View.VISIBLE
                (imageView.parent as? View)?.visibility = View.VISIBLE
                updated++
            }
        }

        if (arrays == null) {
            Log.d(TAG, "official dialog battery drawable arrays not found")
        }

        if (isHeadsetSnapshot(snapshot)) {
            setIcon("imageViewBoxBattery", snapshot.batterySingle)
        } else {
            setIcon("imageViewHeadsetLBattery", snapshot.batteryLeft ?: snapshot.batterySingle)
            setIcon("imageViewHeadsetRBattery", snapshot.batteryRight ?: snapshot.batterySingle)
            setIcon("imageViewBoxBattery", snapshot.batteryCradle)
        }
        Log.d(
            TAG,
            "official dialog stock battery icons applied updated=$updated " +
                "values=${snapshot.batteryLeft ?: snapshot.batterySingle}/" +
                "${snapshot.batteryRight ?: snapshot.batterySingle}/${snapshot.batteryCradle}",
        )
    }

    private fun isHeadsetSnapshot(snapshot: SonyStateSnapshot): Boolean =
        snapshot.formFactor?.equals("HEADSET", ignoreCase = true) == true

    private fun applyHeadsetLayout(view: View, snapshot: SonyStateSnapshot) {
        if (!isHeadsetSnapshot(snapshot)) return
        val activity = activeActivity ?: return
        val roots = listOfNotNull(view, activity.window?.decorView).distinct()

        // SonyStateSnapshot currently has no reliable charging state. The
        // stock fast-connect payload can make these overlay views look like
        // the headset/case is charging, so keep the indicator hidden until a
        // real charging value is available.
        hideOfficialChargingIndicators(roots, activity)

        hideHeadsetSideRow(roots, activity, "L")
        hideHeadsetSideRow(roots, activity, "R")

        val boxLabel = findViewByResourceName(roots, activity, "textViewBox") as? TextView
        boxLabel?.let {
            it.text = "电量"
            it.visibility = View.VISIBLE
        }

        val boxBatteryRow = findViewByResourceName(roots, activity, "textViewBoxBattery")
        boxBatteryRow?.let {
            it.visibility = View.VISIBLE
            centerOfficialImage(view, it)
        }

        // A single-battery headset has no cradle charging indicator.
        findViewByResourceName(roots, activity, "imageViewBoxCharge")?.visibility = View.GONE
        Log.d(TAG, "official dialog headset layout applied: side rows hidden, battery row centered")
    }

    private fun hideHeadsetSideRow(
        roots: List<View>,
        activity: Activity,
        side: String,
    ) {
        val sideLower = side.lowercase()
        val exactNames = listOf(
            "textViewHeadset$side",
            "textViewHeadset${side}Battery",
            "textViewHeadset${side}BatteryPercent",
            "imageViewHeadset${side}Battery",
        )
        val targets = exactNames.mapNotNull {
            findViewByResourceName(roots, activity, it)
        }.distinct()
        if (targets.isEmpty()) return

        val root = roots.firstOrNull { containsView(it, targets.first()) } ?: return
        val row = ancestors(targets.first())
            .filterIsInstance<ViewGroup>()
            .firstOrNull { candidate ->
                if (candidate === root) return@firstOrNull false
                val descendants = allViews(candidate)
                val names = descendants.map { resourceEntryName(activity, it.id).lowercase() }
                val hasThisSide = names.any { it.contains("headset$sideLower") }
                val hasOtherSide = names.any {
                    it.contains("headset") &&
                        (if (sideLower == "l") it.contains("headsetr") else it.contains("headsetl"))
                }
                hasThisSide && !hasOtherSide
            }
        row?.visibility = View.GONE
        targets.forEach { it.visibility = View.GONE }
    }

    private fun showHeadsetSideRow(
        roots: List<View>,
        activity: Activity,
        side: String,
    ) {
        val exactNames = listOf(
            "textViewHeadset$side",
            "textViewHeadset${side}Battery",
            "textViewHeadset${side}BatteryPercent",
            "imageViewHeadset${side}Battery",
        )
        val targets = exactNames.mapNotNull {
            findViewByResourceName(roots, activity, it)
        }.distinct()
        if (targets.isEmpty()) return

        val sideLower = side.lowercase()
        val root = roots.firstOrNull { containsView(it, targets.first()) }
        val row = ancestors(targets.first())
            .filterIsInstance<ViewGroup>()
            .firstOrNull { candidate ->
                if (root != null && candidate === root) return@firstOrNull false
                val descendants = allViews(candidate)
                val names = descendants.map { resourceEntryName(activity, it.id).lowercase() }
                val hasThisSide = names.any { it.contains("headset$sideLower") }
                val hasOtherSide = names.any {
                    it.contains("headset") &&
                        (if (sideLower == "l") it.contains("headsetr") else it.contains("headsetl"))
                }
                hasThisSide && !hasOtherSide
            }
        row?.visibility = View.VISIBLE
        targets.forEach { it.visibility = View.VISIBLE }
        hideOfficialChargingIndicators(roots, activity)
    }

    /**
     * The synthetic official-dialog payload only supplies battery levels, not
     * charging state. Hide the stock charging overlays so stale/default stock
     * state cannot be presented as a real charging status.
     */
    private fun hideOfficialChargingIndicators(
        roots: List<View>,
        activity: Activity,
    ) {
        listOf(
            "imageViewHeadsetLCharge",
            "imageViewHeadsetRCharge",
            "imageViewBoxCharge",
        ).mapNotNull { findViewByResourceName(roots, activity, it) }
            .distinct()
            .forEach { it.visibility = View.GONE }
    }

    private fun ancestors(view: View): List<View> {
        val result = ArrayList<View>()
        var current: View? = view.parent as? View
        while (current != null) {
            result += current
            current = current.parent as? View
        }
        return result
    }

    private data class OfficialBatteryArrays(
        val light: IntArray,
        val dark: IntArray,
    )

    /** Finds the two stock arrays by the resource names they contain. */
    private fun findOfficialBatteryArrays(
        controller: Any,
        activity: Activity,
    ): OfficialBatteryArrays? {
        val arrays = ArrayList<IntArray>()
        var type: Class<*>? = controller.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers) ||
                    field.type != IntArray::class.java
                ) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(controller) as? IntArray
                }.getOrNull()?.let { value ->
                    if (value.isNotEmpty()) arrays += value
                }
            }
            type = type.superclass
        }

        fun isBatteryArray(value: IntArray, dark: Boolean): Boolean {
            val names = value.map { id -> resourceEntryName(activity, id).lowercase() }
            val batteryNames = names.filter { it.contains("battery") }
            if (batteryNames.size < 4) return false
            if (dark && batteryNames.none { it.contains("dark") }) return false
            if (!dark && batteryNames.any { it.contains("dark") }) return false
            return batteryNames.any { it.contains("_0") } &&
                batteryNames.any { it.contains("100") }
        }

        val light = arrays.firstOrNull { isBatteryArray(it, dark = false) }
        val dark = arrays.firstOrNull { isBatteryArray(it, dark = true) }
        return if (light != null && dark != null) OfficialBatteryArrays(light, dark) else null
    }

    private fun officialBatteryDrawable(
        controller: Any,
        arrays: OfficialBatteryArrays,
        value: Int,
    ): Drawable? {
        val activity = activeActivity ?: return null
        // Same bucket calculation used by the official headset model:
        // min((percent + 19) / 20, 5).
        val bucket = ((value.coerceIn(0, 100) + 19) / 20).coerceAtMost(5)
        val resourceId = (if (isDarkMode(activity)) arrays.dark else arrays.light)
            .getOrNull(bucket) ?: return null
        return invokeOfficialDrawableLoader(controller, resourceId)
            ?: runCatching { activity.resources.getDrawable(resourceId, activity.theme) }.getOrNull()
    }

    /**
     * Locates the stock resource-loader method by its runtime signature
     * (Drawable <- int), rather than by its obfuscated class/field/method
     * names. This keeps remote-resource handling on the same official path.
     */
    private fun invokeOfficialDrawableLoader(controller: Any, resourceId: Int): Drawable? {
        val candidates = ArrayList<Any>()
        var type: Class<*>? = controller.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(controller)
                }.getOrNull()?.let { value ->
                    if (value !is Resources && value !is View) candidates += value
                }
            }
            type = type.superclass
        }

        candidates.forEach { candidate ->
            var candidateType: Class<*>? = candidate.javaClass
            while (candidateType != null) {
                candidateType.declaredMethods.forEach { method ->
                    if (java.lang.reflect.Modifier.isStatic(method.modifiers) ||
                        method.parameterTypes.size != 1 ||
                        method.parameterTypes[0] != Int::class.javaPrimitiveType ||
                        !Drawable::class.java.isAssignableFrom(method.returnType)
                    ) return@forEach
                    runCatching {
                        method.isAccessible = true
                        method.invoke(candidate, resourceId) as? Drawable
                    }.getOrNull()?.let { return it }
                }
                candidateType = candidateType.superclass
            }
        }
        return null
    }

    private fun isDarkMode(activity: Activity): Boolean =
        (activity.resources.configuration.uiMode and 0x30) == 0x20

    /**
     * The official renderer decides whether to print a side's value from its
     * private model.  That model is intentionally not touched here: on
     * obfuscated Feature builds its shape is not a stable API.  Instead, guard
     * the semantic battery TextViews at the final Android text boundary.  If
     * the stock renderer writes its fallback 0%, replace only that label with
     * the already verified bridge value.
     */
    private fun installBatteryTextGuard(classLoader: ClassLoader) {
        if (batteryTextHookInstalled) return
        runCatching {
            hookAfter(
                Class.forName("android.widget.TextView", false, classLoader)
                    .getDeclaredMethod("setText", CharSequence::class.java)
                    .apply { isAccessible = true },
                logicalRole = "official-dialog-battery-text-guard",
            ) {
                val textView = instance as? TextView ?: return@hookAfter
                if (batteryTextRewriteDepth.get() == true) return@hookAfter
                val snapshot = latestSnapshot ?: return@hookAfter
                if (!snapshot.probeComplete ||
                    !isManagedOfficialTarget(activeActivity) ||
                    !isActiveBatteryTextView(textView)
                ) return@hookAfter
                val value = batteryValueForTextView(textView, snapshot) ?: return@hookAfter
                val current = textView.text?.toString()?.trim()
                if (current != "0%" && current != "0") return@hookAfter
                batteryTextRewriteDepth.set(true)
                try {
                    textView.text = "${value.coerceIn(0, 100)}%"
                    Log.d(
                        TAG,
                        "official dialog battery fallback corrected " +
                            "resource=${resourceEntryName(activeActivity ?: return@hookAfter, textView.id)} " +
                            "value=$value",
                    )
                } finally {
                    batteryTextRewriteDepth.set(false)
                }
            }
            batteryTextHookInstalled = true
            Log.i(TAG, "official dialog semantic battery TextView guard installed")
        }.onFailure { Log.w(TAG, "official dialog battery TextView guard unavailable", it) }
    }

    private fun isActiveBatteryTextView(textView: TextView): Boolean {
        val activity = activeActivity ?: return false
        if (!isManagedOfficialTarget(activity)) return false
        val resourceName = resourceEntryName(activity, textView.id).lowercase()
        if (!resourceName.contains("battery") ||
            (!resourceName.contains("percent") && !resourceName.contains("level"))
        ) return false
        val roots = listOfNotNull(activeView, activity.window?.decorView).distinct()
        return roots.any { root -> allViews(root).any { it === textView } }
    }

    private fun batteryValueForTextView(
        textView: TextView,
        snapshot: SonyStateSnapshot,
    ): Int? {
        val activity = activeActivity ?: return null
        val name = resourceEntryName(activity, textView.id).lowercase()
        return when {
            name.contains("left") || name.contains("headsetl") ->
                snapshot.batteryLeft ?: snapshot.batterySingle
            name.contains("right") || name.contains("headsetr") ->
                snapshot.batteryRight ?: snapshot.batterySingle
            name.contains("box") || name.contains("case") || name.contains("cradle") ->
                snapshot.batteryCradle
            else -> snapshot.batterySingle ?: snapshot.batteryLeft ?: snapshot.batteryRight
        }
    }

    private fun replaceOfficialImages(view: View) {
        val activity = activeActivity ?: return
        val address = activeAddress ?: return
        val prefs = runCatching { prefsProvider() }.getOrElse { prefs }
        val bitmap = runCatching {
            PodImageLoader.loadBoxBitmap(activity, prefs, address)
        }.getOrNull()

        val roots = listOfNotNull(view, activity.window?.decorView).distinct()
        val imageViews = roots.flatMap(::allViews).filterIsInstance<ImageView>().distinct()
        val namedHeadset = imageViews.firstOrNull { imageResourceMatches(activity, it, "imageViewHeadset") }
        val namedBox = imageViews.firstOrNull { imageResourceMatches(activity, it, "imageViewBox") }
        val semantic = imageViews.filter { image ->
            val name = resourceEntryName(activity, image.id).lowercase()
            (name.contains("headset") || name.contains("ear") || name.contains("box") ||
                name.contains("device") || name.contains("product")) &&
                !name.contains("battery") && !name.contains("charge") &&
                !name.contains("find") && !name.contains("background")
        }
        val fallback = imageViews.filter { image ->
            val name = resourceEntryName(activity, image.id).lowercase()
            !name.contains("battery") && !name.contains("charge") &&
                !name.contains("find") && !name.contains("background") &&
                !name.contains("harman") && !name.contains("lossless") &&
                !name.contains("audio")
        }
        val headset = namedHeadset ?: semantic.firstOrNull() ?: fallback.getOrNull(0)
        val box = namedBox ?: semantic.firstOrNull { it !== headset } ?: fallback.getOrNull(1)

        // The stock TWS layout has separate headset and cradle image slots.
        // Sony's image is a single product image, so collapse the cradle slot
        // and center the remaining official ImageView in its actual parent.
        val single = headset ?: box
        if (single != null) {
            val drawable = bitmap?.let { BitmapDrawable(activity.resources, it) }
            drawable?.let { single.setImageDrawable(it) }
            single.visibility = View.VISIBLE
            single.scaleX = SINGLE_IMAGE_SCALE
            single.scaleY = SINGLE_IMAGE_SCALE
            collapseOfficialBoxImage(roots, activity, single, box)
            centerOfficialImage(view, single)
        }
        latestSnapshot?.let { applyHeadsetLayout(view, it) }
        Log.d(
            TAG,
            "official dialog single image applied hasImage=${bitmap != null} " +
                "views=${imageViews.size} address=$address",
        )
    }

    private fun collapseOfficialBoxImage(
        roots: List<View>,
        activity: Activity,
        single: View,
        box: View?,
    ) {
        val boxLayout = findViewByResourceName(roots, activity, "imageViewBoxLayout")
        if (boxLayout != null && boxLayout !== single && !containsView(boxLayout, single)) {
            boxLayout.visibility = View.GONE
        } else if (box != null && box !== single) {
            box.visibility = View.GONE
        }
    }

    private fun centerOfficialImage(root: View, image: View) {
        // The stock ImageView can be nested in one or more layout wrappers.
        // Center every direct child in its parent until the official content
        // root is reached; centering only imageViewHeadsetLayout is not enough
        // when that wrapper itself is positioned by another container.
        var child: View? = image
        while (child != null && child !== root) {
            centerViewInParent(child)
            child = child.parent as? View
        }
    }

    private fun centerViewInParent(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        val params = view.layoutParams ?: return
        when (params) {
            is LinearLayout.LayoutParams -> {
                if (params.weight > 0f) {
                    params.weight = 0f
                    if (params.width == 0) params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                params.gravity = (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) or
                    Gravity.CENTER_HORIZONTAL
                view.layoutParams = params
            }

            is RelativeLayout.LayoutParams -> {
                params.addRule(RelativeLayout.CENTER_HORIZONTAL, RelativeLayout.TRUE)
                params.addRule(RelativeLayout.ALIGN_PARENT_LEFT, 0)
                params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, 0)
                params.addRule(RelativeLayout.ALIGN_PARENT_START, 0)
                params.addRule(RelativeLayout.ALIGN_PARENT_END, 0)
                view.layoutParams = params
            }

            is FrameLayout.LayoutParams -> {
                params.gravity = (params.gravity and Gravity.VERTICAL_GRAVITY_MASK) or
                    Gravity.CENTER_HORIZONTAL
                view.layoutParams = params
            }
        }
        // LayoutParams gravity/rules do not affect custom or nested stock
        // containers consistently. The post-layout correction is relative to
        // the direct parent and therefore remains valid at every nesting
        // level, including an obfuscated ViewGroup implementation.
        view.post {
            val parentWidth = parent.width
            val childWidth = view.width
            if (parentWidth > 0 && childWidth > 0) {
                view.translationX = (parentWidth - childWidth) / 2f - view.left
            }
        }
        parent.requestLayout()
    }

    private fun findViewByResourceName(
        roots: List<View>,
        activity: Activity,
        name: String,
    ): View? = roots.flatMap(::allViews).firstOrNull {
        resourceEntryName(activity, it.id).equals(name, ignoreCase = true)
    }

    private fun containsView(root: View, target: View): Boolean {
        if (root === target) return true
        if (root !is ViewGroup) return false
        for (index in 0 until root.childCount) {
            if (containsView(root.getChildAt(index), target)) return true
        }
        return false
    }

    private fun imageResourceMatches(activity: Activity, view: ImageView, entryName: String): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        val expected = listOf(
            activity.resources.getIdentifier(entryName, "id", XIAOMI_PACKAGE),
            activity.resources.getIdentifier(entryName, "id", activity.packageName),
        ).filter { it != 0 }
        return id in expected || resourceEntryName(activity, id).equals(entryName, ignoreCase = true)
    }

    private fun currentApplicationContext(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Context
    }.getOrNull()

    private fun isModuleUiForeground(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val uid = context.packageManager
            .getApplicationInfo("com.mercury.sonypods", 0)
            .uid
        val state = ActivityManager::class.java
            .getMethod("getUidProcessState", Int::class.javaPrimitiveType)
            .invoke(am, uid) as Int
        val topState = ActivityManager::class.java
            .getField("PROCESS_STATE_TOP")
            .getInt(null)
        state == topState
    }.getOrDefault(false)

}
