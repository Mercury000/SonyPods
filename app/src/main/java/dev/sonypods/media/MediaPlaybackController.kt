package dev.sonypods.media

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import dev.sonypods.protocol.PlaybackStatus

class MediaPlaybackController(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

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

    private fun dispatch(keyCode: Int) {
        audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
