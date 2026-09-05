package com.pitchmonitor.audio

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pitchmonitor.data.SessionStore
import com.pitchmonitor.model.PitchResult
import com.pitchmonitor.model.PitchSession
import com.pitchmonitor.util.Exporter
import com.pitchmonitor.util.Fmt
import com.pitchmonitor.util.NoteUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
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

/** File-import state machine surfaced to the UI. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val fileName: String, val progress: Float) : ImportState
    data class Done(val sessionId: Long, val name: String) : ImportState
    data class Failed(val message: String) : ImportState
}

/**
 * Owns the audio pipeline and exposes observable UI state.
 *
 * Live pipeline: AudioRecord frames → WAV (record mode, inline, gapless) and
 * → PitchDetector on a side channel → [PitchTracker] (anti-jitter) → UI state.
 *
 * Modes: LIVE monitors without persisting; RECORD additionally stores the
 * (timestamp, frequency) stream and the WAV, offered for naming/saving on stop.
 *
 * File import: decodes an audio file (MP3/AAC/WAV/…) off-line with the same
 * detector + tracker and saves it as a regular session.
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
    }

    private val capture = AudioCapture(SAMPLE_RATE, FRAME_SIZE)
    private val detector = PitchDetector(SAMPLE_RATE)
    private val tonePlayer = ReferenceTonePlayer(SAMPLE_RATE)
    private val tracker = PitchTracker()

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

    private var captureJob: Job? = null

    /**
     * Recording-generation guard. Bumped on every stop(); a collector whose
     * generation is stale no-ops instead of racing the next recording (the
     * v0.3 crash + audio-corruption source).
     */
    private val gen = AtomicInteger(0)

    // ---------- file import ----------
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()
    private var importJob: Job? = null

    // ---------- mode ----------
    fun setMode(m: MonitorMode) {
        if (_isRunning.value) return
        _mode.value = m
    }

    // ---------- recording lifecycle ----------
    fun start() {
        if (_isRunning.value) return
        _isRunning.value = true
        val myGen = gen.incrementAndGet()
        tracker.reset()
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
            val myWav = wav  // local snapshot: an orphaned collector must never touch the next recording's file
            // Pitch detection runs on its own consumer so a slow detection
            // frame can never stall the audio read loop — WAV writes stay
            // gapless and pitch frames, if anything, lag/drop instead.
            val pitchFrames = Channel<ShortArray>(64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val pitchJob = launch(Dispatchers.Default) {
                for (frame in pitchFrames) {
                    if (myGen != gen.get()) break
                    processFrame(frame)
                }
            }
            try {
                capture.frames().collect { frame ->
                    if (myGen != gen.get()) return@collect
                    myWav?.write(frame)
                    pitchFrames.trySend(frame)
                }
            } finally {
                pitchFrames.close()
                runCatching { pitchJob.join() }
            }
        }
    }

    fun stop() {
        if (!_isRunning.value) return
        _isRunning.value = false
        // Invalidate any straggler collector FIRST: after this an orphaned
        // collector no-ops instead of racing the next recording.
        gen.incrementAndGet()
        captureJob?.let { job -> runBlocking { runCatching { withTimeout(2000) { job.join() } } } }
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
                    audioFile = recorder?.let { audioFile(recordCreatedAt).takeIf { f -> f.exists() } },
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

    fun renameSession(id: Long, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            SessionStore.rename(getApplication(), id, newName)
        }
    }

    fun loadSession(id: Long): PitchSession? = SessionStore.load(getApplication(), id)

    // ---------- file import ----------
    fun startImport(uri: Uri) {
        if (_importState.value is ImportState.Running) return
        val app = getApplication<Application>()
        val fileName = Exporter.queryDisplayName(app, uri) ?: "导入音频"
        val baseName = Exporter.sanitizeFileName(fileName.substringBeforeLast('.')).ifEmpty { "导入音频" }
        _importState.value = ImportState.Running(fileName = baseName, progress = 0f)
        val createdAt = System.currentTimeMillis()
        importJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = AudioImporter(
                    app,
                    SessionStore.sessionDir(app),
                    id = createdAt,
                    sampleRate = SAMPLE_RATE,
                ).import(uri, baseName) { progress ->
                    if (_importState.value is ImportState.Running) {
                        _importState.value = ImportState.Running(baseName, progress)
                    }
                }
                val session = PitchSession(
                    id = createdAt,
                    name = baseName,
                    createdAt = createdAt,
                    durationMs = result.durationMs,
                    timesMs = result.timesMs,
                    freqs = result.freqs,
                )
                SessionStore.save(app, session)
                _importState.value = ImportState.Done(createdAt, baseName)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _importState.value = ImportState.Idle
                throw e
            } catch (e: Exception) {
                _importState.value = ImportState.Failed(e.message ?: "导入失败")
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
        _importState.value = ImportState.Idle
    }

    fun consumeImportDone() {
        if (_importState.value is ImportState.Done) _importState.value = ImportState.Idle
    }

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

        var raw: Float? = null
        var clarity = 0f
        if (rms >= SILENCE_RMS_THRESHOLD) {
            val detection = detector.detect(samples, 0, samples.size)
            clarity = detection.clarity
            if (detection.frequency != null && detection.clarity >= MIN_CLARITY) {
                raw = detection.frequency
            }
        }

        val tracked = tracker.feed(raw)
        publish(tracked, if (raw != null) clarity else 0f, rms)
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
        importJob?.cancel()
        tonePlayer.stop()
    }
}
