package com.sagiii.twsevaluate.audio

import java.io.File
import kotlin.math.abs

/** 会議録音(自前で書き出したPCM16/monoのWAV)から、シークバー表示用の概形波形を取り出す。 */
object WaveformExtractor {

    private const val WAV_HEADER_SIZE = 44

    fun extract(file: File, bucketCount: Int = 120): FloatArray {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return FloatArray(bucketCount)
        val sampleAreaSize = bytes.size - WAV_HEADER_SIZE
        if (sampleAreaSize <= 0) return FloatArray(bucketCount)

        val totalSamples = sampleAreaSize / 2
        val bucketSamples = (totalSamples / bucketCount).coerceAtLeast(1)
        val peaks = FloatArray(bucketCount)
        var overallPeak = 1

        for (bucket in 0 until bucketCount) {
            val startSample = bucket * bucketSamples
            val startByte = WAV_HEADER_SIZE + startSample * 2
            val endByte = minOf(startByte + bucketSamples * 2, bytes.size)
            var peak = 0
            var i = startByte
            while (i + 1 < endByte) {
                val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
                val magnitude = abs(sample.toInt())
                if (magnitude > peak) peak = magnitude
                i += 2
            }
            peaks[bucket] = peak.toFloat()
            if (peak > overallPeak) overallPeak = peak
        }

        return FloatArray(bucketCount) { (peaks[it] / overallPeak).coerceIn(0f, 1f) }
    }
}
