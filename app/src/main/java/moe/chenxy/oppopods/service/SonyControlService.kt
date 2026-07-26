package moe.chenxy.oppopods.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.sonypods.data.SonyHeadphoneRepository
import dev.sonypods.data.SonyHeadphoneUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import moe.chenxy.oppopods.MainActivity
import moe.chenxy.oppopods.OppoPodsApp
import moe.chenxy.oppopods.PopupActivity
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.config.ConfigManager
import moe.chenxy.oppopods.config.PodImagePrefs
import moe.chenxy.oppopods.config.PodImageResource
import moe.chenxy.oppopods.ui.toBatteryParams
import moe.chenxy.oppopods.ui.toSinglePodParams
import moe.chenxy.oppopods.utils.miuiStrongToast.MiuiStrongToastUtil
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams

/**
 * Foreground service that keeps [SonyHeadphoneRepository] (the Sony Tandem transport
 * and state hub) alive in the app process, and bridges its state to the HyperOS
 * hook processes (方案 A):
 *  - battery -> com.android.bluetooth (system stack injection) + com.xiaomi.bluetooth
 *    (notification / island / AOD) + com.milink.service + com.android.settings
 *  - ANC / ambient voice -> all hook processes
 *  - connect / disconnect -> notification lifecycle + hook caches
 */
class SonyControlService : Service() {

    private lateinit var repository: SonyHeadphoneRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var lastConnectedAddress: String? = null
    private var lastBatteryParams: BatteryParams? = null
    private var lastSingle: PodParams? = null
    private var lastAncStatus: Int = -1
    private var lastAmbientVoice: Boolean? = null
    private var lastFetchedImageKey: String? = null

