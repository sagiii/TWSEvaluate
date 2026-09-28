package com.sagiii.twsevaluate.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 会議モードの本体: Bluetooth SCO経由のマイク音声をWAVファイルへ録音しつつ、
 * 同時に指定ミリ秒(既定2秒)遅らせて自声をイヤホンにモニタ再生する。
 * SCO接続自体は[BluetoothScoController]が別途担当する前提。
 */
class ConferenceRecorder(private val delayMs: Long = DEFAULT_DELAY_MS) {

    var monitorEnabled: Boolean = true

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var job: Job? = null

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun start(scope: CoroutineScope, outputFile: File) {
        val minBufIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            ENCODING,
            minBufIn * 4,
        )
        val minBufOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, ENCODING)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(ENCODING)
                    .build(),
            )
            .setBufferSizeInBytes(minBufOut * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioRecord = record
        audioTrack = track
        record.startRecording()
        track.play()

        val wavWriter = WavFileWriter(outputFile, SAMPLE_RATE, channels = 1, bitsPerSample = 16)

        job = scope.launch(Dispatchers.IO) {
            val chunkFrames = (SAMPLE_RATE * CHUNK_MS / 1000).toInt()
            val buffer = ByteArray(chunkFrames * 2) // 16bit = 2byte/frame
            val delayQueue = ArrayDeque<ByteArray>()
            var bufferedMs = 0L
            try {
                while (isActive) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val chunk = buffer.copyOf(read)
                        wavWriter.writePcm(chunk)
                        _level.value = rmsLevel(chunk)

                        if (monitorEnabled) {
                            delayQueue.addLast(chunk)
                            bufferedMs += CHUNK_MS
                            if (bufferedMs >= delayMs) {
                                val toPlay = delayQueue.removeFirst()
                                bufferedMs -= CHUNK_MS
                                track.write(toPlay, 0, toPlay.size)
                            }
                        } else if (delayQueue.isNotEmpty()) {
                            delayQueue.clear()
                            bufferedMs = 0
                        }
                    }
                }
            } finally {
                wavWriter.close()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null
        runCatching { audioTrack?.stop() }
        audioTrack?.release()
        audioTrack = null
        _level.value = 0f
    }

    private fun rmsLevel(chunk: ByteArray): Float {
        if (chunk.size < 2) return 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < chunk.size) {
            val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort()
            sum += (sample * sample).toDouble()
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_MS = 20L
        const val DEFAULT_DELAY_MS = 2000L
    }
}
