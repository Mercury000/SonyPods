package dev.sonypods.hook

import android.bluetooth.BluetoothDevice
import dev.sonypods.hook.Log
import dev.sonypods.device.SonyDeviceService

/**
 * Lets the headset's dual-mode earbud into LE Audio.
 *
 * `AdapterService.isLeAudioAllowed()` answers whether a device may use LE Audio at all, by
 * looking up its DIS model name in an allow list built from `ro.bluetooth.leaudio.allow_list`
 * plus `persist.bluetooth.leaudio.allow_list_extend`. Neither property is set on HyperOS, so
 * that list is empty and the answer is always no.
 *
 * `PhonePolicy` tolerates an empty list only for an LE-Audio-only address, which is why one
 * earbud works: it bonds as a separate LE identity with no A2DP or HFP. The other earbud
 * reuses the headset's classic address, so it looks dual-mode, needs the allow list, and gets
 * `isLeAudioProfileAllowed = false` — observable as its VCP, BASS and BATTERY policies being
 * set to FORBIDDEN the moment its UUIDs are discovered. The group then keeps one empty CIS and
 * that ear stays silent.
 *
 * Scoped to addresses the module has already confirmed as this headset, so the stack's own
 * verdict still stands for every other device.
 */
object LeAudioAllowListHook : HookContext() {
    private const val TAG = "SonyPods-Engine"
    private const val ADAPTER_SERVICE = "com.android.bluetooth.btservice.AdapterService"

    override fun onHook() {
        runCatching {
            hookBefore(
                findMethod(ADAPTER_SERVICE, "isLeAudioAllowed", BluetoothDevice::class.java),
                logicalRole = "adapter-is-le-audio-allowed",
            ) {
                val device = args.firstOrNull() as? BluetoothDevice ?: return@hookBefore
                val address = runCatching { device.address }.getOrNull() ?: return@hookBefore
                if (!SonyDeviceService.isKnownSonyAddress(address)) return@hookBefore
                result = true
            }
            Log.i(TAG, "isLeAudioAllowed hook installed")
        }.onFailure {
            Log.w(TAG, "isLeAudioAllowed hook unavailable", it)
        }
    }
}
