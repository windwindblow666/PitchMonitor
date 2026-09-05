package com.pitchmonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.ln
import kotlin.math.max

private const val MIN_FREQ = 55f
private const val MAX_FREQ = 1760f
private val MIN_LOG = ln(MIN_FREQ.toDouble())
private val MAX_LOG = ln(MAX_FREQ.toDouble())

/**
 * Draws a full recorded pitch curve (time on X, log frequency on Y) with
 * octave gridlines. Optional [playheadMs] draws a vertical scrub line with a
 * dot at the intersection with the curve. Optional [onScrubFraction] reports
 * taps / horizontal drags as a 0..1 time fraction.
 */
@Composable
fun CurveCanvas(
    timesMs: List<Long>,
    freqs: List<Float?>,
    durationMs: Long,
    modifier: Modifier = Modifier,
    playheadMs: Long? = null,
    onScrubFraction: ((Float) -> Unit)? = null,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val playheadColor = MaterialTheme.colorScheme.secondary

    val scrubModifiers = if (onScrubFraction != null) {
        Modifier
            .pointerInput(durationMs) {
                detectTapGestures { pos ->
                    onScrubFraction((pos.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    onScrubFraction((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    } else Modifier

    Canvas(modifier = modifier.then(scrubModifiers)) {
        val w = size.width
        val h = size.height
        val left = 8f
        val right = w - 8f
        val top = 8f
        val bottom = h - 8f
        val plotW = max(1f, right - left)
        val plotH = max(1f, bottom - top)
        val dur = max(1L, durationMs).toFloat()

        // octave gridlines
        for (freq in floatArrayOf(55f, 110f, 220f, 440f, 880f, 1760f)) {
            val yRatio = ((ln(freq.toDouble()) - MIN_LOG) / (MAX_LOG - MIN_LOG)).toFloat()
            val y = bottom - yRatio * plotH
            drawLine(gridColor, Offset(left, y), Offset(right, y), 1.5f)
        }

        if (timesMs.isEmpty()) return@Canvas

        // decimate very long recordings so Path building stays cheap
        val n = timesMs.size
        val stride = max(1, n / 2400)

        fun xOf(i: Int): Float = left + (timesMs[i] / dur) * plotW
        fun yOf(freq: Float): Float {
            val ratio = ((ln(freq.toDouble()) - MIN_LOG) / (MAX_LOG - MIN_LOG)).toFloat().coerceIn(0f, 1f)
            return bottom - ratio * plotH
        }

        var path: Path? = null
        var i = 0
        while (i < n) {
            val freq = freqs[i]
            if (freq == null || freq < MIN_FREQ || freq > MAX_FREQ) {
                path?.let { drawPath(it, lineColor, style = Stroke(3f)) }
                path = null
            } else {
                val p = path ?: Path().also { path = it }
                val x = xOf(i)
                val y = yOf(freq)
                if (p.isEmpty) p.moveTo(x, y) else p.lineTo(x, y)
            }
            i += stride
        }
        path?.let { drawPath(it, lineColor, style = Stroke(3f)) }

        // playhead: vertical line + dot at curve intersection
        if (playheadMs != null) {
            val px = left + (playheadMs / dur) * plotW
            drawLine(
                playheadColor, Offset(px, top - 2f), Offset(px, bottom + 2f), 3f,
            )
            // find sample nearest to playhead
            val target = playheadMs
            var lo = 0
            var hi = n - 1
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (timesMs[mid] < target) lo = mid + 1 else hi = mid
            }
            val idx = lo
            val f = freqs[idx]
            if (f != null && f in MIN_FREQ..MAX_FREQ) {
                drawCircle(playheadColor, 9f, Offset(xOf(idx), yOf(f)))
                drawCircle(Color.White.copy(alpha = 0.9f), 4f, Offset(xOf(idx), yOf(f)))
            }
        }
    }
}
