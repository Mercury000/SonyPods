package dev.sonypods.hook.milink

import android.os.Handler
import android.os.Looper
import dev.sonypods.hook.Log
import dev.sonypods.hook.callMethod
import dev.sonypods.hook.getObjectField
import dev.sonypods.hook.setObjectField
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture

/**
 * Feeds the fusion device center's own headset registry so a Sony pod is a first-class
 * headset on the surfaces that read that registry (ball battery ring, ANC label, the
 * headset detail panel's ANC bar / volume / battery).
 *
 * The registry ([com.miui.circulate.api.protocol.headset.HeadsetDeviceManager]) is only
 * written by [com.miui.circulate.api.protocol.headset.HeadsetServiceClient] from Mi headset
 * host events, which a Sony never produces — so it stays empty and every consumer that
 * reads through [com.miui.circulate.api.protocol.headset.HeadsetServiceController] shows a
 * blank device. This hook stands in for that producer:
 *
 *  - it mirrors the module's own Sony state into a [com.miui.circulate.api.protocol.headset.HeadsetDeviceInfo]
 *    entry keyed by the same address the fused service publishes, so the pure cache reads
 *    (mode/battery/name/volume) resolve natively with correct domains and no per-getter
 *    spoofing;
 *  - it supplies the presentation-only answers that a Sony cannot obtain from Xiaomi's
 *    IHeadset backend (refresh/support/bond/mma). Control writes are deliberately not handled
 *    here: MiLink must retain its native local/remote routing, and the runtime endpoint hooks
 *    translate the command only after it reaches the device selected by MiLink.
 *
 * Everything is gated on the service carrying a Sony address; genuine Mi headsets are
 * untouched. The registry entry carries the headset-type in the {0=降噪,1=通透,2=关闭}
 * domain the detail panel expects, distinct from the runtime model's client-state domain.
 */
