package com.example.personal_studio.data.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

object BitmapIo {
    const val DEFAULT_TARGET_LONG_SIDE = 2000

    /** Decode [file] with sample size tuned so the long side is ~[targetLong]. */
    fun decodeDownscaled(file: File, targetLong: Int = DEFAULT_TARGET_LONG_SIDE): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        val sample = (longSide.toFloat() / targetLong).coerceAtLeast(1f).roundToInt()
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: error("failed to decode ${file.absolutePath}")
    }

    /**
     * [src] turned clockwise by [quarters] × 90°. Quarter turns move pixels without resampling;
     * zero (or a multiple of four) returns [src] itself.
     */
    fun rotateQuarters(src: Bitmap, quarters: Int): Bitmap {
        val q = Math.floorMod(quarters, 4)
        if (q == 0) return src
        val m = Matrix().apply { postRotate(q * 90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun writeJpeg(bitmap: Bitmap, dest: File, quality: Int = 90): File {
        FileOutputStream(dest).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return dest
    }
}
