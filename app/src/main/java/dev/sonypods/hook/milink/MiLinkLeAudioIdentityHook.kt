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
 *  - a target connect refused with a bond-class code is resent once against the headset's
 *    other bonded identity at the remote-protocol wire boundary.
 *
 * Identity pairing comes from [SonyDeviceService], classified by transport type or Sony
 * service UUIDs; the duplicate fusion-center ball is fixed separately by collapsing the LE
 * Audio active device in BluetoothServiceClient.
 */
@SuppressLint("MissingPermission")
internal class MiLinkLeAudioIdentityHook(private val hook: MiLinkServiceHook) {

    /** Guards the cross-identity retry against re-entering its own hook. */
    private val retrying = ThreadLocal.withInitial { false }

    /** Guards the second-identity release against re-entering its own hook. */
    private val releasing = ThreadLocal.withInitial { false }

    @Volatile
    private var lastAliasScan = 0L

    fun hookIdentityUnification() {
        hookLeDeviceActiveVeto()
        hookReleaseBothIdentities()
        hookActiveDeviceVerification()
        hookHostBoundProbe()
        hookBluetoothActiveDevice()
        hookProfileImplCache()
        hookDiscoveryLostSignal()
        hookCrossIdentityConnectRetry()
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
     * A circulate release must free the headset completely. ProfileContext.disconnect tears
     * down every profile of the one address it is given; the headset's other identity keeps
     * its own link, so from the headset's point of view it never left this phone and it
     * ignores (or races) the target host's connect — the transfer stalls into a timeout
     * while the audio snaps back here. Release the second identity right after the first.
     */
    private fun hookReleaseBothIdentities() {
        runCatching {
            hook.hookAfter(
                hook.findMethod(PROFILE_CONTEXT, "disconnect", BluetoothDevice::class.java),
                logicalRole = "profile-context-release-both-identities",
            ) {
                if (result != SUCCESS || releasing.get() == true) return@hookAfter
                val device = args.getOrNull(0) as? BluetoothDevice ?: return@hookAfter
                if (!hook.isSonyPod(device)) return@hookAfter
                val address = runCatching { device.address }.getOrNull() ?: return@hookAfter
                val alternate = alternateIdentityOf(address) ?: return@hookAfter
                if (alternate.equals(address, ignoreCase = true)) return@hookAfter
                val context = profileContextInstance() ?: return@hookAfter
                val alternateDevice = runCatching {
                    callMethod(context, "obtainBluetoothDevice", alternate) as? BluetoothDevice
                }.getOrNull() ?: return@hookAfter
                releasing.set(true)
                val released = try {
                    callMethod(context, "disconnect", alternateDevice) as? Int
                } finally {
                    releasing.set(false)
                }
                Log.i(MiLinkServiceHook.TAG, "released second identity=$alternate result=$released")
            }
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "hook ProfileContext.disconnect skipped", it)
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
     * The target-side connect of a transfer, at the remote-protocol boundary where the
     * address actually goes on the wire. A host bonded to only the headset's other identity
     * refuses with a bond-class code; resend once against that other identity before the
     * caller starts rolling the headset back, and let the failure stand only once both
     * identities have been refused.
     */
    private fun hookCrossIdentityConnectRetry() {
        runCatching {
            val remoteConnect = hook.findMethodByParamCount(HEADSET_REMOTE_IMPL, "connect", 3)
            hook.hookAfter(remoteConnect, logicalRole = "headset-remote-cross-identity-connect") {
                val code = result as? Int ?: return@hookAfter
                if (retrying.get() == true || code !in CROSS_IDENTITY_RETRY_CODES) return@hookAfter
                val address = args.getOrNull(1) as? String ?: return@hookAfter
                if (!hook.isSonyAddress(address)) {
                    Log.d(MiLinkServiceHook.TAG, "retry skipped: address not Sony $code")
                    return@hookAfter
                }
                val alternate = alternateIdentityOf(address)
                if (alternate == null) {
                    Log.w(MiLinkServiceHook.TAG, "retry skipped: no alternate identity for $code")
                    return@hookAfter
                }
                retrying.set(true)
                val retried = try {
                    remoteConnect.invoke(instance, args[0], alternate, args[2]) as? Int
                } catch (error: Throwable) {
                    Log.w(MiLinkServiceHook.TAG, "cross-identity circulate retry threw", error)
                    null
                } finally {
                    retrying.set(false)
                }
                Log.i(
                    MiLinkServiceHook.TAG,
                    "cross-identity circulate retry primary=$code alternate=$retried",
                )
                if (retried != null && retried !in CROSS_IDENTITY_RETRY_CODES) {
                    result = retried
                }
            }
        }.onFailure {
            Log.d(MiLinkServiceHook.TAG, "hook HeadsetRemoteImpl.connect skipped", it)
        }
    }

    private companion object {
        const val PROFILE_CONTEXT = "com.miui.headset.runtime.ProfileContext"
        const val PROFILE_IMPL = "com.miui.headset.runtime.ProfileImpl"
        const val MULTIPLATFORM_PROCESSOR = "com.miui.headset.runtime.MultiplatformProcessor"
        const val DISCOVERY_IMPL = "com.miui.headset.runtime.DiscoveryImpl"
        const val HEADSET_REMOTE_IMPL = "com.miui.headset.runtime.HeadsetRemoteImpl"
        const val BLUETOOTH_SERVICE_CLIENT =
            "com.miui.circulate.api.protocol.bluetooth.BluetoothServiceClient"
        const val ALIAS_SCAN_INTERVAL_MS = 10_000L
        const val SUCCESS = 100
        const val HOST_NOT_BOUND = 215

        /** DiscoveryHost's headset-lost update type; the LE stack's own broadcast is unreliable. */
        const val HOST_LOST_UPDATE_TYPE = 3

        /**
         * ProfileImpl.connect outcomes that mean "this host cannot act on the address it was
         * given": the device was not obtainable, the operation was declared unsupported, or
         * the bond needed to be created and could not be. Each is what a host bonded to only
         * the other identity of the headset reports, so each is worth one cross-identity
         * retry. Codes such as 308 (a bond dialog is showing) or 301 (already active here)
         * describe progress and must not be retried.
         */
        val CROSS_IDENTITY_RETRY_CODES = setOf(201, 205, 302, 304, 305, 307)
    }
}
