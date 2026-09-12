package dev.sonypods.hook

import dev.sonypods.hook.symbols.DexKitSymbolBundleDefinition
import dev.sonypods.hook.symbols.FixedSymbolBundleDefinition
import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolQuery
import dev.sonypods.hook.symbols.SymbolReference
import java.lang.reflect.Modifier
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexMethod

/** Stable Android Service ABI exposed by Sound Connect under a non-obfuscated component name. */
internal object SoundConnectServiceSymbols : FixedSymbolBundleDefinition {
    const val SERVICE_CLASS = "com.sony.songpal.mdr.service.KeepConnectionForegroundService"

    override val id = "sound-connect-service"
    override val schemaVersion = 1
    override val requiredSymbols = setOf("onBind", "onStartCommand", "onDestroy")

    private const val SERVICE_DESCRIPTOR = "Lcom/sony/songpal/mdr/service/KeepConnectionForegroundService;"

    override val symbols = mapOf(
        "onBind" to method("$SERVICE_DESCRIPTOR->onBind(Landroid/content/Intent;)Landroid/os/IBinder;"),
        "onStartCommand" to method("$SERVICE_DESCRIPTOR->onStartCommand(Landroid/content/Intent;II)I"),
        "onDestroy" to method("$SERVICE_DESCRIPTOR->onDestroy()V"),
    )

    override fun validate(symbols: Map<String, SymbolReference>) {
        symbols.values.forEach { reference ->
            require(DexMethod(reference.descriptor).className == SERVICE_CLASS) {
                "KeepConnectionForegroundService ABI changed: ${reference.descriptor}"
            }
        }
    }

    private fun method(descriptor: String) = SymbolReference(SymbolKind.METHOD, descriptor)
}

/**
 * Obfuscated Sound Connect MDR-session storage contract.
 *
 * The controller is anchored by stable connection log messages, then the four methods are
 * identified by their Map operations and mutually consistent parameter/return types. No R8
 * class or method name is part of the contract.
 */
internal object SoundConnectSessionSymbols : DexKitSymbolBundleDefinition {
    override val id = "sound-connect-mdr-session"
    override val schemaVersion = 1
    override val requiredSymbols = setOf("controller", "addSession", "removeSession", "clearSessions", "firstSession")

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = requireNotNull(query.bridge) { "Sound Connect session resolution requires DexKit" }
        val controllerDescriptor = query.requireUnique(
            "controller",
            bridge.findClass {
                matcher {
                    usingStrings(
                        "disconnectAllDevices:",
                        "disposeMdrConnection deviceId: ",
                        "resumeFromFwUpdate",
                    )
                }
            }.map { it.descriptor },
        )
        val controller = requireNotNull(bridge.getClassData(controllerDescriptor)) {
            "Sound Connect connection controller is absent from DEX: $controllerDescriptor"
        }

        val addCandidates = controller.methods.filter { method ->
            Modifier.isPrivate(method.modifiers) &&
                method.returnTypeName == "void" &&
                method.paramCount == 2 &&
                method.invokesMapMethod("put", 2)
        }
        val removeCandidates = controller.methods.filter { method ->
            Modifier.isPrivate(method.modifiers) &&
                method.paramCount == 1 &&
                method.returnTypeName != "void" &&
                method.invokesMapMethod("remove", 1)
        }
        val pairs = addCandidates.flatMap { add ->
            removeCandidates.mapNotNull { remove ->
                val compatible = add.paramTypeNames[0] == remove.paramTypeNames[0] &&
                    add.paramTypeNames[1] == remove.returnTypeName &&
                    add.sharedReadFields(remove).isNotEmpty()
                if (compatible) add to remove else null
            }
        }
        check(pairs.size == 1) {
            "MDR add/remove session contract is ambiguous: " +
                pairs.joinToString { "${it.first.descriptor} + ${it.second.descriptor}" }
        }
        val (addSession, removeSession) = pairs.single()
        val holderFields = addSession.sharedReadFields(removeSession)

        val firstSession = controller.methods.singleOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.paramCount == 0 &&
                method.returnTypeName == "java.util.Map\$Entry" &&
                method.invokesMapMethod("isEmpty", 0) &&
                method.invokesMapMethod("entrySet", 0) &&
                method.readFieldDescriptors().any(holderFields::contains)
        } ?: error("MDR first-session probe is absent or ambiguous in ${controller.name}")

        val clearSessions = controller.methods.singleOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.paramCount == 0 &&
                method.returnTypeName == "void" &&
                method.invokesMapMethod("keySet", 0) &&
                method.invokesMapMethod("remove", 1) &&
                method.readFieldDescriptors().any(holderFields::contains)
        } ?: error("MDR clear-sessions method is absent or ambiguous in ${controller.name}")

        return mapOf(
            "controller" to SymbolReference(SymbolKind.CLASS, controller.descriptor),
            "addSession" to SymbolReference(SymbolKind.METHOD, addSession.descriptor),
            "removeSession" to SymbolReference(SymbolKind.METHOD, removeSession.descriptor),
            "clearSessions" to SymbolReference(SymbolKind.METHOD, clearSessions.descriptor),
            "firstSession" to SymbolReference(SymbolKind.METHOD, firstSession.descriptor),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) {
        val controller = symbols.getValue("controller").descriptor
        val methods = listOf("addSession", "removeSession", "clearSessions", "firstSession")
            .associateWith { DexMethod(symbols.getValue(it).descriptor) }
        methods.values.forEach { method ->
            require(method.className == controller.removeSurrounding("L", ";").replace('/', '.')) {
                "Sound Connect session method escaped its controller: $method"
            }
        }
        val add = methods.getValue("addSession")
        val remove = methods.getValue("removeSession")
        val clear = methods.getValue("clearSessions")
        val first = methods.getValue("firstSession")
        require(add.returnTypeName == "void" && add.paramTypeNames.size == 2) { "add-session shape changed: $add" }
        require(remove.paramTypeNames == listOf(add.paramTypeNames[0]) && remove.returnTypeName == add.paramTypeNames[1]) {
            "remove-session shape changed: $remove"
        }
        require(clear.returnTypeName == "void" && clear.paramTypeNames.isEmpty()) { "clear-sessions shape changed: $clear" }
        require(first.returnTypeName == "java.util.Map\$Entry" && first.paramTypeNames.isEmpty()) {
            "first-session shape changed: $first"
        }
    }

    private fun MethodData.invokesMapMethod(name: String, paramCount: Int): Boolean =
        invokes.any { invoked ->
            invoked.name == name &&
                invoked.paramCount == paramCount &&
                (invoked.declaredClassName == "java.util.Map" || invoked.declaredClassName.startsWith("java.util."))
        }

    private fun MethodData.readFieldDescriptors(): Set<String> =
        usingFields.asSequence()
            .filter { it.usingType == FieldUsingType.Read }
            .map { it.field.descriptor }
            .toSet()

    private fun MethodData.sharedReadFields(other: MethodData): Set<String> =
        readFieldDescriptors().intersect(other.readFieldDescriptors())
}
