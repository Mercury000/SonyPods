package dev.sonypods.hook

import android.annotation.SuppressLint
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
 * The beacon is identified by the one thing that is its own: the `LE_` name prefix. No real
 * endpoint of this headset advertises it — not the classic control identity, not the LE Audio
 * identity — so requiring it is what keeps this away from every other bond.
 *
 * It deliberately does *not* judge by address bits. Resolvable private addresses do carry `01`
 * in the top two bits of the first octet, but so does any public MAC whose OUI happens to land
 * there — Sony's own 58:18:62 does. Testing the bits classified a bonded WH-1000XM6's classic
 * address as a throwaway beacon, and these callbacks are the ones classic SSP pairing arrives
 * on too, so with the switch on an incoming pairing of that headset would have been swallowed
 * with no dialog and no notification to say why.
 *
 * Nor does it lean on `bondingInitiatedLocally`. That flag was the guard that actually held the
 * old condition set together, and it is read reflectively out of `RemoteDevices` — on a ROM
 * where that read fails it degrades to "not locally initiated", i.e. the load-bearing guard is
 * the first thing to disappear. An exact positive identification needs no such guard.
 *
 * Two conditions remain, both read in-process from the stack's own device properties:
 *  - the device is not bonded. A bonded address raising SSP is a re-pair, never a beacon.
 *  - the name carries the `LE_` prefix and resolves to this headset.
 *
 * A name the stack has not resolved yet fails the second one and the request is kept. That is
 * the cheap direction to be wrong in: one stray dialog, which is the state this existed to
 * improve on, versus a pairing that fails in silence.
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
                val keep = keepReason(instance, bytes)
                if (keep != null) {
                    if (keep != REASON_DISABLED) Log.d(TAG, "$methodName kept $address: $keep")
                    return@hookBefore
                }
                // A void callback still counts as having a result, and that is what skips the
                // original body: the state machine never queues the request.
                result = null
                Log.d(TAG, "$methodName dropped for identity beacon $address")
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
    private fun keepReason(bondStateMachine: Any?, addressBytes: ByteArray): String? {
        if (!ConfigManager.ignoreRandomLePairingRequests()) return REASON_DISABLED
        val remoteDevices = runCatching { getObjectField(bondStateMachine, "mRemoteDevices") }.getOrNull()
        val device = remoteDevice(remoteDevices, addressBytes) ?: return "no device object"
        val properties = deviceProperties(remoteDevices, device) ?: return "device properties unreadable"
        if (bondStateOf(properties) == BluetoothDevice.BOND_BONDED) return "already bonded"
        val name = nameOf(properties)
        if (!isIdentityBeaconName(name)) return "name is not the identity beacon: $name"
        return null
    }

    /**
     * Whether [name] is the throwaway identity beacon's.
     *
     * The beacon advertises `LE_<model>`; the classic control identity and the LE Audio identity
     * both advertise the bare model name. [SonyDeviceService.isSonyName] cannot be used on its own
     * here because it strips the prefix before matching, which makes the beacon and the real
     * endpoints indistinguishable — it answers "is this headset", and what is needed is "is this
     * the beacon".
     */
    private fun isIdentityBeaconName(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (!normalized.startsWith("le_") && !normalized.startsWith("le-")) return false
        return SonyDeviceService.isSonyName(normalized)
    }

    /**
     * The stack's own device object, obtained the way `sspRequestCallback` itself obtains one.
     *
     * There is deliberately no `BluetoothAdapter` fallback: that path is a Binder round trip out
     * of the JNI callback thread the native stack is blocked on waiting for this callback to
     * return, and it buys nothing — a ROM whose `RemoteDevices` cannot be reached reflectively
     * cannot supply the device properties either, so the request would be kept regardless.
     */
    private fun remoteDevice(remoteDevices: Any?, addressBytes: ByteArray): BluetoothDevice? =
        runCatching { callMethod(remoteDevices, "getDevice", addressBytes) as? BluetoothDevice }.getOrNull()

    /** Carries the name and bond state, both readable without leaving this process. */
    private fun deviceProperties(remoteDevices: Any?, device: BluetoothDevice): Any? =
        runCatching { callMethod(remoteDevices, "getDeviceProperties", device) }.getOrNull()

    private fun bondStateOf(properties: Any?): Int =
        runCatching { callMethod(properties, "getBondState") as? Int }.getOrNull()
            ?: BluetoothDevice.BOND_NONE

    private fun nameOf(properties: Any?): String? =
        runCatching { callMethod(properties, "getName") as? String }.getOrNull()

    /** The callbacks carry the raw address, most significant octet first. */
    private fun formatAddress(bytes: ByteArray): String? {
        if (bytes.size != 6) return null
        return bytes.joinToString(":") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }
    }
}
