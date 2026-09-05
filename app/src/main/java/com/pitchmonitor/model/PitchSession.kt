package com.pitchmonitor.model

/**
 * A saved recording session: a pitch curve over time.
 *
 * @param timesMs    sample timestamps in ms from recording start (parallel to [freqs])
 * @param freqs      detected frequency per sample, null where no pitch
 */
data class PitchSession(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val durationMs: Long,
    val timesMs: List<Long>,
    val freqs: List<Float?>,
)
