package dev.sonypods.hook.milink

import dev.sonypods.hook.symbols.DexKitSymbolBundleDefinition
import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolQuery
import dev.sonypods.hook.symbols.SymbolReference
import java.lang.reflect.Modifier
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

/** Resolves the Wear headset listener, its callback owner, callback list, and capability fields. */
internal object MiLinkWearSymbols : DexKitSymbolBundleDefinition {
    override val id = "milink-wear-capability"
    override val schemaVersion = 3
    override val requiredSymbols = setOf(
        "listener",
        "callbackOwner",
        "callbackInterface",
        "controllerField",
        "serviceField",
        "volumeField",
        "modeField",
        "supportModeField",
        "callbackListField",
        "callbackMethod1",
        "callbackMethod2",
    )

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = requireNotNull(query.bridge) { "MiLink Wear resolution requires DexKit" }
        requireNotNull(bridge.getClassData(HEADSET_SERVICE_CONTROLLER)) { "HeadsetServiceController not found" }
        requireNotNull(bridge.getClassData(CIRCULATE_SERVICE_INFO)) { "CirculateServiceInfo not found" }

        val listeners = bridge.findClass {
            matcher {
                interfaces {
                    add { className(HEADSET_SERVICE_NOTIFY) }
                }
            }
        }.filter { candidate ->
            candidate.name.startsWith("$WEAR_AGENT_PACKAGE.") &&
                candidate.fields.count { it.typeName == HEADSET_SERVICE_CONTROLLER } == 1 &&
                candidate.fields.count { it.typeName == CIRCULATE_SERVICE_INFO } == 1
        }
        val listener = listeners.singleOrNull()
            ?: error("Wear HeadsetServiceNotify listener is ambiguous: ${listeners.map { it.name }}")

        val callbackInterfaces = listener.interfaces.filter { it.name != HEADSET_SERVICE_NOTIFY }
        val callbackInterface = callbackInterfaces.singleOrNull()
            ?: error("Wear headset callback interface is ambiguous: ${callbackInterfaces.map { it.name }}")

        val owners = listener.fields
            .mapNotNull { bridge.getClassData(it.typeName) }
            .distinctBy { it.name }
            .filter { owner ->
                owner.fields.count { it.typeName == COPY_ON_WRITE_ARRAY_LIST } == 1 &&
                    callbackMethods(owner, callbackInterface).size == 2
            }
        val owner = owners.singleOrNull()
            ?: error("Wear headset callback owner is ambiguous: ${owners.map { it.name }}")

        val controllerField = listener.fields.single { it.typeName == HEADSET_SERVICE_CONTROLLER }
        val serviceField = listener.fields.single { it.typeName == CIRCULATE_SERVICE_INFO }
        val volumeField = callbackIntStateField(listener, "onBluetoothVolumeChanged")
        val modeField = callbackIntStateField(listener, "onBluetoothModeChanged")
        val audioEffectField = callbackIntStateField(listener, "onBluetoothAudioEffectChanged")
        // The listener stores mode/support/audioEffect as one adjacent state tuple. Resolve both
        // tuple boundaries from stable HeadsetServiceNotify callbacks; the middle field is support.
        val supportModeRuns = listener.fields.windowed(3).filter { fields ->
            fields.all(::isMutableIntField) &&
                fields[0].descriptor == modeField.descriptor &&
                fields[2].descriptor == audioEffectField.descriptor
        }
        val supportModeField = supportModeRuns.singleOrNull()?.get(1)
            ?: error(
                "Wear mode/support/audioEffect tuple is ambiguous: " +
                    supportModeRuns.map { run -> run.map { it.descriptor } },
            )
        val callbackListField = owner.fields.single { it.typeName == COPY_ON_WRITE_ARRAY_LIST }
        val callbacks = callbackMethods(owner, callbackInterface)
            .sortedBy { it.descriptor }

