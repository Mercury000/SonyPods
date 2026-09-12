package dev.sonypods.hook.milink

import dev.sonypods.hook.symbols.DexKitSymbolBundleDefinition
import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolQuery
import dev.sonypods.hook.symbols.SymbolReference
import java.lang.reflect.Modifier
import org.luckypray.dexkit.wrap.DexField

/** Resolves the card model field and the two stable service fields used to extract its Bluetooth address. */
internal object MiLinkCardArtSymbols : DexKitSymbolBundleDefinition {
    override val id = "milink-card-art"
    override val schemaVersion = 2
    override val requiredSymbols = setOf(
        "circulateServicesField",
        "deviceIdField",
    )
    override val requiredPrefixes = setOf("deviceInfoField.")

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = requireNotNull(query.bridge) { "MiLink CardArt resolution requires DexKit" }
        val deviceInfo = requireNotNull(bridge.getClassData(CIRCULATE_DEVICE_INFO)) {
            "CirculateDeviceInfo not found"
        }
        val serviceInfo = requireNotNull(bridge.getClassData(CIRCULATE_SERVICE_INFO)) {
            "CirculateServiceInfo not found"
        }

        val deviceInfoFields = bridge.findField {
            matcher { type(CIRCULATE_DEVICE_INFO) }
        }.filter { !Modifier.isStatic(it.modifiers) }
            .map { it.descriptor }
            .distinct()
            .sorted()
        check(deviceInfoFields.isNotEmpty()) { "No CirculateDeviceInfo holder fields found" }
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

        return buildMap {
            deviceInfoFields.forEachIndexed { index, descriptor ->
                put("deviceInfoField.$index", SymbolReference(SymbolKind.FIELD, descriptor))
            }
            put("circulateServicesField", SymbolReference(SymbolKind.FIELD, circulateServicesField))
            put("deviceIdField", SymbolReference(SymbolKind.FIELD, deviceIdField))
        }
    }

    override fun validate(symbols: Map<String, SymbolReference>) {
        val deviceInfoFields = symbols.filterKeys { it.startsWith("deviceInfoField.") }
            .values
            .map { DexField(it.descriptor) }
        val servicesField = DexField(symbols.getValue("circulateServicesField").descriptor)
        val deviceIdField = DexField(symbols.getValue("deviceIdField").descriptor)

        require(deviceInfoFields.isNotEmpty()) { "No CirculateDeviceInfo holder fields resolved" }
        deviceInfoFields.forEach { deviceInfoField ->
            require(deviceInfoField.typeName == CIRCULATE_DEVICE_INFO) {
                "deviceInfoField type changed: ${deviceInfoField.typeName}"
            }
        }
        require(servicesField.className == CIRCULATE_DEVICE_INFO && servicesField.name == "circulateServices") {
            "circulateServices field changed: ${servicesField}"
        }
        require(deviceIdField.className == CIRCULATE_SERVICE_INFO && deviceIdField.name == "deviceId") {
            "deviceId field changed: ${deviceIdField}"
        }
    }

    private const val CIRCULATE_DEVICE_INFO = "com.miui.circulate.api.service.CirculateDeviceInfo"
    private const val CIRCULATE_SERVICE_INFO = "com.miui.circulate.api.service.CirculateServiceInfo"
}
