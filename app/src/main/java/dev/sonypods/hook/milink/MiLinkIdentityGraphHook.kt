package dev.sonypods.hook.milink

import dev.sonypods.device.SonyDeviceService
import dev.sonypods.hook.Log
import dev.sonypods.hook.getObjectField
import dev.sonypods.hook.setObjectField
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps MiLink's complete headset identity graph on one physical-headset identity.
 *
 * JADX shows that card validity is a strict join:
 * `DeviceInfo.id -> CirculateDeviceInfo -> CirculateServiceInfo.deviceId ->
 * HeadsetDeviceManager[deviceId]`. A dual-identity Sony can publish C5 on the Wear/MDC side while
 * the HEADSET service and native registry are keyed by 80:99, breaking that join even though every
 * individual object is otherwise valid.
 *
 * [MiLinkServiceHook] normalizes new HeadsetInfo objects at the producer boundary. This hook repairs
 * objects published before that identity became known at MiLink's exact graph-join boundary, mirrors
 * the native registry under the selected identity, and then leaves MiLink's original validator to
 * make the decision. It never returns a synthetic success and never rewrites Android's Bluetooth
 * identities or any non-Sony device.
 */
internal class MiLinkIdentityGraphHook(
    private val hook: MiLinkServiceHook,
    private val registry: MiLinkFusionRegistryHook,
) {
    private val lastRepairSignatures = ConcurrentHashMap.newKeySet<String>()

    fun hookIdentityGraph() {
        val symbols = runCatching { hook.requireSymbols(MiLinkIdentityGraphSymbols) }
            .onFailure { Log.d(MiLinkServiceHook.TAG, "identity graph symbols unavailable", it) }
            .getOrNull() ?: return
        val validator = symbols.method("headsetGraphValidator")

        hook.hookBefore(validator, logicalRole = "milink-headset-identity-graph-repair") {
            val selectedId = args.getOrNull(0) as? String ?: return@hookBefore
            val cachedDevice = args.getOrNull(1) ?: return@hookBefore
            if (!isSelectedSony(selectedId, cachedDevice)) return@hookBefore
            repairJoin(cachedDevice, selectedId)
        }
        hook.hookAfter(validator, logicalRole = "milink-headset-identity-graph-verify") {
            if (result != true) return@hookAfter
            val selectedId = args.getOrNull(0) as? String ?: return@hookAfter
            if (hook.isSonyAddress(selectedId)) {
                Log.d(MiLinkServiceHook.TAG, "MiLink HEADSET identity graph valid id=$selectedId")
            }
        }
    }

    fun reset() {
        lastRepairSignatures.clear()
    }

    private fun isSelectedSony(selectedId: String, cachedDevice: Any): Boolean {
        if (hook.isSonyAddress(selectedId)) return true
        return headsetServices(cachedDevice).any(::serviceLooksSony)
    }

    private fun repairJoin(cachedDevice: Any, selectedId: String) {
        val service = headsetServices(cachedDevice).firstOrNull { candidate ->
            val serviceId = stringField(candidate, "deviceId")
            serviceLooksSony(candidate) ||
                SonyDeviceService.sameHeadset(serviceId, selectedId) ||
                hook.isSonyAddress(serviceId.orEmpty())
        } ?: return

        val previousId = stringField(service, "deviceId")
        setObjectField(service, "deviceId", selectedId)
        setObjectField(service, "connectState", CONNECTED)
        registry.ensureIdentity(selectedId, service)

        canonical(selectedId)?.takeUnless { it.equals(selectedId, ignoreCase = true) }?.let { canonical ->
            registry.ensureIdentity(canonical, service)
        }

        val signature = "$previousId->$selectedId|${stringField(service, "serviceId").orEmpty()}"
        if (lastRepairSignatures.add(signature)) {
            Log.d(
                MiLinkServiceHook.TAG,
                "repaired MiLink HEADSET graph service=$previousId selected=$selectedId canonical=${canonical(selectedId)}",
            )
        }
    }

    private fun headsetServices(device: Any): List<Any> =
        ((getObjectField(device, "circulateServices") as? Collection<*>) ?: emptyList<Any>())
            .filterNotNull()
            .filter { service ->
                (runCatching { getObjectField(service, "protocolType") as? Number }.getOrNull()?.toInt()) ==
                    HEADSET_PROTOCOL
            }

    private fun serviceLooksSony(service: Any): Boolean {
        val address = stringField(service, "deviceId")
        if (!address.isNullOrBlank() && hook.isSonyAddress(address)) return true
        val name = stringField(service, "serviceId")
        return !name.isNullOrBlank() && name == hook.currentName
    }

    private fun canonical(address: String?): String? {
        val resolved = SonyDeviceService.resolveControlAddress(address)
        if (resolved != null && !resolved.equals(address, ignoreCase = true)) return resolved
        return hook.currentAddress?.let(SonyDeviceService::resolveControlAddress) ?: resolved
    }

    private fun stringField(owner: Any, name: String): String? =
        runCatching { getObjectField(owner, name) as? String }.getOrNull()

    private companion object {
        const val HEADSET_PROTOCOL = 393216
        const val CONNECTED = 2
    }
}
