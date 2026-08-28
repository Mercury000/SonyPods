package dev.sonypods.hook.milink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import dev.sonypods.device.SonyDeviceService
import dev.sonypods.hook.Log
import dev.sonypods.hook.callMethod
import dev.sonypods.hook.getObjectField
import dev.sonypods.hook.setObjectField
import java.lang.reflect.Method

/**
 * Lets MiLink circulate a dual-identity LE Audio Sony headset with one stable path.
 *
 * With LC3 active a Sony headset is bonded twice: the classic identity carries Tandem and
 * A2DP, the LE identity carries the LC3 stream. MiLink keys everything on whichever single
 * address is currently published, so a transfer can be aimed at an identity the target host
 * cannot act on. Rather than trying to normalise addresses everywhere they appear, this hook
 * keeps the published value as the stack reports it and intercepts the decision points
 * where an identity mismatch or a half-release would otherwise abort the transfer:
 *
 *  - the source-side release veto (isLeDeviceActive) is lifted for Sony headsets;
 *  - after a release, the headset's other identity is disconnected too — a half-release
 *    makes the headset ignore the target host and snap back to this phone;
 *  - if the LE stack never broadcasts the active-device loss, DiscoveryHost's reliable
 *    HostLost publication completes the waiting disconnect sync instead of a timeout;
 *  - the pre-flight "target has the headset bonded" probe is waived — the connect attempt
 *    itself is the real test;
 *  - a Sony LE identity address never goes on the circulate wire: it is non-discoverable,
 *    so no host that has not already bonded it can act on it. The connect request is
 *    rewritten to the headset's classic address at the source-side proxy boundary, before
 *    the RPC is encoded — a classic bond connects directly, an unbonded target enters the
 *    normal pairing flow, and a target that enables LE Audio upgrades the link to LC3 by
 *    itself.
 *
 * Identity pairing comes from [SonyDeviceService], classified by transport type or Sony
 * service UUIDs; the duplicate fusion-center ball is fixed separately by collapsing the LE
 * Audio active device in BluetoothServiceClient.
 */
@SuppressLint("MissingPermission")
internal class MiLinkLeAudioIdentityHook(private val hook: MiLinkServiceHook) {

    /** Guards the connect-identity rewrite against re-entering its own hook. */
    private val rewriting = ThreadLocal.withInitial { false }

    /** Guards the second-identity release against re-entering its own hook. */
    private val releasing = ThreadLocal.withInitial { false }

    @Volatile
    private var lastAliasScan = 0L

    fun hookIdentityUnification() {
        hookLeDeviceActiveVeto()
        hookSystemAlignedDisconnect()
        hookActiveDeviceVerification()
        hookHostBoundProbe()
        hookBluetoothActiveDevice()
        hookHeadsetDeviceType()
        hookMxManagerSeed()
        hookProfileImplCache()
        hookDiscoveryLostSignal()
        hookConnectClassicAddress()
    }

