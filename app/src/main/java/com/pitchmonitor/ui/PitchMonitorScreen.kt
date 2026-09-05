package com.pitchmonitor.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pitchmonitor.audio.MonitorMode
import com.pitchmonitor.audio.PitchViewModel
import com.pitchmonitor.ui.components.CentsMeter
import com.pitchmonitor.ui.components.FrequencyReadout
import com.pitchmonitor.ui.components.PitchHistoryGraph
import com.pitchmonitor.util.Fmt

@Composable
fun PitchMonitorScreen(
    viewModel: PitchViewModel,
    onOpenSessions: () -> Unit,
    onSavedGoSessions: () -> Unit,
) {
    val context = LocalContext.current
    val result by viewModel.result.collectAsState()
    val history by viewModel.history.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val isTonePlaying by viewModel.isTonePlaying.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val elapsedMs by viewModel.elapsedMs.collectAsState()
    val pending by viewModel.pendingRecording.collectAsState()

    var showNameDialog by remember { mutableStateOf(false) }
    LaunchedEffect(pending) { showNameDialog = pending != null }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "实时音高监测器",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onOpenSessions) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = "历史记录",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- mode selector ----
            ModeSelector(
                mode = mode,
                enabled = !isRunning,
                onSelect = { viewModel.setMode(it) },
            )

            // ---- REC stopwatch (record mode) ----
            if (isRunning && mode == MonitorMode.RECORD) {
                RecBadge(elapsedMs = elapsedMs)
            }

            // ---- readout card ----
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(vertical = 14.dp)) {
                    FrequencyReadout(result = result, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(2.dp))
                    CentsMeter(result = result, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
                }
            }

            // ---- live history ----
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                PitchHistoryGraph(
                    history = history,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }

            // ---- reference tone ----
            OutlinedButton(
                onClick = { viewModel.toggleReferenceTone() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isTonePlaying) "停止标准音 A4 (440 Hz)" else "标准音 A4 (440 Hz)",
                    fontSize = 14.sp,
                )
            }

            // ---- start / stop ----
            Button(
                onClick = {
                    if (!hasPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (isRunning) {
                        viewModel.stop()
                    } else {
                        viewModel.clearHistory()
                        viewModel.start()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                                     else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = when {
                        !hasPermission -> "授权麦克风"
                        isRunning && mode == MonitorMode.RECORD -> "结束并保存"
                        isRunning -> "停止监测"
                        else -> if (mode == MonitorMode.RECORD) "开始记录" else "开始监测"
                    },
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    // ---- naming dialog after a recording ----
    if (showNameDialog && pending != null) {
        var name by remember(pending) { mutableStateOf(pending!!.defaultName()) }
        AlertDialog(
            onDismissRequest = { },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("保存录音", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "时长 ${Fmt.duration(pending!!.durationMs)}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("录音名称（留空使用默认）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.savePending(name)
                    showNameDialog = false
                    onSavedGoSessions()
                }) { Text("保存", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.discardPending()
                    showNameDialog = false
                }) { Text("放弃", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) }
            },
        )
    }
}

// ---------------- mode selector ----------------

@Composable
private fun ModeSelector(
    mode: MonitorMode,
    enabled: Boolean,
    onSelect: (MonitorMode) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            ModePill("实时监测", mode == MonitorMode.LIVE, enabled) { onSelect(MonitorMode.LIVE) }
            ModePill("记录监测", mode == MonitorMode.RECORD, enabled) { onSelect(MonitorMode.RECORD) }
        }
    }
}

@Composable
private fun RowScope.ModePill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = when {
                selected -> MaterialTheme.colorScheme.primary
                enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
            },
        )
    }
}

// ---------------- REC badge ----------------

@Composable
private fun RecBadge(elapsedMs: Long) {
    val alpha = rememberInfiniteTransition(label = "rec")
        .animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = alpha.value), CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "REC  ${Fmt.clock(elapsedMs)}",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}
