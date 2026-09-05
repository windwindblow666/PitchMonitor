package com.pitchmonitor.audio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pitchmonitor.model.PitchResult
import com.pitchmonitor.util.NoteUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Owns the audio pipeline and exposes observable UI state.
 *
 * Raw per-frame MPM detections are jittery (occasional octave jumps, noise
 * blips), so results pass through a tracker before reaching the UI:
 *
 *  1. Gates — silence (rms) and clarity thresholds reject noise frames.
 *  2. Lock-in — a new pitch must repeat consistently for [LOCK_IN_FRAMES]
 *     frames before it becomes the tracked pitch (kills onset garbage).
 *  3. Continuity — detections within [TOLERANCE] of the tracked pitch are
 *     EMA-smoothed; detections near a ×2/×3/÷2/÷3 multiple are treated as
 *     octave errors and held (persistent flips trigger a re-lock).
 *  4. Re-pitch — a genuinely different pitch must repeat for [RELOCK_FRAMES]
 *     frames before the tracker jumps to it.
 *  5. Display median — last 3 tracked values, median shown (kills residue).
 */
class PitchViewModel(
    app: Application,
) : AndroidViewModel(app) {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val FRAME_SIZE = 2048
        private const val HISTORY_SIZE = 300        // ~14 s at ~21 updates/s

        private const val SILENCE_RMS_THRESHOLD = 0.0025f
        private const val MIN_CLARITY = 0.65f

        private const val LOCK_IN_FRAMES = 3        // frames to acquire a first pitch
        private const val RELOCK_FRAMES = 4         // frames to jump to a new pitch
        private const val OCTAVE_ESCAPE_FRAMES = 10 // persistent octave flip → re-lock
        private const val SILENCE_RESET_FRAMES = 15 // ~0.7 s silence → full reset
        private const val TOLERANCE = 0.07f         // ±7 % = same pitch
        private const val OCTAVE_TOL = 0.05f        // ±5 % = octave-error candidate
        private const val EMA_WEIGHT = 0.15f
    }

    private val capture = AudioCapture(SAMPLE_RATE, FRAME_SIZE)
    private val detector = PitchDetector(SAMPLE_RATE)
    private val tonePlayer = ReferenceTonePlayer(SAMPLE_RATE)

    private val _result = MutableStateFlow(PitchResult(null, null, null, null, 0f, 0f))
    val result: StateFlow<PitchResult> = _result.asStateFlow()

    private val _history = MutableStateFlow<List<Float?>>(emptyList())
    val history: StateFlow<List<Float?>> = _history.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isTonePlaying = MutableStateFlow(false)
    val isTonePlaying: StateFlow<Boolean> = _isTonePlaying.asStateFlow()

    private val historyBuffer = ArrayDeque<Float?>()

    // Pitch tracker state (all mutated only on the capture collector thread)
    private var stableFreq: Float? = null
    private var candidateFreq: Float? = null
    private var candidateCount = 0
    private var octaveRejectCount = 0
    private var silentRun = 0
    private val displayWindow = ArrayDeque<Float>(3)

    private var captureJob: Job? = null

    fun start() {
        if (_isRunning.value) return
        _isRunning.value = true
        captureJob = viewModelScope.launch(Dispatchers.Default) {
            capture.frames().collect { frame ->
                processFrame(frame)
            }
        }
    }

    fun stop() {
        _isRunning.value = false
        captureJob?.cancel()
        captureJob = null
    }

    fun clearHistory() {
        historyBuffer.clear()
        _history.value = emptyList()
    }

    /** Toggle the 440 Hz reference tone (A4). */
    fun toggleReferenceTone() {
        if (tonePlayer.isPlaying) {
            tonePlayer.stop()
            _isTonePlaying.value = false
        } else {
            tonePlayer.start(440f)
            _isTonePlaying.value = true
        }
    }

    private fun processFrame(frame: ShortArray) {
        // Convert 16-bit PCM to float [-1, 1] and measure loudness
        val samples = FloatArray(frame.size)
        var rmsSum = 0f
        for (i in frame.indices) {
            val s = frame[i] / 32768f
            samples[i] = s
            rmsSum += s * s
        }
        val rms = sqrt(rmsSum / frame.size)

        if (rms < SILENCE_RMS_THRESHOLD) {
            onUnusableFrame(rms)
            return
        }

        val detection = detector.detect(samples, 0, samples.size)
        val raw = detection.frequency
        if (raw == null || detection.clarity < MIN_CLARITY) {
            onUnusableFrame(rms)
            return
        }

        // Signal is usable
        silentRun = 0
        val tracked = track(raw)

        if (tracked == null) {
            // Still acquiring lock-in — show no pitch for these few frames
            publish(null, detection.clarity, rms)
        } else {
            publish(medianOfWindow(tracked), detection.clarity, rms)
        }
    }

    /**
     * Temporal pitch tracking: octave-error rejection, EMA smoothing,
     * lock-in / re-lock hysteresis. Returns the tracked frequency to display
     * this frame, or null while still acquiring.
     */
    private fun track(raw: Float): Float? {
        val s = stableFreq
        if (s == null) {
            val c = candidateFreq
            candidateCount = if (c != null && near(raw, c, TOLERANCE)) candidateCount + 1 else 1
            candidateFreq = raw
            return if (candidateCount >= LOCK_IN_FRAMES) {
                stableFreq = raw
                candidateFreq = null
                candidateCount = 0
                octaveRejectCount = 0
                raw
            } else null
        }

        return when {
            near(raw, s, TOLERANCE) -> {
                octaveRejectCount = 0
                val smoothed = s + (raw - s) * EMA_WEIGHT
                stableFreq = smoothed
                raw
            }
            near(raw, s * 2f, OCTAVE_TOL) || near(raw, s * 3f, OCTAVE_TOL) ||
                near(raw, s / 2f, OCTAVE_TOL) || near(raw, s / 3f, OCTAVE_TOL) -> {
                octaveRejectCount++
                if (octaveRejectCount >= OCTAVE_ESCAPE_FRAMES) {
                    // The lock itself is probably wrong — adopt the flipped value
                    stableFreq = raw
                    octaveRejectCount = 0
                    raw
                } else {
                    s  // hold the tracked pitch
                }
            }
            else -> {
                // A different pitch — only jump after it proves consistent
                val c = candidateFreq
                candidateCount = if (c != null && near(raw, c, TOLERANCE)) candidateCount + 1 else 1
                candidateFreq = raw
                if (candidateCount >= RELOCK_FRAMES) {
                    stableFreq = raw
                    candidateFreq = null
                    candidateCount = 0
                    octaveRejectCount = 0
                    raw
                } else {
                    s  // hold
                }
            }
        }
    }

    private fun medianOfWindow(newValue: Float): Float {
        displayWindow.addLast(newValue)
        while (displayWindow.size > 3) displayWindow.removeFirst()
        val list = displayWindow.sorted()
        return list[list.size / 2]
    }

    private fun near(a: Float, b: Float, tolerance: Float): Boolean =
        abs(a - b) <= b * tolerance

    /** Silence / noise frame: age out the tracker, report no pitch. */
    private fun onUnusableFrame(rms: Float) {
        silentRun++
        if (silentRun >= SILENCE_RESET_FRAMES) {
            stableFreq = null
            candidateFreq = null
            candidateCount = 0
            octaveRejectCount = 0
            displayWindow.clear()
        }
        publish(null, 0f, rms)
    }

    private fun publish(freq: Float?, clarity: Float, rms: Float) {
        if (freq != null) {
            val noteInfo = NoteUtil.freqToNote(freq)
            _result.value = PitchResult(freq, noteInfo?.name, noteInfo?.octave, noteInfo?.cents, clarity, rms)
            pushHistory(freq)
        } else {
            _result.value = PitchResult(null, null, null, null, clarity, rms)
            pushHistory(null)
        }
    }

    private fun pushHistory(freq: Float?) {
        historyBuffer.addLast(freq)
        while (historyBuffer.size > HISTORY_SIZE) historyBuffer.removeFirst()
        _history.value = historyBuffer.toList()
    }

    override fun onCleared() {
        super.onCleared()
        stop()
        tonePlayer.stop()
    }
}
