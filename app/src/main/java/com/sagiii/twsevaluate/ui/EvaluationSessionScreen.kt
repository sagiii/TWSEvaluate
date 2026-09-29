package com.sagiii.twsevaluate.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sagiii.twsevaluate.audio.AudioWaveformDecoder
import com.sagiii.twsevaluate.audio.EvaluationSessionService
import com.sagiii.twsevaluate.audio.ScoState
import com.sagiii.twsevaluate.data.EvaluationMode
import com.sagiii.twsevaluate.data.ModeSpan
import com.sagiii.twsevaluate.data.Session
import com.sagiii.twsevaluate.data.SessionRepository
import com.sagiii.twsevaluate.music.BundledTrack
import com.sagiii.twsevaluate.music.BundledTracks
import com.sagiii.twsevaluate.video.ConferenceClip
import com.sagiii.twsevaluate.video.ConferenceVideoMuxer
import com.sagiii.twsevaluate.video.SessionVideoRecorder
import com.sagiii.twsevaluate.util.PhotoBitmapLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** セッション内の1区間(動画セグメント or 会議録音)の絶対経過時間範囲。 */
private class SegmentDraft(val path: String, val startMs: Long) {
    var endMs: Long = startMs
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvaluationSessionScreen(
    sessionId: String,
    photoPath: String,
    repository: SessionRepository,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current

    // --- 権限 ---
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var permissionsGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> permissionsGranted = result.values.all { it } }
    DisposableEffect(Unit) {
        if (!permissionsGranted) permissionLauncher.launch(requiredPermissions.toTypedArray())
        onDispose { }
    }

