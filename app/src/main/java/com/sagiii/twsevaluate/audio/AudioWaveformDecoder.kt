package com.sagiii.twsevaluate.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

private const val TAG = "AudioWaveformDecoder"

/**
 * 音楽モードの再生ファイル(同梱AAC/端末内の任意フォーマット)をデコードし、
 * シークバー表示用の概形波形を取り出す。MediaExtractor+MediaCodecで一度PCMに
 * 全部デコードしてから[PcmWaveform]で集計する(曲は最大数分程度なのでメモリ上で問題ない)。
 */
object AudioWaveformDecoder {

    fun extract(context: Context, uri: Uri, bucketCount: Int = 120): FloatArray {
        val extractor = MediaExtractor()
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

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(audioFormat, null, null, 0)
            decoder.start()

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
                    decoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }

            decoder.stop()
            decoder.release()

            val totalBytes = pcmChunks.sumOf { it.size }
            val pcm = ByteArray(totalBytes)
            var offset = 0
            for (chunk in pcmChunks) {
                System.arraycopy(chunk, 0, pcm, offset, chunk.size)
                offset += chunk.size
            }
            PcmWaveform.peakBuckets(pcm, offset = 0, bucketCount = bucketCount)
        } catch (e: Exception) {
            Log.e(TAG, "波形の抽出に失敗しました: $uri", e)
            FloatArray(bucketCount)
        } finally {
            extractor.release()
        }
    }
}
