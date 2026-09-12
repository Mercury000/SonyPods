package dev.sonypods.hook

import dev.sonypods.hook.symbols.SymbolKind
import dev.sonypods.hook.symbols.SymbolReference
import org.junit.Assert.assertThrows
import org.junit.Test

class BluetoothExtensionSymbolsTest {
    @Test
    fun notificationContractAcceptsResolvedToastDataFlow() {
        BluetoothExtensionNotificationSymbols.validate(notificationSymbols())
    }

    @Test
    fun notificationContractRejectsEscapedRequestField() {
        val symbols = notificationSymbols().toMutableMap()
        symbols["requestLeftField"] = field("Lcom/example/Other;->left:I")
        assertThrows(IllegalArgumentException::class.java) {
            BluetoothExtensionNotificationSymbols.validate(symbols)
        }
    }

    @Test
    fun headsetContractAcceptsResolvedBinderSurface() {
        BluetoothExtensionHeadsetSymbols.validate(headsetSymbols())
    }

    @Test
    fun headsetContractRejectsEscapedCallbackFactory() {
        val symbols = headsetSymbols().toMutableMap()
        symbols["callbackFactory"] = method("Lcom/example/Factory;->create(Landroid/os/IBinder;)Lcom/android/bluetooth/ble/app/IMiuiHeadsetCallback;")
        assertThrows(IllegalArgumentException::class.java) {
            BluetoothExtensionHeadsetSymbols.validate(symbols)
        }
    }

    private fun notificationSymbols(): Map<String, SymbolReference> = mapOf(
        "notification" to clazz(NOTIFICATION),
        "contextField" to field("$NOTIFICATION->mContext:Landroid/content/Context;"),
        "invokeStatusBar" to method("$NOTIFICATION->invokeStatusBar(Landroid/content/Context;Ljava/lang/String;Landroid/os/Bundle;)V"),
        "updateParameters" to method("$NOTIFICATION->updateParameters($REQUEST)V"),
        "showConnectedToast" to method("$NOTIFICATION->showConnectedToast(IIIILandroid/bluetooth/BluetoothDevice;Ljava/lang/String;)V"),
        "notificationApiToast" to method("$NOTIFICATION_API->showNewConnectedToast(IIIILandroid/bluetooth/BluetoothDevice;Ljava/lang/String;)V"),
        "request" to clazz(REQUEST),
        "requestLeftField" to field("$REQUEST->left:I"),
        "requestRightField" to field("$REQUEST->right:I"),
        "requestWearField" to field("$REQUEST->wear:I"),
        "requestDeviceField" to field("$REQUEST->device:Landroid/bluetooth/BluetoothDevice;"),
    )

    private fun headsetSymbols(): Map<String, SymbolReference> = mapOf(
        "service" to clazz(SERVICE),
        "onBind" to method("$SERVICE->onBind(Landroid/content/Intent;)Landroid/os/IBinder;"),
        "onCreate" to method("$SERVICE->onCreate()V"),
        "notificationField" to field("$SERVICE->mMiuiBluetoothNotification:$NOTIFICATION"),
        "binder" to clazz(BINDER),
        "callback" to clazz(CALLBACK),
        "callbackFactory" to method("$CALLBACK_FACTORY->m5020K0(Landroid/os/IBinder;)$CALLBACK"),
        "callbackRefresh" to method("$CALLBACK->refreshStatus(Ljava/lang/String;Ljava/lang/String;)V"),
        "checkSupport" to method("$BINDER->checkSupport(Landroid/bluetooth/BluetoothDevice;)Ljava/lang/String;"),
        "getDeviceInfo" to method("$BINDER->getDeviceInfo(Ljava/lang/String;)Ljava/lang/String;"),
        "isSupportAudioSwitch" to method("$BINDER->isSupportAudioSwitch(Ljava/lang/String;)Ljava/lang/String;"),
        "setCommonCommand" to method("$BINDER->setCommonCommand(ILjava/lang/String;Landroid/bluetooth/BluetoothDevice;)Ljava/lang/String;"),
        "connect" to method("$BINDER->connect(Landroid/bluetooth/BluetoothDevice;)V"),
        "getDeviceConfig" to method("$BINDER->getDeviceConfig(Landroid/bluetooth/BluetoothDevice;)V"),
        "getCommonConfig" to method("$BINDER->getCommonConfig(Landroid/bluetooth/BluetoothDevice;Ljava/lang/String;)V"),
        "isMiTWS" to method("$BINDER->isMiTWS(Ljava/lang/String;)Z"),
        "checkIsMiTWS" to method("$BINDER->checkIsMiTWS(Ljava/lang/String;)Z"),
        "getRingFindState" to method("$BINDER->getRingFindState(Ljava/lang/String;)Z"),
        "changeAncMode" to method("$BINDER->changeAncMode(ILandroid/bluetooth/BluetoothDevice;)V"),
        "changeAncLevel" to method("$BINDER->changeAncLevel(Ljava/lang/String;Landroid/bluetooth/BluetoothDevice;)V"),
        "register" to method("$BINDER->register($CALLBACK)V"),
        "registerCallbackDevice" to method("$BINDER->registerCallbackDevice(${CALLBACK}Landroid/bluetooth/BluetoothDevice;)V"),
        "unregister" to method("$BINDER->unregister(${CALLBACK}Landroid/bluetooth/BluetoothDevice;)V"),
    )

    private fun clazz(descriptor: String) = SymbolReference(SymbolKind.CLASS, descriptor)
    private fun method(descriptor: String) = SymbolReference(SymbolKind.METHOD, descriptor)
    private fun field(descriptor: String) = SymbolReference(SymbolKind.FIELD, descriptor)

    companion object {
        private const val NOTIFICATION = "Lcom/android/bluetooth/ble/app/MiuiBluetoothNotification;"
        private const val NOTIFICATION_API = "Lcom/android/bluetooth/ble/app/MiuiBluetoothNotificationApi;"
        private const val REQUEST = "Lcom/android/bluetooth/ble/app/MiuiBluetoothNotification\$c;"
        private const val SERVICE = "Lcom/android/bluetooth/ble/app/headset/BluetoothHeadsetService;"
        private const val BINDER = "Lcom/android/bluetooth/ble/app/headset/BluetoothHeadsetService\$HeadsetBinder;"
        private const val CALLBACK = "Lcom/android/bluetooth/ble/app/IMiuiHeadsetCallback;"
        private const val CALLBACK_FACTORY = "Lcom/android/bluetooth/ble/app/IMiuiHeadsetCallback\$a;"
    }
}
