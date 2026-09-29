package com.sagiii.twsevaluate.audio

import kotlin.math.abs

/** PCM16のバイト列から、シークバー表示用に区間ごとの最大振幅(0f..1f)を計算する。 */
object PcmWaveform {

    fun peakBuckets(pcm: ByteArray, offset: Int = 0, bucketCount: Int = 120): FloatArray {
        val sampleAreaSize = pcm.size - offset
        if (sampleAreaSize <= 0) return FloatArray(bucketCount)

        val totalSamples = sampleAreaSize / 2
        val bucketSamples = (totalSamples / bucketCount).coerceAtLeast(1)
        val peaks = FloatArray(bucketCount)
        var overallPeak = 1

        for (bucket in 0 until bucketCount) {
            val startSample = bucket * bucketSamples
            val startByte = offset + startSample * 2
            val endByte = minOf(startByte + bucketSamples * 2, pcm.size)
            var peak = 0
            var i = startByte
            while (i + 1 < endByte) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
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
