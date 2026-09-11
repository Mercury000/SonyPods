package dev.sonypods.hook.milink

import dev.sonypods.hook.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Keeps the MDC device database's headset typing stable across the bluetooth-search
 * re-publish that follows every fresh circulate client init.
 *
 * All obfuscated targets are resolved by DexKit before this hook touches the host.
 * The hook itself only consumes Method/Field descriptors resolved and cached by
 * [MiLinkDeviceMetaSymbols].
 */
internal class MiLinkDeviceMetaGuardHook(private val hook: MiLinkServiceHook) {

    fun hookDeviceMetaGuard() {
        val symbols = runCatching { hook.requireSymbols(MiLinkDeviceMetaSymbols) }
            .onFailure { Log.d(MiLinkServiceHook.TAG, "device meta downgrade guard skipped", it) }
            .getOrNull() ?: return

        val deviceTypeField = symbols.field("deviceType")
        val titleField = symbols.field("title")
        installGuard(symbols.method("guardEntry"), deviceTypeField, titleField)
        installGuard(symbols.method("guardWrite"), deviceTypeField, titleField)
        Log.d(
            MiLinkServiceHook.TAG,
            "device meta downgrade guard installed from DexKit meta=${symbols.descriptors()["deviceMeta"]}",
        )
    }

    private fun installGuard(guard: Method, deviceTypeField: Field, titleField: Field) {
        hook.hookBefore(guard, logicalRole = "mdc-device-meta-downgrade-guard:${guard.name}") {
            val incoming = args.getOrNull(0) ?: return@hookBefore
            val existing = args.getOrNull(2) ?: return@hookBefore
            if (deviceTypeField.get(incoming) as? String != TYPE_THIRD_HEADSET) return@hookBefore
            if (deviceTypeField.get(existing) as? String != TYPE_HEADSET) return@hookBefore
            val title = titleField.get(existing) as? String ?: ""
            Log.d(MiLinkServiceHook.TAG, "skipped third_headset downgrade of headset row title=$title")
            result = null
        }
    }

    private companion object {
        /** CirculateConstants.DeviceType values the guard cares about. */
        const val TYPE_HEADSET = "headset"
        const val TYPE_THIRD_HEADSET = "third_headset"
    }
}