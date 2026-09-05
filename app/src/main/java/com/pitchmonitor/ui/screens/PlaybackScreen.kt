package com.pitchmonitor.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import com.pitchmonitor.data.SessionStore
import com.pitchmonitor.model.PitchSession
import com.pitchmonitor.ui.components.CurveCanvas
import com.pitchmonitor.util.Fmt
import com.pitchmonitor.util.NoteUtil
import kotlin.math.abs
import kotlin.math.min

/**
 * Playback of a recorded session: full curve with a draggable playhead.
 * Play animates the playhead in real time; dragging scrubs to any position.
 * The pitch under the playhead is shown in a readout card.
 * If the session has a recorded WAV, it plays in sync with the playhead.
 */
@Composable
fun PlaybackScreen(
    session: PitchSession,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var playheadMs by remember(session.id) { mutableStateOf(0L) }
    var isPlaying by remember(session.id) { mutableStateOf(false) }

    // audio player for sessions recorded with sound
    val player = remember(session.id) {
        SessionStore.audioFile(context, session.id)?.let { f ->
            try {
                MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    prepare()
                    isLooping = false
                }
            } catch (_: Exception) {
                null
            }
        }
    }
    DisposableEffect(session.id) {
        onDispose { player?.release() }
    }
    LaunchedEffect(session.id) {
        player?.setOnCompletionListener {
            isPlaying = false
            playheadMs = session.durationMs
        }
    }

    // advance playhead; resync to the audio clock when audio is playing
    LaunchedEffect(isPlaying, session.id) {
        if (!isPlaying) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isPlaying && playheadMs < session.durationMs) {
            withFrameNanos { now ->
                if (player != null && player.isPlaying) {
                    val pos = player.currentPosition.toLong()
                    if (abs(pos - playheadMs) > 250) playheadMs = pos.coerceAtMost(session.durationMs)
                    else playheadMs = min(session.durationMs, playheadMs + (now - last) / 1_000_000L)
                } else {
                    playheadMs = min(session.durationMs, playheadMs + (now - last) / 1_000_000L)
                }
                last = now
            }
        }
        if (playheadMs >= session.durationMs) isPlaying = false
    }

    fun seekTo(ms: Long) {
        playheadMs = ms.coerceIn(0L, session.durationMs)
        runCatching { player?.seekTo(playheadMs.toInt()) }
    }

    fun togglePlay() {
        if (isPlaying) {
            player?.pause()
            isPlaying = false
        } else {
            if (playheadMs >= session.durationMs) seekTo(0L)
            player?.let {
                it.seekTo(playheadMs.toInt())
                it.start()
            }
            isPlaying = true
        }
    }

    // pitch under the playhead
    val idx = remember(playheadMs) { lowerBound(session.timesMs, playheadMs) }
    val freq = session.freqs.getOrElse(idx) { null }
    val noteInfo = freq?.let { NoteUtil.freqToNote(it) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            session.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                        )
                        Text(
                            "时长 ${Fmt.duration(session.durationMs)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        )
                    }
                    if (player != null) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "含录音",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // pitch-under-playhead card
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = freq?.let { "%.1f".format(it) } ?: "—",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Light,
                        color = if (freq != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.weight(1.2f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(
                            text = noteInfo?.let { "${it.name}${it.octave}" } ?: "无音高",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (noteInfo != null) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        )
                        Text(
                            text = noteInfo?.let { NoteUtil.formatCents(it.cents) } ?: "",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                }
            }

            // curve with playhead, draggable
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                CurveCanvas(
                    timesMs = session.timesMs,
                    freqs = session.freqs,
                    durationMs = session.durationMs,
                    playheadMs = playheadMs,
                    onScrubFraction = { fraction -> seekTo((fraction * session.durationMs).toLong()) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                )
            }

            // transport controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    Fmt.clock(playheadMs),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.width(52.dp),
                )
                FilledIconButton(
                    onClick = { togglePlay() },
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Text(
                    Fmt.clock(session.durationMs),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.width(52.dp),
                )
            }
        }
    }
}

/** first index with timesMs[index] >= target */
private fun lowerBound(timesMs: List<Long>, target: Long): Int {
    var lo = 0
    var hi = timesMs.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (timesMs[mid] < target) lo = mid + 1 else hi = mid
    }
    return lo
}
