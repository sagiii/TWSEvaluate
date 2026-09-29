package com.sagiii.twsevaluate.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.sagiii.twsevaluate.audio.WaveformExtractor
import com.sagiii.twsevaluate.data.EvaluationMode
import com.sagiii.twsevaluate.data.ModeSpan
import com.sagiii.twsevaluate.data.Session
import com.sagiii.twsevaluate.util.PhotoBitmapLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    var durationMs by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { mediaPlayer?.release() }
    }

    // 再生中は100msごとに再生位置を拾ってシークバーに反映する
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = mediaPlayer?.currentPosition ?: 0
            delay(100)
        }
    }

    fun loadTrack(path: String, autoPlay: Boolean) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(path)
            setOnCompletionListener {
                isPlaying = false
                positionMs = durationMs
            }
            prepare()
        }
        playingPath = path
        durationMs = mediaPlayer?.duration ?: 0
        positionMs = 0
        if (autoPlay) {
            mediaPlayer?.start()
            isPlaying = true
        } else {
            isPlaying = false
        }
    }

    fun togglePlayback(path: String) {
        if (playingPath != path) {
            loadTrack(path, autoPlay = true)
            return
        }
        if (isPlaying) {
            mediaPlayer?.pause()
            isPlaying = false
        } else {
            mediaPlayer?.start()
            isPlaying = true
        }
    }

    fun seekTrack(path: String, fraction: Float) {
        if (playingPath != path) loadTrack(path, autoPlay = false)
        val target = (durationMs * fraction).toInt()
        mediaPlayer?.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
        positionMs = target
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
                    runCatching { PhotoBitmapLoader.load(session.photoPath, reqSize = 720)?.asImageBitmap() }.getOrNull()
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

            val takeCount = maxOf(session.videoPaths.size, session.conferenceAudioPaths.size)
            if (takeCount > 0) {
                item { Text("会議テイク", style = MaterialTheme.typography.titleMedium) }
                items(takeCount) { i ->
                    val videoPath = session.videoPaths.getOrNull(i)
                    val audioPath = session.conferenceAudioPaths.getOrNull(i)
                    ConferenceTakeCard(
                        index = i,
                        videoPath = videoPath,
                        audioPath = audioPath,
                        isAudioCurrent = audioPath != null && playingPath == audioPath,
                        isPlaying = isPlaying,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        onPlayVideo = { videoPath?.let { playVideo(it) } },
                        onToggleAudio = { audioPath?.let { togglePlayback(it) } },
                        onSeekAudio = { fraction -> audioPath?.let { seekTrack(it, fraction) } },
                    )
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
private fun ConferenceTakeCard(
    index: Int,
    videoPath: String?,
    audioPath: String?,
    isAudioCurrent: Boolean,
    isPlaying: Boolean,
    positionMs: Int,
    durationMs: Int,
    onPlayVideo: () -> Unit,
    onToggleAudio: () -> Unit,
    onSeekAudio: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text("会議 ${index + 1}", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.size(8.dp))

        if (videoPath != null) {
            Button(onClick = onPlayVideo, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Videocam, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("録画を再生")
            }
            Spacer(modifier = Modifier.size(8.dp))
        }

        if (audioPath != null) {
            val amplitudes by produceState(initialValue = FloatArray(0), audioPath) {
                value = withContext(Dispatchers.IO) { WaveformExtractor.extract(File(audioPath)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleAudio) {
                    Icon(
                        if (isAudioCurrent && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isAudioCurrent && isPlaying) "一時停止" else "再生",
                    )
                }
                WaveformSeekBar(
                    amplitudes = amplitudes,
                    progress = if (isAudioCurrent && durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onSeek = onSeekAudio,
                    modifier = Modifier.weight(1f),
                )
            }
            val shownPosition = if (isAudioCurrent) positionMs.toLong() else 0L
            val shownDuration = if (isAudioCurrent) durationMs.toLong() else 0L
            Text(
                "${formatMmSs(shownPosition)} / ${formatMmSs(shownDuration)}",
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            Text("(この区間の録音はありません)", style = MaterialTheme.typography.labelSmall)
        }
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
