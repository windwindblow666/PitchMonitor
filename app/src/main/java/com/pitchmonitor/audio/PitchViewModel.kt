package com.pitchmonitor.audio

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pitchmonitor.data.SessionStore
import com.pitchmonitor.model.PitchResult
import com.pitchmonitor.model.PitchSession
import com.pitchmonitor.util.Fmt
import com.pitchmonitor.util.NoteUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

enum class MonitorMode { LIVE, RECORD }

/** A just-finished recording waiting for the user to name & save (or discard). */
data class PendingRecording(
    val createdAt: Long,
    val durationMs: Long,
    val timesMs: List<Long>,
    val freqs: List<Float?>,
    val audioFile: File?,
) {
    fun defaultName(): String = Fmt.defaultSessionName(createdAt, durationMs)
}

/**
 * Owns the audio pipeline and exposes observable UI state.
 *
 * Raw per-frame MPM detections are jittery, so results pass through a tracker:
 *  1. Gates — silence (rms) and clarity thresholds reject noise frames.
 *  2. Lock-in — a new pitch must repeat consistently before it is tracked.
 *  3. Continuity — near detections are EMA-smoothed; ×2/×3/÷2/÷3 jumps are
 *     octave errors and get held (persistent flips trigger a re-lock).
 *  4. Re-pitch — a genuinely different pitch must repeat before jumping.
 *  5. Display median — last 3 tracked values, median shown.
 *
 * Modes: LIVE monitors without persisting; RECORD additionally stores the
 * (timestamp, frequency) stream and offers it for naming/saving on stop.
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

        private const val LOCK_IN_FRAMES = 3
        private const val RELOCK_FRAMES = 4
        private const val OCTAVE_ESCAPE_FRAMES = 10
        private const val SILENCE_RESET_FRAMES = 15
        private const val TOLERANCE = 0.07f
        private const val OCTAVE_TOL = 0.05f
        private const val EMA_WEIGHT = 0.15f
    }

    private val capture = AudioCapture(SAMPLE_RATE, FRAME_SIZE)
    private val detector = PitchDetector(SAMPLE_RATE)
    private val tonePlayer = ReferenceTonePlayer(SAMPLE_RATE)

    // ---------- mode / recording ----------
    private val _mode = MutableStateFlow(MonitorMode.LIVE)
    val mode: StateFlow<MonitorMode> = _mode.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _pendingRecording = MutableStateFlow<PendingRecording?>(null)
    val pendingRecording: StateFlow<PendingRecording?> = _pendingRecording.asStateFlow()

    private var tickerJob: Job? = null
    private var recordStartElapsed = 0L
    private var recordCreatedAt = 0L
    private var wav: WavRecorder? = null
    private val recTimes = ArrayList<Long>()
    private val recFreqs = ArrayList<Float?>()

    // ---------- live state ----------
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

    // ---------- mode ----------
    fun setMode(m: MonitorMode) {
        if (_isRunning.value) return
        _mode.value = m
    }

    // ---------- recording lifecycle ----------
    fun start() {
        if (_isRunning.value) return
        _isRunning.value = true
        if (_mode.value == MonitorMode.RECORD) {
            recTimes.clear()
            recFreqs.clear()
            recordStartElapsed = SystemClock.elapsedRealtime()
            recordCreatedAt = System.currentTimeMillis()
            _elapsedMs.value = 0L
            wav = WavRecorder(audioFile(recordCreatedAt), SAMPLE_RATE)
            tickerJob = viewModelScope.launch {
                while (true) {
                    _elapsedMs.value = SystemClock.elapsedRealtime() - recordStartElapsed
                    delay(100)
                }
            }
        }
        captureJob = viewModelScope.launch(Dispatchers.Default) {
            capture.frames().collect { frame ->
                wav?.write(frame)
                processFrame(frame)
            }
        }
    }

    fun stop() {
        if (!_isRunning.value) return
        _isRunning.value = false
        // wait for the collector to exit its current blocking read before
        // touching the WAV file (read returns within one frame, ~47 ms)
        captureJob?.let { job -> runBlocking { runCatching { withTimeout(500) { job.join() } } } }
        captureJob = null
        tickerJob?.cancel()
        tickerJob = null
        if (_mode.value == MonitorMode.RECORD) {
            val recorder = wav
            wav = null
            if (recTimes.isNotEmpty()) {
                recorder?.finish()
                _pendingRecording.value = PendingRecording(
                    createdAt = recordCreatedAt,
                    durationMs = recTimes.last(),
                    timesMs = recTimes.toList(),
                    freqs = recFreqs.toList(),
                    audioFile = recorder?.let { File(audioFile(recordCreatedAt).absolutePath).takeIf { f -> f.exists() } },
                )
            } else {
                recorder?.abort()
            }
        }
    }

    private fun audioFile(id: Long): File =
        File(File(getApplication<Application>().filesDir, "pitch_sessions").apply { mkdirs() }, "$id.wav")

    fun audioFileFor(id: Long): File? = audioFile(id).takeIf { it.exists() }

    fun savePending(name: String?) {
        val p = _pendingRecording.value ?: return
        val session = PitchSession(
            id = p.createdAt,
            name = name?.takeIf { it.isNotBlank() } ?: p.defaultName(),
            createdAt = p.createdAt,
            durationMs = p.durationMs,
            timesMs = p.timesMs,
            freqs = p.freqs,
        )
        viewModelScope.launch(Dispatchers.IO) {
            SessionStore.save(getApplication(), session)
        }
        _pendingRecording.value = null
        recTimes.clear()
        recFreqs.clear()
    }

    fun discardPending() {
        _pendingRecording.value?.audioFile?.delete()
        _pendingRecording.value = null
        recTimes.clear()
        recFreqs.clear()
    }

    fun deleteSession(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            SessionStore.delete(getApplication(), id)
        }
    }

    fun loadSession(id: Long): PitchSession? = SessionStore.load(getApplication(), id)

    // ---------- reference tone ----------
    fun toggleReferenceTone() {
        if (tonePlayer.isPlaying) {
            tonePlayer.stop()
            _isTonePlaying.value = false
        } else {
            tonePlayer.start(440f)
            _isTonePlaying.value = true
        }
    }

    fun clearHistory() {
        historyBuffer.clear()
        _history.value = emptyList()
    }

    // ---------- audio processing ----------
    private fun processFrame(frame: ShortArray) {
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

        silentRun = 0
        val tracked = track(raw)
        if (tracked == null) {
            publish(null, detection.clarity, rms)
        } else {
            publish(medianOfWindow(tracked), detection.clarity, rms)
        }
    }

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
                stableFreq = s + (raw - s) * EMA_WEIGHT
                raw
            }
            near(raw, s * 2f, OCTAVE_TOL) || near(raw, s * 3f, OCTAVE_TOL) ||
                near(raw, s / 2f, OCTAVE_TOL) || near(raw, s / 3f, OCTAVE_TOL) -> {
                octaveRejectCount++
                if (octaveRejectCount >= OCTAVE_ESCAPE_FRAMES) {
                    stableFreq = raw
                    octaveRejectCount = 0
                    raw
                } else {
                    s
                }
            }
            else -> {
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
                    s
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
        if (_mode.value == MonitorMode.RECORD && _isRunning.value) {
            recTimes.add(SystemClock.elapsedRealtime() - recordStartElapsed)
            recFreqs.add(freq)
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
