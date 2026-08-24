package dev.sonypods.media

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.view.KeyEvent
import dev.sonypods.protocol.PlaybackStatus

class MediaPlaybackController(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    fun previous() {
        dispatch(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun playPause() {
        dispatch(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    fun next() {
        dispatch(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun currentFallbackStatus(): PlaybackStatus =
        if (audioManager?.isMusicActive == true) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED

    /**
     * Current track info from the phone's own media sessions, used while the
     * headset has none: an LC3 link stays AVRCP-blind until playback starts, so
     * its early metadata answers are legitimately empty even though the audio
     * source is this very phone. Reading sessions requires notification-listener
     * or media-content-control privileges; without them this returns null and
     * the caller keeps the "unknown" placeholders.
     */
    fun currentFallbackMetadata(): PhonePlaybackMetadata? = runCatching {
        val manager = appContext.getSystemService(MediaSessionManager::class.java)
            ?: return null
        val controllers = manager.getActiveSessions(null)
        val picked = controllers.firstOrNull {
            it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()
            ?: return null
        val metadata = picked.metadata ?: return null
        PhonePlaybackMetadata(
            track = metadata.getString(METADATA_TITLE)?.takeIf { it.isNotBlank() },
            artist = metadata.getString(METADATA_ARTIST)?.takeIf { it.isNotBlank() },
            album = metadata.getString(METADATA_ALBUM)?.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    private fun dispatch(keyCode: Int) {
        audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    companion object {
        private const val METADATA_TITLE = "android.media.metadata.TITLE"
        private const val METADATA_ARTIST = "android.media.metadata.ARTIST"
        private const val METADATA_ALBUM = "android.media.metadata.ALBUM"
    }
}

data class PhonePlaybackMetadata(
    val track: String?,
    val artist: String?,
    val album: String?,
)
