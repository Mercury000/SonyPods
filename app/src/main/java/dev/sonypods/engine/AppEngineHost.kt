package dev.sonypods.engine

import android.content.Context
import android.content.Intent
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.bridge.ModelImageSync
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.config.ConfigManager
import dev.sonypods.config.CloudModelInfoStore
import dev.sonypods.config.CloudModelInfoSync
import dev.sonypods.data.SonyHeadphoneRepository
import dev.sonypods.protocol.EqPresetId
import dev.sonypods.protocol.GestureNoiseControlMode
import dev.sonypods.protocol.NoiseAdaptiveSensitivity
import dev.sonypods.protocol.NoiseControlMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bluetooth engine hosted by the application; no Xposed process or remote files are used. */
object AppEngineHost {
    private var context: Context? = null
    private var repository: SonyHeadphoneRepository? = null
    private var scope: CoroutineScope? = null
    private var stateJob: Job? = null

    @Synchronized
    fun start(appContext: Context) {
        if (repository != null) return
        val ctx = appContext.applicationContext ?: appContext
        context = ctx
        val prefs = ctx.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        ConfigManager.init(prefs)
        val catalogPrefs = CloudModelInfoStore.preferences(ctx)
        val repo = SonyHeadphoneRepository.getInstance(
            resourceContext = ctx,
            systemContext = ctx,
            modelInfoReader = { CloudModelInfoStore.readCachedJson(catalogPrefs) },
        )
        repository = repo
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        stateJob = scope!!.launch {
            repo.state.collectLatest { state ->
                publish(SonyStateSnapshot.fromUiState(state))
            }
        }
        repo.startScan()
        publish(SonyStateSnapshot.fromUiState(repo.state.value))
        // The catalog is app-local. Load an already cached catalog without
        // blocking application startup or the first Compose frame.
        scope!!.launch(Dispatchers.IO) {
            if (repo.refreshModelImageCatalog()) {
                withContext(Dispatchers.Main.immediate) {
                    repository?.state?.value?.let { state ->
                        publish(SonyStateSnapshot.fromUiState(state))
                    }
                }
            }
        }
    }

