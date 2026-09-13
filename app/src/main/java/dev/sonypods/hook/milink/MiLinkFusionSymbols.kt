package dev.sonypods.hook.milink

import dev.sonypods.hook.symbols.DexKitSymbolBundleDefinition
import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolQuery
import dev.sonypods.hook.symbols.SymbolReference
import org.luckypray.dexkit.wrap.DexMethod

/**
 * Controller members the fusion panel reaches through, which are inherited from R8-renamed bases.
 *
 * [com.miui.circulate.api.protocol.headset.HeadsetServiceController] keeps a stable class name,
 * but `registerServiceNotify` is declared on its renamed superclass with the renamed notify
 * interface as its parameter, so neither a fixed descriptor nor `findDeclaredMethod` on the
 * stable class resolves it. The method name, void return, and arity are stable, but several
 * unrelated controllers declare the same shape (`DeviceControlStub`, `DeviceControlImpl`, and
 * the ringing controller all do), so the declaring class is pinned structurally instead: the
 * notify registry owner is the one that also declares `mServiceNotifies`, `getServiceNotifies()`,
 * and `unRegisterServiceNotify` with the same parameter type.
 */
internal object MiLinkFusionSymbols : DexKitSymbolBundleDefinition {
    override val id = "milink-fusion-controller"
    override val schemaVersion = 1
    override val requiredSymbols = setOf("registerServiceNotify")

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = requireNotNull(query.bridge) { "MiLink fusion resolution requires DexKit" }
        val candidates = bridge.findMethod {
            matcher {
                name("registerServiceNotify")
                returnType("void")
                paramCount(1)
            }
        }.filter { method ->
            val declared = DexMethod(method.descriptor)
            val owner = bridge.getClassData(declared.className) ?: return@filter false
            owner.fields.any { it.name == NOTIFY_LIST_FIELD && it.typeName == "java.util.List" } &&
                owner.methods.any { it.name == "getServiceNotifies" && it.returnTypeName == "java.util.List" } &&
                owner.methods.any {
                    it.name == "unRegisterServiceNotify" && it.paramTypeNames == declared.paramTypeNames
                }
        }
        val registerServiceNotify = query.requireUnique(
            "registerServiceNotify",
            candidates.map { it.descriptor },
        )
        return mapOf(
            "registerServiceNotify" to SymbolReference(SymbolKind.METHOD, registerServiceNotify),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) {
        val register = DexMethod(symbols.getValue("registerServiceNotify").descriptor)
        require(register.name == "registerServiceNotify" && register.returnTypeName == "void") {
            "registerServiceNotify signature changed: $register"
        }
        require(register.paramTypeNames.size == 1) {
            "registerServiceNotify must take exactly the notify interface: ${register.paramTypeNames}"
        }
    }

    private const val NOTIFY_LIST_FIELD = "mServiceNotifies"
}
