package com.sagiii.twsevaluate.music

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 音楽モード再生。端末内の音楽ファイル(Uri)を選んでA2DP経由で再生する。
 * 同梱サンプル音源を追加したい場合は res/raw に配置し、
 * MusicScreen側の候補リストにRawResourceTrackとして加えるだけでよい。
 */
class MusicPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentTitle = MutableStateFlow<String?>(null)
    val currentTitle: StateFlow<String?> = _currentTitle

    private val _durationMs = MutableStateFlow(0)
    val durationMs: StateFlow<Int> = _durationMs

    fun play(uri: Uri, title: String) {
        release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setDataSource(context, uri)
            setOnCompletionListener { _isPlaying.value = false }
            prepare()
            start()
        }
        _currentTitle.value = title
        _durationMs.value = mediaPlayer?.duration ?: 0
        _isPlaying.value = true
    }

    fun pause() {
        mediaPlayer?.takeIf { it.isPlaying }?.pause()
        _isPlaying.value = false
    }

    fun resume() {
        mediaPlayer?.start()
        _isPlaying.value = true
    }

    fun seekTo(positionMs: Int) {
        // 既定のseekTo(int)は直前の同期点にスナップする粗いシークで、圧縮音源
        // (AAC/MP3)だとタップした位置とずれて聞こえる。SEEK_CLOSESTでフレーム
        // 精度のシークにする。
        mediaPlayer?.seekTo(positionMs.toLong(), MediaPlayer.SEEK_CLOSEST)
    }

    fun currentPositionMs(): Int = mediaPlayer?.currentPosition ?: 0

    fun release() {
        mediaPlayer?.release()
        mediaPlayer = null
        _isPlaying.value = false
        _durationMs.value = 0
    }
}
