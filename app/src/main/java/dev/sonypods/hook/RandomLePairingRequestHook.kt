package dev.sonypods.hook

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import dev.sonypods.config.ConfigManager
import dev.sonypods.device.SonyDeviceService
import java.util.Locale

/**
 * Drops the pairing requests raised by the headset's throwaway LE advertisement.
 *
 * A Sony headset advertises a second LE endpoint whose address is a resolvable private one —
 * `6E:B8:03:13:BB:A7` for a while, a different one after that — carrying Sony's 0xFD2A service
 * data and the name `LE_<model>`. It is an identity beacon for Sony's own SDK: it cannot
 * complete a bond and it never carries audio. Any app that opens a GATT client to it gives the
 * headset a link on which the headset then demands pairing. One observed example reconnects
 * every 60 seconds for as long as it is running.
 *
 * `BondStateMachine` has no reason to doubt the request: its `ignorePairDialog` stores do not
 * list the address, and `isLeAudioDevice` misses because `three_mac_for_ble_f` does not list it
 * either, so `sendPairingRequestIntent` broadcasts ACTION_PAIRING_REQUEST. Settings turns that
 * into a pairing dialog, or — when it may not show one in the foreground — the
 * `BluetoothPairingService` notification. Thirty seconds later the bond times out and the next
 * connect starts the cycle over.
 *
 * Both JNI callbacks are dropped rather than answered. Confirming would let a doomed bond
 * proceed; rejecting would end it as a failure. Returning without queueing the message leaves
 * the request unanswered: no MESSAGE_PAIRING_REQUEST, no broadcast, no UI. The bond still
 * expires on the native timeout, which is exactly what already happened while the notification
 * sat there untouched.
 *
 * Four conditions, all required, keep this away from every other bond — the module's own LE
 * Audio identity bond included:
 *  - the address is a resolvable private address (top two bits `01`). The headset's real LE
 *    Audio identity is a static random address (`11`), so the two can never be confused.
 *  - the name identifies this headset, `LE_` prefix included.
 *  - the device is not bonded.
 *  - the stack did not start the bond itself. `AdapterService.createBond` sets
 *    `bondingInitiatedLocally`, so anything the module or the user initiated is exempt whatever
 *    else holds.
 *
 * Off by default: silently swallowing a system pairing prompt is not something to do behind the
 * user's back.
 */
@SuppressLint("MissingPermission")
object RandomLePairingRequestHook : HookContext() {
    private const val TAG = "SonyPods-Engine"
    private const val BOND_STATE_MACHINE = "com.android.bluetooth.btservice.BondStateMachine"
    private const val REASON_DISABLED = "switch off"

    override fun onHook() {
        installFilter("sspRequestCallback", "bond-ssp-request")
        installFilter("pinRequestCallback", "bond-pin-request")
    }

    /**
     * Both callbacks take five parameters and lead to the same `sendPairingRequestIntent`, so
     * both have to be filtered — the SSP one carries consent and passkey variants, the PIN one
     * everything that would open a keypad.
     */
    private fun installFilter(methodName: String, logicalRole: String) {
        runCatching {
            hookBefore(
                findMethodByParamCount(BOND_STATE_MACHINE, methodName, 5),
                logicalRole = logicalRole,
            ) {
                val bytes = args.firstOrNull() as? ByteArray ?: return@hookBefore
                val address = formatAddress(bytes) ?: return@hookBefore
                val keep = keepReason(instance, bytes, address)
                if (keep != null) {
                    if (keep != REASON_DISABLED) Log.d(TAG, "$methodName kept $address: $keep")
                    return@hookBefore
                }
                // A void callback still counts as having a result, and that is what skips the
                // original body: the state machine never queues the request.
                result = null
                Log.d(TAG, "$methodName dropped for rogue LE address $address")
            }
            Log.d(TAG, "$methodName pairing filter installed")
        }.onFailure {
            Log.w(TAG, "$methodName pairing filter unavailable", it)
        }
    }

