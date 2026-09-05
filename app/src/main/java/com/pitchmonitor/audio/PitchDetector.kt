package com.pitchmonitor.audio

import kotlin.math.max
import kotlin.math.sqrt

/**
 * McLeod Pitch Method (MPM) — a normalized square-difference-function (NSDF)
 * based monophonic pitch detector.
 *
 * Reference: Philip McLeod, "A Smarter Way to Find Pitch" (2005).
 *
 * The method is robust to amplitude variation, works well in real-time, and
 * produces a clarity/confidence value so we can reject silence and noise.
 *
 * Usage: create one instance, reuse it across frames. Call [detect] with a
 * buffer of float samples in the range [-1, 1].
 */
class PitchDetector(
    private val sampleRate: Int,
) {
    companion object {
        /** NSDF global maximum below this → signal too noisy, reject. */
        private const val MIN_CLARITY = 0.5f

        /** First NSDF peak above (this × global max) is the period. */
        private const val CUTOFF_RATIO = 0.9f

        /** Minimum frequency we try to detect (Hz). C2 — covers voice and guitar;
         *  excluding the long-lag region below this avoids noisy NSDF tail. */
        private const val MIN_FREQ = 65f

        /** Maximum frequency we try to detect (Hz). */
        private const val MAX_FREQ = 2000f
    }

    /** Reusable NSDF buffer (length = frameSize). */
    private var nsdf: FloatArray = FloatArray(0)

    data class Detection(val frequency: Float?, val clarity: Float)

    /**
     * Detects the fundamental frequency in [buffer].
     *
     * @param buffer  audio samples, mono, already normalised to [-1, 1].
     * @param offset  start index within buffer.
     * @param length  number of samples to analyse.
     * @return a Detection with the frequency in Hz (or null) and a clarity 0..1.
     */
    fun detect(buffer: FloatArray, offset: Int, length: Int): Detection {
        val n = length
        if (n < 4) return Detection(null, 0f)

        // Lag range derived from min/max detectable frequency.
        val minTau = max(2, (sampleRate / MAX_FREQ.toDouble()).toInt())
        val maxTau = minOf(n / 2, (sampleRate / MIN_FREQ.toDouble()).toInt())
        if (maxTau <= minTau) return Detection(null, 0f)

        if (nsdf.size < n) nsdf = FloatArray(n)

        // --- 1. Compute NSDF (Normalised Square Difference Function) ---
        //   r(tau) = Σ x[j] * x[j+tau]      for j = 0..n-tau-1
        //   m(tau) = Σ (x[j]² + x[j+tau]²)  for j = 0..n-tau-1
        //   nsdf(tau) = 2*r(tau) / m(tau)   (0 when m(tau) == 0)
        for (tau in 0..maxTau) {
            var r = 0f
            var m = 0f
            val end = n - tau
            var i = 0
            while (i < end) {
                val a = buffer[offset + i]
                val b = buffer[offset + i + tau]
                r += a * b
                m += a * a + b * b
                i++
            }
            nsdf[tau] = if (m > 1e-10f) 2f * r / m else 0f
        }

        // --- 2. Find peaks (local maxima) in the NSDF within the valid lag range ---
        val maxima = ArrayList<Int>(32)
        var tau = minTau + 1
        while (tau < maxTau) {
            if (nsdf[tau] > nsdf[tau - 1] && nsdf[tau] >= nsdf[tau + 1]) {
                maxima.add(tau)
                tau += 2  // next sample can't be a higher local max
            } else {
                tau++
            }
        }
        if (maxima.isEmpty()) return Detection(null, 0f)

        // --- 3. Select the period peak ---
        // Global max clarity gates noise; then take the FIRST peak above
        // 0.9 × global max. For periodic signals NSDF peaks at the period AND
        // its integer multiples with near-equal height, so taking the highest
        // peak would cause octave errors — the first peak is the fundamental.
        var globalMax = 0f
        for (t in maxima) if (nsdf[t] > globalMax) globalMax = nsdf[t]
        if (globalMax < MIN_CLARITY) return Detection(null, globalMax)

        val cutoff = globalMax * CUTOFF_RATIO
        var chosenTau = -1
        for (t in maxima) {
            if (nsdf[t] > cutoff) { chosenTau = t; break }
        }
        if (chosenTau < 0) return Detection(null, globalMax)

        val clarity = nsdf[chosenTau]

        // --- 4. Parabolic interpolation for sub-sample period accuracy ---
        val period = parabolicInterpolation(chosenTau)

        val frequency = sampleRate.toFloat() / period
        if (frequency < MIN_FREQ || frequency > MAX_FREQ) return Detection(null, clarity)

        return Detection(frequency, clarity.coerceIn(0f, 1f))
    }

    /**
     * Fits a parabola to the NSDF peak and its two neighbours, returns the
     * interpolated peak location (sub-sample tau).
     */
    private fun parabolicInterpolation(tau: Int): Float {
        val a = if (tau > 0) nsdf[tau - 1] else 0f
        val b = nsdf[tau]
        val c = if (tau + 1 < nsdf.size) nsdf[tau + 1] else 0f

        val denom = a - 2f * b + c
        if (denom == 0f) return tau.toFloat()

        val shift = 0.5f * (a - c) / denom
        return tau + shift
    }

    /**
     * Computes the RMS (root-mean-square) of a buffer, for loudness / silence gating.
     */
    fun rms(buffer: FloatArray, offset: Int, length: Int): Float {
        var sum = 0f
        var i = 0
        val end = offset + length
        while (i < end) {
            sum += buffer[i] * buffer[i]
            i++
        }
        return sqrt(sum / length)
    }
}
