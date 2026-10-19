package com.scanpdf.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.scanpdf.app.doc.Quad
import com.scanpdf.app.doc.QuadDetector
import com.scanpdf.app.img.Adjustments
import com.scanpdf.app.io.ImageIO
import com.scanpdf.app.ocr.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class PageItem(val id: Long, val file: File) {
    /** 每页独立参数；不改原图，随时可复原 */
    var adjustments by mutableStateOf(Adjustments())

    /** 自动裁边检测到的纸张四角（归一化坐标） */
    var quad by mutableStateOf<Quad?>(null)

    /** 是否应用裁边（检测失败时为 false，可手动开启重试） */
    var cropEnabled by mutableStateOf(false)

    /** OCR 结果（点击"识别文字"后生成） */
    var ocr by mutableStateOf<OcrResult?>(null)
}

/**
 * 一次扫描会话：pages 即为一份文档的页序。
 * 用 Compose 的 SnapshotStateList，拍照/删除/重排后界面自动刷新。
 */
object ScanStore {

    private val seq = AtomicLong(0L)
    private val detectLock = Mutex()

    val pages = mutableStateListOf<PageItem>()

    fun captureFile(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "captures")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "IMG_${System.currentTimeMillis()}.jpg")
    }

    fun addPage(file: File): PageItem {
        val page = PageItem(seq.incrementAndGet(), file)
        pages.add(page)
        return page
    }

    fun removeAt(index: Int): PageItem? {
        if (index !in pages.indices) return null
        val page = pages.removeAt(index)
        runCatching { page.file.delete() }
        return page
    }

    /** 拖拽重排：把 from 位置的页移动到 to 位置 */
    fun move(from: Int, to: Int) {
        if (from !in pages.indices || to !in pages.indices || from == to) return
        val item = pages.removeAt(from)
        pages.add(to, item)
    }

    fun applyToAll(adj: Adjustments) {
        pages.forEach { it.adjustments = adj }
    }

    fun reset() {
        pages.forEach { runCatching { it.file.delete() } }
        pages.clear()
    }

    /**
     * 检测纸张边缘。检测串行执行（Mutex），避免连拍时多张同时跑 Hough 抢 CPU。
     * @return 是否得到有效四边形
     */
    suspend fun detectCrop(page: PageItem, force: Boolean = false): Boolean = detectLock.withLock {
        if (!force && page.quad != null) return@withLock true

        val bitmap = withContext(Dispatchers.IO) { ImageIO.decodeOriented(page.file, 900) }
            ?: return@withLock false

        val quad = withContext(Dispatchers.Default) { QuadDetector.detect(bitmap) }
        bitmap.recycle()

        if (quad == null) return@withLock false

        withContext(Dispatchers.Main) {
            page.quad = quad
            page.cropEnabled = true
        }
        true
    }
}
