package com.scanpdf.app.doc

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 透视校正：把一个任意四边形区域"拉平"成矩形。
 *
 * 用 Heckbert 的单位正方形 -> 四边形变换，得到目标像素到源像素的映射，
 * 再逐点双线性采样（逆向映射，无空洞）。
 */
object PerspectiveWarp {

    fun warp(src: Bitmap, quad: Quad, maxDim: Int = 0): Bitmap {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        if (sw < 8f || sh < 8f) return src

        val x0 = quad.tl.x * sw
        val y0 = quad.tl.y * sh
        val x1 = quad.tr.x * sw
        val y1 = quad.tr.y * sh
        val x2 = quad.br.x * sw
        val y2 = quad.br.y * sh
        val x3 = quad.bl.x * sw
        val y3 = quad.bl.y * sh

        var outW = max(hypot(x1 - x0, y1 - y0), hypot(x2 - x3, y2 - y3)).roundToInt()
        var outH = max(hypot(x3 - x0, y3 - y0), hypot(x2 - x1, y2 - y1)).roundToInt()
        if (outW < 16 || outH < 16) return src

        if (maxDim > 0) {
            val longSide = max(outW, outH)
            if (longSide > maxDim) {
                val s = maxDim.toFloat() / longSide
                outW = (outW * s).roundToInt().coerceAtLeast(16)
                outH = (outH * s).roundToInt().coerceAtLeast(16)
            }
        }

        // 单位正方形 (0,0)(1,0)(1,1)(0,1) -> 源图四角
        val h = squareToQuad(x0, y0, x1, y1, x2, y2, x3, y3)
        val a = h[0]
        val b = h[1]
        val c = h[2]
        val d = h[3]
        val e = h[4]
        val f = h[5]
        val g = h[6]
        val hh = h[7]

        val srcPx = IntArray(src.width * src.height)
        src.getPixels(srcPx, 0, src.width, 0, 0, src.width, src.height)
        val dstPx = IntArray(outW * outH)

        val srcW = src.width
        val srcH = src.height
        val maxX = srcW - 1.001f
        val maxY = srcH - 1.001f
        val invW = 1f / outW
        val invH = 1f / outH

        var di = 0
        for (j in 0 until outH) {
            val v = j * invH
            for (i in 0 until outW) {
                val u = i * invW
                val den = g * u + hh * v + 1f
                if (abs(den) < 1e-6f) {
                    dstPx[di++] = 0xFFFFFFFF.toInt()
                    continue
                }
                var sx = (a * u + b * v + c) / den
                var sy = (d * u + e * v + f) / den
                sx = sx.coerceIn(0f, maxX)
                sy = sy.coerceIn(0f, maxY)

                val ix = sx.toInt()
                val iy = sy.toInt()
                val fx = sx - ix
                val fy = sy - iy

                val i00 = srcPx[iy * srcW + ix]
                val i10 = srcPx[iy * srcW + min(ix + 1, srcW - 1)]
                val i01 = srcPx[min(iy + 1, srcH - 1) * srcW + ix]
                val i11 = srcPx[min(iy + 1, srcH - 1) * srcW + min(ix + 1, srcW - 1)]

                val w00 = (1 - fx) * (1 - fy)
                val w10 = fx * (1 - fy)
                val w01 = (1 - fx) * fy
                val w11 = fx * fy

                val r = (((i00 ushr 16) and 0xFF) * w00 + ((i10 ushr 16) and 0xFF) * w10 +
                        ((i01 ushr 16) and 0xFF) * w01 + ((i11 ushr 16) and 0xFF) * w11).toInt()
                val gg = (((i00 ushr 8) and 0xFF) * w00 + ((i10 ushr 8) and 0xFF) * w10 +
                        ((i01 ushr 8) and 0xFF) * w01 + ((i11 ushr 8) and 0xFF) * w11).toInt()
                val bb = ((i00 and 0xFF) * w00 + (i10 and 0xFF) * w10 +
                        (i01 and 0xFF) * w01 + (i11 and 0xFF) * w11).toInt()

                dstPx[di++] = (0xFF shl 24) or
                        (r.coerceIn(0, 255) shl 16) or
                        (gg.coerceIn(0, 255) shl 8) or
                        bb.coerceIn(0, 255)
            }
        }

        return Bitmap.createBitmap(dstPx, outW, outH, Bitmap.Config.ARGB_8888)
    }

    /** 返回 [a,b,c,d,e,f,g,h]，满足 x=(a*u+b*v+c)/(g*u+h*v+1), y=(d*u+e*v+f)/(g*u+h*v+1) */
    private fun squareToQuad(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float
    ): FloatArray {
        val dx1 = x1 - x2
        val dx2 = x3 - x2
        val dx3 = x0 - x1 + x2 - x3
        val dy1 = y1 - y2
        val dy2 = y3 - y2
        val dy3 = y0 - y1 + y2 - y3

        if (dx3 == 0f && dy3 == 0f) {
            return floatArrayOf(
                x1 - x0, x2 - x1, x0,
                y1 - y0, y2 - y1, y0,
                0f, 0f
            )
        }
        val den = dx1 * dy2 - dx2 * dy1
        if (abs(den) < 1e-7f) {
            return floatArrayOf(
                x1 - x0, x2 - x1, x0,
                y1 - y0, y2 - y1, y0,
                0f, 0f
            )
        }
        val g = (dx3 * dy2 - dx2 * dy3) / den
        val h = (dx1 * dy3 - dx3 * dy1) / den
        return floatArrayOf(
            x1 - x0 + g * x1,
            x3 - x0 + h * x3,
            x0,
            y1 - y0 + g * y1,
            y3 - y0 + h * y3,
            y0,
            g,
            h
        )
    }
}
