package com.sagiii.twsevaluate.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.core.content.ContextCompat
import com.sagiii.twsevaluate.audio.EvaluationSessionService
import com.sagiii.twsevaluate.audio.ScoState
import com.sagiii.twsevaluate.data.EvaluationMode
import com.sagiii.twsevaluate.data.ModeSpan
import com.sagiii.twsevaluate.data.Session
import com.sagiii.twsevaluate.data.SessionRepository
import java.io.File
import kotlinx.coroutines.launch

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
    var conferenceSegmentIndex by remember { mutableStateOf(0) }
    val conferenceFiles = remember { mutableStateListOf<String>() }
    var isRecordingConference by remember { mutableStateOf(false) }
    var monitorEnabled by remember { mutableStateOf(true) }
    var musicTitle by remember { mutableStateOf<String?>(null) }

    fun elapsedNow(): Long = SystemClock.elapsedRealtime() - sessionStartElapsed

    val scoState by produceState(initialValue = ScoState.DISCONNECTED, service) {
        service?.scoState?.collect { value = it }
    }
    val conferenceLevel by produceState(initialValue = 0f, service) {
        service?.conferenceLevel?.collect { value = it }
    }
    val musicIsPlaying by produceState(initialValue = false, service) {
        service?.musicIsPlaying?.collect { value = it }
    }

    fun stopCurrentModeSideEffects() {
        when (selectedMode) {
            EvaluationMode.MUSIC -> service?.pauseMusic()
            EvaluationMode.CONFERENCE -> {
                service?.stopConferenceRecording()
                service?.stopConferenceMode()
                isRecordingConference = false
            }
        }
    }

    fun switchTo(newMode: EvaluationMode) {
        if (newMode == selectedMode || service == null) return
        val now = elapsedNow()
        modeTimeline.add(ModeSpan(selectedMode, currentSegmentStartMs, now))
        stopCurrentModeSideEffects()
        selectedMode = newMode
        currentSegmentStartMs = now
        when (newMode) {
            EvaluationMode.MUSIC -> service?.resumeMusic()
            EvaluationMode.CONFERENCE -> service?.startConferenceMode()
        }
    }

    // 会議モード選択中にSCOが接続できたら録音を開始する
    LaunchedEffect(selectedMode, scoState, service) {
        if (selectedMode == EvaluationMode.CONFERENCE && scoState == ScoState.CONNECTED && !isRecordingConference) {
            val file = File(repository.sessionDir(sessionId), "conference_${conferenceSegmentIndex}.wav")
            service?.startConferenceRecording(file)
            conferenceFiles.add(file.absolutePath)
            conferenceSegmentIndex++
            isRecordingConference = true
        }
    }
    LaunchedEffect(monitorEnabled) {
        service?.setMonitorEnabled(monitorEnabled)
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val title = queryDisplayName(context, uri)
            musicTitle = title
            service?.playMusic(uri, title)
        }
    }

    val scope = rememberCoroutineScope()

    fun finish() {
        val now = elapsedNow()
        modeTimeline.add(ModeSpan(selectedMode, currentSegmentStartMs, now))
        stopCurrentModeSideEffects()
        service?.stopMusic()
        scope.launch {
            repository.save(
                Session(
                    id = sessionId,
                    createdAtEpochMs = System.currentTimeMillis() - now,
                    photoPath = photoPath,
                    videoPath = null,
                    conferenceAudioPaths = conferenceFiles.toList(),
                    modeTimeline = modeTimeline.toList(),
                ),
            )
            onFinished()
        }
    }

    Scaffold(
        topBar = {
            EvaluationTopBar(photoPath = photoPath, onClose = { finish() })
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

            Spacer(modifier = Modifier.size(24.dp))

            if (!permissionsGranted) {
                Text("録音・Bluetooth・通知の権限が必要です")
            } else when (selectedMode) {
                EvaluationMode.MUSIC -> MusicModeContent(
                    isPlaying = musicIsPlaying,
                    title = musicTitle,
                    onPickFile = { audioPicker.launch(arrayOf("audio/*")) },
                    onPlayPause = {
                        if (musicIsPlaying) service?.pauseMusic() else service?.resumeMusic()
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
private fun EvaluationTopBar(photoPath: String, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val bitmap = remember(photoPath) {
            runCatching { BitmapFactory.decodeFile(photoPath)?.asImageBitmap() }.getOrNull()
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
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "評価を終了")
        }
    }
}

@Composable
private fun MusicModeContent(
    isPlaying: Boolean,
    title: String?,
    onPickFile: () -> Unit,
    onPlayPause: () -> Unit,
) {
    Column {
        Button(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text("端末内の音楽ファイルを選ぶ")
        }
        Spacer(modifier = Modifier.size(16.dp))
        Text(title ?: "曲が選択されていません", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.size(12.dp))
        if (title != null) {
            Button(onClick = onPlayPause) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(if (isPlaying) "一時停止" else "再生")
            }
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
