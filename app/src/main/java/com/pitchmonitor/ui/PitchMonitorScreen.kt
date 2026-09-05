package com.pitchmonitor.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pitchmonitor.audio.PitchViewModel
import com.pitchmonitor.ui.components.CentsMeter
import com.pitchmonitor.ui.components.FrequencyReadout
import com.pitchmonitor.ui.components.PitchHistoryGraph

@Composable
fun PitchMonitorScreen(
    viewModel: PitchViewModel,
) {
    val context = LocalContext.current
    val result by viewModel.result.collectAsState()
    val history by viewModel.history.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val isTonePlaying by viewModel.isTonePlaying.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Text(
                    text = "实时音高监测器",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Frequency readout
            FrequencyReadout(
                result = result,
                modifier = Modifier.fillMaxWidth(),
            )

            // Cents meter
            CentsMeter(
                result = result,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            // Pitch history graph — flexes to fill remaining space
            PitchHistoryGraph(
                history = history,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            // Start / Stop button
            // Reference tone A4 toggle (also serves as a self-test source)
            OutlinedButton(
                onClick = { viewModel.toggleReferenceTone() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isTonePlaying) "停止标准音 A4 (440 Hz)" else "播放标准音 A4 (440 Hz)",
                    fontSize = 15.sp,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

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
                    .height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                                     else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = when {
                        !hasPermission -> "授权麦克风"
                        isRunning -> "停止监测"
                        else -> "开始监测"
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
