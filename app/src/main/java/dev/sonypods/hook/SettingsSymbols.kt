package dev.sonypods.hook

import dev.sonypods.hook.symbols.DexKitSymbolBundleDefinition
import dev.sonypods.hook.symbols.FixedSymbolBundleDefinition
import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolQuery
import dev.sonypods.hook.symbols.SymbolReference
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod


/** Stable AIDL client proxy used by Settings to call Xiaomi Bluetooth. */
internal object SettingsServiceProxySymbols : FixedSymbolBundleDefinition {
    override val id = "settings-headset-service-proxy"
    override val schemaVersion = 1
    override val requiredSymbols = setOf(
        "checkSupport",
        "getDeviceInfo",
        "isSupportAudioSwitch",
        "setCommonCommand",
        "connect",
        "getDeviceConfig",
        "getCommonConfig",
        "isMiTWS",
        "checkIsMiTWS",
        "getRingFindState",
        "changeAncMode",
        "changeAncLevel",
    )

    private const val PROXY = "Lcom/android/bluetooth/ble/app/IMiuiHeadsetService\$Stub\$Proxy;"
    override val symbols = mapOf(
        "checkSupport" to method("$PROXY->checkSupport(Landroid/bluetooth/BluetoothDevice;)Ljava/lang/String;"),
        "getDeviceInfo" to method("$PROXY->getDeviceInfo(Ljava/lang/String;)Ljava/lang/String;"),
        "isSupportAudioSwitch" to method("$PROXY->isSupportAudioSwitch(Ljava/lang/String;)Ljava/lang/String;"),
        "setCommonCommand" to method("$PROXY->setCommonCommand(ILjava/lang/String;Landroid/bluetooth/BluetoothDevice;)Ljava/lang/String;"),
        "connect" to method("$PROXY->connect(Landroid/bluetooth/BluetoothDevice;)V"),
        "getDeviceConfig" to method("$PROXY->getDeviceConfig(Landroid/bluetooth/BluetoothDevice;)V"),
        "getCommonConfig" to method("$PROXY->getCommonConfig(Landroid/bluetooth/BluetoothDevice;Ljava/lang/String;)V"),
        "isMiTWS" to method("$PROXY->isMiTWS(Ljava/lang/String;)Z"),
        "checkIsMiTWS" to method("$PROXY->checkIsMiTWS(Ljava/lang/String;)Z"),
        "getRingFindState" to method("$PROXY->getRingFindState(Ljava/lang/String;)Z"),
        "changeAncMode" to method("$PROXY->changeAncMode(ILandroid/bluetooth/BluetoothDevice;)V"),
        "changeAncLevel" to method("$PROXY->changeAncLevel(Ljava/lang/String;Landroid/bluetooth/BluetoothDevice;)V"),
    )

    override fun validate(symbols: Map<String, SymbolReference>) {
        val owner = PROXY.removeSurrounding("L", ";").replace('/', '.')
        symbols.values.forEach { reference ->
            require(DexMethod(reference.descriptor).className == owner) {
                "Settings service proxy ABI changed: ${reference.descriptor}"
            }
        }
    }

    private fun method(descriptor: String) = SymbolReference(SymbolKind.METHOD, descriptor)
}

/** Settings headset activity entry points, located from its intent/service protocol. */
internal object SettingsActivitySymbols : DexKitSymbolBundleDefinition {
    override val id = "settings-headset-activity"
    override val schemaVersion = 1
    override val requiredSymbols = setOf("activity", "onCreate", "getSupport", "getDevice", "deviceField")

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = query.requireBridge()
        val activity = query.uniqueClassByName(
            "activity",
            bridge,
            "com.android.settings.bluetooth.MiuiHeadsetActivity",
        ) { candidate ->
            candidate.method("onCreate", "void", "android.os.Bundle") != null &&
                candidate.method("getSupport", "java.lang.String") != null &&
                candidate.method("getDevice", "android.bluetooth.BluetoothDevice") != null
        }
        return mapOf(
            "activity" to activity.ref(),
            "onCreate" to activity.requireMethod("onCreate", "void", "android.os.Bundle").ref(),
            "getSupport" to activity.requireMethod("getSupport", "java.lang.String").ref(),
            "getDevice" to activity.requireMethod("getDevice", "android.bluetooth.BluetoothDevice").ref(),
            "deviceField" to activity.requireUniqueField("activity device", "android.bluetooth.BluetoothDevice").ref(),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) = validateOwnedSymbols(symbols, "activity")
}

