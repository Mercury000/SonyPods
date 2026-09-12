package dev.sonypods.hook.milink

import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolReference
import org.junit.Assert.assertThrows
import org.junit.Test

class MiLinkDeviceMetaSymbolsTest {
    @Test
    fun validateAcceptsOneObserverAndOneDeviceMetaContract() {
        MiLinkDeviceMetaSymbols.validate(validSymbols())
    }

    @Test
    fun validateRejectsGuardWhoseParametersAreNotOneDeviceMetaType() {
        val symbols = validSymbols().toMutableMap()
        symbols["guardWrite"] = SymbolReference(
            SymbolKind.METHOD,
            "Lcom/example/Observer;->b(Lcom/example/Meta;Lcom/example/Other;Lcom/example/Meta;)V",
        )

        assertThrows(IllegalArgumentException::class.java) {
            MiLinkDeviceMetaSymbols.validate(symbols)
        }
    }

    @Test
    fun validateRejectsFieldOutsideDeviceMeta() {
        val symbols = validSymbols().toMutableMap()
        symbols["title"] = SymbolReference(SymbolKind.FIELD, "Lcom/example/Other;->d:Ljava/lang/String;")

        assertThrows(IllegalArgumentException::class.java) {
            MiLinkDeviceMetaSymbols.validate(symbols)
        }
    }

    private fun validSymbols(): Map<String, SymbolReference> = mapOf(
        "observer" to SymbolReference(SymbolKind.CLASS, "Lcom/example/Observer;"),
        "deviceMeta" to SymbolReference(SymbolKind.CLASS, "Lcom/example/Meta;"),
        "guardEntry" to SymbolReference(
            SymbolKind.METHOD,
            "Lcom/example/Observer;->a(Lcom/example/Meta;Lcom/example/Meta;Lcom/example/Meta;)V",
        ),
        "guardWrite" to SymbolReference(
            SymbolKind.METHOD,
            "Lcom/example/Observer;->b(Lcom/example/Meta;Lcom/example/Meta;Lcom/example/Meta;)V",
        ),
        "deviceType" to SymbolReference(SymbolKind.FIELD, "Lcom/example/Meta;->c:Ljava/lang/String;"),
        "title" to SymbolReference(SymbolKind.FIELD, "Lcom/example/Meta;->d:Ljava/lang/String;"),
    )
}

class MiLinkSampleDescriptorTest {
    @Test
    fun cardArtSymbolsMatchCurrentMilinkSampleContract() {
        MiLinkCardArtSymbols.validate(
            mapOf(
                "deviceInfoField.0" to SymbolReference(
                    SymbolKind.FIELD,
                    "Lcom/miui/circulate/world/sticker/BluetoothCardView;->G:Lcom/miui/circulate/api/service/CirculateDeviceInfo;",
                ),
                "deviceInfoField.1" to SymbolReference(
                    SymbolKind.FIELD,
                    "Lcom/miui/circulateplus/world/headset/HeadSetsDetail;->J:Lcom/miui/circulate/api/service/CirculateDeviceInfo;",
                ),
                "circulateServicesField" to SymbolReference(
                    SymbolKind.FIELD,
                    "Lcom/miui/circulate/api/service/CirculateDeviceInfo;->circulateServices:Ljava/util/Set;",
                ),
                "deviceIdField" to SymbolReference(
                    SymbolKind.FIELD,
                    "Lcom/miui/circulate/api/service/CirculateServiceInfo;->deviceId:Ljava/lang/String;",
                ),
            ),
        )
    }

    @Test
    fun wearSymbolsMatchCurrentMilinkSampleContract() {
        MiLinkWearSymbols.validate(
            mapOf(
                "listener" to SymbolReference(
                    SymbolKind.CLASS,
                    "Lcom/miui/circulate/wear/agent/device/controller/b;",
                ),
                "callbackOwner" to SymbolReference(SymbolKind.CLASS, "Ltb/g;"),
                "callbackInterface" to SymbolReference(SymbolKind.CLASS, "Ltb/g\$b;"),
                "controllerField" to SymbolReference(
                    SymbolKind.FIELD,
                    "Lcom/miui/circulate/wear/agent/device/controller/b;->r:Lcom/miui/circulate/api/protocol/headset/HeadsetServiceController;",
                ),
                "serviceField" to SymbolReference(
                    SymbolKind.FIELD,
                    "Lcom/miui/circulate/wear/agent/device/controller/b;->s:Lcom/miui/circulate/api/service/CirculateServiceInfo;",
                ),
                "volumeField" to SymbolReference(
                    SymbolKind.FIELD,
                    "Lcom/miui/circulate/wear/agent/device/controller/b;->l:I",
                ),
                "modeField" to SymbolReference(
                    SymbolKind.FIELD,
                    "Lcom/miui/circulate/wear/agent/device/controller/b;->o:I",
                ),
                "supportModeField" to SymbolReference(
                    SymbolKind.FIELD,
                    "Lcom/miui/circulate/wear/agent/device/controller/b;->p:I",
                ),
                "callbackListField" to SymbolReference(
                    SymbolKind.FIELD,
                    "Ltb/g;->p:Ljava/util/concurrent/CopyOnWriteArrayList;",
                ),
                "callbackMethod1" to SymbolReference(SymbolKind.METHOD, "Ltb/g;->k(Ltb/g\$b;)V"),
                "callbackMethod2" to SymbolReference(SymbolKind.METHOD, "Ltb/g;->u(Ltb/g\$b;)V"),
            ),
        )
    }
}

class MiLinkStableAndRuntimeSymbolsTest {
    @Test
    fun fixedStableSymbolsHaveValidDescriptors() {
        MiLinkStableSymbols.validate(MiLinkStableSymbols.symbols)
    }

    @Test
    fun runtimeInternalSymbolsValidateWithCurrentDescriptors() {
        MiLinkRuntimeSymbols.validate(
            mapOf(
                "hostBoundCheck" to SymbolReference(
                    SymbolKind.METHOD,
                    "Lcom/miui/headset/runtime/MultiplatformProcessor;->hostBoundCheck(Ljava/lang/String;Lfm/l;)I",
                ),
                "getDeviceSpatialType" to SymbolReference(
                    SymbolKind.METHOD,
                    "Lcom/miui/headset/runtime/AncBatteryModel;->getDeviceSpatialType()I",
                ),
            ),
        )
    }
}
