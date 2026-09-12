package dev.sonypods.hook.milink

import dev.sonypods.hook.symbols.DexKitSymbolBundleDefinition
import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolQuery
import dev.sonypods.hook.symbols.SymbolReference
import java.lang.reflect.Modifier
import org.luckypray.dexkit.wrap.DexMethod

/** Resolves MiLink's private HEADSET identity-graph validator without depending on R8 names. */
internal object MiLinkIdentityGraphSymbols : DexKitSymbolBundleDefinition {
    override val id = "milink-headset-identity-graph"
    override val schemaVersion = 1
    override val requiredSymbols = setOf("headsetGraphValidator")

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = requireNotNull(query.bridge) { "MiLink identity graph resolution requires DexKit" }
        val descriptor = query.requireUnique(
            "headsetGraphValidator",
            bridge.findMethod {
                matcher {
                    declaredClass(ML_CARD_VIEW_HOST_SERVICE)
                    modifiers(Modifier.PRIVATE)
                    returnType("boolean")
                    paramTypes("java.lang.String", CIRCULATE_DEVICE_INFO)
                }
            }.map { it.descriptor },
        )
        return mapOf("headsetGraphValidator" to SymbolReference(SymbolKind.METHOD, descriptor))
    }

    override fun validate(symbols: Map<String, SymbolReference>) {
        val method = DexMethod(symbols.getValue("headsetGraphValidator").descriptor)
        require(method.className == ML_CARD_VIEW_HOST_SERVICE && method.returnTypeName == "boolean") {
            "HEADSET graph validator owner/return type changed: $method"
        }
        require(method.paramTypeNames == listOf("java.lang.String", CIRCULATE_DEVICE_INFO)) {
            "HEADSET graph validator signature changed: ${method.paramTypeNames}"
        }
    }

    private const val ML_CARD_VIEW_HOST_SERVICE = "com.miui.circulate.world.MLCardViewHostService"
    private const val CIRCULATE_DEVICE_INFO = "com.miui.circulate.api.service.CirculateDeviceInfo"
}
