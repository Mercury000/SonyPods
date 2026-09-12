package dev.sonypods.hook

import dev.sonypods.hook.symbols.DexKitSymbolBundleDefinition
import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolQuery
import dev.sonypods.hook.symbols.SymbolReference
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

/** Bluetooth Extension notification surface and its connected-toast request record. */
internal object BluetoothExtensionNotificationSymbols : DexKitSymbolBundleDefinition {
    override val id = "bluetooth-extension-notification"
    override val schemaVersion = 1
    override val requiredSymbols = setOf(
        "notification",
        "contextField",
        "invokeStatusBar",
        "updateParameters",
        "showConnectedToast",
        "notificationApiToast",
        "request",
        "requestLeftField",
        "requestRightField",
        "requestWearField",
        "requestDeviceField",
    )
    override val requiredPrefixes = setOf("constructor.")

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = query.requireExtensionBridge()
        val notification = query.uniqueClass(bridge, "notification", NOTIFICATION)
        val constructors = notification.methods.filter { it.isConstructor && it.paramCount == 2 }
            .sortedBy { it.descriptor }
        check(constructors.isNotEmpty()) { "MiuiBluetoothNotification has no two-argument constructor" }
        val update = notification.requireMethodByShape("updateParameters", "void", 1)
        val request = requireNotNull(bridge.getClassData(update.paramTypeNames.single())) {
            "connected-toast request class is absent: ${update.paramTypeNames.single()}"
        }
        val requestConstructor = request.methods.singleOrNull { method ->
            method.isConstructor && method.paramTypeNames == listOf(
                "int", "int", "int", "int", "android.bluetooth.BluetoothDevice", "java.lang.String",
            )
        } ?: error("connected-toast request constructor contract is absent or ambiguous")
        val requestWrites = requestConstructor.usingFields
            .filter { it.usingType == FieldUsingType.Write && it.field.declaredClassName == request.name }
            .map { it.field }
            .distinctBy { it.descriptor }
        check(requestWrites.size == 6) {
            "connected-toast request constructor must assign six fields, found ${requestWrites.size}"
        }
        check(
            requestWrites.count { it.typeName == "int" } == 4 &&
                requestWrites.count { it.typeName == "android.bluetooth.BluetoothDevice" } == 1 &&
                requestWrites.count { it.typeName == "java.lang.String" } == 1,
        ) { "connected-toast request field types changed: ${requestWrites.map { it.typeName }}" }
        val requestInts = requestWrites.filter { it.typeName == "int" }
        val requestLeft = requestInts[1]
        val requestRight = requestInts[2]
        val requestWear = requestInts[3]
        val requestDevice = requestWrites.single { it.typeName == "android.bluetooth.BluetoothDevice" }
        val updateReads = update.usingFields
            .filter { it.usingType == FieldUsingType.Read && it.field.declaredClassName == request.name }
            .map { it.field.descriptor }
            .toSet()
        val updateWrites = update.usingFields
            .filter { it.usingType == FieldUsingType.Write && it.field.declaredClassName == request.name }
            .map { it.field.descriptor }
            .toSet()
        listOf(requestWear, requestDevice).forEach { field ->
            check(field.descriptor in updateReads) {
                "connected-toast field ${field.descriptor} is not read by updateParameters"
            }
        }
        listOf(requestLeft, requestRight, requestWear).forEach { field ->
            check(field.descriptor in updateWrites) {
                "connected-toast field ${field.descriptor} is not written by updateParameters"
            }
        }
        return buildMap {
            put("notification", notification.ref())
            constructors.forEachIndexed { index, method -> put("constructor.$index", method.ref()) }
            put("contextField", notification.requireUniqueField("contextField", "android.content.Context").ref())
            put(
                "invokeStatusBar",
                notification.requireMethod(
                    "invokeStatusBar", "void", "android.content.Context", "java.lang.String", "android.os.Bundle",
                ).ref(),
            )
            put("updateParameters", update.ref())
            put(
                "showConnectedToast",
                notification.requireMethod(
                    "showConnectedToast", "void", "int", "int", "int", "int",
                    "android.bluetooth.BluetoothDevice", "java.lang.String",
                ).ref(),
            )
            put(
                "notificationApiToast",
                query.uniqueMethod(
                    bridge,
                    "notificationApiToast",
                    NOTIFICATION_API,
                    "showNewConnectedToast",
                    "void",
                    listOf("int", "int", "int", "int", "android.bluetooth.BluetoothDevice", "java.lang.String"),
                ).ref(),
            )
            put("request", request.ref())
            put("requestLeftField", requestLeft.ref())
            put("requestRightField", requestRight.ref())
            put("requestWearField", requestWear.ref())
            put("requestDeviceField", requestDevice.ref())
        }
    }

    override fun validate(symbols: Map<String, SymbolReference>) {
        val notification = symbols.getValue("notification").className()
        val request = symbols.getValue("request").className()
        require(notification == NOTIFICATION) { "unexpected notification owner: $notification" }
        requireField(symbols, "contextField", notification, "android.content.Context")
        val requestFields = listOf("requestLeftField", "requestRightField", "requestWearField", "requestDeviceField")
        requestFields.forEach { require(DexField(symbols.getValue(it).descriptor).className == request) { "$it escaped $request" } }
        require(requestFields.map { symbols.getValue(it).descriptor }.distinct().size == requestFields.size) {
            "connected-toast request fields are not distinct"
        }
        listOf("requestLeftField", "requestRightField", "requestWearField").forEach {
            requireField(symbols, it, request, "int")
        }
        requireField(symbols, "requestDeviceField", request, "android.bluetooth.BluetoothDevice")
        requireMethod(symbols, "updateParameters", notification, "void", listOf(request))
        requireMethod(
            symbols, "invokeStatusBar", notification, "void",
            listOf("android.content.Context", "java.lang.String", "android.os.Bundle"),
        )
        requireMethod(
            symbols, "showConnectedToast", notification, "void",
            listOf("int", "int", "int", "int", "android.bluetooth.BluetoothDevice", "java.lang.String"),
        )
        requireMethod(
            symbols, "notificationApiToast", NOTIFICATION_API, "void",
            listOf("int", "int", "int", "int", "android.bluetooth.BluetoothDevice", "java.lang.String"),
        )
    }

    private const val NOTIFICATION = "com.android.bluetooth.ble.app.MiuiBluetoothNotification"
    private const val NOTIFICATION_API = "com.android.bluetooth.ble.app.MiuiBluetoothNotificationApi"
}

