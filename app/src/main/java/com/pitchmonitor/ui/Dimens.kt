package com.pitchmonitor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Responsive dimension set. Phones (width < 600dp) use compact sizes; tablets
 * use expanded sizes plus a content max-width so cards don't stretch across
 * the whole screen.
 */
data class Dimens(
    val screenHPad: Dp,
    val spacing: Dp,

    // monitor top bar
    val titleFont: Int,

    // frequency readout
    val freqFont: Int,
    val hzFont: Int,
    val noteFont: Int,
    val octaveFont: Int,
    val clarityFont: Int,

    // cents meter
    val meterCanvasHeight: Dp,
    val meterTextFont: Int,

    // history graph
    val graphTitleFont: Int,

    // buttons
    val startButtonHeight: Dp,
    val startButtonFont: Int,
    val toneButtonHeight: Dp,
    val toneButtonFont: Int,

    // REC badge
    val recFont: Int,

    // playback screen
    val playbackFreqFont: Int,
    val playbackNoteFont: Int,

    // sessions list
    val cardTitleFont: Int,
    val cardMetaFont: Int,

    /** content column max width; null = fill available width */
    val contentMaxWidth: Dp?,
)

private val CompactDimens = Dimens(
    screenHPad = 16.dp,
    spacing = 12.dp,
    titleFont = 20,
    freqFont = 60,
    hzFont = 16,
    noteFont = 44,
    octaveFont = 22,
    clarityFont = 12,
    meterCanvasHeight = 70.dp,
    meterTextFont = 20,
    graphTitleFont = 13,
    startButtonHeight = 58.dp,
    startButtonFont = 19,
    toneButtonHeight = 44.dp,
    toneButtonFont = 14,
    recFont = 17,
    playbackFreqFont = 44,
    playbackNoteFont = 26,
    cardTitleFont = 15,
    cardMetaFont = 12,
    contentMaxWidth = null,
)

private val ExpandedDimens = Dimens(
    screenHPad = 28.dp,
    spacing = 16.dp,
    titleFont = 26,
    freqFont = 80,
    hzFont = 20,
    noteFont = 58,
    octaveFont = 28,
    clarityFont = 14,
    meterCanvasHeight = 84.dp,
    meterTextFont = 24,
    graphTitleFont = 15,
    startButtonHeight = 68.dp,
    startButtonFont = 22,
    toneButtonHeight = 52.dp,
    toneButtonFont = 16,
    recFont = 20,
    playbackFreqFont = 60,
    playbackNoteFont = 32,
    cardTitleFont = 18,
    cardMetaFont = 14,
    contentMaxWidth = 640.dp,
)

val LocalDimens = staticCompositionLocalOf { CompactDimens }

@Composable
fun rememberDimens(): Dimens {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return if (widthDp < 600) CompactDimens else ExpandedDimens
}
