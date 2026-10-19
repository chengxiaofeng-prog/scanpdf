package com.scanpdf.app.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

/**
 * 图片解码：按目标长边降采样 + 按 EXIF 方向摆正。
 * 相机拍出来的 JPEG 常常是"横向存储 + 旋转标记"，不摆正会导致 PDF 里图片躺倒。
 */
object ImageIO {

    fun decodeOriented(file: File, maxDim: Int = 0): Bitmap? {
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        if (maxDim > 0) {
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) {
                sample *= 2
            }
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val degrees = readRotation(file)
        if (degrees == 0) return raw

        return try {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (rotated !== raw) raw.recycle()
            rotated
        } catch (e: Exception) {
            raw
        }
    }

    private fun readRotation(file: File): Int = try {
        when (ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }
}
