package dev.sonypods.hook.milink

import dev.sonypods.hook.Log

/**
 * Remote-protocol layer for the MiLink circulate flow. The device that physically
 * holds the Sony headset runs the protocol server (RemoteProtocol$Stub), and answers
 * getHeadsetProperty / updateHeadsetMode requests from the circulating client.
 *
 * Both methods are intercepted at the server entry (HeadsetRemoteImpl):
 *  - getHeadsetProperty: refresh the complete local HeadsetInfo and reply 100.
 *  - updateHeadsetMode: apply the ANC change locally, refresh the complete local
 *    HeadsetInfo, and reply 100.
 *
 * The refresh goes through DiscoveryImpl.assembleHeadsetInfo() so volume and other
 * fields are not replaced by a hand-built partial HeadsetInfo.
 * Any failure is only logged; the official implementation is left untouched.
 */
internal class MiLinkRemoteProtocolHook(private val hook: MiLinkServiceHook) {
    private val statusSuccess = 100

    fun hookRemoteProtocol() {
        hookGetHeadsetProperty()
        hookUpdateHeadsetMode()
    }

    private fun hookGetHeadsetProperty() {
        runCatching {
            hook.hookBefore(
                hook.findMethod(
                    "com.miui.headset.runtime.HeadsetRemoteImpl",
                    "getHeadsetProperty",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                ),
                logicalRole = "remote-get-headset-property",
            ) {
                val address = args[1] as? String ?: return@hookBefore
                val deviceId = args[2] as? String ?: return@hookBefore
                if (!isSonyRequest(address, deviceId)) return@hookBefore
                hook.pushStateToPanel()
                Log.i(MiLinkServiceHook.TAG, "remote getHeadsetProperty answered 100 address=$address")
                this.result = statusSuccess
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook HeadsetRemoteImpl.getHeadsetProperty skipped", it) }
    }

    private fun hookUpdateHeadsetMode() {
        runCatching {
            hook.hookBefore(
                hook.findMethod(
                    "com.miui.headset.runtime.HeadsetRemoteImpl",
                    "updateHeadsetMode",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                ),
                logicalRole = "remote-update-headset-mode",
            ) {
                val address = args[1] as? String ?: return@hookBefore
                val deviceId = args[2] as? String ?: return@hookBefore
                if (!isSonyRequest(address, deviceId)) return@hookBefore
                val mode = args[3] as? Int ?: return@hookBefore
                hook.applyRemoteAncMode(mode)
                hook.pushStateToPanel()
                Log.i(MiLinkServiceHook.TAG, "remote updateHeadsetMode applied anc=$mode address=$address")
                this.result = statusSuccess
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook HeadsetRemoteImpl.updateHeadsetMode skipped", it) }
    }

    private fun isSonyRequest(address: String, deviceId: String): Boolean {
        if (hook.isSonyAddress(address)) return true
        return deviceId == hook.fakeDeviceId()
    }
}
