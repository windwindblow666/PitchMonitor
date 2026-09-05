package com.pitchmonitor.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

enum class ThemeMode(val label: String) {
    DARK("深色模式"),
    LIGHT("浅色模式"),
    SYSTEM("跟随系统");

    companion object {
        fun from(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: DARK
    }
}

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF0277BD),
    onPrimary = Color.White,
    secondary = Color(0xFF2E7D32),
    onSecondary = Color.White,
    error = Color(0xFFD32F2F),
    background = Color(0xFFF3F5F9),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE3E9F0),
    onSurfaceVariant = Color(0xFF4A5058),
    outline = Color(0xFFB4BBC4),
)

/**
 * App theme. DARK / LIGHT are forced; SYSTEM follows the OS setting.
 * Also keeps the edge-to-edge status-bar icons readable in both themes.
 */
@Composable
fun PitchMonitorTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val darkWhenSystem = isSystemInDarkTheme()
    val useDark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> darkWhenSystem
    }
    val colors = if (useDark) DarkColors else LightColors

    // status bar icons: dark icons on light backgrounds
    val view = LocalView.current
    val context = LocalContext.current
    LaunchedEffect(useDark) {
        (context as? Activity)?.window?.let { window ->
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !useDark
        }
    }

    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
