package com.pitchmonitor.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes an audio file (MP3/AAC/WAV/FLAC/OGG — anything the platform can
 * decode) into mono PCM with [MediaCodec], streams it into a WAV next to the
 * other sessions, and runs the same MPM detector + tracker over it to build a
 * (time, frequency) curve. The result saves as a regular session, so the
 * playback screen works unchanged.
 *
 * Memory stays bounded: PCM is never fully materialised.
 */
class AudioImporter(
    private val context: Context,
    private val sessionsDir: File,
    private val id: Long,
    private val sampleRate: Int,
) {

    data class Imported(
        val timesMs: List<Long>,
        val freqs: List<Float?>,
        val durationMs: Long,
        val sampleRate: Int,
    )

    companion object {
        private const val SILENCE_RMS_THRESHOLD = 0.0025f
        private const val MIN_CLARITY = 0.65f
        private const val FRAME = 2048          // ~46 ms @44.1k
        private const val HOP = 1024            // 50 % overlap ≈ 21.5 detections/s
        private const val TIMEOUT_US = 10_000L
    }

    /**
     * @param onProgress 0f..1f analysis progress (decode position / duration)
     */
    suspend fun import(
        uri: Uri,
        baseName: String,
        onProgress: (Float) -> Unit,
    ): Imported = withContext(Dispatchers.Default) {
        val wavFile = File(sessionsDir, "$id.wav")
        var wav: WavRecorder? = null
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw IllegalStateException("无法读取所选文件")

            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = f
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) {
                throw IllegalStateException("文件中没有找到音频轨道")
            }
            extractor.selectTrack(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            var sr = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else sampleRate
            var channels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            var detector: PitchDetector? = null
            val tracker = PitchTracker()
            val times = ArrayList<Long>(4096)
            val freqs = ArrayList<Float?>(4096)

            var globalSamples = 0L
            var lastProgress = -1f
            var tail = FloatArray(0)     // last (FRAME-HOP) samples, for 50% overlap
            var tailPrimed = false

            fun startStreamIfNeeded() {
                if (wav == null) {
                    wav = WavRecorder(wavFile, sr)
                    detector = PitchDetector(sr)
                    tracker.reset()
                }
            }

            /** stream a mono chunk to WAV + analyse every HOP-sized window */
            fun feedMono(chunk: ShortArray) {
                startStreamIfNeeded()
                wav!!.write(chunk)
                globalSamples += chunk.size

                val chunkF = FloatArray(chunk.size) { i -> chunk[i] / 32768f }
                val combined = FloatArray(tail.size + chunkF.size).also {
                    tail.copyInto(it, 0)
                    chunkF.copyInto(it, tail.size)
                }
                val chunkStartSample = globalSamples - chunk.size

                var offset = 0
                while (combined.size - offset >= FRAME) {
                    var sum = 0f
                    for (k in 0 until FRAME) sum += combined[offset + k] * combined[offset + k]
                    val rms = kotlin.math.sqrt(sum / FRAME)

                    var raw: Float? = null
                    if (rms >= SILENCE_RMS_THRESHOLD) {
                        val det = detector!!.detect(combined, offset, FRAME)
                        if (det.frequency != null && det.clarity >= MIN_CLARITY) raw = det.frequency
                    }

                    val winStartSample = chunkStartSample - tail.size + offset
                    times.add(winStartSample * 1000L / sr)
                    freqs.add(tracker.feed(raw))
                    offset += HOP
                }

                val keep = if (tailPrimed) FRAME - HOP else minOf(FRAME - HOP, combined.size)
                tail = combined.copyOfRange(combined.size - keep, combined.size)
                tailPrimed = true
            }

            var sawInputEOS = false
            var sawOutputEOS = false
            val info = MediaCodec.BufferInfo()

            while (!sawOutputEOS) {
                ensureActive()

                if (!sawInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(ib, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(outIdx)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            val shorts = ShortArray(info.size / 2)
                            ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

                            val mono = if (channels >= 2) {
                                val n = shorts.size / channels
                                ShortArray(n) { i ->
                                    var acc = 0
                                    for (c in 0 until channels) acc += shorts[i * channels + c]
                                    (acc / channels).toShort()
                                }
                            } else shorts

                            feedMono(mono)

                            if (durationUs > 0) {
                                val p = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                if (p - lastProgress > 0.005f) {
                                    lastProgress = p
                                    onProgress(p)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEOS = true
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }

            wav?.finish()
            val durationMs = if (sr > 0) globalSamples * 1000L / sr else 0L
            Imported(times, freqs, durationMs, sr)
        } catch (e: CancellationException) {
            // user cancelled — remove the partial WAV, no session will be saved
            wav?.abort()
            wavFile.delete()
            throw e
        } catch (e: Exception) {
            wav?.abort()
            wavFile.delete()
            throw e
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }
}