/** Bluetooth Extension service, concrete Binder implementation and callback bridge. */
internal object BluetoothExtensionHeadsetSymbols : DexKitSymbolBundleDefinition {
    override val id = "bluetooth-extension-headset"
    override val schemaVersion = 1
    override val requiredSymbols = setOf(
        "service", "onBind", "onCreate", "notificationField", "binder", "callback", "callbackFactory",
        "callbackRefresh", "checkSupport", "getDeviceInfo", "isSupportAudioSwitch", "setCommonCommand",
        "connect", "getDeviceConfig", "getCommonConfig", "isMiTWS", "checkIsMiTWS", "getRingFindState",
        "changeAncMode", "changeAncLevel", "register", "registerCallbackDevice", "unregister",
    )

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = query.requireExtensionBridge()
        val service = query.uniqueClass(bridge, "service", SERVICE)
        val callback = query.uniqueClass(bridge, "callback", CALLBACK)
        val checkSupport = query.uniqueMethod(
            bridge, "checkSupport", null, "checkSupport", "java.lang.String",
            listOf("android.bluetooth.BluetoothDevice"),
        ) { it.className.startsWith(HEADSET_PACKAGE) }
        val binder = requireNotNull(bridge.getClassData(checkSupport.className)) {
            "headset Binder owner is absent: ${checkSupport.className}"
        }
        fun binderMethod(name: String, returnType: String, vararg params: String) =
            binder.requireMethod(name, returnType, *params).ref()
        val callbackFactory = query.uniqueMethod(
            bridge, "callbackFactory", null, null, CALLBACK, listOf("android.os.IBinder"),
        ) { Modifier.isStatic(it.modifiers) && it.className.startsWith(CALLBACK) }

