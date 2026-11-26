package com.example.personal_studio.data.deep

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

internal data class ImageSize(val width: Int, val height: Int, val sample: Int)

internal fun jpegSize(width: Int, height: Int): ImageSize {
    require(width > 0 && height > 0)
    val longest = maxOf(width, height)
    val scale = minOf(1.0, 2048.0 / longest)
    val sample = Integer.highestOneBit(maxOf(1, longest / 2048))
    return ImageSize(maxOf(1, (width * scale).roundToInt()), maxOf(1, (height * scale).roundToInt()), sample)
}

internal class ImageFailure(path: String) : IOException("无法读取图片“${File(path).name}”，请重新选择图片")

internal fun encodeImage(path: String): String {
    val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, info)
    if (info.outWidth <= 0 || info.outHeight <= 0) throw ImageFailure(path)
    val size = jpegSize(info.outWidth, info.outHeight)
    val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = size.sample })
        ?: throw ImageFailure(path)
    try {
        val scaled = Bitmap.createScaledBitmap(bitmap, size.width, size.height, true)
        try {
            val bytes = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, 85, bytes)) throw ImageFailure(path)
            return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    } finally {
        bitmap.recycle()
    }
}
