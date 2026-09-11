package dev.sonypods.hook.milink

import dev.sonypods.hook.symbols.DexKitSymbolBundleDefinition
import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolQuery
import dev.sonypods.hook.symbols.SymbolReference
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField

/** Resolves the card model field and the two stable service fields used to extract its Bluetooth address. */
internal object MiLinkCardArtSymbols : DexKitSymbolBundleDefinition {
    override val id = "milink-card-art"
    override val schemaVersion = 1
    override val requiredSymbols = setOf(
        "cardView",
        "deviceInfoField",
        "circulateServicesField",
        "deviceIdField",
    )

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = requireNotNull(query.bridge) { "MiLink CardArt resolution requires DexKit" }
        val cardView = bridge.findClass {
            matcher { className(BLUETOOTH_CARD_VIEW) }
        }.singleOrNull() ?: error("BluetoothCardView not found")
        val deviceInfo = requireNotNull(bridge.getClassData(CIRCULATE_DEVICE_INFO)) {
            "CirculateDeviceInfo not found"
        }
        val serviceInfo = requireNotNull(bridge.getClassData(CIRCULATE_SERVICE_INFO)) {
            "CirculateServiceInfo not found"
        }

        val deviceInfoField = query.requireUnique(
            "deviceInfoField",
            bridge.findField {
                matcher {
                    declaredClass(cardView.name)
                    type(CIRCULATE_DEVICE_INFO)
                }
            }.map { it.descriptor },
        )
        val circulateServicesField = query.requireUnique(
            "circulateServicesField",
            bridge.findField {
                matcher {
                    declaredClass(deviceInfo.name)
                    name("circulateServices")
                }
            }.map { it.descriptor },
        )
        val deviceIdField = query.requireUnique(
            "deviceIdField",
            bridge.findField {
                matcher {
                    declaredClass(serviceInfo.name)
                    name("deviceId")
                }
            }.map { it.descriptor },
        )

        return mapOf(
            "cardView" to SymbolReference(SymbolKind.CLASS, cardView.descriptor),
            "deviceInfoField" to SymbolReference(SymbolKind.FIELD, deviceInfoField),
            "circulateServicesField" to SymbolReference(SymbolKind.FIELD, circulateServicesField),
            "deviceIdField" to SymbolReference(SymbolKind.FIELD, deviceIdField),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) {
        val cardView = DexClass(symbols.getValue("cardView").descriptor).typeName
        val deviceInfoField = DexField(symbols.getValue("deviceInfoField").descriptor)
        val servicesField = DexField(symbols.getValue("circulateServicesField").descriptor)
        val deviceIdField = DexField(symbols.getValue("deviceIdField").descriptor)

        require(deviceInfoField.className == cardView) { "deviceInfoField must belong to $cardView" }
        require(deviceInfoField.typeName == CIRCULATE_DEVICE_INFO) {
            "deviceInfoField type changed: ${deviceInfoField.typeName}"
        }
        require(servicesField.className == CIRCULATE_DEVICE_INFO && servicesField.name == "circulateServices") {
            "circulateServices field changed: ${servicesField}"
        }
        require(deviceIdField.className == CIRCULATE_SERVICE_INFO && deviceIdField.name == "deviceId") {
            "deviceId field changed: ${deviceIdField}"
        }
    }

    private const val BLUETOOTH_CARD_VIEW = "com.miui.circulate.world.sticker.BluetoothCardView"
    private const val CIRCULATE_DEVICE_INFO = "com.miui.circulate.api.service.CirculateDeviceInfo"
    private const val CIRCULATE_SERVICE_INFO = "com.miui.circulate.api.service.CirculateServiceInfo"
}