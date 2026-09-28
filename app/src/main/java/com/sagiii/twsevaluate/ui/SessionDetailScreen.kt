package com.sagiii.twsevaluate.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.sagiii.twsevaluate.data.EvaluationMode
import com.sagiii.twsevaluate.data.ModeSpan
import com.sagiii.twsevaluate.data.Session
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    session: Session,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var playingPath by remember { mutableStateOf<String?>(null) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) {
        onDispose { mediaPlayer?.release() }
    }

    fun toggleAudioPlayback(path: String) {
        if (playingPath == path) {
            mediaPlayer?.release()
            mediaPlayer = null
            playingPath = null
            return
        }
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(path)
            setOnCompletionListener {
                playingPath = null
            }
            prepare()
            start()
        }
        playingPath = path
    }

    fun playVideo(path: String) {
        val file = File(path)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        runCatching { context.startActivity(intent) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(formatDateTime(session.createdAtEpochMs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
        ) {
            item {
                val bitmap = remember(session.photoPath) {
                    runCatching { BitmapFactory.decodeFile(session.photoPath)?.asImageBitmap() }.getOrNull()
                }
                bitmap?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
                Spacer(modifier = Modifier.size(16.dp))
            }

            if (session.videoPaths.isNotEmpty()) {
                item { Text("録画", style = MaterialTheme.typography.titleMedium) }
                items(session.videoPaths) { path ->
                    Button(
                        onClick = { playVideo(path) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("録画を再生 (${File(path).name})")
                    }
                }
                item { Spacer(modifier = Modifier.size(16.dp)) }
            }

            if (session.conferenceAudioPaths.isNotEmpty()) {
                item { Text("会議録音", style = MaterialTheme.typography.titleMedium) }
                items(session.conferenceAudioPaths) { path ->
                    val isPlaying = playingPath == path
                    Button(
                        onClick = { toggleAudioPlayback(path) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = if (isPlaying) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
                    ) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("会議録音を再生 (${File(path).name})")
                    }
                }
                item { Spacer(modifier = Modifier.size(16.dp)) }
            }

            if (session.modeTimeline.isNotEmpty()) {
                item { Text("モード切替タイムライン", style = MaterialTheme.typography.titleMedium) }
                items(session.modeTimeline) { span -> TimelineRow(span) }
                item { Spacer(modifier = Modifier.size(24.dp)) }
            }

            item {
                Button(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("削除")
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("このセッションを削除しますか?") },
            text = { Text("写真・録画・録音がすべて削除され、元に戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("キャンセル") }
            },
        )
    }
}

@Composable
private fun TimelineRow(span: ModeSpan) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (span.mode == EvaluationMode.MUSIC) Icons.Default.MusicNote else Icons.Default.Mic,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Text("${formatMmSs(span.startMs)} - ${formatMmSs(span.endMs)}")
    }
}

private fun formatMmSs(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(epochMs))