    fun handle(intent: Intent) {
        val repo = repository ?: return
        when (intent.getStringExtra(SonyBridge.EXTRA_COMMAND)) {
            SonyBridge.CMD_SET_NOISE_CONTROL -> intent.string(SonyBridge.EXTRA_STRING)?.let { name ->
                NoiseControlMode.entries.firstOrNull { it.name == name }?.let(repo::setNoiseControlMode)
            }
            SonyBridge.CMD_SET_AMBIENT_LEVEL -> repo.setAmbientLevel(intent.getIntExtra(SonyBridge.EXTRA_INT, 10))
            SonyBridge.CMD_SET_AMBIENT_VOICE -> repo.setAmbientVoiceMode(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_SET_NOISE_ADAPTIVE -> repo.setNoiseAdaptive(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_SET_NOISE_ADAPTIVE_SENSITIVITY -> intent.string(SonyBridge.EXTRA_STRING)?.let { name ->
                NoiseAdaptiveSensitivity.entries.firstOrNull { it.name == name }?.let(repo::setNoiseAdaptiveSensitivity)
            }
            SonyBridge.CMD_SET_EQ_PRESET -> intent.string(SonyBridge.EXTRA_STRING)?.let { name -> EqPresetId.entries.firstOrNull { it.name == name }?.let(repo::setEqPreset) }
            SonyBridge.CMD_SET_CLEAR_BASS -> repo.setClearBass(intent.getIntExtra(SonyBridge.EXTRA_INT, 0))
            SonyBridge.CMD_POWER_OFF -> repo.powerOff()
            SonyBridge.CMD_SET_EQ_BAND -> repo.setCustomEqBand(intent.getIntExtra(SonyBridge.EXTRA_INDEX, 0), intent.getIntExtra(SonyBridge.EXTRA_INT, 0))
            SonyBridge.CMD_SET_GESTURE_PRESET -> repo.setGesturePreset(intent.getIntExtra(SonyBridge.EXTRA_KEY_CODE, -1), intent.getIntExtra(SonyBridge.EXTRA_PRESET_CODE, -1))
            SonyBridge.CMD_SET_GESTURE_FUNCTION -> repo.setGestureFunction(intent.getIntExtra(SonyBridge.EXTRA_KEY_CODE, -1), intent.getIntExtra(SonyBridge.EXTRA_ACTION_CODE, -1), intent.getIntExtra(SonyBridge.EXTRA_FUNCTION_CODE, -1))
            SonyBridge.CMD_SET_QUICK_ACCESS_FUNCTION -> repo.setQuickAccessFunction(intent.getIntExtra(SonyBridge.EXTRA_QUICK_ACCESS_ACTION_INDEX, -1), intent.getIntExtra(SonyBridge.EXTRA_QUICK_ACCESS_FUNCTION_CODE, -1))
            SonyBridge.CMD_SET_GESTURE_AMBIENT_MODES -> repo.setGestureAmbientModes((intent.getIntArrayExtra(SonyBridge.EXTRA_FUNCTION_CODE) ?: intArrayOf()).asSequence().mapNotNull { GestureNoiseControlMode.entries.getOrNull(it) }.toSet())
            SonyBridge.CMD_SET_MULTIPOINT_PAIRING_MODE -> repo.setMultipointPairingMode(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_CONNECT_MULTIPOINT_DEVICE -> intent.string(SonyBridge.EXTRA_STRING)?.let(repo::connectMultipointDevice)
            SonyBridge.CMD_DISCONNECT_MULTIPOINT_DEVICE -> intent.string(SonyBridge.EXTRA_STRING)?.let(repo::disconnectMultipointDevice)
            SonyBridge.CMD_UNPAIR_MULTIPOINT_DEVICE -> intent.string(SonyBridge.EXTRA_STRING)?.let(repo::unpairMultipointDevice)
            SonyBridge.CMD_SET_SOURCE_SWITCH_ENABLED -> repo.setSourceSwitchEnabled(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_SET_MULTIPOINT_ENABLED -> repo.setMultipointEnabled(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_REPLY_MULTIPOINT_ALERT -> repo.replyMultipointAlert(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_SET_FIXED_SOURCE -> intent.string(SonyBridge.EXTRA_STRING)?.let(repo::setFixedSource)
            SonyBridge.CMD_SET_MUSIC_HAND_OVER -> repo.setMusicHandOverEnabled(intent.getBooleanExtra(SonyBridge.EXTRA_BOOL, false))
            SonyBridge.CMD_PLAYBACK_PREVIOUS -> repo.playbackPrevious()
            SonyBridge.CMD_PLAYBACK_PLAY_PAUSE -> repo.playbackPlayPause()
            SonyBridge.CMD_PLAYBACK_NEXT -> repo.playbackNext()
            SonyBridge.CMD_SET_PLAYBACK_VOLUME -> repo.setPlaybackVolume(intent.getIntExtra(SonyBridge.EXTRA_INT, -1))
            SonyBridge.CMD_CONNECT -> repo.connect(intent.string(SonyBridge.EXTRA_STRING).orEmpty(), intent.string("device_name") ?: "Sony audio device")
            SonyBridge.CMD_DISCONNECT -> repo.disconnect()
            SonyBridge.CMD_START_SCAN -> repo.startScan()
            SonyBridge.CMD_REFRESH -> repo.refreshBasics()
            SonyBridge.CMD_IMAGE_READY,
            SonyBridge.CMD_CLOUD_MODEL_INFO_READY -> {
                if (repo.refreshModelImageCatalog()) {
                    repository?.state?.value?.let { publish(SonyStateSnapshot.fromUiState(it)) }
                }
            }
            SonyBridge.CMD_REPUBLISH -> repository?.state?.value?.let { publish(SonyStateSnapshot.fromUiState(it)) }
        }
    }

    /** Called by the foreground service and Bluetooth receiver when the app is backgrounded. */
    fun ensureBackgroundConnection() {
        repository?.ensureAutoConnect()
    }

    fun rearmBackgroundConnection() {
        repository?.rearmAutoConnect()
    }

    private fun publish(snapshot: SonyStateSnapshot) {
        val ctx = context ?: return
        // Artwork/catalog refresh is optional. Never let a malformed cache or
        // image download stop the state collector that drives the app UI.
        runCatching { CloudModelInfoSync.onState(ctx, snapshot) }
            .onFailure { android.util.Log.w("SonyPods-App", "Cloud model sync failed", it) }
        runCatching { ModelImageSync.onState(ctx, snapshot) }
            .onFailure { android.util.Log.w("SonyPods-App", "Model image sync failed", it) }
        ctx.sendBroadcast(Intent(SonyBridge.ACTION_STATE).apply {
            putExtra(SonyStateSnapshot.EXTRA_SNAPSHOT, snapshot.toBundle())
            setPackage(ctx.packageName)
        })
    }

    private fun Intent.string(key: String): String? = getStringExtra(key)?.takeIf { it.isNotBlank() }
}
