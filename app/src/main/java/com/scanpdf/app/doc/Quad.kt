package com.scanpdf.app.doc

/** 归一化坐标点（0..1，相对摆正后的原图） */
data class Pt(val x: Float, val y: Float)

/** 纸张四角，顺序固定：左上、右上、右下、左下 */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {

    fun points(): List<Pt> = listOf(tl, tr, br, bl)

    /** 校验：凸四边形、面积合理、不越界太多 */
    fun isSane(): Boolean {
        val p = points()
        val area = shoelace(p)
        if (area < 0.12f || area > 0.995f) return false
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

    private fun shoelace(p: List<Pt>): Float {
        var s = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            s += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(s) / 2f
    }
}
