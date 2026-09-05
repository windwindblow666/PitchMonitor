package com.pitchmonitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CC8F8),
    onPrimary = Color(0xFF06283A),
    secondary = Color(0xFF8FE3A5),
    onSecondary = Color(0xFF0A2A14),
    error = Color(0xFFFF6B6B),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE8EDF4),
    surface = Color(0xFF141922),
    onSurface = Color(0xFFE8EDF4),
    surfaceVariant = Color(0xFF1D2430),
    onSurfaceVariant = Color(0xFFA9B4C2),
    outline = Color(0xFF39424F),
)

/** Dark-only theme — a monitoring app reads best on dark. */
@Composable
fun PitchMonitorTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