        return mapOf(
            "listener" to SymbolReference(SymbolKind.CLASS, listener.descriptor),
            "callbackOwner" to SymbolReference(SymbolKind.CLASS, owner.descriptor),
            "callbackInterface" to SymbolReference(SymbolKind.CLASS, callbackInterface.descriptor),
            "controllerField" to SymbolReference(SymbolKind.FIELD, controllerField.descriptor),
            "serviceField" to SymbolReference(SymbolKind.FIELD, serviceField.descriptor),
            "volumeField" to SymbolReference(SymbolKind.FIELD, volumeField.descriptor),
            "modeField" to SymbolReference(SymbolKind.FIELD, modeField.descriptor),
            "supportModeField" to SymbolReference(SymbolKind.FIELD, supportModeField.descriptor),
            "callbackListField" to SymbolReference(SymbolKind.FIELD, callbackListField.descriptor),
            "callbackMethod1" to SymbolReference(SymbolKind.METHOD, callbacks[0].descriptor),
            "callbackMethod2" to SymbolReference(SymbolKind.METHOD, callbacks[1].descriptor),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) {
        val listener = DexClass(symbols.getValue("listener").descriptor).typeName
        val owner = DexClass(symbols.getValue("callbackOwner").descriptor).typeName
        val callback = DexClass(symbols.getValue("callbackInterface").descriptor).typeName
        val controller = DexField(symbols.getValue("controllerField").descriptor)
        val service = DexField(symbols.getValue("serviceField").descriptor)
        val volume = DexField(symbols.getValue("volumeField").descriptor)
        val mode = DexField(symbols.getValue("modeField").descriptor)
        val supportMode = DexField(symbols.getValue("supportModeField").descriptor)
        val callbackList = DexField(symbols.getValue("callbackListField").descriptor)
        val callback1 = DexMethod(symbols.getValue("callbackMethod1").descriptor)
        val callback2 = DexMethod(symbols.getValue("callbackMethod2").descriptor)

        require(controller.className == listener && controller.typeName == HEADSET_SERVICE_CONTROLLER) {
            "Wear controller field changed: $controller"
        }
        require(service.className == listener && service.typeName == CIRCULATE_SERVICE_INFO) {
            "Wear service field changed: $service"
        }
        mapOf("volume" to volume, "mode" to mode, "supportMode" to supportMode).forEach { (role, field) ->
            require(field.className == listener && field.typeName == "int") {
                "Wear $role field changed: $field"
            }
        }
        require(setOf(volume.toString(), mode.toString(), supportMode.toString()).size == 3) {
            "Wear state fields must be distinct"
        }
        require(callbackList.className == owner && callbackList.typeName == COPY_ON_WRITE_ARRAY_LIST) {
            "Wear callback list changed: $callbackList"
        }
        listOf(callback1, callback2).forEach { method ->
            require(method.className == owner && method.paramTypeNames == listOf(callback)) {
                "Wear callback method changed: $method"
            }
        }
    }

    private fun callbackIntStateField(listener: ClassData, callbackName: String): FieldData {
        val callback = listener.methods.singleOrNull { method ->
            method.name == callbackName &&
                method.returnTypeName == "void" &&
                method.paramTypeNames == listOf(CIRCULATE_SERVICE_INFO, "int")
        } ?: error("Wear $callbackName implementation is absent or ambiguous in ${listener.name}")
        val writes = callback.usingFields
            .filter {
                it.usingType == FieldUsingType.Write &&
                    it.field.declaredClassName == listener.name &&
                    isMutableIntField(it.field)
            }
            .map { it.field }
            .distinctBy { it.descriptor }
        return writes.singleOrNull()
            ?: error("Wear $callbackName state field is ambiguous: ${writes.map { it.descriptor }}")
    }

    private fun isMutableIntField(field: FieldData): Boolean =
        field.typeName == "int" && !Modifier.isStatic(field.modifiers) && !Modifier.isFinal(field.modifiers)

    private fun callbackMethods(owner: ClassData, callback: ClassData): List<MethodData> =
        owner.methods.filter {
            !Modifier.isStatic(it.modifiers) &&
                it.returnTypeName == "void" &&
                it.paramTypeNames == listOf(callback.name)
        }

    private const val WEAR_AGENT_PACKAGE = "com.miui.circulate.wear.agent"
    private const val HEADSET_SERVICE_NOTIFY = "com.miui.circulate.api.protocol.headset.HeadsetServiceNotify"
    private const val HEADSET_SERVICE_CONTROLLER =
        "com.miui.circulate.api.protocol.headset.HeadsetServiceController"
    private const val CIRCULATE_SERVICE_INFO = "com.miui.circulate.api.service.CirculateServiceInfo"
    private const val COPY_ON_WRITE_ARRAY_LIST = "java.util.concurrent.CopyOnWriteArrayList"
}
