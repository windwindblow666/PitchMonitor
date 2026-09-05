package com.pitchmonitor.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pitchmonitor.audio.ImportState
import com.pitchmonitor.model.PitchSession
import com.pitchmonitor.ui.LocalDimens
import com.pitchmonitor.ui.components.CurveCanvas
import com.pitchmonitor.util.Fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * List of saved recordings. Tap a card to open playback; pencil renames;
 * trash icon deletes. The upload icon imports an audio file (MP3/WAV/…) and
 * analyses its pitch into a new session.
 */
@Composable
fun SessionsScreen(
    sessions: List<PitchSession>,
    importState: ImportState,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onImportPicked: (Uri) -> Unit,
    onCancelImport: () -> Unit,
    onImported: (Long) -> Unit,
) {
    val d = LocalDimens.current
    var confirmDelete by remember { mutableStateOf<PitchSession?>(null) }
    var renameTarget by remember { mutableStateOf<PitchSession?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportPicked) }

    // when the import finishes, jump straight into the new session's playback
    LaunchedEffect(importState) {
        val s = importState
        if (s is ImportState.Done) onImported(s.sessionId)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    "历史记录",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) {
                    Icon(
                        Icons.Filled.Upload,
                        contentDescription = "导入音频",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) { padding ->
        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("暂无录音", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                Spacer(Modifier.height(6.dp))
                Text(
                    "在首页切换到「记录监测」开始第一次录音",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = d.screenHPad, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(sessions, key = { it.id }) { s ->
                    SessionCard(
                        session = s,
                        onClick = { onOpen(s.id) },
                        onRename = { renameTarget = s },
                        onDelete = { confirmDelete = s },
                        modifier = d.contentMaxWidth?.let { Modifier.widthIn(max = it) } ?: Modifier,
                    )
                }
            }
        }
    }

    if (renameTarget != null) {
        var newName by remember(renameTarget) { mutableStateOf(renameTarget!!.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("重命名", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("录音名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = newName.trim()
                    if (n.isNotEmpty()) onRename(renameTarget!!.id, n)
                    renameTarget = null
                }) { Text("保存", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            },
        )
    }

    if (confirmDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除录音", fontWeight = FontWeight.Bold) },
            text = { Text("确定删除「${confirmDelete!!.name}」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(confirmDelete!!.id)
                    confirmDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            },
        )
    }

    // ---- import progress / result ----
    when (val st = importState) {
        is ImportState.Running -> AlertDialog(
            onDismissRequest = { },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("正在解析音频", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(st.fileName, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { st.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${(st.progress * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onCancelImport) { Text("取消") }
            },
        )
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = { },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("导入失败", fontWeight = FontWeight.Bold) },
            text = { Text(st.message) },
            confirmButton = {
                TextButton(onClick = onCancelImport) { Text("确定") }
            },
        )
        else -> {}
    }
}

@Composable
private fun SessionCard(
    session: PitchSession,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDimens.current
    val dateStr = remember(session.createdAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(session.createdAt))
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.name,
                    fontSize = d.cardTitleFont.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "$dateStr · ${Fmt.duration(session.durationMs)}",
                    fontSize = d.cardMetaFont.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
                Spacer(Modifier.height(6.dp))
                CurveCanvas(
                    timesMs = session.timesMs,
                    freqs = session.freqs,
                    durationMs = session.durationMs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                )
            }
            IconButton(onClick = onRename) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "重命名",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                )
            }
        }
    }
}
