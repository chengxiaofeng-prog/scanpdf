package com.scanpdf.app.doc

import android.graphics.Bitmap
import com.scanpdf.app.img.Adjustments
import com.scanpdf.app.img.ImageProcessor

/**
 * 单页渲染管线：透视校正 -> 三大参数。
 * 预览与导出共用同一套逻辑，保证"所见即所得"。
 */
object PageRenderer {

    fun render(src: Bitmap, adj: Adjustments, crop: Boolean, quad: Quad?, maxDim: Int): Bitmap {
        var current = src
        var warped: Bitmap? = null

        if (crop && quad != null) {
            val w = PerspectiveWarp.warp(current, quad, maxDim)
            if (w !== current) {
                warped = w
                current = w
            }
        }

        val out = ImageProcessor.processScaled(current, adj, maxDim)
        if (out !== current) {
            warped?.recycle()
        }
        return out
    }
}
