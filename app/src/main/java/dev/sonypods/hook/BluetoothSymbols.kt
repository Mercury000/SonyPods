package dev.sonypods.hook

import dev.sonypods.hook.symbols.DexKitSymbolBundleDefinition
import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolQuery
import dev.sonypods.hook.symbols.SymbolReference
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

/** Stable platform Bluetooth entry points, resolved and validated against the target DEX. */
internal object BluetoothAdapterSymbols : DexKitSymbolBundleDefinition {
    override val id = "bluetooth-adapter-runtime"
    override val schemaVersion = 1
    override val requiredSymbols = setOf(
        "adapterService",
        "onCreate",
        "isLeAudioAllowed",
    )
    override val requiredPrefixes = setOf("deviceDisconnect.")

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = query.requireBluetoothBridge()
        val adapter = query.uniqueBluetoothClass(bridge, "adapterService", ADAPTER_SERVICE)
        val disconnects = bridge.findMethod {
            matcher {
                name("disconnectAllEnabledProfiles")
                returnType("int")
            }
        }.filter { method ->
            method.paramTypeNames.firstOrNull() == "android.bluetooth.BluetoothDevice" &&
                method.className.startsWith(ADAPTER_PACKAGE)
        }.sortedBy { it.descriptor }
        check(disconnects.isNotEmpty()) { "no Bluetooth device-level disconnect entry found" }

        return buildMap {
            put("adapterService", adapter.ref())
            put("onCreate", adapter.requireMethod("onCreate", "void").ref())
            put(
                "isLeAudioAllowed",
                adapter.requireMethod(
                    "isLeAudioAllowed",
                    "boolean",
                    "android.bluetooth.BluetoothDevice",
                ).ref(),
            )
            disconnects.forEachIndexed { index, method ->
                put("deviceDisconnect.$index", method.ref())
            }
        }
    }

    override fun validate(symbols: Map<String, SymbolReference>) {
        val adapter = symbols.getValue("adapterService").className()
        require(adapter == ADAPTER_SERVICE) { "unexpected AdapterService owner: $adapter" }
        requireMethod(symbols, "onCreate", adapter, "void", emptyList())
        requireMethod(
            symbols,
            "isLeAudioAllowed",
            adapter,
            "boolean",
            listOf("android.bluetooth.BluetoothDevice"),
        )
        symbols.filterKeys { it.startsWith("deviceDisconnect.") }.values.forEach { reference ->
            val method = DexMethod(reference.descriptor)
            require(method.className.startsWith(ADAPTER_PACKAGE)) {
                "device disconnect escaped Bluetooth adapter package: $method"
            }
            require(method.returnTypeName == "int") { "device disconnect return type changed: $method" }
            require(method.paramTypeNames.firstOrNull() == "android.bluetooth.BluetoothDevice") {
                "device disconnect no longer starts with BluetoothDevice: $method"
            }
        }
    }

    private const val ADAPTER_PACKAGE = "com.android.bluetooth.btservice."
    private const val ADAPTER_SERVICE = "com.android.bluetooth.btservice.AdapterService"
}

