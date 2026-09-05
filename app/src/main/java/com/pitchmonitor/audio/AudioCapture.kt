package com.pitchmonitor.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose

/**
 * Wraps [AudioRecord] into a cold [Flow] that emits fixed-size mono PCM-16 frames.
 *
 * Each emitted [ShortArray] contains exactly the samples read (16-bit signed).
 * The flow runs on the calling coroutine's dispatcher; AudioRecord.read is a
 * blocking call, so collect on a background dispatcher.
 *
 * PCM-16 samples are in the range [-32768, 32767].
 */
class AudioCapture(
    private val sampleRate: Int = 44100,
    private val frameSize: Int = 2048,
) {
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    @SuppressLint("MissingPermission")
    fun frames(): Flow<ShortArray> = callbackFlow {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        // generous slack: keeps recording gapless through short processing spikes
        val bufferSize = maxOf(minBuf * 2, frameSize * 8)

        val recorder = try {
            AudioRecord(
                // MIC = raw-ish path. VOICE_RECOGNITION would apply AGC + noise
                // suppression, which suppresses steady tones (bad for a tuner).
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize,
            )
        } catch (e: Exception) {
            Log.e("AudioCapture", "Failed to create AudioRecord", e)
            close(e)
            return@callbackFlow
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            close(IllegalStateException("AudioRecord not initialised"))
            return@callbackFlow
        }

        recorder.startRecording()
        val frame = ShortArray(frameSize)

        try {
            while (!isClosedForSend) {
                val read = recorder.read(frame, 0, frameSize)
                if (read > 0) {
                    // emit exactly the samples read — never stale tail data
                    trySend(frame.copyOf(read))
                } else if (read < 0) {
                    Log.e("AudioCapture", "read error: $read")
                    break
                }
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }

        awaitClose { /* cleanup handled above when flow is cancelled */ }
    }
}