        return mapOf(
            "service" to service.ref(),
            "onBind" to service.requireMethod("onBind", "android.os.IBinder", "android.content.Intent").ref(),
            "onCreate" to service.requireMethod("onCreate", "void").ref(),
            "notificationField" to service.requireUniqueField(
                "notificationField", "com.android.bluetooth.ble.app.MiuiBluetoothNotification",
            ).ref(),
            "binder" to binder.ref(),
            "callback" to callback.ref(),
            "callbackFactory" to callbackFactory.ref(),
            "callbackRefresh" to callback.requireMethod(
                "refreshStatus", "void", "java.lang.String", "java.lang.String",
            ).ref(),
            "checkSupport" to checkSupport.ref(),
            "getDeviceInfo" to binderMethod("getDeviceInfo", "java.lang.String", "java.lang.String"),
            "isSupportAudioSwitch" to binderMethod("isSupportAudioSwitch", "java.lang.String", "java.lang.String"),
            "setCommonCommand" to binderMethod(
                "setCommonCommand", "java.lang.String", "int", "java.lang.String", "android.bluetooth.BluetoothDevice",
            ),
            "connect" to binderMethod("connect", "void", "android.bluetooth.BluetoothDevice"),
            "getDeviceConfig" to binderMethod("getDeviceConfig", "void", "android.bluetooth.BluetoothDevice"),
            "getCommonConfig" to binderMethod(
                "getCommonConfig", "void", "android.bluetooth.BluetoothDevice", "java.lang.String",
            ),
            "isMiTWS" to binderMethod("isMiTWS", "boolean", "java.lang.String"),
            "checkIsMiTWS" to binderMethod("checkIsMiTWS", "boolean", "java.lang.String"),
            "getRingFindState" to binderMethod("getRingFindState", "boolean", "java.lang.String"),
            "changeAncMode" to binderMethod("changeAncMode", "void", "int", "android.bluetooth.BluetoothDevice"),
            "changeAncLevel" to binderMethod(
                "changeAncLevel", "void", "java.lang.String", "android.bluetooth.BluetoothDevice",
            ),
            "register" to binderMethod("register", "void", CALLBACK),
            "registerCallbackDevice" to binderMethod(
                "registerCallbackDevice", "void", CALLBACK, "android.bluetooth.BluetoothDevice",
            ),
            "unregister" to binderMethod("unregister", "void", CALLBACK, "android.bluetooth.BluetoothDevice"),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) {
        val service = symbols.getValue("service").className()
        val binder = symbols.getValue("binder").className()
        val callback = symbols.getValue("callback").className()
        require(service == SERVICE) { "unexpected headset service: $service" }
        require(callback == CALLBACK) { "unexpected callback interface: $callback" }
        require(binder.startsWith(HEADSET_PACKAGE)) { "Binder escaped headset package: $binder" }
        requireMethod(symbols, "onBind", service, "android.os.IBinder", listOf("android.content.Intent"))
        requireMethod(symbols, "onCreate", service, "void", emptyList())
        requireField(
            symbols, "notificationField", service,
            "com.android.bluetooth.ble.app.MiuiBluetoothNotification",
        )
        requireMethod(symbols, "checkSupport", binder, "java.lang.String", listOf("android.bluetooth.BluetoothDevice"))
        val callbackFactory = symbols.getValue("callbackFactory").method()
        require(callbackFactory.className.startsWith(CALLBACK) && callbackFactory.className != callback) {
            "callback factory escaped AIDL callback hierarchy: $callbackFactory"
        }
        requireMethod(symbols, "callbackFactory", callbackFactory.className, callback, listOf("android.os.IBinder"))
        requireMethod(symbols, "callbackRefresh", callback, "void", listOf("java.lang.String", "java.lang.String"))
        val expected = mapOf(
            "getDeviceInfo" to Pair("java.lang.String", listOf("java.lang.String")),
            "isSupportAudioSwitch" to Pair("java.lang.String", listOf("java.lang.String")),
            "setCommonCommand" to Pair("java.lang.String", listOf("int", "java.lang.String", "android.bluetooth.BluetoothDevice")),
            "connect" to Pair("void", listOf("android.bluetooth.BluetoothDevice")),
            "getDeviceConfig" to Pair("void", listOf("android.bluetooth.BluetoothDevice")),
            "getCommonConfig" to Pair("void", listOf("android.bluetooth.BluetoothDevice", "java.lang.String")),
            "isMiTWS" to Pair("boolean", listOf("java.lang.String")),
            "checkIsMiTWS" to Pair("boolean", listOf("java.lang.String")),
            "getRingFindState" to Pair("boolean", listOf("java.lang.String")),
            "changeAncMode" to Pair("void", listOf("int", "android.bluetooth.BluetoothDevice")),
            "changeAncLevel" to Pair("void", listOf("java.lang.String", "android.bluetooth.BluetoothDevice")),
            "register" to Pair("void", listOf(callback)),
            "registerCallbackDevice" to Pair("void", listOf(callback, "android.bluetooth.BluetoothDevice")),
            "unregister" to Pair("void", listOf(callback, "android.bluetooth.BluetoothDevice")),
        )
        expected.forEach { (key, shape) -> requireMethod(symbols, key, binder, shape.first, shape.second) }
    }