/** Optional split-install trampoline used before the real headset activity. */
internal object SettingsActivityPluginSymbols : DexKitSymbolBundleDefinition {
    override val id = "settings-headset-activity-plugin"
    override val schemaVersion = 1
    override val requiredSymbols = setOf("plugin", "onCreate")

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = query.requireBridge()
        val plugin = query.uniqueClassByName(
            "plugin",
            bridge,
            "com.android.settings.bluetooth.MiuiHeadsetActivityPlugin",
        ) { candidate ->
            candidate.method("onCreate", "void", "android.os.Bundle") != null &&
                candidate.fields.any { it.typeName == "android.bluetooth.BluetoothDevice" }
        }
        return mapOf(
            "plugin" to plugin.ref(),
            "onCreate" to plugin.requireMethod("onCreate", "void", "android.os.Bundle").ref(),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) = validateOwnedSymbols(symbols, "plugin")
}

/** Static support gates that decide whether Settings may render and operate the headset page. */
internal object SettingsSupportSymbols : DexKitSymbolBundleDefinition {
    override val id = "settings-headset-support"
    override val schemaVersion = 1
    override val requiredSymbols = setOf("owner", "checkSupport", "isBleMmaConnectContext", "isBleMmaConnectService")

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = query.requireBridge()
        val owner = query.uniqueClassByName(
            "owner",
            bridge,
            "com.android.settings.bluetooth.HeadsetIDConstants",
        ) { it.method("checkSupport", "boolean", "java.lang.String") != null }
        return mapOf(
            "owner" to owner.ref(),
            "checkSupport" to owner.requireMethod("checkSupport", "boolean", "java.lang.String").ref(),
            "isBleMmaConnectContext" to owner.requireMethod(
                "isBleMmaConnect",
                "boolean",
                "android.content.Context",
                "android.bluetooth.BluetoothDevice",
                "java.lang.String",
            ).ref(),
            "isBleMmaConnectService" to owner.requireMethod(
                "isBleMmaConnect",
                "boolean",
                HEADSET_SERVICE,
                "android.bluetooth.BluetoothDevice",
                "java.lang.String",
            ).ref(),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) = validateOwnedSymbols(symbols, "owner")
}

/** Battery widget methods and state used for Sony's one/three-cell rendering. */
internal object SettingsBatterySymbols : DexKitSymbolBundleDefinition {
    override val id = "settings-headset-battery"
    override val schemaVersion = 1
    override val requiredSymbols = setOf("owner", "onBatteryChangedString", "onBatteryChangedInts", "deviceField", "rootViewField")

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = query.requireBridge()
        val owner = query.uniqueClassByName(
            "owner",
            bridge,
            "com.android.settings.bluetooth.tws.MiuiHeadsetBattery",
        ) { it.method("onBatteryChanged", "void", "java.lang.String") != null }
        return mapOf(
            "owner" to owner.ref(),
            "onBatteryChangedString" to owner.requireMethod("onBatteryChanged", "void", "java.lang.String").ref(),
            "onBatteryChangedInts" to owner.requireMethod("onBatteryChanged", "void", "int", "int", "int").ref(),
            "deviceField" to owner.requireUniqueField("battery device", "android.bluetooth.BluetoothDevice").ref(),
            "rootViewField" to owner.requireNamedField("mRootView", "java.lang.ref.WeakReference").ref(),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) = validateOwnedSymbols(symbols, "owner")
}

