package com.pitchmonitor.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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
import com.pitchmonitor.ui.LocalDimens
import com.pitchmonitor.ui.components.CurveCanvas
import com.pitchmonitor.util.Exporter
import com.pitchmonitor.util.Fmt
import com.pitchmonitor.util.NoteUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    val d = LocalDimens.current
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
            runCatching { player?.pause() }
            isPlaying = false
        } else {
            if (playheadMs >= session.durationMs) seekTo(0L)
            player?.let {
                runCatching {
                    it.seekTo(playheadMs.toInt())
                    it.start()
                }
            }
            isPlaying = true
        }
    }

    // pitch under the playhead
    val idx = remember(playheadMs) { lowerBound(session.timesMs, playheadMs) }
    val freq = session.freqs.getOrElse(idx) { null }
    val noteInfo = freq?.let { NoteUtil.freqToNote(it) }

    // ---- export ----
    val scope = rememberCoroutineScope()
    var showExportChooser by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf<Triple<String, String, android.net.Uri>?>(null) } // name, mime, uri
    var exportError by remember { mutableStateOf<String?>(null) }

    fun doExport(kind: String) {
        showExportChooser = false
        scope.launch(Dispatchers.IO) {
            try {
                val result = if (kind == "wav") {
                    val f = SessionStore.audioFile(context, session.id)!!
                    Exporter.exportAudio(context, session, f.readBytes())
                } else {
                    Exporter.exportCsv(context, session)
                }
                val mime = if (kind == "wav") "audio/wav" else "text/csv"
                exported = Triple(result.displayName, mime, result.uri)
            } catch (e: Exception) {
                exportError = e.message ?: "导出失败"
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = d.screenHPad / 2, vertical = 6.dp),
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
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    IconButton(onClick = { showExportChooser = true }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = "导出",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(d.contentMaxWidth?.let { Modifier.widthIn(max = it) } ?: Modifier.fillMaxWidth())
                .padding(horizontal = d.screenHPad)
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
                        fontSize = d.playbackFreqFont.sp,
                        fontWeight = FontWeight.Light,
                        color = if (freq != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.weight(1.2f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(
                            text = noteInfo?.let { "${it.name}${it.octave}" } ?: "无音高",
                            fontSize = d.playbackNoteFont.sp,
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

    // export chooser
    if (showExportChooser) {
        AlertDialog(
            onDismissRequest = { showExportChooser = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("导出录音", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    TextButton(
                        onClick = { doExport("wav") },
                        enabled = player != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (player != null) "🎵 录音文件（WAV）" else "🎵 无录音文件（本条无音频）",
                            color = if (player != null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        )
                    }
                    TextButton(
                        onClick = { doExport("csv") },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("📊 音高数据（CSV）", color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "保存到设备的「下载」目录",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportChooser = false }) { Text("取消") }
            },
        )
    }

    // export success → offer share
    exported?.let { (name, mime, shareUri) ->
        AlertDialog(
            onDismissRequest = { exported = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("导出成功", fontWeight = FontWeight.Bold) },
            text = { Text("已保存到「下载」目录：\n$name") },
            confirmButton = {
                TextButton(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(android.content.Intent.EXTRA_STREAM, shareUri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "分享"))
                    exported = null
                }) { Text("分享", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { exported = null }) { Text("完成") }
            },
        )
    }

    // export failure
    exportError?.let { msg ->
        AlertDialog(
            onDismissRequest = { exportError = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("导出失败", fontWeight = FontWeight.Bold) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { exportError = null }) { Text("确定") }
            },
        )
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
