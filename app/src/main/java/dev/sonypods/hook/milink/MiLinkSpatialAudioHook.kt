package dev.sonypods.hook.milink

import android.bluetooth.BluetoothDevice
import android.os.Bundle
import dev.sonypods.config.ConfigManager
import dev.sonypods.hook.Log
import dev.sonypods.hook.callMethod
import dev.sonypods.hook.getObjectField
import dev.sonypods.hook.setObjectField

internal class MiLinkSpatialAudioHook(private val hook: MiLinkServiceHook) {
    fun hookMxBluetoothRuntime(classes: List<String>) {
        classes.forEach { className ->
            hook.hookBluetoothDeviceResult(className, "getSpatialMode") { hook.miLinkSpatialMode() }
            hookSpatialCommand(className, "setSpatialMode")
        }
    }

    fun hookHeadsetRuntimeDisplay() {
        hook.hookBluetoothDeviceResult("com.miui.headset.runtime.AncBatteryController", "getMiAudioEffect") {
            hook.miLinkSpatialMode()
        }
        hookSpatialStateBlock()
        hookDeviceSpatialTypeModel()
        hookSpatialCallbacks()
        hookProfileSpatialEffect()
        hookProfileAudioEffectState()
        hook.hookHeadsetInfoNoArg("getAudioEffectState") { hook.miLinkAudioEffectState() }
        hook.hookHeadsetInfoNoArg("component10") { hook.miLinkAudioEffectState() }
    }

    fun hookCirculateHeadsetServiceInfo() {
        runCatching {
            hook.hookAfter(hook.findMethod("com.miui.circulate.api.service.CirculateServiceInfo", "setHeadsetId", String::class.java, Int::class.javaPrimitiveType!!)) {
                val headsetId = args[0] as? String ?: return@hookAfter
                val address = getObjectField(instance, "deviceId") as? String ?: return@hookAfter
                if (!hook.isSonyAddress(address) && address != hook.currentAddress && headsetId != hook.fakeDeviceId()) return@hookAfter
                val serviceProperties = getObjectField(instance, "serviceProperties")
                val bundle = callMethod(serviceProperties, "getAll") as? Bundle ?: return@hookAfter
                // Keep the fusion-center controls (ANC / volume) enabled for Sony devices.
                bundle.putInt("headset_switch_state", 1)
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook CirculateServiceInfo.setHeadsetId skipped", it) }
    }

    private fun hookSpatialCommand(className: String, methodName: String) {
        runCatching {
            hook.hookBefore(hook.findMethod(className, methodName, BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!hook.isSonyPod(device)) return@hookBefore
                hook.cacheRuntimeOwner(className, instance)
                hook.captureRuntimeContext(instance)
                val miLinkMode = args[1] as? Int ?: return@hookBefore
                val mode = hook.spatialModeFromMiLink(miLinkMode)
                hook.updateSpatialAudioMode(mode)
                hook.notifySpatialUiChanged(instance, device, mode)
                this.result = 1
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook $className.$methodName spatial command skipped", it) }
    }

    private fun hookSpatialStateBlock() {
        runCatching {
            hook.hookBefore(hook.findMethod("com.miui.headset.runtime.AncBatteryController", "setMiAudioEffect", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!hook.isSonyPod(device)) return@hookBefore
                hook.lastAncBatteryController = instance
                hook.captureRuntimeContext(instance)
                val mode = hook.spatialModeFromMiLink(args[1] as? Int ?: return@hookBefore)
                hook.updateSpatialAudioMode(mode)
                hook.notifySpatialUiChanged(instance, device, mode)
                this.result = null
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook AncBatteryController.setMiAudioEffect skipped", it) }

        runCatching {
            hook.hookBefore(hook.findMethod("com.miui.headset.runtime.AncBatteryController", "setHeadTracking", BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!hook.isSonyPod(device)) return@hookBefore
                hook.lastAncBatteryController = instance
                hook.captureRuntimeContext(instance)
                hook.updateSpatialAudioMode(ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
                hook.notifySpatialUiChanged(instance, device, hook.currentSpatialAudioMode)
                this.result = 100
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook AncBatteryController.setHeadTracking skipped", it) }
    }

    private fun hookDeviceSpatialTypeModel() {
        runCatching {
            hook.hookAfter(hook.findMethodByParamCount("com.miui.headset.runtime.AncBatteryModel", "getDeviceSpatialType", 0)) {
                if (!hook.isTargetAncBatteryModel(instance)) return@hookAfter
                this.result = hook.miLinkDeviceSpatialType()
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook AncBatteryModel.getDeviceSpatialType skipped", it) }

        runCatching {
            hook.hookAfter(hook.findMethod("com.miui.headset.runtime.AncBatteryModel", "setDeviceSpatialType", Int::class.javaPrimitiveType!!)) {
                if (!hook.isTargetAncBatteryModel(instance)) return@hookAfter
                setObjectField(instance, "deviceSpatialType", hook.miLinkDeviceSpatialType())
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook AncBatteryModel.setDeviceSpatialType skipped", it) }
    }

    private fun hookSpatialCallbacks() {
        runCatching {
            hook.hookBefore(hook.findMethod("com.miui.headset.runtime.AncBatteryController\$mmaCallback\$1", "onDeviceSpatialType", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!hook.isSonyPod(device)) return@hookBefore
                hook.notifySpatialUiChanged(instance, device, hook.currentSpatialAudioMode)
                this.result = null
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook mmaCallback.onDeviceSpatialType skipped", it) }

        runCatching {
            hook.hookBefore(hook.findMethod("com.miui.headset.runtime.AncBatteryController\$mmaCallback\$1", "onReportSpatialState", BluetoothDevice::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!hook.isSonyPod(device)) return@hookBefore
                val mode = hook.currentSpatialAudioMode.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
                hook.notifySpatialUiChanged(instance, device, mode)
                this.result = null
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook mmaCallback.onReportSpatialState skipped", it) }
    }

    private fun hookProfileSpatialEffect() {
        runCatching {
            hook.hookAfter(hook.findMethod("com.miui.headset.runtime.ProfileContext", "getAudioSpatialEffectState", BluetoothDevice::class.java)) {
                val device = args[0] as? BluetoothDevice ?: return@hookAfter
                if (!hook.isSonyPod(device)) return@hookAfter
                hook.lastProfileContext = instance
                hook.captureRuntimeContext(instance)
                this.result = hook.miLinkSpatialMode()
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook ProfileContext.getAudioSpatialEffectState skipped", it) }
    }

    private fun hookProfileAudioEffectState() {
        runCatching {
            hook.hookBefore(hook.findMethod("com.miui.headset.runtime.ProfileContext", "setAudioEffectState", BluetoothDevice::class.java, String::class.java, Int::class.javaPrimitiveType!!)) {
                val device = args[0] as? BluetoothDevice ?: return@hookBefore
                if (!hook.isSonyPod(device)) return@hookBefore
                hook.lastProfileContext = instance
                hook.captureRuntimeContext(instance)
                val state = args[2] as? Int ?: return@hookBefore
                val mode = state.coerceIn(ConfigManager.SPATIAL_AUDIO_OFF, ConfigManager.SPATIAL_AUDIO_HEAD_TRACKING)
                hook.updateSpatialAudioMode(mode)
                hook.notifySpatialUiChanged(instance, device, mode)
                this.result = null
            }
        }.onFailure { Log.d(MiLinkServiceHook.TAG, "hook ProfileContext.setAudioEffectState skipped", it) }
    }
}
