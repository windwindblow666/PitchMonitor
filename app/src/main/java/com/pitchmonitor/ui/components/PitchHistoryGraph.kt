package com.pitchmonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ln

/**
 * Scrolling pitch-history graph. Plots frequency on a logarithmic Y-axis
 * (natural for musical pitch) over the last ~10 seconds. Gaps appear where
 * no pitch was detected (silence/noise).
 *
 * The graph flexes: pass [Modifier.weight] from the parent Column so it fills
 * leftover screen space (min height 100dp).
 */
@Composable
fun PitchHistoryGraph(
    history: List<Float?>,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

    // Log-scale frequency range: 55 Hz (A1) to 1760 Hz (A6)
    val minFreq = 55f
    val maxFreq = 1760f
    val minLog = ln(minFreq.toDouble())
    val maxLog = ln(maxFreq.toDouble())

    Column(modifier = modifier) {
        Text(
            text = "音高历史",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = labelColor,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
                .defaultMinSize(minHeight = 100.dp),
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize(),
            ) {
                val w = size.width
                val h = size.height
                val left = 36f
                val right = w - 4f
                val top = 4f
                val bottom = h - 4f
                val plotH = bottom - top

                // Horizontal grid lines at each octave (A1, A2, A3, A4, A5, A6)
                val octaveFreqs = floatArrayOf(55f, 110f, 220f, 440f, 880f, 1760f)
                for (freq in octaveFreqs) {
                    val yRatio = ((ln(freq.toDouble()) - minLog) / (maxLog - minLog)).toFloat()
                    val y = bottom - yRatio * plotH
                    drawLine(
                        color = gridColor,
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 1f,
                    )
                }

                if (history.isEmpty()) return@Canvas

                // Build the pitch line, breaking at null gaps
                val stepX = if (history.size > 1) (right - left) / (history.size - 1) else (right - left)
                var path = Path()
                var pathStarted = false

                for (i in history.indices) {
                    val freq = history[i]
                    val x = left + i * stepX

                    if (freq == null || freq < minFreq || freq > maxFreq) {
                        // Gap — flush current path and start a new one
                        if (pathStarted) {
                            drawPath(path = path, color = lineColor, style = Stroke(width = 3f))
                            path = Path()
                            pathStarted = false
                        }
                    } else {
                        val yRatio = ((ln(freq.toDouble()) - minLog) / (maxLog - minLog)).toFloat()
                            .coerceIn(0f, 1f)
                        val y = bottom - yRatio * plotH
                        if (!pathStarted) {
                            path.moveTo(x, y)
                            pathStarted = true
                        } else {
                            path.lineTo(x, y)
                        }
                    }
                }
                if (pathStarted) {
                    drawPath(path = path, color = lineColor, style = Stroke(width = 3f))
                }
            }

            // Frequency axis labels overlaid via Compose Text
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .align(Alignment.CenterStart),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("1760", "880", "440", "220", "110", "55").forEach { label ->
                    Text(
                        text = label,
                        fontSize = 9.sp,
                        color = labelColor,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
    }
}
