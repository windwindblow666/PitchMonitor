package com.pitchmonitor.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a pure reference tone (default A4 = 440 Hz) through the speaker so the
 * user can tune by ear, and doubles as a self-test source: the mic hears the
 * phone's own speaker at close range with ample level.
 *
 * Uses AudioTrack in static mode with an infinitely looping quarter-second
 * sine buffer — no assets, no gaps at loop boundaries.
 */
class ReferenceTonePlayer(
    private val sampleRate: Int = 44100,
) {
    private var track: AudioTrack? = null

    val isPlaying: Boolean
        get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    fun start(freqHz: Float = 440f) {
        if (isPlaying) return

        val samplesPerLoop = sampleRate / 4          // 0.25 s — integer cycles kept by construction
        val pcm = ShortArray(samplesPerLoop) { i ->
            (0.6 * Short.MAX_VALUE * sin(2 * PI * freqHz * i / sampleRate)).toInt().toShort()
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        track = AudioTrack(
            attrs, format,
            pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE,
        ).apply {
            write(pcm, 0, pcm.size)
            setLoopPoints(0, samplesPerLoop, -1)
            play()
        }
    }

    fun stop() {
        track?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        track = null
    }
}
