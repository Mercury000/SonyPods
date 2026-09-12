package dev.sonypods.hook

import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolReference
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsSymbolsTest {
    @Test
    fun fragmentContractAcceptsOneOwner() {
        SettingsFragmentSymbols.validate(fragmentSymbols())
    }

    @Test
    fun fragmentContractRejectsEscapedMethod() {
        val symbols = fragmentSymbols().toMutableMap()
        symbols["updateAncUi"] = method("Lcom/example/Other;->updateAncUi(Ljava/lang/String;Z)V")
        assertThrows(IllegalArgumentException::class.java) {
            SettingsFragmentSymbols.validate(symbols)
        }
    }

    @Test
    fun activityContractAcceptsResolvedMembers() {
        SettingsActivitySymbols.validate(
            mapOf(
                "activity" to clazz(ACTIVITY),
                "onCreate" to method("$ACTIVITY->onCreate(Landroid/os/Bundle;)V"),
                "getSupport" to method("$ACTIVITY->getSupport()Ljava/lang/String;"),
                "getDevice" to method("$ACTIVITY->getDevice()Landroid/bluetooth/BluetoothDevice;"),
                "deviceField" to field("$ACTIVITY->mDevice:Landroid/bluetooth/BluetoothDevice;"),
            ),
        )
    }

    private fun fragmentSymbols(): Map<String, SymbolReference> = mapOf(
        "fragment" to clazz(FRAGMENT),
        "onCreate" to method("$FRAGMENT->onCreate(Landroid/os/Bundle;)V"),
        "onCreateView" to method("$FRAGMENT->onCreateView(Landroid/view/LayoutInflater;Landroid/view/ViewGroup;Landroid/os/Bundle;)Landroid/view/View;"),
        "onServiceConnected" to method("$FRAGMENT->onServiceConnected()V"),
        "refreshStatus" to method("$FRAGMENT->refreshStatus(Ljava/lang/String;Ljava/lang/String;)V"),
        "handleConnectMmaFailed" to method("$FRAGMENT->handleConnectMmaFailed(Ljava/lang/String;)V"),
        "refreshStatusUi" to method("$FRAGMENT->refreshStatusUi(Ljava/lang/String;)V"),
        "updateAncMode" to method("$FRAGMENT->updateAncMode(IZ)V"),
        "updateAncLevel" to method("$FRAGMENT->updateAncLevel(Ljava/lang/String;Z)V"),
        "updateAncUi" to method("$FRAGMENT->updateAncUi(Ljava/lang/String;Z)V"),
        "updateAtUiInfo" to method("$FRAGMENT->updateAtUiInfo(Ljava/lang/String;)V"),
        "activityField" to field("$FRAGMENT->mActivity:Landroid/app/Activity;"),
        "deviceField" to field("$FRAGMENT->mDevice:Landroid/bluetooth/BluetoothDevice;"),
        "rootViewField" to field("$FRAGMENT->mRootView:Landroid/view/View;"),
        "deviceIdField" to field("$FRAGMENT->mDeviceId:Ljava/lang/String;"),
        "supportField" to field("$FRAGMENT->mSupport:Ljava/lang/String;"),
        "serviceField" to field("$FRAGMENT->mService:Lcom/android/bluetooth/ble/app/IMiuiHeadsetService;"),
        "bluetoothHfpField" to field("$FRAGMENT->mBluetoothHfp:Landroid/bluetooth/BluetoothHeadset;"),
        "cachedDeviceField" to field("$FRAGMENT->mCachedDevice:Lcom/android/settingslib/bluetooth/CachedBluetoothDevice;"),
        "supportAncField" to field("$FRAGMENT->mSupportAnc:Ljava/lang/Boolean;"),
        "ancCachedField" to field("$FRAGMENT->mAncCached:Ljava/lang/String;"),
        "pendingAncField" to field("$FRAGMENT->mPendingAnc:Ljava/lang/String;"),
        "ancPendingStatusField" to field("$FRAGMENT->mAncPendingStatus:I"),
        "virtualSurroundField" to field("$FRAGMENT->mVirtualSurroundSound:Landroidx/preference/CheckBoxPreference;"),
    )

    private fun clazz(descriptor: String) = SymbolReference(SymbolKind.CLASS, descriptor)
    private fun method(descriptor: String) = SymbolReference(SymbolKind.METHOD, descriptor)
    private fun field(descriptor: String) = SymbolReference(SymbolKind.FIELD, descriptor)

    companion object {
        private const val ACTIVITY = "Lcom/example/HeadsetActivity;"
        private const val FRAGMENT = "Lcom/example/HeadsetFragment;"
    }
}