    // --- Foreground Serviceのバインド ---
    var service by remember { mutableStateOf<EvaluationSessionService?>(null) }
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as EvaluationSessionService.LocalBinder).getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
    }
    DisposableEffect(Unit) {
        val intent = Intent(context, EvaluationSessionService::class.java)
        ContextCompat.startForegroundService(context, intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            runCatching { context.unbindService(connection) }
            context.stopService(intent)
        }
    }

    // --- セッション状態 ---
    var selectedMode by remember { mutableStateOf(EvaluationMode.MUSIC) }
    var currentSegmentStartMs by remember { mutableStateOf(0L) }
    val modeTimeline = remember { mutableStateListOf<ModeSpan>() }
    val sessionStartElapsed = remember { SystemClock.elapsedRealtime() }
    fun elapsedNow(): Long = SystemClock.elapsedRealtime() - sessionStartElapsed
    var conferenceSegmentIndex by remember { mutableStateOf(0) }
    val conferenceFiles = remember { mutableStateListOf<String>() }
    val conferenceClipDrafts = remember { mutableListOf<SegmentDraft>() }
    var isRecordingConference by remember { mutableStateOf(false) }
    var monitorEnabled by remember { mutableStateOf(true) }
    var musicTitle by remember { mutableStateOf<String?>(null) }

    // --- 動画録画(アウトカメラ+端末内蔵マイク) ---
    val lifecycleOwner = LocalLifecycleOwner.current
    val videoRecorder = remember(lifecycleOwner) { SessionVideoRecorder(context, lifecycleOwner) }
    var videoSegmentIndex by remember { mutableStateOf(0) }
    val videoSegmentDrafts = remember { mutableListOf<SegmentDraft>() }
    var isRecordingVideo by remember { mutableStateOf(false) }

    fun startVideoSegment() {
        val file = File(repository.sessionDir(sessionId), "video_${videoSegmentIndex}.mp4")
        videoSegmentIndex++
        isRecordingVideo = true
        val startMs = elapsedNow()
        videoRecorder.startNewSegment(file) { path, _ ->
            isRecordingVideo = false
            if (path != null) videoSegmentDrafts.add(SegmentDraft(path, startMs).apply { endMs = elapsedNow() })
        }
    }

    // 動画は会議モード中のみ録画する(音楽モードの録画は無音になるだけで冗長なため)。
    // switchTo()で会議モードに入るときに開始し、抜けるときに停止する。
    DisposableEffect(lifecycleOwner) {
        var isFirstStart = true
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (isRecordingVideo) {
                        videoRecorder.releaseCamera()
                        isRecordingVideo = false
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (isFirstStart) {
                        isFirstStart = false
                    } else if (selectedMode == EvaluationMode.CONFERENCE && !isRecordingVideo) {
                        startVideoSegment()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scoState by produceState(initialValue = ScoState.DISCONNECTED, service) {
        service?.scoState?.collect { value = it }
    }
    val conferenceLevel by produceState(initialValue = 0f, service) {
        service?.conferenceLevel?.collect { value = it }
    }
    val musicIsPlaying by produceState(initialValue = false, service) {
        service?.musicIsPlaying?.collect { value = it }
    }
    val musicDurationMs by produceState(initialValue = 0, service) {
        service?.musicDurationMs?.collect { value = it }
    }
    var musicPositionMs by remember { mutableStateOf(0) }
    LaunchedEffect(musicIsPlaying, service) {
        while (musicIsPlaying) {
            musicPositionMs = service?.musicPositionMs() ?: 0
            delay(150)
        }
    }

    fun stopCurrentModeSideEffects() {
        when (selectedMode) {
            EvaluationMode.MUSIC -> service?.pauseMusic()
            EvaluationMode.CONFERENCE -> {
                service?.stopConferenceRecording()
                service?.stopConferenceMode()
                isRecordingConference = false
                conferenceClipDrafts.lastOrNull()?.endMs = elapsedNow()
            }
        }
    }

    fun switchTo(newMode: EvaluationMode) {
        if (newMode == selectedMode || service == null) return
        val now = elapsedNow()
        modeTimeline.add(ModeSpan(selectedMode, currentSegmentStartMs, now))
        val leavingConference = selectedMode == EvaluationMode.CONFERENCE
        stopCurrentModeSideEffects()
        if (leavingConference) {
            // 会議モードを抜けたら録画も終了する(会議区間=動画区間になるようにする)
            videoRecorder.stopFinal { }
        }
        selectedMode = newMode
        currentSegmentStartMs = now
        when (newMode) {
            EvaluationMode.MUSIC -> service?.resumeMusic()
            EvaluationMode.CONFERENCE -> {
                service?.startConferenceMode()
                startVideoSegment()
            }
        }
    }

    // 会議モード選択中にSCOが接続できたら録音を開始する
    LaunchedEffect(selectedMode, scoState, service) {
        if (selectedMode == EvaluationMode.CONFERENCE && scoState == ScoState.CONNECTED && !isRecordingConference) {
            val file = File(repository.sessionDir(sessionId), "conference_${conferenceSegmentIndex}.wav")
            service?.startConferenceRecording(file)
            conferenceFiles.add(file.absolutePath)
            conferenceClipDrafts.add(SegmentDraft(file.absolutePath, elapsedNow()))
            conferenceSegmentIndex++
            isRecordingConference = true
        }
    }
    LaunchedEffect(monitorEnabled) {
        service?.setMonitorEnabled(monitorEnabled)
    }

    var musicCurrentUri by remember { mutableStateOf<Uri?>(null) }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            musicPositionMs = 0
            val title = queryDisplayName(context, uri)
            musicTitle = title
            musicCurrentUri = uri
            service?.playMusic(uri, title)
        }
    }

    val scope = rememberCoroutineScope()
    var isFinishing by remember { mutableStateOf(false) }

    fun finish() {
        if (isFinishing) return
        isFinishing = true
        val now = elapsedNow()
        modeTimeline.add(ModeSpan(selectedMode, currentSegmentStartMs, now))
        stopCurrentModeSideEffects()
        service?.stopMusic()
        // 最後の動画セグメントのファイルが確定するのを待ってから、
        // 会議録音と重なる区間だけ動画の音声トラックを差し替えて保存する
        videoRecorder.stopFinal {
            scope.launch {
                val conferenceClips = conferenceClipDrafts.map { ConferenceClip(it.path, it.startMs, it.endMs) }
                val finalVideoPaths = withContext(Dispatchers.Default) {
                    videoSegmentDrafts.map { segment ->
                        val mixedFile = File(File(segment.path).parentFile, "mixed_${File(segment.path).name}")
                        val mixed = ConferenceVideoMuxer.mux(
                            sourceVideo = File(segment.path),
                            outputVideo = mixedFile,
                            segmentStartMs = segment.startMs,
                            segmentEndMs = segment.endMs,
                            conferenceClips = conferenceClips,
                        )
                        if (mixed) {
                            File(segment.path).delete()
                            mixedFile.absolutePath
                        } else {
                            segment.path
                        }
                    }
                }
                repository.save(
                    Session(
                        id = sessionId,
                        createdAtEpochMs = System.currentTimeMillis() - now,
                        photoPath = photoPath,
                        videoPaths = finalVideoPaths,
                        conferenceAudioPaths = conferenceFiles.toList(),
                        modeTimeline = modeTimeline.toList(),
                    ),
                )
                onFinished()
            }
        }
    }

    Scaffold(
        topBar = {
            EvaluationTopBar(photoPath = photoPath, isRecordingVideo = isRecordingVideo, onClose = { finish() })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = selectedMode == EvaluationMode.MUSIC,
                    onClick = { switchTo(EvaluationMode.MUSIC) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                ) { Text("音楽") }
                SegmentedButton(
                    selected = selectedMode == EvaluationMode.CONFERENCE,
                    onClick = { switchTo(EvaluationMode.CONFERENCE) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                ) { Text("会議") }
            }

            Spacer(modifier = Modifier.size(16.dp))

            if (isRecordingVideo) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    LiveCameraPreview(
                        videoRecorder = videoRecorder,
                        modifier = Modifier
                            .size(width = 96.dp, height = 128.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                Spacer(modifier = Modifier.size(16.dp))
            }

            if (!permissionsGranted) {
                Text("録音・Bluetooth・通知の権限が必要です")
            } else when (selectedMode) {
                EvaluationMode.MUSIC -> MusicModeContent(
                    isPlaying = musicIsPlaying,
                    title = musicTitle,
                    currentUri = musicCurrentUri,
                    positionMs = musicPositionMs,
                    durationMs = musicDurationMs,
                    onPickFile = { audioPicker.launch(arrayOf("audio/*")) },
                    onPlayPause = {
                        if (musicIsPlaying) service?.pauseMusic() else service?.resumeMusic()
                    },
                    onSeek = { positionMs ->
                        service?.seekMusic(positionMs)
                        musicPositionMs = positionMs
                    },
                    onPlayBundledTrack = { track ->
                        musicPositionMs = 0
                        val uri = Uri.parse("android.resource://${context.packageName}/${track.resId}")
                        musicTitle = track.title
                        musicCurrentUri = uri
                        service?.playMusic(uri, track.title)
                    },
                )
                EvaluationMode.CONFERENCE -> ConferenceModeContent(
                    scoState = scoState,
                    isRecording = isRecordingConference,
                    level = conferenceLevel,
                    monitorEnabled = monitorEnabled,
                    onMonitorEnabledChange = { monitorEnabled = it },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { finish() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("評価を終了して保存") }
        }
    }
}

@Composable
private fun LiveCameraPreview(videoRecorder: SessionVideoRecorder, modifier: Modifier = Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = androidx.camera.view.PreviewView(ctx).apply {
                scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
            }
            videoRecorder.setPreviewSurfaceProvider(previewView.surfaceProvider)
            previewView
        },
        onRelease = { videoRecorder.setPreviewSurfaceProvider(null) },
    )
}

@Composable
private fun EvaluationTopBar(photoPath: String, isRecordingVideo: Boolean, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val bitmap = remember(photoPath) {
                runCatching { PhotoBitmapLoader.load(photoPath, reqSize = 160)?.asImageBitmap() }.getOrNull()
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                bitmap?.let {
                    Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            if (isRecordingVideo) {
                Spacer(modifier = Modifier.size(8.dp))
                Icon(
                    Icons.Default.FiberManualRecord,
                    contentDescription = "録画中",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text("REC", style = MaterialTheme.typography.labelMedium)
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "評価を終了")
        }
    }
}

@Composable
private fun MusicModeContent(
    isPlaying: Boolean,
    title: String?,
    currentUri: Uri?,
    positionMs: Int,
    durationMs: Int,
    onPickFile: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit,
    onPlayBundledTrack: (BundledTrack) -> Unit,
) {
    Column {
        Text("同梱の試聴用BGM", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.size(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            for ((genre, label) in BundledTracks.genreLabels) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    BundledTracks.tracksFor(genre).forEach { track ->
                        val isCurrent = title == track.title
                        IconButton(
                            onClick = { onPlayBundledTrack(track) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                if (isCurrent && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = track.title,
                                tint = if (isCurrent) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.size(12.dp))
        Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text("端末内の音楽ファイルを選ぶ")
        }
        Spacer(modifier = Modifier.size(16.dp))
        Text(title ?: "曲が選択されていません", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.size(8.dp))
        if (title != null && currentUri != null) {
            val context = LocalContext.current
            val amplitudes by produceState(initialValue = FloatArray(0), currentUri) {
                value = withContext(Dispatchers.Default) { AudioWaveformDecoder.extract(context, currentUri) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlayPause) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                }
                WaveformSeekBar(
                    amplitudes = amplitudes,
                    progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onSeek = { fraction -> onSeek((durationMs * fraction).toInt()) },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "${formatMmSs(positionMs.toLong())} / ${formatMmSs(durationMs.toLong())}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ConferenceModeContent(
    scoState: ScoState,
    isRecording: Boolean,
    level: Float,
    monitorEnabled: Boolean,
    onMonitorEnabledChange: (Boolean) -> Unit,
) {
    Column {
        Text(
            when (scoState) {
                ScoState.DISCONNECTED -> "SCO接続: 未接続"
                ScoState.CONNECTING -> "SCO接続: 接続中..."
                ScoState.CONNECTED -> if (isRecording) "SCO接続: 接続中(録音中)" else "SCO接続: 接続中"
                ScoState.ERROR -> "SCO接続: エラー"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.size(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("自声モニタ(2秒遅延)")
            Spacer(modifier = Modifier.weight(1f))
            Switch(checked = monitorEnabled, onCheckedChange = onMonitorEnabledChange)
        }
        Spacer(modifier = Modifier.size(16.dp))
        LevelMeter(level = level)
    }
}

@Composable
private fun LevelMeter(level: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) return cursor.getString(nameIndex)
    }
    return uri.lastPathSegment ?: "選択した音源"
}

private fun formatMmSs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
