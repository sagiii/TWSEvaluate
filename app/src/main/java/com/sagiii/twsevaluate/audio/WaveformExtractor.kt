package com.sagiii.twsevaluate.audio

import java.io.File

/** 会議録音(自前で書き出したPCM16/monoのWAV)から、シークバー表示用の概形波形を取り出す。 */
object WaveformExtractor {

    private const val WAV_HEADER_SIZE = 44

    fun extract(file: File, bucketCount: Int = 120): FloatArray {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return FloatArray(bucketCount)
        return PcmWaveform.peakBuckets(bytes, offset = WAV_HEADER_SIZE, bucketCount = bucketCount)
    }
}
