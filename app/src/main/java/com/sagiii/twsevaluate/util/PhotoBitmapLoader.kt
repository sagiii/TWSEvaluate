package com.sagiii.twsevaluate.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface

/**
 * 商品写真の読み込み。CameraXが書き出すJPEGはEXIFの回転タグで向きを示すだけで
 * ピクセル自体は回転していないため([BitmapFactory]はEXIFを無視する)、ここで
 * 向きを補正してから返す。[reqSize]を指定すると縮小読み込みしてメモリを節約する
 * (一覧のサムネイルなど、フル解像度が不要な場面向け)。
 */
object PhotoBitmapLoader {

    fun load(path: String, reqSize: Int = Int.MAX_VALUE): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOptions)
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

        var sampleSize = 1
        while (boundsOptions.outWidth / (sampleSize * 2) >= reqSize &&
            boundsOptions.outHeight / (sampleSize * 2) >= reqSize
        ) {
            sampleSize *= 2
        }

        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            ?: return null

        val rotationDegrees = runCatching {
            when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        if (rotationDegrees == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }
}
