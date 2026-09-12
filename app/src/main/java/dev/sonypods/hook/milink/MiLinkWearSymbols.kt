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
    override val schemaVersion = 4
    override val requiredSymbols = setOf(
        "listener",
        "callbackOwner",
        "callbackInterface",
        "controllerField",
        "serviceField",
        "volumeField",
        "batteryField",
        "modeField",
        "supportModeField",
        "volumeCallback",
        "batteryCallback",
        "modeCallback",
        "publishMethod",
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
        val volumeCallback = callbackMethod(listener, "onBluetoothVolumeChanged", "int")
        val batteryCallback = callbackMethod(listener, "onBluetoothBatteryChanged", "java.util.List")
        val modeCallback = callbackMethod(listener, "onBluetoothModeChanged", "int")
        val audioEffectCallback = callbackMethod(listener, "onBluetoothAudioEffectChanged", "int")
        val volumeField = callbackStateField(listener, volumeCallback, "int")
        val batteryField = callbackStateField(listener, batteryCallback, "java.util.List")
        val modeField = callbackStateField(listener, modeCallback, "int")
        val audioEffectField = callbackStateField(listener, audioEffectCallback, "int")
        val publishingCallbacks = listOf(volumeCallback, batteryCallback, modeCallback)
        val publishCandidates = publishingCallbacks
            .map { callback ->
                callback.invokes
                    .filter { invoked ->
                        invoked.returnTypeName == "void" && invoked.paramTypeNames.isEmpty()
                    }
                    .map { it.descriptor }
                    .toSet()
            }
            .reduce(Set<String>::intersect)
        val publishDescriptor = query.requireUnique("wearPublishMethod", publishCandidates)
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
            "batteryField" to SymbolReference(SymbolKind.FIELD, batteryField.descriptor),
            "modeField" to SymbolReference(SymbolKind.FIELD, modeField.descriptor),
            "supportModeField" to SymbolReference(SymbolKind.FIELD, supportModeField.descriptor),
            "volumeCallback" to SymbolReference(SymbolKind.METHOD, volumeCallback.descriptor),
            "batteryCallback" to SymbolReference(SymbolKind.METHOD, batteryCallback.descriptor),
            "modeCallback" to SymbolReference(SymbolKind.METHOD, modeCallback.descriptor),
            "publishMethod" to SymbolReference(SymbolKind.METHOD, publishDescriptor),
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
        val battery = DexField(symbols.getValue("batteryField").descriptor)
        val mode = DexField(symbols.getValue("modeField").descriptor)
        val supportMode = DexField(symbols.getValue("supportModeField").descriptor)
        val volumeCallback = DexMethod(symbols.getValue("volumeCallback").descriptor)
        val batteryCallback = DexMethod(symbols.getValue("batteryCallback").descriptor)
        val modeCallback = DexMethod(symbols.getValue("modeCallback").descriptor)
        val publishMethod = DexMethod(symbols.getValue("publishMethod").descriptor)
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
        require(battery.className == listener && battery.typeName == "java.util.List") {
            "Wear battery field changed: $battery"
        }
        require(setOf(volume.toString(), battery.toString(), mode.toString(), supportMode.toString()).size == 4) {
            "Wear state fields must be distinct"
        }
        mapOf(
            "volume" to volumeCallback,
            "battery" to batteryCallback,
            "mode" to modeCallback,
        ).forEach { (role, method) ->
            require(method.className == listener && method.returnTypeName == "void") {
                "Wear $role callback changed: $method"
            }
        }
        require(publishMethod.returnTypeName == "void" && publishMethod.paramTypeNames.isEmpty()) {
            "Wear publish method changed: $publishMethod"
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

    private fun callbackMethod(listener: ClassData, callbackName: String, valueType: String): MethodData =
        listener.methods.singleOrNull { method ->
            method.name == callbackName &&
                method.returnTypeName == "void" &&
                method.paramTypeNames == listOf(CIRCULATE_SERVICE_INFO, valueType)
        } ?: error("Wear $callbackName implementation is absent or ambiguous in ${listener.name}")

    private fun callbackStateField(listener: ClassData, callback: MethodData, fieldType: String): FieldData {
        val writes = callback.usingFields
            .filter {
                it.usingType == FieldUsingType.Write &&
                    it.field.declaredClassName == listener.name &&
                    it.field.typeName == fieldType &&
                    !Modifier.isStatic(it.field.modifiers) &&
                    !Modifier.isFinal(it.field.modifiers)
            }
            .map { it.field }
            .distinctBy { it.descriptor }
        return writes.singleOrNull()
            ?: error("Wear ${callback.name} state field is ambiguous: ${writes.map { it.descriptor }}")
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