/** A2DP and LE Audio transition points consumed by the engine dispatcher. */
internal object BluetoothProfileSymbols : DexKitSymbolBundleDefinition {
    override val id = "bluetooth-profile-runtime"
    override val schemaVersion = 1
    override val requiredSymbols = setOf(
        "a2dpService",
        "a2dpConnectionChanged",
        "a2dpHandler",
        "leAudioService",
        "leAudioConnectionChanged",
        "leAudioActiveDeviceChanged",
        "leAudioGroupDevices",
        "leAudioHandler",
    )

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = query.requireBluetoothBridge()
        val a2dp = query.uniqueBluetoothClass(bridge, "a2dpService", A2DP_SERVICE)
        val leAudio = query.uniqueBluetoothClass(bridge, "leAudioService", LE_AUDIO_SERVICE)
        return mapOf(
            "a2dpService" to a2dp.ref(),
            "a2dpConnectionChanged" to a2dp.requireMethod(
                "handleConnectionStateChanged",
                "void",
                "android.bluetooth.BluetoothDevice",
                "int",
                "int",
            ).ref(),
            "a2dpHandler" to a2dp.requireNamedField("mHandler", "android.os.Handler").ref(),
            "leAudioService" to leAudio.ref(),
            "leAudioConnectionChanged" to leAudio.requireMethod(
                "notifyConnectionStateChanged",
                "void",
                "android.bluetooth.BluetoothDevice",
                "int",
                "int",
            ).ref(),
            "leAudioActiveDeviceChanged" to leAudio.requireMethod(
                "notifyActiveDeviceChanged",
                "void",
                "android.bluetooth.BluetoothDevice",
            ).ref(),
            "leAudioGroupDevices" to leAudio.requireMethod(
                "getGroupDevices",
                "java.util.List",
                "android.bluetooth.BluetoothDevice",
            ).ref(),
            "leAudioHandler" to leAudio.requireNamedField("mHandler", "android.os.Handler").ref(),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) {
        val a2dp = symbols.getValue("a2dpService").className()
        val leAudio = symbols.getValue("leAudioService").className()
        require(a2dp == A2DP_SERVICE) { "unexpected A2dpService owner: $a2dp" }
        require(leAudio == LE_AUDIO_SERVICE) { "unexpected LeAudioService owner: $leAudio" }
        requireMethod(
            symbols,
            "a2dpConnectionChanged",
            a2dp,
            "void",
            listOf("android.bluetooth.BluetoothDevice", "int", "int"),
        )
        requireField(symbols, "a2dpHandler", a2dp, "android.os.Handler")
        requireMethod(
            symbols,
            "leAudioConnectionChanged",
            leAudio,
            "void",
            listOf("android.bluetooth.BluetoothDevice", "int", "int"),
        )
        requireMethod(
            symbols,
            "leAudioActiveDeviceChanged",
            leAudio,
            "void",
            listOf("android.bluetooth.BluetoothDevice"),
        )
        requireMethod(
            symbols,
            "leAudioGroupDevices",
            leAudio,
            "java.util.List",
            listOf("android.bluetooth.BluetoothDevice"),
        )
        requireField(symbols, "leAudioHandler", leAudio, "android.os.Handler")
    }

    private const val A2DP_SERVICE = "com.android.bluetooth.a2dp.A2dpService"
    private const val LE_AUDIO_SERVICE = "com.android.bluetooth.le_audio.LeAudioService"
}

private fun SymbolQuery.requireBluetoothBridge(): DexKitBridge =
    requireNotNull(bridge) { "Bluetooth symbol resolution requires DexKit" }

private fun SymbolQuery.uniqueBluetoothClass(
    bridge: DexKitBridge,
    role: String,
    className: String,
): ClassData {
    val candidates = bridge.findClass { matcher { className(className) } }
    val descriptor = requireUnique(role, candidates.map { it.descriptor })
    return candidates.single { it.descriptor == descriptor }
}

private fun ClassData.requireMethod(name: String, returnType: String, vararg params: String): MethodData =
    methods.filter {
        it.name == name && it.returnTypeName == returnType && it.paramTypeNames == params.toList()
    }.singleOrNull() ?: error("$this has no unique $name(${params.joinToString()}):$returnType")

private fun ClassData.requireNamedField(name: String, type: String): FieldData =
    fields.filter { it.name == name && it.typeName == type }.singleOrNull()
        ?: error("$this has no unique field $name:$type")

private fun ClassData.ref() = SymbolReference(SymbolKind.CLASS, descriptor)
private fun MethodData.ref() = SymbolReference(SymbolKind.METHOD, descriptor)
private fun FieldData.ref() = SymbolReference(SymbolKind.FIELD, descriptor)

private fun SymbolReference.className(): String =
    descriptor.removeSurrounding("L", ";").replace('/', '.')

private fun requireMethod(
    symbols: Map<String, SymbolReference>,
    key: String,
    owner: String,
    returnType: String,
    params: List<String>,
) {
    val method = DexMethod(symbols.getValue(key).descriptor)
    require(method.className == owner) { "$key escaped $owner: $method" }
    require(method.returnTypeName == returnType && method.paramTypeNames == params) {
        "$key signature changed: $method"
    }
}

private fun requireField(
    symbols: Map<String, SymbolReference>,
    key: String,
    owner: String,
    type: String,
) {
    val field = DexField(symbols.getValue(key).descriptor)
    require(field.className == owner && field.typeName == type) { "$key signature changed: $field" }
}
