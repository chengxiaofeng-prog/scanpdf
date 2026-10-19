package com.scanpdf.app.doc

import kotlin.math.abs
import kotlin.math.atan2

/** 归一化坐标点（0..1，相对摆正后的原图） */
data class Pt(val x: Float, val y: Float)

/** 纸张四角，顺序固定：左上、右上、右下、左下 */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {

    fun points(): List<Pt> = listOf(tl, tr, br, bl)

    /** 拖动第 index 个角（0=tl,1=tr,2=br,3=bl），位移为归一化增量，自动夹在画面内 */
    fun moved(index: Int, dx: Float, dy: Float): Quad {
        fun f(p: Pt) = Pt((p.x + dx).coerceIn(0f, 1f), (p.y + dy).coerceIn(0f, 1f))
        return when (index) {
            0 -> copy(tl = f(tl))
            1 -> copy(tr = f(tr))
            2 -> copy(br = f(br))
            else -> copy(bl = f(bl))
        }
    }

    /**
     * 手动拖拽可能把角拖成"蝴蝶结"或顺序错乱。
     * 这里绕质心按极角排序，再从最靠左上的一点开始，重新标定为 tl/tr/br/bl。
     */
    fun normalized(): Quad {
        val p = points()
        val cx = p.sumOf { it.x.toDouble() } / p.size
        val cy = p.sumOf { it.y.toDouble() } / p.size
        val sorted = p.sortedBy { atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
        val start = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y } ?: 0
        val r = List(4) { sorted[(start + it) % 4] }
        return Quad(r[0], r[1], r[2], r[3])
    }

    /** 校验：凸四边形、面积合理、不越界太多 */
    fun isSane(): Boolean {
        val p = points()
        val area = shoelace(p)
        if (area < 0.02f || area > 0.995f) return false
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross > 1e-6f) {
                if (sign < 0) return false
                sign = 1
            } else if (cross < -1e-6f) {
                if (sign > 0) return false
                sign = -1
            }
        }
        return sign != 0
    }

    /** 面积占比 0..1 */
    fun areaRatio(): Float = shoelace(points())

    private fun shoelace(p: List<Pt>): Float {
        var s = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    companion object {
        /** 整页：四角即画面四角 */
        val FULL = Quad(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))
    }
}