    private const val SERVICE = "com.android.bluetooth.ble.app.headset.BluetoothHeadsetService"
    private const val CALLBACK = "com.android.bluetooth.ble.app.IMiuiHeadsetCallback"
    private const val HEADSET_PACKAGE = "com.android.bluetooth.ble.app.headset."
}

private fun SymbolQuery.requireExtensionBridge(): DexKitBridge =
    requireNotNull(bridge) { "Bluetooth Extension symbol resolution requires DexKit" }

private fun SymbolQuery.uniqueClass(bridge: DexKitBridge, role: String, name: String): ClassData {
    val candidates = bridge.findClass { matcher { className(name) } }
    val descriptor = requireUnique(role, candidates.map { it.descriptor })
    return candidates.single { it.descriptor == descriptor }
}

private fun SymbolQuery.uniqueMethod(
    bridge: DexKitBridge,
    role: String,
    owner: String?,
    name: String?,
    returnType: String,
    params: List<String>,
    extra: (MethodData) -> Boolean = { true },
): MethodData {
    val candidates = bridge.findMethod {
        matcher {
            owner?.let(::declaredClass)
            name?.let(::name)
            returnType(returnType)
            paramTypes(*params.toTypedArray())
        }
    }.filter(extra)
    val descriptor = requireUnique(role, candidates.map { it.descriptor })
    return candidates.single { it.descriptor == descriptor }
}

private fun ClassData.requireMethod(name: String, returnType: String, vararg params: String): MethodData =
    methods.filter { it.name == name && it.returnTypeName == returnType && it.paramTypeNames == params.toList() }
        .singleOrNull() ?: error("$this has no unique $name(${params.joinToString()}):$returnType")

private fun ClassData.requireMethodByShape(name: String, returnType: String, paramCount: Int): MethodData =
    methods.filter { it.name == name && it.returnTypeName == returnType && it.paramCount == paramCount }
        .singleOrNull() ?: error("$this has no unique $name/$paramCount:$returnType")

private fun ClassData.requireUniqueField(role: String, type: String): FieldData =
    fields.filter { it.typeName == type }.singleOrNull()
        ?: error("$this has no unique $role field:$type")

private fun ClassData.ref() = SymbolReference(SymbolKind.CLASS, descriptor)
private fun MethodData.ref() = SymbolReference(SymbolKind.METHOD, descriptor)
private fun FieldData.ref() = SymbolReference(SymbolKind.FIELD, descriptor)
private fun SymbolReference.className() = descriptor.removeSurrounding("L", ";").replace('/', '.')
private fun SymbolReference.method() = DexMethod(descriptor)

private fun requireMethod(
    symbols: Map<String, SymbolReference>, key: String, owner: String, returnType: String, params: List<String>,
) {
    val method = symbols.getValue(key).method()
    require(method.className == owner && method.returnTypeName == returnType && method.paramTypeNames == params) {
        "$key signature changed: $method"
    }
}

private fun requireField(
    symbols: Map<String, SymbolReference>, key: String, owner: String, type: String,
) {
    val field = DexField(symbols.getValue(key).descriptor)
    require(field.className == owner && field.typeName == type) { "$key signature changed: $field" }
}
