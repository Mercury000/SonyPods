package dev.sonypods.hook.milink

import dev.sonypods.hook.symbols.FixedSymbolBundleDefinition
import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolReference
import org.luckypray.dexkit.wrap.DexMethod

/**
 * Stable MiLink ABI symbols whose names and exact signatures are part of a public/interface contract.
 * These intentionally bypass DexKit: cache misses and target-version checks cannot improve a fixed ABI.
 */
internal object MiLinkStableSymbols : FixedSymbolBundleDefinition {
    override val id = "milink-stable-api"
    override val schemaVersion = 1

    override val requiredSymbols = setOf(
        "headsetInfoConstructor",
        "headsetInfoWriteToParcel",
        "headsetInfoGetDeviceId",
        "headsetInfoComponent3",
        "headsetInfoGetPowers",
        "headsetInfoComponent4",
        "headsetInfoGetMode",
        "headsetInfoComponent5",
        "headsetInfoGetSwitchState",
        "headsetInfoComponent8",
        "headsetInfoGetAudioEffectState",
        "headsetInfoComponent10",
        "headsetNotifyMode",
        "headsetNotifyBattery",
        "headsetNotifyVolume",
        "profileConnect",
        "profileContextInstance",
    )

    override val symbols: Map<String, SymbolReference> = mapOf(
        "headsetInfoConstructor" to method(
            "Lcom/miui/headset/api/HeadsetInfo;-><init>" +
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;IIIIIII)V",
        ),
        "headsetInfoWriteToParcel" to method(
            "Lcom/miui/headset/api/HeadsetInfo;->writeToParcel(Landroid/os/Parcel;I)V",
        ),
        "headsetInfoGetDeviceId" to method("Lcom/miui/headset/api/HeadsetInfo;->getDeviceId()Ljava/lang/String;"),
        "headsetInfoComponent3" to method("Lcom/miui/headset/api/HeadsetInfo;->component3()Ljava/lang/String;"),
        "headsetInfoGetPowers" to method("Lcom/miui/headset/api/HeadsetInfo;->getPowers()Ljava/util/List;"),
        "headsetInfoComponent4" to method("Lcom/miui/headset/api/HeadsetInfo;->component4()Ljava/util/List;"),
        "headsetInfoGetMode" to method("Lcom/miui/headset/api/HeadsetInfo;->getMode()I"),
        "headsetInfoComponent5" to method("Lcom/miui/headset/api/HeadsetInfo;->component5()I"),
        "headsetInfoGetSwitchState" to method("Lcom/miui/headset/api/HeadsetInfo;->getSwitchState()I"),
        "headsetInfoComponent8" to method("Lcom/miui/headset/api/HeadsetInfo;->component8()I"),
        "headsetInfoGetAudioEffectState" to method("Lcom/miui/headset/api/HeadsetInfo;->getAudioEffectState()I"),
        "headsetInfoComponent10" to method("Lcom/miui/headset/api/HeadsetInfo;->component10()I"),
        "headsetNotifyMode" to method(
            "Lcom/miui/circulate/api/protocol/headset/HeadsetServiceNotify;->" +
                "onBluetoothModeChanged(Lcom/miui/circulate/api/service/CirculateServiceInfo;I)V",
        ),
        "headsetNotifyBattery" to method(
            "Lcom/miui/circulate/api/protocol/headset/HeadsetServiceNotify;->" +
                "onBluetoothBatteryChanged(Lcom/miui/circulate/api/service/CirculateServiceInfo;Ljava/util/List;)V",
        ),
        "headsetNotifyVolume" to method(
            "Lcom/miui/circulate/api/protocol/headset/HeadsetServiceNotify;->" +
                "onBluetoothVolumeChanged(Lcom/miui/circulate/api/service/CirculateServiceInfo;I)V",
        ),
        "profileConnect" to method(
            "Lcom/miui/headset/runtime/ProfileProxy;->" +
                "connect(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",
        ),
        "profileContextInstance" to field(
            "Lcom/miui/headset/runtime/ProfileContext;->INSTANCE:Lcom/miui/headset/runtime/ProfileContext;",
        ),
    )

    override fun validate(symbols: Map<String, SymbolReference>) {
        val parsed = METHOD_SYMBOLS.associateWith { DexMethod(symbols.getValue(it).descriptor) }
        HEADSET_INFO_METHODS.forEach { name ->
            require(parsed.getValue(name).className == HEADSET_INFO) { "$name is not declared by $HEADSET_INFO" }
        }
        require(parsed.getValue("headsetInfoWriteToParcel").paramTypeNames == listOf("android.os.Parcel", "int")) {
            "HeadsetInfo.writeToParcel signature changed"
        }
        HEADSET_INFO_NO_ARG_METHODS.forEach { name ->
            require(parsed.getValue(name).paramTypeNames.isEmpty()) { "$name must remain no-arg" }
        }
        require(parsed.getValue("headsetInfoConstructor").isConstructor) {
            "headsetInfoConstructor is not a constructor"
        }
        require(parsed.getValue("profileConnect").className == PROFILE_PROXY &&
            parsed.getValue("profileConnect").paramTypeNames == listOf("java.lang.String", "java.lang.String", "java.lang.String")
        ) {
            "ProfileProxy.connect signature changed"
        }
        listOf("headsetNotifyMode", "headsetNotifyBattery", "headsetNotifyVolume").forEach { name ->
            require(parsed.getValue(name).className == HEADSET_SERVICE_NOTIFY) {
                "$name is not declared by $HEADSET_SERVICE_NOTIFY"
            }
        }
    }

    private fun method(descriptor: String) = SymbolReference(SymbolKind.METHOD, descriptor)
    private fun field(descriptor: String) = SymbolReference(SymbolKind.FIELD, descriptor)

    private val HEADSET_INFO_METHODS = setOf(
        "headsetInfoConstructor",
        "headsetInfoWriteToParcel",
        "headsetInfoGetDeviceId",
        "headsetInfoComponent3",
        "headsetInfoGetPowers",
        "headsetInfoComponent4",
        "headsetInfoGetMode",
        "headsetInfoComponent5",
        "headsetInfoGetSwitchState",
        "headsetInfoComponent8",
        "headsetInfoGetAudioEffectState",
        "headsetInfoComponent10",
    )
    private val HEADSET_INFO_NO_ARG_METHODS = HEADSET_INFO_METHODS - setOf(
        "headsetInfoConstructor",
        "headsetInfoWriteToParcel",
    )
    private val METHOD_SYMBOLS = requiredSymbols - "profileContextInstance"
    private const val HEADSET_INFO = "com.miui.headset.api.HeadsetInfo"
    private const val HEADSET_SERVICE_NOTIFY = "com.miui.circulate.api.protocol.headset.HeadsetServiceNotify"
    private const val PROFILE_PROXY = "com.miui.headset.runtime.ProfileProxy"
}