/** Complete Settings headset fragment contract consumed by Sony state/UI injection. */
internal object SettingsFragmentSymbols : DexKitSymbolBundleDefinition {
    override val id = "settings-headset-fragment"
    override val schemaVersion = 1
    override val requiredSymbols = setOf(
        "fragment",
        "onCreate",
        "onCreateView",
        "onServiceConnected",
        "refreshStatus",
        "handleConnectMmaFailed",
        "refreshStatusUi",
        "updateAncMode",
        "updateAncLevel",
        "updateAncUi",
        "updateAtUiInfo",
        "activityField",
        "deviceField",
        "rootViewField",
        "deviceIdField",
        "supportField",
        "serviceField",
        "bluetoothHfpField",
        "cachedDeviceField",
        "supportAncField",
        "ancCachedField",
        "pendingAncField",
        "ancPendingStatusField",
        "virtualSurroundField",
    )

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = query.requireBridge()
        val fragment = query.uniqueClassByName(
            "fragment",
            bridge,
            "com.android.settings.bluetooth.MiuiHeadsetFragment",
        ) { candidate ->
            candidate.method("refreshStatus", "void", "java.lang.String", "java.lang.String") != null &&
                candidate.method("updateAncUi", "void", "java.lang.String", "boolean") != null
        }
        fun method(name: String, returnType: String, vararg params: String) =
            fragment.requireMethod(name, returnType, *params).ref()
        fun field(name: String, type: String) = fragment.requireNamedField(name, type).ref()
        return mapOf(
            "fragment" to fragment.ref(),
            "onCreate" to method("onCreate", "void", "android.os.Bundle"),
            "onCreateView" to method(
                "onCreateView",
                "android.view.View",
                "android.view.LayoutInflater",
                "android.view.ViewGroup",
                "android.os.Bundle",
            ),
            "onServiceConnected" to method("onServiceConnected", "void"),
            "refreshStatus" to method("refreshStatus", "void", "java.lang.String", "java.lang.String"),
            "handleConnectMmaFailed" to method("handleConnectMmaFailed", "void", "java.lang.String"),
            "refreshStatusUi" to method("refreshStatusUi", "void", "java.lang.String"),
            "updateAncMode" to method("updateAncMode", "void", "int", "boolean"),
            "updateAncLevel" to method("updateAncLevel", "void", "java.lang.String", "boolean"),
            "updateAncUi" to method("updateAncUi", "void", "java.lang.String", "boolean"),
            "updateAtUiInfo" to method("updateAtUiInfo", "void", "java.lang.String"),
            "activityField" to field("mActivity", "android.app.Activity"),
            "deviceField" to field("mDevice", "android.bluetooth.BluetoothDevice"),
            "rootViewField" to field("mRootView", "android.view.View"),
            "deviceIdField" to field("mDeviceId", "java.lang.String"),
            "supportField" to field("mSupport", "java.lang.String"),
            "serviceField" to field("mService", HEADSET_SERVICE),
            "bluetoothHfpField" to field("mBluetoothHfp", "android.bluetooth.BluetoothHeadset"),
            "cachedDeviceField" to field("mCachedDevice", "com.android.settingslib.bluetooth.CachedBluetoothDevice"),
            "supportAncField" to field("mSupportAnc", "java.lang.Boolean"),
            "ancCachedField" to field("mAncCached", "java.lang.String"),
            "pendingAncField" to field("mPendingAnc", "java.lang.String"),
            "ancPendingStatusField" to field("mAncPendingStatus", "int"),
            "virtualSurroundField" to field("mVirtualSurroundSound", "androidx.preference.CheckBoxPreference"),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) = validateOwnedSymbols(symbols, "fragment")
}

