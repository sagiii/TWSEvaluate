package com.sagiii.twsevaluate.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlin.math.abs

private const val TAG = "AudioWaveformDecoder"

/**
 * 音楽モードの再生ファイル(同梱AAC/端末内の任意フォーマット)から、シークバー
 * 表示用の概形波形を取り出す。曲全体をフルデコードすると数分の曲では数秒の
 * ラグが出るため、区間ごとにシーク+短いウィンドウだけデコードするサンプリング
 * 方式にしている(曲の長さに関わらずほぼ一定時間で終わる)。
 */
object AudioWaveformDecoder {

    private const val SAMPLE_WINDOW_BYTES = 8192
    private const val MAX_ITERATIONS_PER_BUCKET = 60
    private const val TIMEOUT_US = 5_000L

    fun extract(context: Context, uri: Uri, bucketCount: Int = 120): FloatArray {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            val audioFormat = format ?: return FloatArray(bucketCount)
            extractor.selectTrack(trackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return FloatArray(bucketCount)
            val durationUs = runCatching { audioFormat.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            if (durationUs <= 0L) {
                // durationが取れない場合は素直に全体をデコードする(フォールバック)
                return fullDecode(extractor, codec, bucketCount)
            }

            val peaks = FloatArray(bucketCount)
            var overallPeak = 1
            val bufferInfo = MediaCodec.BufferInfo()

            for (bucket in 0 until bucketCount) {
                val targetUs = durationUs * bucket / bucketCount
                extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()

                var peak = 0
                var collectedBytes = 0
                var inputDone = false
                var iterations = 0

                while (collectedBytes < SAMPLE_WINDOW_BYTES && iterations < MAX_ITERATIONS_PER_BUCKET) {
                    iterations++
                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inIndex)!!
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            var i = 0
                            val n = bufferInfo.size
                            while (i + 1 < n) {
                                val lo = outBuf.get().toInt() and 0xFF
                                val hi = outBuf.get().toInt()
                                val sample = ((hi shl 8) or lo).toShort()
                                val magnitude = abs(sample.toInt())
                                if (magnitude > peak) peak = magnitude
                                i += 2
                            }
                            collectedBytes += bufferInfo.size
                        }
                        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outIndex, false)
                        if (isEos) break
                    }
                }

                peaks[bucket] = peak.toFloat()
                if (peak > overallPeak) overallPeak = peak
            }

            FloatArray(bucketCount) { (peaks[it] / overallPeak).coerceIn(0f, 1f) }
        } catch (e: Exception) {
            Log.e(TAG, "波形の抽出に失敗しました: $uri", e)
            FloatArray(bucketCount)
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }

    /** durationが取得できない稀なケース向けの、曲全体をデコードするフォールバック。 */
    private fun fullDecode(extractor: MediaExtractor, decoder: MediaCodec, bucketCount: Int): FloatArray {
        val pcmChunks = mutableListOf<ByteArray>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val timeoutUs = 10_000L

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outIndex >= 0) {
                if (bufferInfo.size > 0) {
                    val outBuf = decoder.getOutputBuffer(outIndex)!!
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    val chunk = ByteArray(bufferInfo.size)
                    outBuf.get(chunk)
                    pcmChunks.add(chunk)
                }
                val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                decoder.releaseOutputBuffer(outIndex, false)
                if (isEos) outputDone = true
            }
        }

        val totalBytes = pcmChunks.sumOf { it.size }
        val pcm = ByteArray(totalBytes)
        var offset = 0
        for (chunk in pcmChunks) {
            System.arraycopy(chunk, 0, pcm, offset, chunk.size)
            offset += chunk.size
        }
        return PcmWaveform.peakBuckets(pcm, offset = 0, bucketCount = bucketCount)
    }
}
