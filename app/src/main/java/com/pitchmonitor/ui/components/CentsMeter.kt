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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitchmonitor.model.PitchResult
import kotlin.math.abs

/**
 * Horizontal tuning meter showing cents deviation from -50 to +50.
 * A needle moves left/right; the center zone turns green when in tune (±5 cents).
 */
@Composable
fun CentsMeter(
    result: PitchResult,
    modifier: Modifier = Modifier,
) {
    val cents = result.cents
    val hasPitch = cents != null
    val centsValue = cents ?: 0f

    val inTuneColor = Color(0xFF4CAF50)
    val needleColor = if (hasPitch && abs(centsValue) < 5f) inTuneColor
                      else if (hasPitch) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val tickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val greenZoneColor = Color(0xFF4CAF50).copy(alpha = 0.15f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp),
            ) {
                val w = size.width
                val h = size.height
                val centerY = h * 0.55f
                val trackLeft = 40f
                val trackRight = w - 40f
                val trackWidth = trackRight - trackLeft

                // Green "in-tune" zone (±5 cents)
                val zoneHalfWidth = trackWidth * (5f / 100f)
                drawRect(
                    color = greenZoneColor,
                    topLeft = Offset(w / 2f - zoneHalfWidth, centerY - 24f),
                    size = androidx.compose.ui.geometry.Size(zoneHalfWidth * 2, 48f),
                )

                // Main track
                drawLine(
                    color = trackColor,
                    start = Offset(trackLeft, centerY),
                    end = Offset(trackRight, centerY),
                    strokeWidth = 4f,
                )

                // Center line (0 cents)
                drawLine(
                    color = tickColor,
                    start = Offset(w / 2f, centerY - 20f),
                    end = Offset(w / 2f, centerY + 20f),
                    strokeWidth = 3f,
                )

                // Tick marks at -50, -25, +25, +50
                for (tick in listOf(-50f, -25f, 25f, 50f)) {
                    val x = w / 2f + (tick / 50f) * (trackWidth / 2f)
                    drawLine(
                        color = tickColor,
                        start = Offset(x, centerY - 12f),
                        end = Offset(x, centerY + 12f),
                        strokeWidth = 2f,
                    )
                }

                // Needle
                if (hasPitch) {
                    val needleX = w / 2f + (centsValue.coerceIn(-50f, 50f) / 50f) * (trackWidth / 2f)
                    drawLine(
                        color = needleColor,
                        start = Offset(needleX, centerY - 30f),
                        end = Offset(needleX, centerY + 30f),
                        strokeWidth = 5f,
                    )
                    drawCircle(
                        color = needleColor,
                        radius = 6f,
                        center = Offset(needleX, centerY - 30f),
                    )
                }
            }
            // Scale labels overlaid via Compose Text
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf(-50, -25, 0, 25, 50).forEach { label ->
                    Text(
                        text = "$label",
                        fontSize = 11.sp,
                        color = tickColor,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Cents value text
        Text(
            text = if (hasPitch) {
                val sign = if (centsValue > 0) "+" else ""
                "$sign${centsValue.toInt()}¢"
            } else "—",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = needleColor,
        )
    }
}
