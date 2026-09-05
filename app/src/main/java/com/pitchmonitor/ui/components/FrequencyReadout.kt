package com.pitchmonitor.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitchmonitor.model.PitchResult
import com.pitchmonitor.ui.LocalDimens

/**
 * Large frequency readout: big Hz number on top, note name + octave below.
 * Shows "—" / "无音高" when no pitch is detected.
 *
 * Every text block has a FIXED height (dimens freqBlock/noteBlock) and the
 * clarity line is always rendered, so the card size is identical whether or
 * not a pitch is present — constant height while monitoring, no jarring
 * reflow when states flip.
 */
@Composable
fun FrequencyReadout(
    result: PitchResult,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimens.current
    val freq = result.freqHz
    val hasPitch = freq != null

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Frequency in Hz — fixed-height block
        Box(
            modifier = Modifier.height(d.freqBlockHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (hasPitch) "%.1f".format(freq) else "—",
                fontSize = d.freqFont.sp,
                fontWeight = FontWeight.Light,
                color = if (hasPitch) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                textAlign = TextAlign.Center,
            )
        }

        Text(
            text = "Hz",
            fontSize = d.hzFont.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )

        // Note name + octave — fixed-height block
        Box(
            modifier = Modifier.height(d.noteBlockHeight),
            contentAlignment = Alignment.Center,
        ) {
            if (hasPitch && result.note != null) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = result.note,
                        fontSize = d.noteFont.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${result.octave ?: ""}",
                        fontSize = d.octaveFont.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            } else {
                Text(
                    text = "无音高",
                    fontSize = (d.noteFont.sp.value * 0.45f).sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )
            }
        }

        // Clarity — always rendered so the row never appears/disappears
        Text(
            text = if (hasPitch) "清晰度 %d%%".format((result.clarity * 100).toInt()) else "清晰度 —",
            fontSize = d.clarityFont.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (hasPitch) 0.4f else 0.2f),
        )
    }
}
