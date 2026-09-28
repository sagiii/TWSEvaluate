package com.sagiii.twsevaluate.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM 16bitのバイト列をWAV(RIFF)ファイルとして書き出す。ヘッダーのサイズはclose()時に確定させる。 */
class WavFileWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16,
) {
    private val out = BufferedOutputStream(FileOutputStream(file))
    private var dataLength = 0L

    init {
        out.write(ByteArray(44)) // 後でpatchHeader()が上書きする仮ヘッダー
    }

    fun writePcm(bytes: ByteArray, length: Int = bytes.size) {
        out.write(bytes, 0, length)
        dataLength += length
    }

    fun close() {
        out.flush()
        out.close()
        patchHeader()
    }

    private fun patchHeader() {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((36 + dataLength).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray())
            putInt(dataLength.toInt())
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header.array())
        }
    }
}
