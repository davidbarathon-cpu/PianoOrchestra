package com.pianoorchestra.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import java.io.File

/**
 * Le son du groupe (le WAV exporté de FL Studio) : lecture, position, vitesse (sans changer la hauteur).
 * Il joue en même temps que le synthé de la tablette (écho du piano, clavier à l'écran).
 */
class BackingTrack(file: File) {
    private val player = MediaPlayer()
    @Volatile var ready = false
        private set
    var speed = 1f
        private set

    init {
        player.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        player.setDataSource(file.absolutePath)
        player.prepare()
        ready = true
    }

    val duration: Double get() = if (ready) player.duration / 1000.0 else 0.0
    val position: Double get() = if (ready) player.currentPosition / 1000.0 else 0.0
    val playing: Boolean get() = ready && player.isPlaying

    fun play() {
        if (!ready) return
        runCatching { player.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
        player.start()
    }

    fun pause() { if (ready && player.isPlaying) player.pause() }

    fun seek(seconds: Double) {
        if (!ready) return
        player.seekTo((seconds.coerceIn(0.0, duration) * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    /** 0,5 à 1,0 : plus lent pour apprendre, la hauteur ne change pas. */
    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.5f, 1.25f)
        if (ready && player.isPlaying) runCatching { player.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
    }

    fun setVolume(v: Float) { if (ready) player.setVolume(v, v) }

    fun release() {
        ready = false
        runCatching { player.release() }
    }
}
