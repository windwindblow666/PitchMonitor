package com.pitchmonitor.audio

import kotlin.math.abs

/**
 * Temporal pitch tracking shared by the live monitor and the offline file
 * importer: octave-error rejection, EMA smoothing, lock-in / re-lock
 * hysteresis, and a display median.
 *
 * Feed one detection per frame via [feed] (null = silence/noise frame) and
 * render the returned value (null = show no pitch this frame).
 */
class PitchTracker {

    companion object {
        private const val LOCK_IN_FRAMES = 3        // frames to acquire a first pitch
        private const val RELOCK_FRAMES = 4         // frames to jump to a new pitch
        private const val OCTAVE_ESCAPE_FRAMES = 10 // persistent octave flip → re-lock
        private const val SILENCE_RESET_FRAMES = 15 // silence run → full reset
        private const val TOLERANCE = 0.07f         // ±7 % = same pitch
        private const val OCTAVE_TOL = 0.05f        // ±5 % = octave-error candidate
        private const val EMA_WEIGHT = 0.15f
        private const val DISPLAY_MEDIAN = 3
    }

    private var stableFreq: Float? = null
    private var candidateFreq: Float? = null
    private var candidateCount = 0
    private var octaveRejectCount = 0
    private var unusableRun = 0
    private val displayWindow = ArrayDeque<Float>(DISPLAY_MEDIAN)

    /**
     * @param raw    this frame's detected frequency, or null if the frame was
     *               silent / too noisy for a confident detection
     */
    fun feed(raw: Float?): Float? {
        if (raw == null) {
            unusableRun++
            if (unusableRun >= SILENCE_RESET_FRAMES) reset()
            return null
        }
        unusableRun = 0
        val tracked = track(raw) ?: return null
        return median(tracked)
    }

    fun reset() {
        stableFreq = null
        candidateFreq = null
        candidateCount = 0
        octaveRejectCount = 0
        displayWindow.clear()
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
                    s  // hold the tracked pitch
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
                    s  // hold
                }
            }
        }
    }

    private fun median(newValue: Float): Float {
        displayWindow.addLast(newValue)
        while (displayWindow.size > DISPLAY_MEDIAN) displayWindow.removeFirst()
        val list = displayWindow.sorted()
        return list[list.size / 2]
    }

    private fun near(a: Float, b: Float, tolerance: Float): Boolean =
        abs(a - b) <= b * tolerance
}
