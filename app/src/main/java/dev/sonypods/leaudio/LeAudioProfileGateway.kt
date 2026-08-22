package dev.sonypods.leaudio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.lang.reflect.Method
import java.util.ArrayDeque

/**
 * Small Android-side equivalent of Sound Connect's BtProfileGateway LE queue.
 * The Sony command is released only after Sound Connect's platform-specific
 * profile readiness condition is met. A supported platform with Bluetooth off
 * or a binding in progress remains queued instead of being treated as unsupported.
 */
class LeAudioProfileGateway(context: Context) {
    enum class Availability { UNSUPPORTED, BT_OFF, BINDING, READY }
    enum class Platform { UNSUPPORTED, STANDARD, SONY_QUALCOMM }

    private val appContext = context.applicationContext ?: context
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = ArrayDeque<() -> Unit>()
    private val lock = Any()
    private val adapter: BluetoothAdapter? = runCatching {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()
    private var proxy: BluetoothProfile? = null
    private var a2dpProxy: BluetoothProfile? = null
    private var targetLeAudioProxy: BluetoothProfile? = null
    private var bindingRequested = false
    private var a2dpBindingRequested = false
    private var targetLeAudioBindingRequested = false
    private var closed = false
    private var retryScheduled = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> bindIfNeeded()
                BluetoothAdapter.STATE_OFF -> synchronized(lock) {
                    proxy = null
                    a2dpProxy = null
                    targetLeAudioProxy = null
                    bindingRequested = false
                    a2dpBindingRequested = false
                    targetLeAudioBindingRequested = false
                }
            }
        }
    }

    init {
        runCatching {
            appContext.registerReceiver(
                bluetoothReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                Context.RECEIVER_EXPORTED,
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun availability(): Availability {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || adapter == null) {
            return Availability.UNSUPPORTED
        }
        if (!isOfficialPlatformSupported()) {
            return Availability.UNSUPPORTED
        }
        if (!runCatching { adapter?.isEnabled == true }.getOrDefault(false)) return Availability.BT_OFF
        val ready = if (isQualcommPlatform()) {
            // The Qualcomm implementation's LeAudioAvailability delegates
            // readiness to its persistent A2DP observer. Profile 32 is
            // requested separately for LE routing but is not the queue gate.
            a2dpProxy != null
        } else {
            proxy != null
        }
        return if (ready) Availability.READY else Availability.BINDING
    }

    private fun isOfficialPlatformSupported(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val targetPlatform = isQualcommPlatform()
        return targetPlatform || runCatching { adapter?.isLeAudioSupported == 10 }.getOrDefault(false)
    }

    private fun isQualcommPlatform(): Boolean = runCatching {
        val type = Class.forName("android.os.SystemProperties")
        val get: Method = type.getMethod("get", String::class.java)
        (get.invoke(type, "vendor.somc.qti_lea.support") as? String)
            ?.toBoolean() == true
    }.getOrDefault(false)

    fun platform(): Platform = when {
        !isOfficialPlatformSupported() -> Platform.UNSUPPORTED
        isQualcommPlatform() -> Platform.SONY_QUALCOMM
        else -> Platform.STANDARD
    }

    /** Returns false only when the platform is definitively unsupported. */
    @SuppressLint("MissingPermission")
    fun request(onReady: (platform: Platform) -> Unit): Boolean {
        val state = availability()
        if (state == Availability.UNSUPPORTED) {
            handler.post { onReady(Platform.UNSUPPORTED) }
            return true
        }
        val platform = platform()
        synchronized(lock) {
            if (closed) return false
            if (state == Availability.READY) {
                handler.post { onReady(platform) }
                return true
            }
            callbacks.add { onReady(platform) }
        }
        bindIfNeeded()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun bindIfNeeded() {
        val localAdapter = adapter ?: return
        if (!runCatching { localAdapter.isEnabled }.getOrDefault(false)) return
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, service: BluetoothProfile) {
                synchronized(lock) {
                    if (closed) return
                    when {
                        profile == BluetoothProfile.A2DP -> {
                            a2dpProxy = service
                            a2dpBindingRequested = false
                        }
                        profile == BluetoothProfile.LE_AUDIO -> {
                            proxy = service
                            bindingRequested = false
                        }
                        profile == QUALCOMM_LE_AUDIO_PROFILE -> {
                            targetLeAudioProxy = service
                            targetLeAudioBindingRequested = false
                        }
                        else -> return
                    }
                }
                if ((isQualcommPlatform() && profile == BluetoothProfile.A2DP) ||
                    (!isQualcommPlatform() && profile == BluetoothProfile.LE_AUDIO)
                ) {
                    drain()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                synchronized(lock) {
                    when {
                        profile == BluetoothProfile.A2DP -> {
                            a2dpProxy = null
                            a2dpBindingRequested = false
                        }
                        profile == BluetoothProfile.LE_AUDIO -> {
                            proxy = null
                            bindingRequested = false
                        }
                        profile == QUALCOMM_LE_AUDIO_PROFILE -> {
                            targetLeAudioProxy = null
                            targetLeAudioBindingRequested = false
                        }
                        else -> return
                    }
                }
                scheduleBindRetry()
            }
        }

        if (isQualcommPlatform()) {
            var retryA2dp = false
            var retryTargetLeAudio = false
            synchronized(lock) {
                if (!closed && a2dpProxy == null && !a2dpBindingRequested) {
                    a2dpBindingRequested = true
                    retryA2dp = !runCatching {
                        localAdapter.getProfileProxy(appContext, listener, BluetoothProfile.A2DP)
                    }.getOrDefault(false)
                    if (retryA2dp) a2dpBindingRequested = false
                }
                if (!closed && targetLeAudioProxy == null && !targetLeAudioBindingRequested) {
                    targetLeAudioBindingRequested = true
                    retryTargetLeAudio = !runCatching {
                        // Sound Connect uses profile id 32 on Qualcomm builds.
                        localAdapter.getProfileProxy(appContext, listener, QUALCOMM_LE_AUDIO_PROFILE)
                    }.getOrDefault(false)
                    if (retryTargetLeAudio) targetLeAudioBindingRequested = false
                }
            }
            if (retryA2dp || retryTargetLeAudio) scheduleBindRetry()
            return
        }

        var retry = false
        synchronized(lock) {
            if (closed || proxy != null || bindingRequested) return
            bindingRequested = true
            retry = !runCatching {
                localAdapter.getProfileProxy(appContext, listener, BluetoothProfile.LE_AUDIO)
            }.getOrDefault(false)
            if (retry) bindingRequested = false
        }
        if (retry) scheduleBindRetry()
    }

    private fun scheduleBindRetry() {
        synchronized(lock) {
            if (closed || retryScheduled || callbacks.isEmpty()) return
            retryScheduled = true
        }
        handler.postDelayed({
            synchronized(lock) { retryScheduled = false }
            bindIfNeeded()
        }, PROFILE_BIND_RETRY_MS)
    }

    private fun drain() {
        val pending = synchronized(lock) {
            val result = callbacks.toList()
            callbacks.clear()
            result
        }
        pending.forEach { handler.post(it) }
    }

    @SuppressLint("MissingPermission")
    fun close() {
        runCatching { appContext.unregisterReceiver(bluetoothReceiver) }
        val proxies = synchronized(lock) {
            closed = true
            callbacks.clear()
            val value = listOfNotNull(
                proxy?.let { BluetoothProfile.LE_AUDIO to it },
                a2dpProxy?.let { BluetoothProfile.A2DP to it },
                targetLeAudioProxy?.let { QUALCOMM_LE_AUDIO_PROFILE to it },
            )
            proxy = null
            a2dpProxy = null
            targetLeAudioProxy = null
            value
        }
        if (adapter != null) {
            proxies.forEach { (id, profile) ->
                runCatching { adapter?.closeProfileProxy(id, profile) }
            }
        }
    }

    companion object {
        private const val PROFILE_BIND_RETRY_MS = 1_000L
        private const val QUALCOMM_LE_AUDIO_PROFILE = 32
    }
}
