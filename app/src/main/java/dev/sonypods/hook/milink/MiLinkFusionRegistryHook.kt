package dev.sonypods.hook.milink

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.hook.Log
import dev.sonypods.hook.callMethod
import dev.sonypods.hook.getObjectField
import dev.sonypods.hook.setObjectField
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
 *  - it answers the controller calls that would otherwise dial the real Mi headset client
 *    (IHeadset), which has no Mi host for a Sony to talk to: refresh/support/bond/mma come
 *    back as ready values, and the ANC / volume writes translate to the Sony side, mirror
 *    the change back and broadcast it over the same [com.miui.circulate.api.protocol.headset.HeadsetServiceNotify]
 *    bus a real host update would use — so the panel and the ball follow via their native
 *    listeners.
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

    @Volatile
    private var lastVolume = -1

    /** Signature of the last mode/battery push, so no-op snapshots do not re-render surfaces. */
    @Volatile
    private var lastPushMode = -1

    @Volatile
    private var lastPushBatteryKey: String? = null

    /** Guards notify fan-out so a listener that re-enters the controller cannot loop. */
    private val broadcasting = ThreadLocal.withInitial { false }

    fun hook() {
        hookHeadsetServiceController()
        hookHeadsetServiceLifecycle()
        hookNotifySubscription()
    }

    fun reset() {
        controller = null
        service = null
        mirroredDeviceId = null
        lastVolume = -1
        lastPushMode = -1
        lastPushBatteryKey = null
    }

    /** Called whenever module state lands (see [MiLinkServiceHook.applySnapshot]). */
    fun onSonyStateChanged() {
        refreshRegistry(null, broadcast = true)
    }

    private fun hookHeadsetServiceController() {
        val controllerName = "com.miui.circulate.api.protocol.headset.HeadsetServiceController"
        val serviceName = "com.miui.circulate.api.service.CirculateServiceInfo"
        val deviceInfoName = "com.miui.circulate.api.service.CirculateDeviceInfo"
        val intType = Int::class.javaPrimitiveType!!

        // The panel's open path ends in refreshHeadsetProperty; for a Sony the real body
        // would call the Mi client and, on its failure, wipe the registry entry's mode and
        // power. Short-circuit to success and make sure the mirror is in place first so the
        // read of the panel right after this call resolves.
        runCatching {
            hook.hookBefore(
                hook.findMethod(controllerName, "refreshHeadsetProperty", hook.findClass(serviceName)),
                logicalRole = "fusion-registry-refresh",
            ) {
                val svc = args.getOrNull(0)
                if (!isSonyService(svc)) return@hookBefore
                remember(svc, instance)
                refreshRegistry(svc, broadcast = false)
                this.result = completed(100)
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook HeadsetServiceController.refreshHeadsetProperty skipped", it) }

        // Every mode/battery/volume/name read funnels through getBluetoothDeviceInfo. Make the
        // mirror exist before any of them can observe a null entry, and latch the Sony service
        // the panel is using so later state pushes target the right listeners.
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

        // getSupportAncMode: 2 makes the detail panel show the full ANC card (通透/降噪/关闭).
        runCatching {
            hook.hookBefore(
                hook.findMethod(controllerName, "getSupportAncMode", hook.findClass(serviceName)),
                logicalRole = "fusion-registry-support-anc",
            ) {
                if (!isSonyService(args.getOrNull(0))) return@hookBefore
                remember(args[0], instance)
                this.result = completed(2)
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

        // ANC write: translate the panel's {0降噪,1通透,2关闭} mode to the Sony side, mirror
        // it back into the registry and broadcast over the native notify bus, then return the
        // success code the panel's own callback requires to commit the bar.
        runCatching {
            hook.hookBefore(
                hook.findMethod(controllerName, "setNoiseCancelling", hook.findClass(serviceName), intType),
                logicalRole = "fusion-registry-set-anc",
            ) {
                val svc = args.getOrNull(0)
                if (!isSonyService(svc)) return@hookBefore
                remember(svc, instance)
                val panelMode = args.getOrNull(1) as? Int ?: return@hookBefore
                applyPanelAncMode(panelMode)
                writeMirrorMode(panelMode)
                broadcastMode(panelMode)
                this.result = completed(100)
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook HeadsetServiceController.setNoiseCancelling skipped", it) }

        // Volume write: forward to the Sony side and mirror the value back.
        runCatching {
            hook.hookBefore(
                hook.findMethod(controllerName, "setVolume", hook.findClass(serviceName), intType),
                logicalRole = "fusion-registry-set-volume",
            ) {
                val svc = args.getOrNull(0)
                if (!isSonyService(svc)) return@hookBefore
                remember(svc, instance)
                val volume = (args.getOrNull(1) as? Int)?.coerceIn(0, 100) ?: return@hookBefore
                applyVolume(volume)
                writeMirrorVolume(volume)
                broadcastVolume(volume)
                this.result = completed(100)
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook HeadsetServiceController.setVolume skipped", it) }
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

    /**
     * The detail panel opens with refresh → registerServiceNotify → a0(). Its a0() shows the
     * ANC card for over-ear (type 7) but never selects the current mode (only the TWS branch
     * calls updateMode), so the over-ear bar would open unhighlighted until the next change.
     * When the detail subscribes to headset property updates we already hold the mirror's mode;
     * re-announce it so the panel's own notify handler (onBluetoothModeChanged → updateMode)
     * highlights the current ANC mode on first render.
     */
    private fun hookNotifySubscription() {
        runCatching {
            val method = hook.findClass(CONTROLLER_BASE).declaredMethods
                .filter { it.name == "registerServiceNotify" && it.parameterTypes.size == 1 }
                .firstOrNull() ?: throw NoSuchMethodException("$CONTROLLER_BASE.registerServiceNotify/1")
            method.isAccessible = true
            hook.hookAfter(method, logicalRole = "fusion-registry-subscribe-announce") {
                if (instance !== controller) return@hookAfter
                announceCurrentState()
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook $CONTROLLER_BASE.registerServiceNotify skipped", it) }
    }

    /** Push the current mode/battery to subscribed listeners once (e.g. right after attach). */
    private fun announceCurrentState() {
        val svc = service ?: return
        val deviceId = runCatching { getObjectField(svc, "deviceId") as? String }.getOrNull() ?: return
        if (!hook.isSonyAddress(deviceId)) return
        main.post {
            if (broadcasting.get() == true) return@post
            broadcasting.set(true)
            try {
                notifyListeners("onBluetoothModeChanged", panelAncMode())
                notifyListeners("onBluetoothBatteryChanged", java.util.ArrayList(hook.miLinkBatteryLevels()))
            } finally {
                broadcasting.set(false)
            }
        }
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
     * registry under the published device id, refreshed from the module's current state.
     * [broadcast] only fan mode/battery out when they actually changed since the last push,
     * so a state snapshot that repeats itself does not re-render every listening surface.
     */
    private fun refreshRegistry(svc: Any? = null, broadcast: Boolean) {
        val deviceId = deviceIdOf(svc) ?: return
        if (!hook.isSonyAddress(deviceId)) return
        val manager = manager() ?: return
        val info = buildDeviceInfo(deviceId) ?: return
        val added = runCatching {
            callMethod(manager, "addBluetoothDevice", info)
            true
        }.getOrDefault(false)
        if (!added) return
        mirroredDeviceId = deviceId
        if (broadcast) broadcastModeAndBattery()
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
        setObjectField(info, "vidPid", "0")
        setObjectField(info, "noNeedBackBox", false)
        setObjectField(info, "isOutput", false)
        setObjectField(info, "wiredState", 0)
        info
    }.getOrNull()

    /** Track the headset volume we last mirrored; before any write, seed from the system media stream. */
    private fun mirrorVolume(): Int {
        if (lastVolume >= 0) return lastVolume
        val ctx = hook.context ?: return 0
        return runCatching {
            val audio = ctx.getSystemService(AudioManager::class.java) ?: return@runCatching 0
            val stream = AudioManager.STREAM_MUSIC
            val max = audio.getStreamMaxVolume(stream)
            if (max <= 0) 0 else (audio.getStreamVolume(stream) * 100) / max
        }.getOrDefault(0)
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

    private fun writeMirrorVolume(volume: Int) {
        lastVolume = volume
        val info = storedInfo() ?: return
        runCatching { setObjectField(info, "headsetVolume", volume) }
    }

    /**
     * Module state domain is {0=关闭,1=降噪,2=通透} (runtime client domain); the fusion
     * registry expects {0=降噪,1=通透,2=关闭}. Rotate once at this boundary.
     */
    private fun panelAncMode(): Int = (hook.miLinkAncState() + 2) % 3

    /** Panel mode {0降噪,1通透,2关闭} → runtime domain {0关闭,1降噪,2通透} → Sony side. */
    private fun applyPanelAncMode(panelMode: Int) {
        val runtimeMode = (panelMode + 1) % 3
        hook.applyRemoteAncMode(runtimeMode)
    }

    private fun applyVolume(volume: Int) {
        val ctx = hook.context ?: return
        runCatching { SonyBridge.setPlaybackVolume(ctx, volume) }
            .onFailure { Log.w(MiLinkServiceHook.TAG, "Sony volume write failed volume=$volume", it) }
    }

    private fun <T> completed(value: T): CompletableFuture<T> = CompletableFuture.completedFuture(value)

    private fun broadcastModeAndBattery() {
        if (broadcasting.get() == true) return
        broadcasting.set(true)
        try {
            if (!statePushChanged()) return
            notifyListeners("onBluetoothModeChanged", panelAncMode())
            notifyListeners("onBluetoothBatteryChanged", java.util.ArrayList(hook.miLinkBatteryLevels()))
        } finally {
            broadcasting.set(false)
        }
    }

    /** Records the current mode/battery and reports whether they differ from the last push. */
    private fun statePushChanged(): Boolean {
        val mode = panelAncMode()
        val batteryKey = hook.miLinkBatteryLevels().joinToString(",")
        val changed = mode != lastPushMode || batteryKey != lastPushBatteryKey
        lastPushMode = mode
        lastPushBatteryKey = batteryKey
        return changed
    }

    private fun broadcastMode(panelMode: Int) {
        if (broadcasting.get() == true) return
        broadcasting.set(true)
        try {
            notifyListeners("onBluetoothModeChanged", panelMode)
        } finally {
            broadcasting.set(false)
        }
    }

    private fun broadcastVolume(volume: Int) {
        if (broadcasting.get() == true) return
        broadcasting.set(true)
        try {
            notifyListeners("onBluetoothVolumeChanged", volume)
        } finally {
            broadcasting.set(false)
        }
    }

    /**
     * Fan the update out over the same [com.miui.circulate.api.protocol.headset.HeadsetServiceNotify]
     * listeners the service client would use, with the fused service as the target. The
     * runtime delivers these on its main handler; so do we.
     */
    private fun notifyListeners(methodName: String, value: Any?) {
        val ctrl = controller ?: return
        val svc = service ?: return
        val notifies = runCatching { callMethod(ctrl, "getServiceNotifies") as? List<*> }.getOrNull() ?: return
        main.post {
            for (notify in notifies) {
                if (notify == null) continue
                runCatching {
                    val method = notify.javaClass.methods.firstOrNull {
                        it.name == methodName && it.parameterTypes.size == 2
                    } ?: return@runCatching
                    method.isAccessible = true
                    method.invoke(notify, svc, value)
                }.onFailure {
                    Log.d(MiLinkServiceHook.TAG, "notify $methodName to ${notify.javaClass.name} failed", it)
                }
            }
        }
    }

    private companion object {
        const val HEADSET_DEVICE_INFO = "com.miui.circulate.api.protocol.headset.HeadsetDeviceInfo"
        const val HEADSET_DEVICE_MANAGER = "com.miui.circulate.api.protocol.headset.HeadsetDeviceManager"

        /** Common controller base that declares registerServiceNotify (headset/bluetooth/synergy). */
        const val CONTROLLER_BASE = "ba.AbstractC2181g"

        /** Over-ear single-battery headphone: detail battery card renders slots 2/5 as one cell. */
        const val TYPE_OVER_EAR = 7

        /** Generic TWS / multi-ear rendering (case+left+right battery card). */
        const val TYPE_TWS = 11
    }
}
