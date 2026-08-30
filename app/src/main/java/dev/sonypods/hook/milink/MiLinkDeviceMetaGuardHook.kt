package dev.sonypods.hook.milink

import dev.sonypods.hook.Log
import java.lang.reflect.Method

/**
 * Keeps the MDC device database's headset typing stable across the bluetooth-search
 * re-publish that follows every fresh circulate client init.
 *
 * [BluetoothDeviceObserver.foundOrUpdateDevice] classifies the local device's headset
 * over the raw bluetooth protocol (524288) as third_headset by definition — regardless
 * of the service info's own deviceType. That write lands ~300ms before the headset
 * runtime protocol (393216) rewrites the row as "headset" from the hook-fed state, and
 * the gap is the card flip in the fusion device center (observed 2026-08-30 as a
 * third_headset row with battery [49,49,49,-1,-1,-1] surviving 320-360ms).
 *
 * The observer's own merge guard — the private void taking three DeviceMeta args,
 * reached when the stored row is already "headset" — is meant to keep the type and
 * only refresh the battery, but its DAO call is `UPDATE OR REPLACE` over the whole
 * row, so the downgrade goes through anyway. Skip that write when it would demote an
 * existing headset row to third_headset; the rule is device-agnostic (a battery
 * refresh must never change a card's type), so no Sony detection is needed and the
 * genuine reclassification paths — MSG_CONNECT_STATE_CHANGE on disconnect, the
 * bonded/connected purge — keep working untouched.
 *
 * Method and field names in this area are obfuscated, so the guard methods are located
 * structurally (void, three params of one DeviceMeta type; two candidates share the
 * signature and one delegates to the other — both are hooked so the write is skipped
 * whichever entry the caller uses) and the deviceType is parsed from DeviceMeta's
 * hand-written toString, whose labels survive obfuscation. DeviceMeta itself is
 * likewise only reachable as its obfuscated runtime name, so it is derived from the
 * guard method's signature rather than looked up by name.
 */
internal class MiLinkDeviceMetaGuardHook(private val hook: MiLinkServiceHook) {

    fun hookDeviceMetaGuard() {
        runCatching { install() }
            .onFailure { Log.d(MiLinkServiceHook.TAG, "device meta downgrade guard skipped", it) }
    }

    private fun install() {
        val observerClass = hook.findClass(BLUETOOTH_DEVICE_OBSERVER)
        // DeviceMeta's source-level name exists only inside bytecode strings; at runtime
        // the class is the obfuscated p180h7.C11389a. Derive it from the guard method's
        // own signature instead of Class.forName.
        val guards = observerClass.declaredMethods.filter { method ->
            method.returnType == Void.TYPE &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes.all { it == method.parameterTypes[0] }
        }
        if (guards.isEmpty()) {
            Log.d(MiLinkServiceHook.TAG, "device meta downgrade guard: no merge method found")
            return
        }
        guards.forEach { guard -> installGuard(guard) }
        Log.d(MiLinkServiceHook.TAG, "device meta downgrade guard installed on ${guards.size} method(s) meta=${guards[0].parameterTypes[0].name}")
    }

    private fun installGuard(guard: Method) {
        // The two guard candidates share one signature, so the registry id needs the
        // method name to stay unique (same pattern as hookConstructorAfterAll).
        val role = "mdc-device-meta-downgrade-guard:${guard.name}"
        hook.hookBefore(guard, logicalRole = role) {
            val incoming = args.getOrNull(0) ?: return@hookBefore
            val existing = args.getOrNull(2) ?: return@hookBefore
            if (stringFieldOf(incoming, "deviceType") != TYPE_THIRD_HEADSET) return@hookBefore
            if (stringFieldOf(existing, "deviceType") != TYPE_HEADSET) return@hookBefore
            val title = stringFieldOf(existing, "title") ?: ""
            Log.d(MiLinkServiceHook.TAG, "skipped third_headset downgrade of headset row title=$title")
            result = null
        }
    }

    /** DeviceMeta's fields and getters are obfuscated; toString prints stable labels. */
    private fun stringFieldOf(meta: Any, field: String): String? {
        val text = runCatching { meta.toString() }.getOrNull() ?: return null
        return Regex("$field='([^']*)'").find(text)?.groupValues?.get(1)
    }

    private companion object {
        const val BLUETOOTH_DEVICE_OBSERVER =
            "com.miui.circulate.device.service.search.impl.BluetoothDeviceObserver"

        /** CirculateConstants.DeviceType values the guard cares about. */
        const val TYPE_HEADSET = "headset"
        const val TYPE_THIRD_HEADSET = "third_headset"
    }
}
