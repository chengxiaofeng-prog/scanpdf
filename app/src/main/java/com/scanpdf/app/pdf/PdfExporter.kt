package com.scanpdf.app.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.scanpdf.app.data.PageItem
import com.scanpdf.app.doc.PageRenderer
import com.scanpdf.app.io.ImageIO
import com.scanpdf.app.ocr.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * 多页合成一份 PDF。
 * 页面尺寸固定 A4（1240x1754 ≈ 150dpi），图片等比缩放并居中留白。
 * - 导出时才用原分辨率重跑「透视校正 + 三参数」管线，保证清晰度
 * - 已做 OCR 的页会叠加一层近乎透明的文字，使 PDF 可搜索、可复制
 */
object PdfExporter {

    private const val PAGE_W = 1240
    private const val PAGE_H = 1754
    private const val MARGIN = 18f
    private const val MAX_DECODE = 2600   // 长边上限，防止低内存机 OOM

    suspend fun export(
        ctx: Context,
        pages: List<PageItem>,
        onProgress: (done: Int, total: Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext null

        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "pdf")
        if (!dir.exists()) dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
        val outFile = File(dir, "扫描件_$stamp.pdf")

        val doc = PdfDocument()
        try {
            pages.forEachIndexed { index, item ->
                val src = ImageIO.decodeOriented(item.file, MAX_DECODE)
                if (src != null) {
                    val bmp = PageRenderer.render(
                        src = src,
                        adj = item.adjustments,
                        crop = item.cropEnabled,
                        quad = item.quad,
                        maxDim = MAX_DECODE
                    )
                    if (bmp !== src) src.recycle()

                    val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create()
                    val page = doc.startPage(info)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)

                    val availW = PAGE_W - MARGIN * 2
                    val availH = PAGE_H - MARGIN * 2
                    val scale = min(availW / bmp.width.toFloat(), availH / bmp.height.toFloat())
                    val dw = bmp.width * scale
                    val dh = bmp.height * scale
                    val left = (PAGE_W - dw) / 2f
                    val top = (PAGE_H - dh) / 2f

                    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                    canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), paint)

                    item.ocr?.let { ocr ->
                        if (ocr.lines.isNotEmpty()) {
                            drawInvisibleText(canvas, ocr, left, top, dw, dh)
                        }
                    }

                    doc.finishPage(page)
                    bmp.recycle()
                }
                withContext(Dispatchers.Main) { onProgress(index + 1, pages.size) }
            }

            FileOutputStream(outFile).use { doc.writeTo(it) }
            outFile
        } catch (e: Exception) {
            runCatching { outFile.delete() }
            throw e
        } finally {
            doc.close()
        }
    }

    /**
     * 在图片上方绘制几乎不可见的文字层（alpha=3）。
     * 视觉上无感，但 PDF 阅读器能选中/搜索到这些文字。
     */
    private fun drawInvisibleText(
        canvas: Canvas,
        ocr: OcrResult,
        left: Float,
        top: Float,
        dw: Float,
        dh: Float
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(3, 0, 0, 0)
            typeface = Typeface.DEFAULT
        }
        for (line in ocr.lines) {
            val x = left + line.left * dw
            val y = top + line.top * dh
            val w = (line.right - line.left) * dw
            val h = (line.bottom - line.top) * dh
            if (w <= 1f || h <= 1f) continue

            paint.textSize = h * 0.85f
            paint.textScaleX = 1f
            val measured = paint.measureText(line.text)
            if (measured > w && measured > 0f) {
                paint.textScaleX = (w / measured).coerceIn(0.4f, 1f)
            }
            // 先按文字行框裁剪，避免个别行溢出到相邻区域
            canvas.save()
            canvas.clipRect(x - 1f, y - 1f, x + w + 1f, y + h + 1f)
            canvas.drawText(line.text, x, y + h * 0.85f, paint)
            canvas.restore()
        }
    }
}