/** Stock headset animation surface replaced by the catalog product image. */
internal object SettingsRenderSymbols : DexKitSymbolBundleDefinition {
    override val id = "settings-headset-render"
    override val schemaVersion = 1
    override val requiredSymbols = setOf("animation", "loadDefaultInternal", "contextField", "rootViewField", "handlerField")

    override fun resolve(query: SymbolQuery): Map<String, SymbolReference> {
        val bridge = query.requireBridge()
        val animation = query.uniqueClassByName(
            "animation",
            bridge,
            "com.android.settings.bluetooth.tws.MiuiHeadsetAnimation",
        ) { it.method("loadDefaultInternal", "void") != null }
        return mapOf(
            "animation" to animation.ref(),
            "loadDefaultInternal" to animation.requireMethod("loadDefaultInternal", "void").ref(),
            "contextField" to animation.requireNamedField("mContext", "java.lang.ref.WeakReference").ref(),
            "rootViewField" to animation.requireNamedField("mRootView", "java.lang.ref.WeakReference").ref(),
            "handlerField" to animation.requireNamedField("mHandler", "java.lang.ref.WeakReference").ref(),
        )
    }

    override fun validate(symbols: Map<String, SymbolReference>) = validateOwnedSymbols(symbols, "animation")
}

private const val HEADSET_SERVICE = "com.android.bluetooth.ble.app.IMiuiHeadsetService"

private fun SymbolQuery.requireBridge(): DexKitBridge =
    requireNotNull(bridge) { "Settings symbol resolution requires DexKit" }

private fun SymbolQuery.uniqueClassByName(
    role: String,
    bridge: DexKitBridge,
    className: String,
    predicate: (ClassData) -> Boolean,
): ClassData {
    val candidates = bridge.findClass { matcher { className(className) } }.filter(predicate)
    val descriptor = requireUnique(role, candidates.map { it.descriptor })
    return candidates.single { it.descriptor == descriptor }
}

private fun ClassData.method(name: String, returnType: String, vararg params: String): MethodData? =
    methods.singleOrNull {
        it.name == name && it.returnTypeName == returnType && it.paramTypeNames == params.toList()
    }

private fun ClassData.requireMethod(name: String, returnType: String, vararg params: String): MethodData =
    methods.filter {
        it.name == name && it.returnTypeName == returnType && it.paramTypeNames == params.toList()
    }.singleOrNull() ?: error("$this has no unique $name(${params.joinToString()}):$returnType")

private fun ClassData.requireNamedField(name: String, type: String): FieldData =
    fields.filter { it.name == name && it.typeName == type }.singleOrNull()
        ?: error("$this has no unique field $name:$type")

private fun ClassData.requireUniqueField(role: String, type: String): FieldData =
    fields.filter { it.typeName == type }.singleOrNull()
        ?: error("$role field of type $type is absent or ambiguous in $name")

private fun ClassData.ref() = SymbolReference(SymbolKind.CLASS, descriptor)
private fun MethodData.ref() = SymbolReference(SymbolKind.METHOD, descriptor)
private fun FieldData.ref() = SymbolReference(SymbolKind.FIELD, descriptor)

private fun validateOwnedSymbols(symbols: Map<String, SymbolReference>, ownerKey: String) {
    val owner = symbols.getValue(ownerKey).descriptor.removeSurrounding("L", ";").replace('/', '.')
    symbols.filterKeys { it != ownerKey }.values.forEach { reference ->
        when (reference.kind) {
            SymbolKind.METHOD -> require(DexMethod(reference.descriptor).className == owner) {
                "Settings method escaped $owner: ${reference.descriptor}"
            }
            SymbolKind.FIELD -> require(DexField(reference.descriptor).className == owner) {
                "Settings field escaped $owner: ${reference.descriptor}"
            }
            SymbolKind.CLASS -> error("unexpected nested Settings class symbol: ${reference.descriptor}")
        }
    }
}