@Suppress("UNCHECKED_CAST")
internal class MiLinkFusionRegistryHook(private val hook: MiLinkServiceHook) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var controller: Any? = null

    /** The Sony headset CirculateServiceInfo the detail panel is currently using. */
    @Volatile
    private var service: Any? = null

    @Volatile
    private var mirroredDeviceId: String? = null

    /** Signature of the last mode/battery/volume push, so no-op snapshots do not re-render surfaces. */
    @Volatile
    private var lastPushMode = -1

    @Volatile
    private var lastPushBatteryKey: String? = null

    @Volatile
    private var lastPushVolume = -1

    /** Guards state fan-out so a listener that re-enters the controller cannot loop. */
    private val broadcasting = ThreadLocal.withInitial { false }

    @Volatile
    private var wearCapabilityHookInstalled = false
    private lateinit var wearListenerClass: Class<*>
    private lateinit var wearControllerField: Field
    private lateinit var wearServiceField: Field
    private lateinit var wearSupportModeField: Field
    private lateinit var wearCallbackListField: Field
    private lateinit var wearCallbackMethods: List<Method>

    fun hook() {
        hookHeadsetServiceController()
        hookHeadsetServiceLifecycle()
    }

    fun reset() {
        controller = null
        service = null
        mirroredDeviceId = null
        lastPushMode = -1
        lastPushBatteryKey = null
        lastPushVolume = -1
        wearCapabilityHookInstalled = false
    }

    /** Remove the synthetic Sony entry through MiLink's native headset registry API. */
    fun onSonyDisconnected(address: String?) {
        val deviceId = mirroredDeviceId ?: deviceIdOf(service) ?: address
        if (!deviceId.isNullOrBlank()) {
            manager()?.let { registry ->
                runCatching { callMethod(registry, "removeBluetoothDevice", deviceId) }
                    .onFailure { Log.d(MiLinkServiceHook.TAG, "remove disconnected Sony registry entry failed", it) }
            }
        }
        service?.let { runCatching { setObjectField(it, "connectState", 0) } }
        service = null
        mirroredDeviceId = null
        lastPushMode = -1
        lastPushBatteryKey = null
        lastPushVolume = -1
    }

    /** Called whenever module state lands (see [MiLinkServiceHook.applySnapshot]). */
    fun onSonyStateChanged() {
        refreshRegistry(null, broadcast = true)
    }

    private fun hookHeadsetServiceController() {
        val controllerName = "com.miui.circulate.api.protocol.headset.HeadsetServiceController"
        val serviceName = "com.miui.circulate.api.service.CirculateServiceInfo"
        val deviceInfoName = "com.miui.circulate.api.service.CirculateDeviceInfo"

        // Prime a fallback before the native refresh, but never consume the call. In a remote
        // circulation the official request is what fetches the holder's authoritative HeadsetInfo;
        // returning a synthetic success here leaves the viewer stuck on its last local snapshot.
        runCatching {
            hook.hookBefore(
                hook.findMethod(controllerName, "refreshHeadsetProperty", hook.findClass(serviceName)),
                logicalRole = "fusion-registry-refresh",
            ) {
                val svc = args.getOrNull(0)
                if (!isSonyService(svc)) return@hookBefore
                remember(svc, instance)
                refreshRegistry(svc, broadcast = false)
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook HeadsetServiceController.refreshHeadsetProperty skipped", it) }

        // Every mode/battery/volume/name read funnels through getBluetoothDeviceInfo. Populate only
        // a missing/invalid entry. A valid entry may have just arrived from the remote holder and
        // must not be overwritten with this process's stale cached Sony snapshot.
        runCatching {
            hook.hookBefore(
                hook.findMethod(controllerName, "getBluetoothDeviceInfo", hook.findClass(serviceName)),
                logicalRole = "fusion-registry-read-guard",
            ) {
                val svc = args.getOrNull(0)
                if (!isSonyService(svc)) return@hookBefore
                remember(svc, instance)
                refreshRegistry(svc, broadcast = false)
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook HeadsetServiceController.getBluetoothDeviceInfo skipped", it) }

        // getSupportAncMode: 2 makes both the detail panel and Wear Agent advertise the full
        // ANC set (通透/降噪/关闭). Capability is a property of the Sony headset, not of which
        // MiLink process currently handles its Tandem session. Gating this presentation read lets
        // a cold-start/recovery race fall through to MiLink's stock query; its result 1 is then
        // cached by Wear Agent and hides 通透 until a later mode change happens to refresh it.
        runCatching {
            hook.hookBefore(
                hook.findMethod(controllerName, "getSupportAncMode", hook.findClass(serviceName)),
                logicalRole = "fusion-registry-support-anc",
            ) {
                if (!isSonyService(args.getOrNull(0))) return@hookBefore
                remember(args[0], instance)
                this.result = completed(FULL_ANC_SUPPORT_MODE)
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook HeadsetServiceController.getSupportAncMode skipped", it) }

        // A Sony is never an MMA headset; the real query would go to a Mi host it has not got.
        runCatching {
            hook.hookBefore(
                hook.findMethod(controllerName, "isMmaHeadset", hook.findClass(deviceInfoName), hook.findClass(serviceName)),
                logicalRole = "fusion-registry-is-mma",
            ) {
                if (!isSonyService(args.getOrNull(1))) return@hookBefore
                remember(args[1], instance)
                this.result = completed(false)
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook HeadsetServiceController.isMmaHeadset skipped", it) }

        // Bonded target so the panel never shows an offline / "not nearby" state.
        runCatching {
            hook.hookBefore(
                hook.findMethod(controllerName, "getTargetBondStatus", hook.findClass(deviceInfoName), hook.findClass(serviceName)),
                logicalRole = "fusion-registry-bond-status",
            ) {
                if (!isSonyService(args.getOrNull(1))) return@hookBefore
                remember(args[1], instance)
                this.result = completed(1)
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook HeadsetServiceController.getTargetBondStatus skipped", it) }

    }
    private fun hookHeadsetServiceLifecycle() {
        // The headset client clears the whole registry when its service dies; drop the cached
        // identity so the mirror is rebuilt (via the read guard) for a fresh generation.
        runCatching {
            hook.hookAfter(
                hook.findMethod(HEADSET_DEVICE_MANAGER, "clearBluetoothDevices"),
                logicalRole = "fusion-registry-rearm",
            ) {
                service = null
                mirroredDeviceId = null
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook HeadsetDeviceManager.clearBluetoothDevices skipped", it) }
    }

    /** Install once the target Application exists, before Wear creates/resumes its controller. */
    fun onApplicationReady() {
        if (wearCapabilityHookInstalled) return
        runCatching {
            val symbols = hook.requireSymbols(MiLinkWearSymbols)
            wearListenerClass = symbols.clazz("listener")
            wearControllerField = symbols.field("controllerField")
            wearServiceField = symbols.field("serviceField")
            wearSupportModeField = symbols.field("supportModeField")
            wearCallbackListField = symbols.field("callbackListField")
            wearCallbackMethods = listOf(
                symbols.method("callbackMethod1"),
                symbols.method("callbackMethod2"),
            )

            wearCallbackMethods.forEach { method ->
                hook.hookBefore(
                    method,
                    logicalRole = "fusion-registry-wear-capability-seed:${method.name}",
                ) {
                    val listener = args.getOrNull(0) ?: return@hookBefore
                    if (!wearListenerClass.isInstance(listener)) return@hookBefore
                    val registered = runCatching {
                        (wearCallbackListField.get(instance) as? Collection<*>)?.contains(listener) == true
                    }.getOrDefault(false)
                    // The sibling method removes callbacks. Only an absent listener is being added.
                    if (registered) return@hookBefore
                    seedWearCapabilityFromController(listener)
                }
            }
            wearCapabilityHookInstalled = true
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook Wear active callback registration skipped", it) }
    }

    /**
     * Query the authoritative controller synchronously only when its future is already complete,
     * then initialize Wear's consumer state before any callback-triggered ShareDevice publication.
     */
    private fun seedWearCapabilityFromController(notify: Any) {
        val wearController = runCatching { wearControllerField.get(notify) }.getOrNull() ?: return
        val wearService = runCatching { wearServiceField.get(notify) }.getOrNull() ?: return
        if (!isSonyService(wearService)) return

        val supportFuture = runCatching {
            callMethod(wearController, "getSupportAncMode", wearService) as? CompletableFuture<*>
        }.getOrNull() ?: return
        val supportMode = runCatching { supportFuture.getNow(null) as? Number }
            .getOrNull()
            ?.toInt()
            ?.takeIf { it in 1..2 }
            ?: return
        runCatching { wearSupportModeField.setInt(notify, supportMode) }
            .onFailure { Log.d(MiLinkServiceHook.TAG, "initialize Wear supportMode skipped", it) }
    }

    private fun remember(svc: Any?, ctrl: Any?) {
        if (ctrl != null) controller = ctrl
        if (svc != null) service = svc
    }

    /** Whether [svc] is a fusion service carrying one of the module's Sony addresses. */
    private fun isSonyService(svc: Any?): Boolean {
        if (svc == null) return false
        val deviceId = runCatching { getObjectField(svc, "deviceId") as? String }.getOrNull()
        if (!deviceId.isNullOrBlank() && hook.isSonyAddress(deviceId)) return true
        val name = runCatching { getObjectField(svc, "serviceId") as? String }.getOrNull()
        return !name.isNullOrBlank() && name == hook.currentName
    }

    private fun deviceIdOf(svc: Any?): String? =
        if (svc == null) hook.currentAddress
        else runCatching { getObjectField(svc, "deviceId") as? String }.getOrNull()
            ?: hook.currentAddress

    /**
     * Ensure a Sony [com.miui.circulate.api.protocol.headset.HeadsetDeviceInfo] sits in the
     * registry under the published device id.
     *
     * [broadcast] means a connected Sony snapshot landed in this process, so the module state is
     * authoritative and may replace the mirror. Read/refresh guards pass false: they are fallback
     * paths and must preserve a valid entry produced by MiLink's native remote HeadsetHost flow.
     */
    private fun refreshRegistry(svc: Any? = null, broadcast: Boolean) {
        val deviceId = deviceIdOf(svc) ?: return
        if (!hook.isSonyAddress(deviceId)) return
        val manager = manager() ?: return
        val existing = runCatching { callMethod(manager, "getBluetoothDevice", deviceId) }.getOrNull()
        if (!broadcast && hasAuthoritativeMode(existing)) {
            mirroredDeviceId = deviceId
            return
        }

        val info = buildDeviceInfo(deviceId) ?: return
        val added = runCatching {
            callMethod(manager, "addBluetoothDevice", info)
            true
        }.getOrDefault(false)
        if (!added) return
        mirroredDeviceId = deviceId
        if (broadcast) broadcastModeAndBattery()
    }

    /** Panel/native registry mode domain: 0=降噪, 1=通透, 2=关闭. */
    private fun hasAuthoritativeMode(info: Any?): Boolean {
        val mode = runCatching { getObjectField(info, "mode") as? Int }.getOrNull()
        return mode in 0..2
    }

    private fun buildDeviceInfo(deviceId: String): Any? = runCatching {
        val ctor = hook.findClass(HEADSET_DEVICE_INFO).getDeclaredConstructor()
        ctor.isAccessible = true
        val info = ctor.newInstance()
        setObjectField(info, "deviceId", deviceId)
        setObjectField(info, "mac", deviceId)
        setObjectField(info, "name", hook.currentName ?: deviceId)
        // Type drives which battery layout the detail card renders: over-ear (single battery)
        // must be 7 so the single-battery card reads slots 2/5 — matching the module's over-ear
        // slot scheme — while TWS rides the generic 11 for the case/left/right card. Values are
        // otherwise only special-cased up to 9, so 11 is a safe default multi-ear fallback.
        setObjectField(info, "type", if (hook.isOverEar) TYPE_OVER_EAR else TYPE_TWS)
        setObjectField(info, "mode", panelAncMode())
        setObjectField(info, "power", java.util.ArrayList(hook.miLinkBatteryLevels()))
        // -1 = unsupported: the Mi audio-effect (spatial) section hides itself on this value
        // (updateMiAudioEffectStatus(-1) → hide), so an unbacked 空间音频/头部追踪 row never
        // shows for the Sony.
        setObjectField(info, "audioEffectState", -1)
        setObjectField(info, "headsetVolume", mirrorVolume())
        // -1 = unsupported: the find-ring card only renders for a value >= 0, and a Sony has no
        // Mi profile back-end to ring it, so an unbacked 响铃查找 control must not appear.
        setObjectField(info, "findRingState", -1)
        // MiLink forwards vidPid as the remote capability/model key. A known carrier keeps
        // the Wear ANC surface on the complete 通透/降噪/关闭 capability set.
        setObjectField(info, "vidPid", hook.fakeDeviceId())
        setObjectField(info, "noNeedBackBox", false)
        setObjectField(info, "isOutput", false)
        setObjectField(info, "wiredState", 0)
        info
    }.getOrNull()

    /** Read the headphone's own volume step and convert to 0-100 for the fusion panel. */
    private fun mirrorVolume(): Int {
        val step = hook.musicVolume ?: return 0
        val maxStep = hook.musicVolumeStep
        if (maxStep <= 1) return 0
        return (step * 100) / (maxStep - 1)
    }

    private fun manager(): Any? = runCatching {
        hook.findClass(HEADSET_DEVICE_MANAGER).getMethod("get").invoke(null)
    }.getOrNull()

    private fun storedInfo(): Any? {
        val deviceId = mirroredDeviceId ?: deviceIdOf(service) ?: hook.currentAddress ?: return null
        if (!hook.isSonyAddress(deviceId)) return null
        val manager = manager() ?: return null
        return runCatching { callMethod(manager, "getBluetoothDevice", deviceId) }.getOrNull()
    }

    private fun writeMirrorMode(panelMode: Int) {
        val info = storedInfo() ?: run { refreshRegistry(service, broadcast = false); storedInfo() } ?: return
        runCatching { setObjectField(info, "mode", panelMode) }
    }

    /**
     * Module state domain is {0=关闭,1=降噪,2=通透} (runtime client domain); the fusion
     * registry expects {0=降噪,1=通透,2=关闭}. Rotate once at this boundary.
     */
    private fun panelAncMode(): Int = (hook.miLinkAncState() + 2) % 3

    private fun <T> completed(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)

    private fun broadcastModeAndBattery() {
        if (broadcasting.get() == true) return
        broadcasting.set(true)
        try {
            if (!statePushChanged()) return
            val stable = hook.requireSymbols(MiLinkStableSymbols)
            notifyListeners(stable.method("headsetNotifyMode"), panelAncMode())
            notifyListeners(stable.method("headsetNotifyBattery"), java.util.ArrayList(hook.miLinkBatteryLevels()))
            notifyListeners(stable.method("headsetNotifyVolume"), mirrorVolume())
        } finally {
            broadcasting.set(false)
        }
    }

    /** Records the current mode/battery and reports whether they differ from the last push. */
    private fun statePushChanged(): Boolean {
        val mode = panelAncMode()
        val batteryKey = hook.miLinkBatteryLevels().joinToString(",")
        val volume = mirrorVolume()
        val changed = mode != lastPushMode || batteryKey != lastPushBatteryKey || volume != lastPushVolume
        lastPushMode = mode
        lastPushBatteryKey = batteryKey
        lastPushVolume = volume
        return changed
    }

    /**
     * Fan the update out over the same [com.miui.circulate.api.protocol.headset.HeadsetServiceNotify]
     * listeners the service client would use, with the fused service as the target. The
     * runtime delivers these on its main handler; so do we.
     */
    private fun notifyListeners(method: Method, value: Any?) {
        val ctrl = controller ?: return
        val svc = service ?: return
        val notifies = runCatching { callMethod(ctrl, "getServiceNotifies") as? List<*> }.getOrNull() ?: return
        main.post {
            for (notify in notifies) {
                if (notify == null) continue
                runCatching {
                    method.invoke(notify, svc, value)
                }.onFailure {
                    Log.d(MiLinkServiceHook.TAG, "notify ${method.name} to ${notify.javaClass.name} failed", it)
                }
            }
        }
    }

    private companion object {
        const val HEADSET_DEVICE_INFO = "com.miui.circulate.api.protocol.headset.HeadsetDeviceInfo"
        const val HEADSET_DEVICE_MANAGER = "com.miui.circulate.api.protocol.headset.HeadsetDeviceManager"
        const val HEADSET_SERVICE_CONTROLLER = "com.miui.circulate.api.protocol.headset.HeadsetServiceController"

        const val CIRCULATE_SERVICE_INFO = "com.miui.circulate.api.service.CirculateServiceInfo"

        /** MiLink/Wear capability value for 通透 + 降噪 + 关闭. */
        const val FULL_ANC_SUPPORT_MODE = 2

        /** Over-ear single-battery headphone: detail battery card renders slots 2/5 as one cell. */
        const val TYPE_OVER_EAR = 7

        /** Generic TWS / multi-ear rendering (case+left+right battery card). */
        const val TYPE_TWS = 11
    }
}
