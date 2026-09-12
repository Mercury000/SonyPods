package dev.sonypods.hook.milink

import dev.sonypods.hook.symbols.DexKitSymbolBundleDefinition
import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolQuery
import dev.sonypods.hook.symbols.SymbolReference
import java.lang.reflect.Modifier
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

/**
 * Resolves the obfuscated DeviceMeta merge guard as one atomic contract.
 *
 * The guard is the pair of private void methods in BluetoothDeviceObserver that take three
 * instances of the same DeviceMeta type: one entry delegates to the one that writes the DAO.
 * DeviceMeta fields are resolved from their order in the class's stable toString contract;
 * the deviceType field is independently validated against the string classifier that compares
 * it with TV/audio_stereo. No runtime reflection scan or toString parsing is used after caching.
 */
internal object MiLinkDeviceMetaSymbols : DexKitSymbolBundleDefinition {
    override val id = "milink-device-meta"
    override val schemaVersion = 2

    override val requiredSymbols = setOf(
        "observer",
        "deviceMeta",
        "guardEntry",
        "guardWrite",
        "deviceType",
        "title",
    )

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = requireNotNull(query.bridge) { "MiLink DeviceMeta resolution requires DexKit" }

        val observerDescriptor = query.requireUnique(
            "observer",
            bridge.findClass {
                matcher { className(BLUETOOTH_DEVICE_OBSERVER) }
            }.map { it.descriptor },
        )

        val guards = bridge.findMethod {
            matcher {
                declaredClass(BLUETOOTH_DEVICE_OBSERVER)
                modifiers(Modifier.PRIVATE)
                returnType("void")
                paramCount(3)
            }
        }.filter { method ->
            method.paramTypes.map { it.descriptor }.distinct().size == 1
        }
        check(guards.size == 2) {
            "expected two three-DeviceMeta guard methods, found ${guards.size}: " +
                guards.joinToString { it.descriptor }
        }

        val guardDescriptors = guards.map { it.descriptor }.toSet()
        val entry = guards.filter { method ->
            method.invokes.any { it.descriptor in guardDescriptors }
        }
        val write = guards.filter { method ->
            method.invokes.none { it.descriptor in guardDescriptors }
        }
        val guardEntry = query.requireUnique("guardEntry", entry.map { it.descriptor })
        val guardWrite = query.requireUnique("guardWrite", write.map { it.descriptor })
        val writeMethod = guards.single { it.descriptor == guardWrite }

        val deviceMeta = writeMethod.paramTypes.single()
        val deviceMetaClass = requireNotNull(bridge.getClassData(deviceMeta.descriptor)) {
            "DeviceMeta class is absent from DEX: ${deviceMeta.descriptor}"
        }
        val primaryConstructor = deviceMetaClass.methods.singleOrNull { method ->
            method.isConstructor &&
                (method.modifiers and ACC_SYNTHETIC) == 0 &&
                method.paramTypeNames.size >= 4 &&
                method.paramTypeNames.take(4).all { it == "java.lang.String" }
        } ?: error("DeviceMeta primary constructor not found in ${deviceMetaClass.name}")
        val writtenFields = primaryConstructor.usingFields
            .filter { it.usingType == FieldUsingType.Write && it.field.declaredClassName == deviceMetaClass.name }
            .map { it.field }
            .distinctBy { it.descriptor }
        check(writtenFields.size >= 4 && writtenFields.take(4).all { it.typeName == "java.lang.String" }) {
            "DeviceMeta constructor field contract changed: ${writtenFields.take(6).map { it.descriptor }}"
        }

        val classifier = deviceMetaClass.methods.singleOrNull { method ->
            method.usingStrings.contains("TV") &&
                method.usingStrings.contains("audio_stereo") &&
                method.usingFields.any { it.usingType == FieldUsingType.Read && it.field.typeName == "java.lang.String" }
        } ?: error("DeviceMeta deviceType classifier not found in ${deviceMetaClass.name}")
        val classifierReadCounts = classifier.usingFields
            .filter { it.usingType == FieldUsingType.Read && it.field.declaredClassName == deviceMetaClass.name }
            .groupingBy { it.field.descriptor }
            .eachCount()
        val deviceTypeCandidates = writtenFields.filter { it.typeName == "java.lang.String" }
            .map { it to (classifierReadCounts[it.descriptor] ?: 0) }
            .filter { it.second > 1 }
        check(deviceTypeCandidates.size == 1) {
            "DeviceMeta deviceType candidate is ambiguous: ${deviceTypeCandidates.map { it.first.descriptor to it.second }}"
        }
        val deviceTypeField = deviceTypeCandidates.single().first
        val deviceTypeIndex = writtenFields.indexOfFirst { it.descriptor == deviceTypeField.descriptor }
        val titleField = writtenFields.getOrNull(deviceTypeIndex + 1)
            ?.takeIf { it.typeName == "java.lang.String" }
            ?: error("DeviceMeta title field not found")

        return mapOf(
            "observer" to SymbolReference(SymbolKind.CLASS, observerDescriptor),
            "deviceMeta" to SymbolReference(SymbolKind.CLASS, deviceMeta.descriptor),
            "guardEntry" to SymbolReference(SymbolKind.METHOD, guardEntry),
            "guardWrite" to SymbolReference(SymbolKind.METHOD, guardWrite),
            "deviceType" to SymbolReference(SymbolKind.FIELD, deviceTypeField.descriptor),
            "title" to SymbolReference(SymbolKind.FIELD, titleField.descriptor),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) {
        val observer = DexClass(symbols.getValue("observer").descriptor).typeName
        val deviceMeta = DexClass(symbols.getValue("deviceMeta").descriptor).typeName
        val entry = DexMethod(symbols.getValue("guardEntry").descriptor)
        val write = DexMethod(symbols.getValue("guardWrite").descriptor)
        val deviceType = DexField(symbols.getValue("deviceType").descriptor)
        val title = DexField(symbols.getValue("title").descriptor)

        require(entry.className == observer && write.className == observer) {
            "DeviceMeta guards must be declared by $observer"
        }
        require(entry.paramTypeNames == listOf(deviceMeta, deviceMeta, deviceMeta)) {
            "DeviceMeta entry guard signature changed: ${entry.paramTypeNames}"
        }
        require(write.paramTypeNames == listOf(deviceMeta, deviceMeta, deviceMeta)) {
            "DeviceMeta write guard signature changed: ${write.paramTypeNames}"
        }
        require(deviceType.className == deviceMeta && deviceType.typeName == "java.lang.String") {
            "deviceType is not a String field of $deviceMeta"
        }
        require(title.className == deviceMeta && title.typeName == "java.lang.String") {
            "title is not a String field of $deviceMeta"
        }
    }

    private const val BLUETOOTH_DEVICE_OBSERVER =
        "com.miui.circulate.device.service.search.impl.BluetoothDeviceObserver"
    private const val ACC_SYNTHETIC = 0x1000
}