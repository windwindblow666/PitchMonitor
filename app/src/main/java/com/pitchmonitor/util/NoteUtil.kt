package com.pitchmonitor.util

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Converts between frequency (Hz) and 12-tone equal-temperament notes.
 * Reference: A4 = 440 Hz (MIDI note 69).
 */
object NoteUtil {

    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    private const val A4_MIDI = 69
    private const val A4_FREQ = 440.0f

    data class NoteInfo(val name: String, val octave: Int, val cents: Float)

    /**
     * Converts a frequency to the nearest note, its octave, and the cents offset.
     * Returns null for non-positive frequencies.
     */
    fun freqToNote(freqHz: Float): NoteInfo? {
        if (freqHz <= 0f) return null

        // MIDI note number (float, not rounded): 69 + 12 * log2(f / 440)
        val midiFloat = A4_MIDI + 12f * log2(freqHz / A4_FREQ)
        val midiRound = midiFloat.roundToInt()

        val noteIndex = ((midiRound % 12) + 12) % 12  // 0..11
        val octave = midiRound / 12 - 1                 // MIDI 0 = C-1, MIDI 12 = C0, MIDI 69 = A4

        val cents = (midiFloat - midiRound) * 100f      // -50..+50

        return NoteInfo(NOTE_NAMES[noteIndex], octave, cents)
    }

    /**
     * Formats a cents value, always showing the sign.
     */
    fun formatCents(cents: Float): String {
        return if (abs(cents) < 0.5f) "0¢" else "${if (cents > 0) "+" else ""}${cents.roundToInt()}¢"
    }
}
