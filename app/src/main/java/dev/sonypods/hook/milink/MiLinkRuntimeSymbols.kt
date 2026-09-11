package dev.sonypods.hook.milink

import dev.sonypods.hook.symbols.DexKitSymbolBundleDefinition
import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolQuery
import dev.sonypods.hook.symbols.SymbolReference
import org.luckypray.dexkit.wrap.DexMethod

/** Internal MiLink runtime methods which have readable names today but no stable ABI guarantee. */
internal object MiLinkRuntimeSymbols : DexKitSymbolBundleDefinition {
    override val id = "milink-runtime-internal"
    override val schemaVersion = 1
    override val requiredSymbols = setOf("hostBoundCheck", "getDeviceSpatialType")

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = requireNotNull(query.bridge) { "MiLink runtime resolution requires DexKit" }
        val hostBoundCheck = query.requireUnique(
            "hostBoundCheck",
            bridge.findMethod {
                matcher {
                    declaredClass(MULTIPLATFORM_PROCESSOR)
                    name("hostBoundCheck")
                    returnType("int")
                    paramTypes("java.lang.String", null)
                }
            }.map { it.descriptor },
        )
        val getDeviceSpatialType = query.requireUnique(
            "getDeviceSpatialType",
            bridge.findMethod {
                matcher {
                    declaredClass(ANC_BATTERY_MODEL)
                    name("getDeviceSpatialType")
                    returnType("int")
                    paramCount(0)
                }
            }.map { it.descriptor },
        )
        return mapOf(
            "hostBoundCheck" to SymbolReference(SymbolKind.METHOD, hostBoundCheck),
            "getDeviceSpatialType" to SymbolReference(SymbolKind.METHOD, getDeviceSpatialType),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) {
        val host = DexMethod(symbols.getValue("hostBoundCheck").descriptor)
        val spatial = DexMethod(symbols.getValue("getDeviceSpatialType").descriptor)
        require(host.className == MULTIPLATFORM_PROCESSOR && host.paramTypeNames.firstOrNull() == "java.lang.String") {
            "MultiplatformProcessor.hostBoundCheck signature changed: $host"
        }
        require(host.returnTypeName == "int") { "hostBoundCheck return type changed" }
        require(spatial.className == ANC_BATTERY_MODEL && spatial.paramTypeNames.isEmpty()) {
            "AncBatteryModel.getDeviceSpatialType signature changed: $spatial"
        }
        require(spatial.returnTypeName == "int") { "getDeviceSpatialType return type changed" }
    }

    private const val MULTIPLATFORM_PROCESSOR = "com.miui.headset.runtime.MultiplatformProcessor"
    private const val ANC_BATTERY_MODEL = "com.miui.headset.runtime.AncBatteryModel"
}