    override fun onCreate() {
        super.onCreate()
        repository = SonyHeadphoneRepository.getInstance(this)
        startForeground(NOTIFICATION_ID, createNotification(repository.state.value))

        serviceScope.launch {
            repository.state.collect { uiState ->
                updateNotification(uiState)
                bridgeState(uiState)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> repository.disconnect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // State bridge towards the HyperOS hook processes
    // ------------------------------------------------------------------

    private fun bridgeState(uiState: SonyHeadphoneUiState) {
        val address = uiState.connectedDevice?.address
        val name = uiState.deviceInfo.modelName ?: uiState.connectedDevice?.name ?: ""

        if (address != null && lastConnectedAddress == null) {
            sendStateBroadcast(OppoPodsAction.ACTION_PODS_CONNECTED) {
                putExtra("address", address)
                putExtra("device_name", name)
            }
        } else if (address == null && lastConnectedAddress != null) {
            sendStateBroadcast(OppoPodsAction.ACTION_PODS_DISCONNECTED) {
                putExtra("address", lastConnectedAddress)
            }
            lastConnectedAddress?.let { previous ->
                remoteDevice(previous)?.let { MiuiStrongToastUtil.cancelPodsNotificationByMiuiBt(this, it) }
            }
            lastBatteryParams = null
            lastSingle = null
            lastAncStatus = -1
            lastAmbientVoice = null
        }
        lastConnectedAddress = address

        if (address == null) return

        val battery = uiState.batteryState.toBatteryParams()
        val single = uiState.batteryState.toSinglePodParams()
        if (battery != lastBatteryParams || single != lastSingle) {
            lastBatteryParams = battery
            lastSingle = single
            broadcastBattery(address, battery, single)
        }

        val ancStatus = ancStatusFromMode(uiState.noiseControlState.controlMode)
        if (ancStatus != lastAncStatus) {
            lastAncStatus = ancStatus
            sendStateBroadcast(OppoPodsAction.ACTION_PODS_ANC_CHANGED) {
                putExtra("address", address)
                putExtra("status", ancStatus)
            }
        }

        val ambientVoice = uiState.noiseControlState.ambientVoiceMode
        if (ambientVoice != lastAmbientVoice) {
            lastAmbientVoice = ambientVoice
            sendStateBroadcast(OppoPodsAction.ACTION_PODS_AMBIENT_VOICE_CHANGED) {
                putExtra("address", address)
                putExtra("enabled", ambientVoice)
            }
        }

        maybeFetchModelImage(address, name, uiState.deviceInfo.modelImageUrl)
    }

    /**
     * Downloads the cloud model image (SonyModelImageCatalog match) once per
     * device+URL and stores it as the box image, unless the user already set a
     * custom one. The stored image feeds the system notification / island via
     * PodImageProvider.
     */
    private fun maybeFetchModelImage(address: String, name: String, imageUrl: String?) {
        if (imageUrl.isNullOrBlank()) return
        val key = "$address|$imageUrl"
        if (key == lastFetchedImageKey) return
        val prefs = getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        if (PodImagePrefs.find(prefs, address)?.boxImagePath != null) {
            lastFetchedImageKey = key
            return
        }
        lastFetchedImageKey = key
        serviceScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { URL(imageUrl).openStream().use { it.readBytes() } }
                    .onFailure { Log.w(TAG, "model image download failed url=$imageUrl", it) }
                    .getOrNull()
            } ?: return@launch
            if (bytes.isEmpty()) return@launch
            runCatching {
                PodImagePrefs.saveImageBytes(
                    context = this@SonyControlService,
                    prefs = prefs,
                    service = OppoPodsApp.xposedService,
                    address = address,
                    name = name,
                    images = mapOf(PodImageResource.BOX to bytes),
                )
                Log.d(TAG, "model image stored address=$address url=$imageUrl bytes=${bytes.size}")
            }.onFailure { Log.w(TAG, "model image store failed", it) }
        }
    }

    private fun broadcastBattery(address: String, battery: BatteryParams, single: PodParams?) {
        // The headband form factor reports one level; feed it through the "left"
        // slot so the hooks' min/aggregation logic keeps working.
        val effective = if (single != null) BatteryParams(left = single) else battery
        val systemLevel = listOfNotNull(
            single?.takeIf { it.isConnected }?.battery,
            battery.left?.takeIf { it.isConnected }?.battery,
            battery.right?.takeIf { it.isConnected }?.battery,
        ).minOrNull() ?: -1

        sendStateBroadcast(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED) {
            putExtra("address", address)
            putExtra("status", effective)
            putExtra("system_battery_level", systemLevel)
            putExtra("left_battery", effective.left?.battery ?: 0)
            putExtra("left_charging", effective.left?.isCharging == true)
            putExtra("left_connected", effective.left?.isConnected == true)
            putExtra("right_battery", effective.right?.battery ?: 0)
            putExtra("right_charging", effective.right?.isCharging == true)
            putExtra("right_connected", effective.right?.isConnected == true)
            putExtra("case_battery", effective.case?.battery ?: 0)
            putExtra("case_charging", effective.case?.isCharging == true)
            putExtra("case_connected", effective.case?.isConnected == true)
        }

        // System notification + focus island + AOD are rendered by the
        // com.xiaomi.bluetooth hook.
        remoteDevice(address)?.let { device ->
            MiuiStrongToastUtil.showPodsNotificationByMiuiBt(this, effective, device)
            MiuiStrongToastUtil.showPodsBatteryToastByMiuiBt(this, effective, device)
        }
    }

    private fun sendStateBroadcast(action: String, fill: Intent.() -> Unit) {
        HOOK_PACKAGES.forEach { targetPackage ->
            runCatching {
                sendBroadcast(Intent(action).apply {
                    fill()
                    setPackage(targetPackage)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                })
            }.onFailure {
                Log.w(TAG, "state broadcast failed action=$action target=$targetPackage", it)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun remoteDevice(address: String): BluetoothDevice? {
        return runCatching {
            getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Foreground notification
    // ------------------------------------------------------------------

    private fun updateNotification(uiState: SonyHeadphoneUiState) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification(uiState))
    }

    private fun createNotification(uiState: SonyHeadphoneUiState): Notification {
        createChannelIfNeeded()

        val connected = uiState.connectedDevice != null
        val title = uiState.deviceInfo.modelName
            ?: uiState.connectedDevice?.name
            ?: getString(R.string.app_name)
        val battery = uiState.batteryState
        val content = if (connected) buildString {
            battery.single?.let { append("$it% ") }
            battery.left?.let { append("L:$it% ") }
            battery.right?.let { append("R:$it% ") }
            battery.cradle?.let { append("Case:$it%") }
            if (isBlank()) {
                append(uiState.noiseControlState.controlMode?.name ?: getString(R.string.connect))
            }
        } else getString(R.string.service_disconnected)

        val disconnectIntent = Intent(this, SonyControlService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPending = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val popupIntent = Intent(this, PopupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val popupPending = PendingIntent.getActivity(
            this, 2, popupIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val mainPending = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(mainPending)
            .setOngoing(connected)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.notification_btn_popup), popupPending
                ).build()
            )
            .apply {
                if (connected) {
                    addAction(
                        Notification.Action.Builder(
                            null, getString(R.string.notification_btn_disconnect), disconnectPending
                        ).build()
                    )
                }
            }
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createChannelIfNeeded() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
            )
        }
    }

    companion object {
        private const val TAG = "SonyControlService"
        private const val CHANNEL_ID = "sony_control_service"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_DISCONNECT = "dev.sonypods.action.SERVICE_DISCONNECT"

        private val HOOK_PACKAGES = listOf(
            "com.android.bluetooth",
            "com.xiaomi.bluetooth",
            "com.milink.service",
            "com.android.settings",
        )

        fun ensureRunning(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, SonyControlService::class.java))
            }.onFailure {
                Log.w(TAG, "startForegroundService failed", it)
            }
        }
    }
}
