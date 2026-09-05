package com.pitchmonitor.model

/**
 * Single pitch detection result.
 *
 * @param freqHz   detected fundamental frequency in Hz, or null if no clear pitch
 * @param note     note name without octave (e.g. "A", "C#"), or null
 * @param octave   octave number (A4 = 440 Hz → octave 4), or null
 * @param cents    deviation from the nearest equal-temperament note, -50..+50, or null
 * @param clarity  NSDF peak clarity / confidence, 0..1
 * @param rms      root-mean-square of the audio frame (loudness indicator)
 */
data class PitchResult(
    val freqHz: Float?,
    val note: String?,
    val octave: Int?,
    val cents: Float?,
    val clarity: Float,
    val rms: Float,
)
