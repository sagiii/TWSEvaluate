package com.sagiii.twsevaluate.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "ConferenceVideoMuxer"
private const val SAMPLE_RATE = 16000
private const val CHANNEL_COUNT = 1
private const val BIT_RATE = 64_000
private const val WAV_HEADER_SIZE = 44

/** セッション内の1つの会議録音区間(絶対経過時間ベース)。 */
data class ConferenceClip(val path: String, val startMs: Long, val endMs: Long)

/**
 * Phase 1b: 動画区間の音声トラックを、その時間帯に重なる会議録音(WAV)に
 * 差し替える。会議録音が無い(=音楽モードのみだった)時間帯は無音になる。
 * 動画区間に会議録音が1つも重ならない場合は何もしない(呼び出し元が元の
 * ファイルをそのまま使う)。
 *
 * 動画トラック自体は再エンコードせず、MediaExtractorでそのままコピーする。
 * 音声はゼロから合成したPCMをAACに再エンコードしてから多重化する。
 */
object ConferenceVideoMuxer {

    fun mux(
        sourceVideo: File,
        outputVideo: File,
        segmentStartMs: Long,
        segmentEndMs: Long,
        conferenceClips: List<ConferenceClip>,
    ): Boolean {
        val overlapping = conferenceClips.filter { it.endMs > segmentStartMs && it.startMs < segmentEndMs }
        if (overlapping.isEmpty()) return false

        return runCatching {
            val pcm = buildSilencedPcm(segmentStartMs, segmentEndMs, overlapping)
            val (audioFormat, encodedAudio) = encodeToAac(pcm)
            muxVideoAndAudio(sourceVideo, outputVideo, audioFormat, encodedAudio)
            true
        }.onFailure {
            Log.e(TAG, "動画への会議音声の焼き込みに失敗しました: ${sourceVideo.name}", it)
        }.getOrDefault(false)
    }

    private fun buildSilencedPcm(
        segmentStartMs: Long,
        segmentEndMs: Long,
        clips: List<ConferenceClip>,
    ): ByteArray {
        val durationMs = (segmentEndMs - segmentStartMs).coerceAtLeast(0)
        val totalSamples = (durationMs * SAMPLE_RATE / 1000).toInt()
        val buffer = ByteArray(totalSamples * 2) // 16bit mono、初期値0 = 無音

        for (clip in clips) {
            val clipBytes = runCatching { File(clip.path).readBytes() }.getOrNull() ?: continue
            if (clipBytes.size <= WAV_HEADER_SIZE) continue
            val pcm = clipBytes.copyOfRange(WAV_HEADER_SIZE, clipBytes.size)

            val destOffsetMs = clip.startMs - segmentStartMs
            var destOffsetBytes = ((destOffsetMs * SAMPLE_RATE / 1000).toInt()) * 2
            var srcOffsetBytes = 0
            if (destOffsetBytes < 0) {
                srcOffsetBytes = -destOffsetBytes
                destOffsetBytes = 0
            }
            val copyLength = minOf(pcm.size - srcOffsetBytes, buffer.size - destOffsetBytes)
            if (copyLength > 0) {
                System.arraycopy(pcm, srcOffsetBytes, buffer, destOffsetBytes, copyLength)
            }
        }
        return buffer
    }

    private fun encodeToAac(pcm: ByteArray): Pair<MediaFormat, List<Pair<ByteArray, MediaCodec.BufferInfo>>> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val encodedChunks = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()
        var outputFormat = format
        var presentationTimeUs = 0L
        var offset = 0
        var inputDone = false
        var outputDone = false
        val timeoutUs = 10_000L
        val bufferInfo = MediaCodec.BufferInfo()
        val bytesPerSample = 2 * CHANNEL_COUNT

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = encoder.dequeueInputBuffer(timeoutUs)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    val remaining = pcm.size - offset
                    val chunkSize = minOf(inputBuffer.capacity(), remaining)
                    if (chunkSize <= 0) {
                        encoder.queueInputBuffer(inputIndex, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        inputBuffer.put(pcm, offset, chunkSize)
                        encoder.queueInputBuffer(inputIndex, 0, chunkSize, presentationTimeUs, 0)
                        presentationTimeUs += chunkSize.toLong() * 1_000_000L / (SAMPLE_RATE * bytesPerSample)
                        offset += chunkSize
                    }
                }
            }

            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = encoder.outputFormat
                }
                outputIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputIndex)!!
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (bufferInfo.size > 0 && !isConfig) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        val info = MediaCodec.BufferInfo().apply {
                            set(0, chunk.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                        }
                        encodedChunks.add(chunk to info)
                    }
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if (isEos) outputDone = true
                }
            }
        }

        encoder.stop()
        encoder.release()
        return outputFormat to encodedChunks
    }

    private fun muxVideoAndAudio(
        sourceVideo: File,
        outputVideo: File,
        audioFormat: MediaFormat,
        encodedAudio: List<Pair<ByteArray, MediaCodec.BufferInfo>>,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(sourceVideo.absolutePath)

        var videoTrackIndex = -1
        var videoFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                videoFormat = format
                break
            }
        }
        val resolvedVideoFormat = requireNotNull(videoFormat) { "動画トラックが見つかりません: ${sourceVideo.name}" }
        extractor.selectTrack(videoTrackIndex)

        // MediaMuxer#addTrack()はMediaFormatのKEY_ROTATIONを自動的には引き継がないため、
        // 明示的にsetOrientationHint()で回転情報を移し替える(これを忘れると縦で撮った
        // 動画が横向きで保存されてしまう)。
        val rotationDegrees = runCatching {
            resolvedVideoFormat.getInteger(MediaFormat.KEY_ROTATION)
        }.getOrDefault(0)

        val muxer = MediaMuxer(outputVideo.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (rotationDegrees != 0) muxer.setOrientationHint(rotationDegrees)
        val muxerVideoTrack = muxer.addTrack(resolvedVideoFormat)
        val muxerAudioTrack = muxer.addTrack(audioFormat)
        muxer.start()

        val maxInputSize = resolvedVideoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2_000_000)
        val buffer = ByteBuffer.allocate(maxInputSize)
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = extractor.sampleTime
            bufferInfo.flags = extractor.sampleFlags
            muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
            extractor.advance()
        }
        extractor.release()

        for ((chunk, info) in encodedAudio) {
            muxer.writeSampleData(muxerAudioTrack, ByteBuffer.wrap(chunk), info)
        }

        muxer.stop()
        muxer.release()
    }
}
