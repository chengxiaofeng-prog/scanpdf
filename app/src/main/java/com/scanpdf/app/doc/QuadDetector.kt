package com.scanpdf.app.doc

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 文档边缘检测：灰度 -> 高斯近似模糊 -> Sobel -> Otsu 二值 -> Hough 直线 ->
 * 取"最左/最右/最上/最下"四条主直线求交点，得到纸张四角。
 *
 * 纯 Kotlin，无 OpenCV。为了速度先缩到长边 640，Hough 角度分辨率 1°。
 * 检测失败（无边缘、四边形不合理）返回 null，调用方应回退到整页。
 */
object QuadDetector {

    private const val MAX_SIDE = 640
    private const val THETA_STEPS = 180
    private const val MIN_AREA_RATIO = 0.15f

    /** Hough 峰值：法线角度下标 t、rho 下标 r、票数 v */
    private class Peak(val t: Int, val r: Int, val v: Int)

    fun detect(src: Bitmap): Quad? {
        if (src.width < 48 || src.height < 48) return null

        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height))
        val w = (src.width * scale).toInt().coerceAtLeast(48)
        val h = (src.height * scale).toInt().coerceAtLeast(48)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src

        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()

        val gray = IntArray(w * h) {
            val c = px[it]
            (((c ushr 16) and 0xFF) * 77 + ((c ushr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        boxBlur3(gray, w, h)

        val mag = IntArray(w * h)
        var maxMag = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = (gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1]) -
                        (gray[i - w - 1] + 2 * gray[i - 1] + gray[i + w - 1])
                val gy = (gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]) -
                        (gray[i - w - 1] + 2 * gray[i - w] + gray[i - w + 1])
                val m = abs(gx) + abs(gy)
                mag[i] = m
                if (m > maxMag) maxMag = m
            }
        }
        if (maxMag < 60) return null

        val otsu = otsuThreshold(mag, maxMag)
        val edgeThr = maxOf(otsu, (maxMag * 0.28f).toInt())

        val edgeIdx = ArrayList<Int>(4096)
        for (i in mag.indices) if (mag[i] >= edgeThr) edgeIdx.add(i)
        if (edgeIdx.size < 150) return null

        // Hough：rho 分辨率 1px，theta 分辨率 1°
        val cosT = FloatArray(THETA_STEPS)
        val sinT = FloatArray(THETA_STEPS)
        for (t in 0 until THETA_STEPS) {
            val a = Math.toRadians(t.toDouble())
            cosT[t] = cos(a).toFloat()
            sinT[t] = sin(a).toFloat()
        }
        val rhoMax = ceil(hypot(w.toDouble(), h.toDouble())).toInt()
        val rhoCount = rhoMax * 2 + 1
        val acc = IntArray(THETA_STEPS * rhoCount)

        val step = if (edgeIdx.size > 50000) edgeIdx.size / 50000 + 1 else 1
        var k = 0
        while (k < edgeIdx.size) {
            val idx = edgeIdx[k]
            val x = idx % w
            val y = idx / w
            for (t in 0 until THETA_STEPS) {
                val r = (x * cosT[t] + y * sinT[t]).toInt() + rhoMax
                if (r in 0 until rhoCount) acc[t * rhoCount + r]++
            }
            k += step
        }

        var maxVote = 0
        for (v in acc) if (v > maxVote) maxVote = v
        if (maxVote < 24) return null

        val minVote = maxOf(18, (maxVote * 0.32f).toInt())
        val raw = ArrayList<Peak>(256)
        for (t in 0 until THETA_STEPS) {
            val base = t * rhoCount
            for (r in 0 until rhoCount) {
                val v = acc[base + r]
                if (v >= minVote) raw.add(Peak(t, r, v))
            }
        }
        if (raw.size < 4) return null
        raw.sortByDescending { it.v }

        // 非极大值抑制
        val peaks = ArrayList<Peak>(24)
        for (p in raw) {
            if (peaks.size >= 24) break
            var ok = true
            for (q in peaks) {
                var dt = abs(p.t - q.t)
                if (dt > 90) dt = THETA_STEPS - dt
                if (dt <= 4 && abs(p.r - q.r) <= 8) {
                    ok = false
                    break
                }
            }
            if (ok) peaks.add(p)
        }

        val vertical = peaks.filter { abs(cosT[it.t]) > 0.75f }
        val horizontal = peaks.filter { abs(sinT[it.t]) > 0.75f }
        if (vertical.size < 2 || horizontal.size < 2) return null

        val midX = w / 2f
        val midY = h / 2f

        fun xAtY(p: Peak, y: Float) = (p.r - rhoMax - y * sinT[p.t]) / cosT[p.t]
        fun yAtX(p: Peak, x: Float) = (p.r - rhoMax - x * cosT[p.t]) / sinT[p.t]

        val left = vertical.minByOrNull { xAtY(it, midY) } ?: return null
        val right = vertical.maxByOrNull { xAtY(it, midY) } ?: return null
        val top = horizontal.minByOrNull { yAtX(it, midX) } ?: return null
        val bottom = horizontal.maxByOrNull { yAtX(it, midX) } ?: return null

        if (xAtY(right, midY) - xAtY(left, midY) < w * 0.35f) return null
        if (yAtX(bottom, midX) - yAtX(top, midX) < h * 0.35f) return null

        fun intersect(a: Peak, b: Peak): Pt? {
            val c1 = cosT[a.t]
            val s1 = sinT[a.t]
            val r1 = (a.r - rhoMax).toFloat()
            val c2 = cosT[b.t]
            val s2 = sinT[b.t]
            val r2 = (b.r - rhoMax).toFloat()
            val det = c1 * s2 - s1 * c2
            if (abs(det) < 1e-4f) return null
            return Pt((r1 * s2 - s1 * r2) / det, (c1 * r2 - r1 * c2) / det)
        }

        val tl = intersect(left, top) ?: return null
        val tr = intersect(right, top) ?: return null
        val br = intersect(right, bottom) ?: return null
        val bl = intersect(left, bottom) ?: return null

        val mx = w * 0.15f
        val my = h * 0.15f
        val all = listOf(tl, tr, br, bl)
        for (p in all) {
            if (p.x < -mx || p.x > w + mx || p.y < -my || p.y > h + my) return null
        }

        val quad = Quad(
            tl = Pt((tl.x / w).coerceIn(0f, 1f), (tl.y / h).coerceIn(0f, 1f)),
            tr = Pt((tr.x / w).coerceIn(0f, 1f), (tr.y / h).coerceIn(0f, 1f)),
            br = Pt((br.x / w).coerceIn(0f, 1f), (br.y / h).coerceIn(0f, 1f)),
            bl = Pt((bl.x / w).coerceIn(0f, 1f), (bl.y / h).coerceIn(0f, 1f))
        )
        if (quad.isSane().not()) return null
        val area = areaOf(quad)
        if (area < MIN_AREA_RATIO) return null

        // 四角全部贴着图像边界 = 检测到的是整幅画面（或无有效纸张），不做裁切
        val huggingBorder = quad.points().all {
            (it.x < 0.02f || it.x > 0.98f) && (it.y < 0.02f || it.y > 0.98f)
        }
        if (huggingBorder && area > 0.95f) return null

        return quad
    }

    private fun areaOf(q: Quad): Float {
        var s = 0f
        val p = q.points()
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    /** 3x3 盒式模糊，用于压噪 */
    private fun boxBlur3(src: IntArray, w: Int, h: Int) {
        val copy = src.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var s = 0
                for (dy in -1..1) {
                    val row = (y + dy) * w
                    s += copy[row + x - 1] + copy[row + x] + copy[row + x + 1]
                }
                src[y * w + x] = s / 9
            }
        }
    }

    private fun otsuThreshold(values: IntArray, maxV: Int): Int {
        val hist = IntArray(1024)
        for (v in values) hist[minOf(v, 1023)]++
        val total = values.size
        var sum = 0L
        for (i in 0..1023) sum += i.toLong() * hist[i]

        var sumB = 0L
        var wB = 0
        var best = -1.0
        var thr = 0
        for (i in 0..1023) {
            wB += hist[i]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += i.toLong() * hist[i]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                thr = i
            }
        }
        // 阈值不能太离谱
        val lo = (maxV * 0.08f).toInt()
        return thr.coerceAtLeast(lo)
    }
}
