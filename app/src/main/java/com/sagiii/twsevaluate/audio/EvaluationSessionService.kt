package com.sagiii.twsevaluate.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.net.Uri
import android.os.IBinder
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.sagiii.twsevaluate.R
import com.sagiii.twsevaluate.music.MusicPlayer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow

/**
 * 評価セッション中の音声処理(会議モードのSCO接続・マイク録音+遅延モニタ、
 * 音楽モードの再生)を1つのForeground Serviceにまとめて保持する。
 * Activityが裏に回っても(メーカー純正アプリでANC切替する間も)
 * この処理は継続する。
 */
class EvaluationSessionService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var scoController: BluetoothScoController
        private set
    lateinit var conferenceRecorder: ConferenceRecorder
        private set
    lateinit var musicPlayer: MusicPlayer
        private set

    val scoState: StateFlow<ScoState> get() = scoController.state
    val conferenceLevel: StateFlow<Float> get() = conferenceRecorder.level
    val musicIsPlaying: StateFlow<Boolean> get() = musicPlayer.isPlaying

    inner class LocalBinder : Binder() {
        fun getService(): EvaluationSessionService = this@EvaluationSessionService
    }

    override fun onCreate() {
        super.onCreate()
        scoController = BluetoothScoController(applicationContext)
        conferenceRecorder = ConferenceRecorder()
        musicPlayer = MusicPlayer(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        conferenceRecorder.stop()
        scoController.stop()
        musicPlayer.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    fun playMusic(uri: Uri, title: String) {
        musicPlayer.play(uri, title)
    }

    fun pauseMusic() = musicPlayer.pause()

    fun resumeMusic() = musicPlayer.resume()

    fun stopMusic() = musicPlayer.release()

    fun startConferenceMode() {
        scoController.start()
    }

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun startConferenceRecording(outputFile: File) {
        conferenceRecorder.start(serviceScope, outputFile)
    }

    fun stopConferenceRecording() {
        conferenceRecorder.stop()
    }

    fun stopConferenceMode() {
        scoController.stop()
    }

    fun setMonitorEnabled(enabled: Boolean) {
        conferenceRecorder.monitorEnabled = enabled
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "評価セッション実行中",
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TWS評価セッション実行中")
            .setContentText("音楽/会議モードの録音・再生を継続しています")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "evaluation_session"
        private const val NOTIFICATION_ID = 1
    }
}