    /**
     * Null when the request is to be dropped, otherwise the condition that spared it.
     *
     * Reported rather than reduced to a boolean because these callbacks only fire on an actual
     * pairing attempt: one line per decision costs nothing and is the difference between seeing
     * which condition vetoed a request and having to guess.
     *
     * Deliberately does not consult [SonyDeviceService.isKnownSonyAddress]. That set is filled
     * by name matching, and this beacon advertises the headset's own name — so the engine's ACL
     * tracker adds the beacon's address to it on the very first connection, after which the
     * check would veto every request it was meant to catch. The conditions below identify a real
     * endpoint without it.
     */
    private fun keepReason(bondStateMachine: Any?, addressBytes: ByteArray, address: String): String? {
        if (!ConfigManager.ignoreRandomLePairingRequests()) return REASON_DISABLED
        if (!isResolvablePrivateAddress(address)) return "address is not a resolvable private one"
        if (SonyDeviceService.identityAliasesOf(address).isNotEmpty()) return "known paired identity"
        val remoteDevices = runCatching { getObjectField(bondStateMachine, "mRemoteDevices") }.getOrNull()
        val device = remoteDevice(remoteDevices, addressBytes, address) ?: return "no device object"
        val properties = deviceProperties(remoteDevices, device)
        if (bondStateOf(properties, device) == BluetoothDevice.BOND_BONDED) return "already bonded"
        if (isBondingInitiatedLocally(properties)) return "bonding initiated locally"
        val name = nameOf(properties, device)
        if (!SonyDeviceService.isSonyName(name)) return "name is not this headset: $name"
        return null
    }

    /**
     * Resolvable private addresses carry `01` in the top two bits of the first octet; a static
     * random address — which is what the headset uses for the LE Audio identity the module
     * bonds — carries `11`.
     */
    private fun isResolvablePrivateAddress(address: String): Boolean {
        val topOctet = address.substringBefore(':').toIntOrNull(16) ?: return false
        return (topOctet ushr 6) == 0b01
    }

    /**
     * The stack's own device object, obtained the way `sspRequestCallback` itself obtains one.
     *
     * Going through `BluetoothAdapter` instead means a Binder round trip out of the JNI callback
     * thread the native stack is blocked on waiting for this callback to return; that path stays
     * only as a fallback for a ROM whose `RemoteDevices` cannot be reached reflectively.
     */
    private fun remoteDevice(remoteDevices: Any?, addressBytes: ByteArray, address: String): BluetoothDevice? =
        runCatching { callMethod(remoteDevices, "getDevice", addressBytes) as? BluetoothDevice }.getOrNull()
            ?: runCatching { BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address) }.getOrNull()

    /** Carries the name, bond state and initiator, all readable without leaving this process. */
    private fun deviceProperties(remoteDevices: Any?, device: BluetoothDevice): Any? =
        runCatching { callMethod(remoteDevices, "getDeviceProperties", device) }.getOrNull()

    private fun bondStateOf(properties: Any?, device: BluetoothDevice): Int =
        runCatching { callMethod(properties, "getBondState") as? Int }.getOrNull()
            ?: runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)

    /**
     * Who started this bond. `AdapterService.createBond` sets it, so the module's own LE Audio
     * identity bond — and anything the user started — is exempt whatever else holds. Unreadable
     * on a ROM whose `RemoteDevices` differs, in which case the remaining conditions carry the
     * decision alone.
     */
    private fun isBondingInitiatedLocally(properties: Any?): Boolean =
        runCatching { callMethod(properties, "isBondingInitiatedLocally") as? Boolean }.getOrNull()
            ?: false

    private fun nameOf(properties: Any?, device: BluetoothDevice): String? =
        runCatching { callMethod(properties, "getName") as? String }.getOrNull()
            ?: runCatching { device.name }.getOrNull()
            ?: runCatching { device.alias }.getOrNull()

    /** The callbacks carry the raw address, most significant octet first. */
    private fun formatAddress(bytes: ByteArray): String? {
        if (bytes.size != 6) return null
        return bytes.joinToString(":") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }
    }
}