    /**
     * The device-type poison is born once per milink process: BluetoothServiceClient's first
     * refresh can run while its mxBluetoothManager field is still null (the SDK binds async),
     * isMiHeadset then answers false without ever reaching our checkIsMiTWS hook, and the
     * resulting third_headset lives on in that process's cached CirculateServiceInfo — every
     * later update rewrites it to the database even though all queries now answer correctly.
     * Seed the field with a live manager at init and before every refresh so the very first
     * classification already lands on our hooked answers; a stale type can then never form.
     */
    private fun hookMxManagerSeed() {
        runCatching {
            hook.hookAfter(
                hook.findMethod(BLUETOOTH_SERVICE_CLIENT, "init"),
                logicalRole = "bluetooth-client-mx-seed-init",
            ) { seedMxManager(instance) }
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "hook BluetoothServiceClient.init skipped", it)
        }
        runCatching {
            hook.hookBefore(
                hook.findMethod(BLUETOOTH_SERVICE_CLIENT, "refreshBluetoothDevice", Boolean::class.javaPrimitiveType!!),
                logicalRole = "bluetooth-client-mx-seed-refresh",
            ) { seedMxManager(instance) }
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "hook BluetoothServiceClient.refreshBluetoothDevice skipped", it)
        }
    }

    private fun seedMxManager(client: Any?) {
        if (client == null) return
        if (getObjectField(client, MX_MANAGER_FIELD) != null) return
        val context: Context = hook.context ?: return
        val manager = runCatching {
            hook.findClass(MX_MANAGER)
                .getMethod("getInstanceForIsMiTWS", Context::class.java)
                .invoke(null, context)
        }.getOrNull() ?: return
        setObjectField(client, MX_MANAGER_FIELD, manager)
        Log.i(MiLinkServiceHook.TAG, "seeded live mx manager before first classification")
    }

    /**
     * The first BluetoothServiceClient refresh in a freshly started milink process races the
     * MxBluetoothService bind: until the inner service connects, isMiHeadset answers false and
     * the card is written as third_headset — no ANC or battery controls — until the next
     * refresh self-heals it. Classify through the unified Sony judgment instead, which needs
     * no mx state: a bonded Sony headset is a headset whatever the SDK's bind phase is doing.
     */
    private fun hookHeadsetDeviceType() {
        runCatching {
            hook.hookAfter(
                hook.findMethod(BLUETOOTH_SERVICE_CLIENT, "getDeviceType", BluetoothDevice::class.java),
                logicalRole = "bluetooth-client-device-type",
            ) {
                if (result == HEADSET_TYPE) return@hookAfter
                val device = args.getOrNull(0) as? BluetoothDevice ?: return@hookAfter
                if (!hook.isSonyPod(device)) return@hookAfter
                Log.i(MiLinkServiceHook.TAG, "device type forced to headset (mx bind window)")
                result = HEADSET_TYPE
            }
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "hook BluetoothServiceClient.getDeviceType skipped", it)
        }
    }

    /** Latest ProfileImpl instance, cached from its own hooks so later hooks can reach its sync state. */
    @Volatile
    private var profileImpl: Any? = null

    private fun hookProfileImplCache() {
        runCatching {
            hook.hookAfter(
                hook.findMethod(PROFILE_IMPL, "verifyActiveDevice", BluetoothDevice::class.java),
                logicalRole = "profile-impl-instance-cache",
            ) { profileImpl = instance }
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "hook ProfileImpl instance cache skipped", it)
        }
    }

    /**
     * Under LE Audio the stack often never broadcasts an active-device-removed event after
     * the last link drops, so ProfileImpl's post-disconnect wait burns its whole timeout
     * even though the release worked. DiscoveryHost reliably publishes the loss; forward it
     * into the waiting disconnect sync.
     */
    private fun hookDiscoveryLostSignal() {
        runCatching {
            val discoveryUpdate = hook.findMethod(
                DISCOVERY_IMPL,
                "notifyHeadsetInfoUpdate",
                Int::class.javaPrimitiveType!!,
                hook.findClass("com.miui.headset.api.HeadsetInfo"),
                String::class.java,
            )
            hook.hookBefore(discoveryUpdate, logicalRole = "discovery-lost-disconnect-signal") {
                if ((args.getOrNull(0) as? Int) != HOST_LOST_UPDATE_TYPE) return@hookBefore
                val impl = profileImpl ?: return@hookBefore
                val pending = runCatching {
                    getObjectField(impl, "pendingDisconnectAddress") as? String
                }.getOrNull()
                if (pending.isNullOrEmpty()) return@hookBefore
                val sync = runCatching { getObjectField(impl, "disconnectSync") }.getOrNull() ?: return@hookBefore
                val signalled = runCatching {
                    callMethod(sync, "signal", pending, SUCCESS)
                    true
                }.getOrDefault(false)
                if (signalled) {
                    Log.i(MiLinkServiceHook.TAG, "disconnect sync signalled from discovery loss")
                }
            }
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "hook DiscoveryImpl.notifyHeadsetInfoUpdate skipped", it)
        }
    }

    /**
     * ProfileImpl waits for the headset to become active again after it asks the stack to
     * connect, and verifies the device named by the broadcast against the profile's current
     * active device. Those two can be different identities of the same headset — the classic
     * profiles come up first while LC3 is still being negotiated — and a stock mismatch is
     * reported as ActiveChangedFailed. Treat the two identities of one headset as equal.
     */
    private fun hookActiveDeviceVerification() {
        runCatching {
            hook.hookAfter(
                hook.findMethod(PROFILE_IMPL, "verifyActiveDevice", BluetoothDevice::class.java),
                logicalRole = "profile-impl-verify-active-identity",
            ) {
                if (result != false) return@hookAfter
                val reported = args.getOrNull(0) as? BluetoothDevice ?: return@hookAfter
                if (!hook.isSonyPod(reported)) return@hookAfter
                val active = runCatching {
                    callMethod(profileContextInstance(), "getActiveDevice") as? BluetoothDevice
                }.getOrNull() ?: return@hookAfter
                if (!sameHeadset(reported.address, active.address)) return@hookAfter
                Log.i(MiLinkServiceHook.TAG, "accepting cross-identity active device match")
                result = true
            }
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "hook ProfileImpl.verifyActiveDevice skipped", it)
        }
    }

    /** Whether two addresses are the two bonded identities of one headset, or the same one. */
    private fun sameHeadset(first: String?, second: String?): Boolean {
        if (first == null || second == null) return false
        if (first.equals(second, ignoreCase = true)) return true
        val firstControl = controlAddressOf(first) ?: first
        val secondControl = controlAddressOf(second) ?: second
        return firstControl.equals(secondControl, ignoreCase = true)
    }

    private fun profileContextInstance(): Any? = runCatching {
        hook.findClass(PROFILE_CONTEXT).getDeclaredField("INSTANCE")
            .apply { isAccessible = true }
            .get(null)
    }.getOrNull()

    /**
     * The classic identity for [address], or null when [address] is not a Sony LE identity.
     *
     * The alias map is process-local and nothing else in the MiLink process fills it, so an
     * unknown address triggers a rescan of the bonded set. The rescan is throttled because
     * these hooks sit on paths MiLink walks for every device on every refresh.
     */
    private fun controlAddressOf(address: String?): String? {
        if (address.isNullOrEmpty()) return null
        knownControlAddressOf(address)?.let { return it }
        if (!refreshAliases()) return null
        return knownControlAddressOf(address)
    }

    private fun knownControlAddressOf(address: String): String? =
        SonyDeviceService.resolveControlAddress(address)
            ?.takeIf { !it.equals(address, ignoreCase = true) }

    /** Returns false when the scan was throttled or found nothing to pair up. */
    private fun refreshAliases(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAliasScan < ALIAS_SCAN_INTERVAL_MS) return false
        lastAliasScan = now
        val bonded = bondedDevices()
        if (bonded.isEmpty()) return false
        SonyDeviceService.linkLeAudioIdentities(bonded)
        return true
    }

    /**
     * ProfileImpl.disconnect refuses outright with OpNotSupport while an LE device is the
     * active audio device, which is the single reason a transfer away from this phone fails
     * under LC3 and works otherwise. isLeDeviceActive() has exactly one caller — that veto
     * — so answering false for a Sony headset lifts the veto and changes nothing else. The
     * disconnect that follows still runs against the connected BluetoothDevice, so alias
     * knowledge is deliberately not required here.
     */
    private fun hookLeDeviceActiveVeto() {
        runCatching {
            hook.hookAfter(
                hook.findMethod(PROFILE_CONTEXT, "isLeDeviceActive"),
                logicalRole = "profile-context-le-active-veto",
            ) {
                if (result != true) return@hookAfter
                val active = runCatching {
                    callMethod(instance, "getActiveDevice") as? BluetoothDevice
                }.getOrNull()
                if (active == null) {
                    Log.d(MiLinkServiceHook.TAG, "LE veto kept: no active device")
                    return@hookAfter
                }
                if (!hook.isSonyPod(active)) {
                    Log.d(MiLinkServiceHook.TAG, "LE veto kept: active device not Sony")
                    return@hookAfter
                }
                Log.i(MiLinkServiceHook.TAG, "lifting LE circulate veto")
                result = false
            }
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "hook ProfileContext.isLeDeviceActive skipped", it)
        }
    }

    /**
     * A circulate release must disconnect the headset exactly the way the system's own
     * "断开连接" button does. The system path (Settings → CachedBluetoothDevice.disconnect)
     * issues `BluetoothDevice.disconnect()` for **every CSIP group member** — the LE
     * identity first, the classic one ~18ms later, both nearly in parallel — which
     * HyperOS routes to the same `disconnectAllEnabledProfiles` binder entry. The
     * headset perceives LE-then-classic as "fully leaving" and enters its reconnect
     * phase cleanly.
     *
     * milink instead disconnects only the classic address, leaving the LE link alive
     * while classic drops; the headset then treats the classic loss as one-sided,
     * and its reconnect attempt collides with the target host's incoming connect —
     * the drop observed in the failing transfer (headset killed the target's HFP
     * 187ms after SLC and paged this phone instead).
     *
     * This hook runs *before* milink's classic disconnect and issues the very same
     * `BluetoothDevice.disconnect()` call the Settings button makes, against the
     * headset's other identity — aligning order (LE first), coverage (both
     * identities) and API (identical entry) with the system.
     */
    private fun hookSystemAlignedDisconnect() {
        runCatching {
            hook.hookBefore(
                hook.findMethod(PROFILE_CONTEXT, "disconnect", BluetoothDevice::class.java),
                logicalRole = "profile-context-system-aligned-disconnect",
            ) {
                if (releasing.get() == true) return@hookBefore
                val device = args.getOrNull(0) as? BluetoothDevice ?: return@hookBefore
                if (!hook.isSonyPod(device)) return@hookBefore
                val address = runCatching { device.address }.getOrNull() ?: return@hookBefore
                val alternate = alternateIdentityFresh(address) ?: return@hookBefore
                if (alternate.equals(address, ignoreCase = true)) return@hookBefore
                val context: Context = hook.context ?: return@hookBefore
                val alternateDevice = runCatching {
                    context.getSystemService(BluetoothManager::class.java)?.adapter
                        ?.getRemoteDevice(alternate)
                }.getOrNull() ?: return@hookBefore
                releasing.set(true)
                val invoked = try {
                    runCatching {
                        BluetoothDevice::class.java.getMethod("disconnect").invoke(alternateDevice)
                        true
                    }.getOrDefault(false)
                } finally {
                    releasing.set(false)
                }
                if (invoked) {
                    Log.i(
                        MiLinkServiceHook.TAG,
                        "system-aligned disconnect: LE identity $alternate released before classic",
                    )
                }
            }
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "hook ProfileContext.disconnect system-aligned skipped", it)
        }
    }

    private fun bondedDevices(): Collection<BluetoothDevice> = runCatching {
        val context: Context = hook.context ?: return emptyList()
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * The pre-flight "is the headset bonded on the target host" probe compares a single
     * published address against the target's bond list, so under LE Audio it answers
     * BondNone for a target that holds the headset's other identity and the transfer dies
     * with HostNotBound before anything runs. Skip that gate: whether the target can take
     * the headset is proven by the connect attempt itself, whose identity refusal is caught
     * by [hookCrossIdentityConnectRetry].
     */
    private fun hookHostBoundProbe() {
        runCatching {
            hook.hookAfter(
                hook.findMethodByParamCount(MULTIPLATFORM_PROCESSOR, "hostBoundCheck", 2),
                logicalRole = "multiplatform-host-bound-probe",
            ) {
                val code = result as? Int ?: return@hookAfter
                if (code == HOST_NOT_BOUND) {
                    Log.i(MiLinkServiceHook.TAG, "hostBoundCheck HostNotBound waived for circulate")
                    result = SUCCESS
                }
            }
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "hook MultiplatformProcessor.hostBoundCheck skipped", it)
        }
    }

    /**
     * BluetoothServiceClient.updateActiveBt() remembers the LE Audio active device in its own
     * field and refreshBluetoothDevice() then reports that device unconditionally, skipping
     * the duplicate check the classic bond has to pass. That is the second ball in the fusion
     * device center. Collapsing the field onto the classic identity means the LE device is
     * never reported, the classic address is recognised as active again, and the existing
     * reconciliation pass removes a ball previously published for the LE address.
     */
    private fun hookBluetoothActiveDevice() {
        runCatching {
            hook.hookAfter(
                hook.findMethod(BLUETOOTH_SERVICE_CLIENT, "updateActiveBt"),
                logicalRole = "bluetooth-client-active-identity",
            ) {
                val leDevice = runCatching {
                    getObjectField(instance, "mLeadAudioActiveDevice") as? BluetoothDevice
                }.getOrNull() ?: return@hookAfter
                if (!hook.isSonyPod(leDevice)) return@hookAfter
                val leAddress = runCatching { leDevice.address }.getOrNull() ?: return@hookAfter
                val control = controlAddressOf(leAddress) ?: return@hookAfter
                runCatching {
                    setObjectField(instance, "mLeadAudioActiveDevice", null)
                    setObjectField(instance, "mCurrentBtAddress", control)
                }.onSuccess {
                    Log.i(MiLinkServiceHook.TAG, "collapsed LE audio device onto control identity")
                }.onFailure {
                    Log.w(MiLinkServiceHook.TAG, "failed to collapse LE audio device", it)
                }
            }
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "hook BluetoothServiceClient.updateActiveBt skipped", it)
        }
    }

    /** The headset's other bonded identity, rescanning once if the alias map is cold. */
    private fun alternateIdentityOf(address: String): String? {
        SonyDeviceService.identityAliasesOf(address).firstOrNull()?.let { return it }
        if (!refreshAliases()) return null
        return SonyDeviceService.identityAliasesOf(address).firstOrNull()
    }

    /**
     * Same lookup, but bypassing the alias-scan throttle: a circulate disconnect is
     * rare and may run on a host whose alias cache is cold (the pad target never
     * held a Tandem session to warm it), so the bond set is scanned right now.
     */
    private fun alternateIdentityFresh(address: String): String? {
        lastAliasScan = 0L
        refreshAliases()
        return SonyDeviceService.identityAliasesOf(address).firstOrNull()
    }

    /**
     * The source-side connect of a transfer, at the proxy boundary before the RPC is
     * encoded. Under LC3 the address MiLink publishes is the headset's LE identity, but
     * that address is useless on the wire: it is non-discoverable, so a target host that
     * has not bonded it can only burn its bond timeout on a doomed createBond (the
     * removed failure-retry hook failed for exactly this reason). The classic address is
     * the one every flow understands — already-bonded targets connect directly, unbonded
     * ones enter the normal pairing flow, and a target that enables LE Audio upgrades the
     * link to LC3 by itself after connecting over classic.
     *
     * The rewrite is unconditional for Sony LE identities: whether the target knows the
     * classic bond is the target's business, decided after the request arrives, and can
     * only be guessed at here. The alias map is authoritative on the source side — fed by
     * the engine snapshot while the headset is connected here, so at the moment of a
     * transfer it is necessarily warm.
     */
    private fun hookConnectClassicAddress() {
        runCatching {
            val proxyConnect = hook.findMethodByParamCount(PROFILE_PROXY, "connect", 3)
            hook.hookBefore(proxyConnect, logicalRole = "profile-proxy-connect-classic-address") {
                if (rewriting.get() == true) return@hookBefore
                val address = args.getOrNull(1) as? String ?: return@hookBefore
                if (!hook.isSonyAddress(address)) return@hookBefore
                val control = controlAddressOf(address) ?: return@hookBefore
                if (control.equals(address, ignoreCase = true)) return@hookBefore
                rewriting.set(true)
                val connected = try {
                    runCatching { proxyConnect.invoke(instance, args[0], control, args[2]) as? Int }
                        .getOrNull()
                } finally {
                    rewriting.set(false)
                }
                if (connected != null) {
                    Log.i(
                        MiLinkServiceHook.TAG,
                        "circulate connect rewritten LE→classic=$control result=$connected",
                    )
                    result = connected
                }
            }
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "hook ProfileProxy.connect classic rewrite skipped", it)
        }
    }

    private companion object {
        const val PROFILE_CONTEXT = "com.miui.headset.runtime.ProfileContext"
        const val PROFILE_IMPL = "com.miui.headset.runtime.ProfileImpl"
        const val PROFILE_PROXY = "com.miui.headset.runtime.ProfileProxy"
        const val MULTIPLATFORM_PROCESSOR = "com.miui.headset.runtime.MultiplatformProcessor"
        const val DISCOVERY_IMPL = "com.miui.headset.runtime.DiscoveryImpl"
        const val BLUETOOTH_SERVICE_CLIENT =
            "com.miui.circulate.api.protocol.bluetooth.BluetoothServiceClient"
        const val ALIAS_SCAN_INTERVAL_MS = 10_000L
        const val SUCCESS = 100
        const val HOST_NOT_BOUND = 215

        /** CirculateConstants.DeviceType.HEADSET — the card that carries ANC and battery. */
        const val HEADSET_TYPE = "headset"

        /** BluetoothServiceClient's lazy MxBluetoothManager holder; null until the SDK binds. */
        const val MX_MANAGER = "com.xiaomi.mxbluetoothsdk.manager.MxBluetoothManager"
        const val MX_MANAGER_FIELD = "mxBluetoothManager"

        /** DiscoveryHost's headset-lost update type; the LE stack's own broadcast is unreliable. */
        const val HOST_LOST_UPDATE_TYPE = 3
    }
}
