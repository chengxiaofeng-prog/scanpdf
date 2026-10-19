package com.scanpdf.app.img

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * 三个可调参数。全部为纯数据，可自由 copy / 序列化。
 *
 * 处理顺序固定：去底灰 -> 对比度 -> 阈值。
 * 顺序颠倒会把纸张底灰"焊死"在图里，所以不允许调换。
 */
data class Adjustments(
    /** 输出灰度；默认 false 保留原色，需要纯黑白文档时再手动切 */
    val gray: Boolean = false,
    /** 去底灰强度 0..100 */
    val deGray: Int = 50,
    /** 对比度 0..100 */
    val contrast: Int = 20,
    /** 是否启用阈值（二值化，会把彩色压成纯黑白） */
    val thresholdOn: Boolean = false,
    /** 阈值 0..255，越小越黑 */
    val threshold: Int = 160
) {
    val isIdentity: Boolean
        get() = !gray && deGray == 0 && contrast == 0 && !thresholdOn
}

object ImageProcessor {

    /**
     * 缩放到长边不超过 [maxDim] 后处理。用于编辑界面实时预览（快）。
     * 返回新的 Bitmap（除非 isIdentity 时直接返回原图）。
     */
    fun processScaled(src: Bitmap, adj: Adjustments, maxDim: Int): Bitmap {
        if (adj.isIdentity) return src
        val longSide = max(src.width, src.height)
        var work = src
        if (maxDim > 0 && longSide > maxDim) {
            val s = maxDim.toFloat() / longSide
            val w = (src.width * s).toInt().coerceAtLeast(1)
            val h = (src.height * s).toInt().coerceAtLeast(1)
            work = Bitmap.createScaledBitmap(src, w, h, true)
        }
        val out = process(work, adj)
        if (work !== src && work !== out) work.recycle()
        return out
    }

    /**
     * 主管线：去底灰（背景亮度归一化）-> 对比度（LUT）-> 阈值（二值化）。
     */
    fun process(src: Bitmap, adj: Adjustments): Bitmap {
        if (adj.isIdentity) return src

        val w = src.width
        val h = src.height
        val n = w * h
        if (n <= 0) return src

        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // 亮度通道（BT.601 近似，定点整数运算）
        val lum = IntArray(n)
        for (i in 0 until n) {
            val c = px[i]
            lum[i] = (((c ushr 16) and 0xFF) * 77 +
                    ((c ushr 8) and 0xFF) * 150 +
                    (c and 0xFF) * 29) shr 8
        }

        // 1) 去底灰：用大半径均值估计纸张背景亮度，再按 255/背景 做平场校正
        var ratio: FloatArray? = null
        if (adj.deGray > 0) {
            val radius = (min(w, h) / 18).coerceIn(6, 80)
            val mean = boxBlur(lum, w, h, radius)
            val strength = adj.deGray / 100f
            ratio = FloatArray(n)
            for (i in 0 until n) {
                val bg = if (mean[i] < 10) 10 else mean[i]
                val target = 255f / bg
                ratio[i] = 1f + (target - 1f) * strength
            }
        }

        // 2) 对比度：绕中灰 128 线性拉伸，斜率 k = 1 + 2 * 强度
        val k = 1f + adj.contrast / 100f * 2f
        val lut = IntArray(256)
        for (i in 0 until 256) {
            lut[i] = (((i - 128) * k) + 128f).toInt().coerceIn(0, 255)
        }

        // 3) 阈值
        val binOn = adj.thresholdOn
        val t = adj.threshold.coerceIn(0, 255)
        val grayOut = adj.gray

        // 直接写回 px（读 c 在前、写在后，同下标互不干扰），省掉一份 w*h 的临时数组。
        // 导出时 2600 长边约 500 万像素，这一份省下来就是 20MB 的峰值。
        for (i in 0 until n) {
            val f = ratio?.get(i) ?: 1f
            if (grayOut) {
                var v = (lum[i] * f).toInt()
                if (v > 255) v = 255
                v = lut[v]
                if (binOn) v = if (v >= t) 255 else 0
                px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            } else {
                val c = px[i]
                var r = (((c ushr 16) and 0xFF) * f).toInt().coerceAtMost(255)
                var g = (((c ushr 8) and 0xFF) * f).toInt().coerceAtMost(255)
                var b = ((c and 0xFF) * f).toInt().coerceAtMost(255)
                r = lut[r]
                g = lut[g]
                b = lut[b]
                if (binOn) {
                    val l = (r * 77 + g * 150 + b * 29) shr 8
                    val v = if (l >= t) 255 else 0
                    r = v
                    g = v
                    b = v
                }
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 可分离盒式模糊（滑动窗口求和），O(n)，用于估计局部背景亮度。 */
    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        val win = 2 * r + 1

        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (x in -r..r) sum += src[row + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / win
                sum += src[row + (x + r + 1).coerceIn(0, w - 1)] -
                        src[row + (x - r).coerceIn(0, w - 1)]
            }
        }

        for (x in 0 until w) {
            var sum = 0
            for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = sum / win
                sum += tmp[(y + r + 1).coerceIn(0, h - 1) * w + x] -
                        tmp[(y - r).coerceIn(0, h - 1) * w + x]
            }
        }
        return out
    }
}
