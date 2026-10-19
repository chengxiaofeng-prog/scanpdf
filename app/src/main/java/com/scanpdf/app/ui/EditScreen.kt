package com.scanpdf.app.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scanpdf.app.data.ScanStore
import com.scanpdf.app.doc.PageRenderer
import com.scanpdf.app.doc.PerspectiveWarp
import com.scanpdf.app.doc.Quad
import com.scanpdf.app.img.Adjustments
import com.scanpdf.app.io.ImageIO
import com.scanpdf.app.ocr.OcrEngine
import com.scanpdf.app.pdf.PdfExporter
import com.scanpdf.app.share.ShareHelper
import com.scanpdf.app.ui.theme.InkSub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap

@Composable
fun EditScreen(startIndex: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val pages = ScanStore.pages

    if (pages.isEmpty()) {
        EmptyDoc(onBack)
        return
    }

    // 用 id 而非下标做选中锚点：拖拽重排后仍停留在同一页
    var selectedId by remember {
        mutableStateOf(pages.getOrNull(startIndex)?.id ?: pages.first().id)
    }
    val found = pages.indexOfFirst { it.id == selectedId }
    val index = if (found >= 0) found else 0
    val page = pages[index]
    val adj = page.adjustments
    val quad = page.quad

    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var processed by remember { mutableStateOf<Bitmap?>(null) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(page.id, adj, page.cropEnabled, quad) {
        working = true
        delay(60)
        val out = withContext(Dispatchers.Default) {
            val src = PreviewCache.load(page.file) ?: return@withContext null
            val base = if (page.cropEnabled && quad != null) {
                WarpCache.load(src, quad, page.file)
            } else {
                src
            }
            PageRenderer.render(base, adj, crop = false, quad = null, maxDim = 1400)
        }
        processed = out
        preview = out?.asImageBitmap()
        working = false
    }

    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(pages.size) }
    var pdfFile by remember { mutableStateOf<File?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var ordering by remember { mutableStateOf(false) }
    var cropEditing by remember { mutableStateOf(false) }
    var ocrBusy by remember { mutableStateOf(false) }
    var showOcr by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val weChatInstalled = remember { ShareHelper.isInstalled(ctx, ShareHelper.WECHAT) }
    val qqInstalled = remember { ShareHelper.isInstalled(ctx, ShareHelper.QQ) }

    fun exportPdf() {
        if (exporting) return
        exporting = true
        progress = 0
        total = pages.size
        scope.launch {
            try {
                pdfFile = PdfExporter.export(ctx, pages.toList()) { done, _ -> progress = done }
            } catch (e: Exception) {
                errorText = e.message ?: "导出失败"
            }
            exporting = false
        }
    }

    fun shareTo(file: File, pkg: String?) {
        if (ShareHelper.sharePdf(ctx, file, pkg)) return
        if (pkg != null && ShareHelper.sharePdf(ctx, file, null)) return
        Toast.makeText(ctx, "分享失败，请确认已安装对应应用", Toast.LENGTH_SHORT).show()
    }

    fun runOcr() {
        if (ocrBusy) return
        ocrBusy = true
        scope.launch {
            try {
                val bmp = processed ?: withContext(Dispatchers.Default) {
                    val src = PreviewCache.load(page.file)
                    if (src == null) null else {
                        val base = if (page.cropEnabled && page.quad != null) {
                            WarpCache.load(src, page.quad!!, page.file)
                        } else {
                            src
                        }
                        PageRenderer.render(base, page.adjustments, false, null, 1400)
                    }
                }
                if (bmp == null) {
                    errorText = "图片读取失败"
                    return@launch
                }
                val result = OcrEngine.recognize(bmp)
                page.ocr = result
                if (result.isEmpty) {
                    Toast.makeText(ctx, "没有识别到文字", Toast.LENGTH_SHORT).show()
                } else {
                    showOcr = true
                }
            } catch (e: Exception) {
                errorText = "文字识别失败：${e.message}"
            } finally {
                ocrBusy = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

            // ---------- 顶栏 ----------
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.weight(1f)) {
                    Text("调整与导出", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "第 ${index + 1} 页 / 共 ${pages.size} 页",
                        fontSize = 11.sp,
                        color = InkSub
                    )
                }
                TextButton(onClick = { ordering = true }) { Text("页序") }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除本页", tint = InkSub)
                }
            }

            // ---------- 预览区 ----------
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF191D23)),
                contentAlignment = Alignment.Center
            ) {
                val shown = preview
                if (shown != null) {
                    Image(
                        bitmap = shown,
                        contentDescription = "处理预览",
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = Color.White.copy(alpha = 0.75f),
                        strokeWidth = 2.dp
                    )
                }

                if (working && shown != null) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(14.dp)
                            .size(20.dp),
                        color = Color.White.copy(alpha = 0.7f),
                        strokeWidth = 2.dp
                    )
                }

                if (ocrBusy) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Text(
                            "正在识别文字…",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                ) {
                    Text(
                        "${index + 1}/${pages.size}",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // ---------- 参数面板 ----------
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 330.dp)
                    .verticalScroll(rememberScrollState())
                    .background(Color.White)
                    .padding(top = 6.dp, bottom = 2.dp)
            ) {
                SliderRow(
                    title = "去底灰",
                    valueText = "${adj.deGray}%",
                    value = adj.deGray.toFloat(),
                    range = 0f..100f,
                    hint = "把发灰的纸张拉成白底，强度越大底色越干净（过高会让浅色字迹变淡）"
                ) { page.adjustments = page.adjustments.copy(deGray = it.toInt()) }

                SliderRow(
                    title = "对比度",
                    valueText = "${adj.contrast}%",
                    value = adj.contrast.toFloat(),
                    range = 0f..100f,
                    hint = "拉开墨色与纸底的差距，字形更实（过高会糊边）"
                ) { page.adjustments = page.adjustments.copy(contrast = it.toInt()) }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("阈值", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (adj.thresholdOn) "${adj.threshold}" else "关闭",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Text("纯黑白", fontSize = 11.sp, color = InkSub)
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = adj.thresholdOn,
                        onCheckedChange = {
                            page.adjustments = page.adjustments.copy(thresholdOn = it)
                        }
                    )
                }

                if (adj.thresholdOn) {
                    SliderRow(
                        title = "阈值数值",
                        valueText = "${adj.threshold}",
                        value = adj.threshold.toFloat(),
                        range = 0f..255f,
                        hint = "数值越低文字越粗（越黑），120~180 适合印刷体"
                    ) { page.adjustments = page.adjustments.copy(threshold = it.toInt()) }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 边缘校正（手动四角）+ OCR
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("边缘校正", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                !page.cropEnabled -> "未启用，按整页输出"
                                page.quad == null -> "尚未设置四角"
                                else -> "已按四点校正"
                            },
                            fontSize = 11.sp,
                            color = InkSub
                        )
                    }
                    TextButton(onClick = { cropEditing = true }) { Text("调整四点") }
                    Switch(
                        checked = page.cropEnabled,
                        onCheckedChange = { page.cropEnabled = it }
                    )
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("文字识别", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                ocrBusy -> "正在识别…"
                                page.ocr == null -> "未识别（可选）"
                                else -> "${page.ocr!!.lines.size} 行 · 导出后可搜索"
                            },
                            fontSize = 11.sp,
                            color = InkSub
                        )
                    }
                    TextButton(
                        onClick = {
                            if (page.ocr != null) showOcr = true else runOcr()
                        },
                        enabled = !ocrBusy
                    ) {
                        Text(
                            when {
                                ocrBusy -> "识别中…"
                                page.ocr != null -> "查看文字"
                                else -> "识别文字"
                            }
                        )
                    }
                }

                // 输出模式：彩色 / 黑白
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("输出", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                    ModeChip("彩色", selected = !adj.gray) {
                        page.adjustments = page.adjustments.copy(gray = false)
                    }
                    Spacer(Modifier.width(8.dp))
                    ModeChip("黑白", selected = adj.gray) {
                        page.adjustments = page.adjustments.copy(gray = true)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        when {
                            adj.isIdentity -> "原图直出"
                            adj.thresholdOn -> "二值化"
                            adj.gray -> "灰度"
                            else -> "彩色 + 校正"
                        },
                        fontSize = 11.sp,
                        color = InkSub
                    )
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        page.adjustments = Adjustments(
                            gray = false, deGray = 0, contrast = 0,
                            thresholdOn = false, threshold = adj.threshold
                        )
                    }) { Text("原图") }
                    TextButton(onClick = { page.adjustments = Adjustments() }) { Text("重置") }
                    TextButton(onClick = { ScanStore.applyToAll(page.adjustments) }) {
                        Text("应用到全部页")
                    }
                }
            }

            // ---------- 底栏 ----------
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (index > 0) selectedId = pages[index - 1].id },
                    enabled = index > 0
                ) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "上一页")
                }
                Text("${index + 1}/${pages.size}", fontSize = 12.sp, color = InkSub)
                IconButton(
                    onClick = { if (index < pages.lastIndex) selectedId = pages[index + 1].id },
                    enabled = index < pages.lastIndex
                ) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = "下一页")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { exportPdf() }, enabled = !exporting) {
                    Text("导出 PDF")
                }
            }
        }

        // ---------- 页序管理覆盖层 ----------
        if (ordering) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                PageOrderScreen(onBack = { ordering = false })
            }
        }

        // ---------- 手动边缘校正覆盖层 ----------
        if (cropEditing) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF11151A)) {
                CropEditorScreen(
                    file = page.file,
                    initial = page.quad,
                    onCancel = { cropEditing = false },
                    onConfirm = { q ->
                        page.quad = q
                        page.cropEnabled = true
                        cropEditing = false
                    }
                )
            }
        }
    }

    // ---------- 弹窗 ----------
    if (exporting) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在合成 PDF") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else progress.toFloat() / total },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("第 $progress / $total 页", fontSize = 12.sp, color = InkSub)
                }
            },
            confirmButton = { }
        )
    }

    errorText?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text("出错了") },
            text = { Text(msg, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { errorText = null }) { Text("知道了") }
            }
        )
    }

    if (showOcr && page.ocr != null) {
        val ocr = page.ocr!!
        AlertDialog(
            onDismissRequest = { showOcr = false },
            title = { Text("识别结果（${ocr.lines.size} 行）") },
            text = {
                Box(
                    Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(ocr.text, fontSize = 12.sp, lineHeight = 18.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(ocr.text))
                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制全部") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showOcr = false
                        runOcr()
                    }) { Text("重新识别") }
                    TextButton(onClick = { showOcr = false }) { Text("关闭") }
                }
            }
        )
    }

    pdfFile?.let { file ->
        val withOcr = pages.count { it.ocr != null }
        AlertDialog(
            onDismissRequest = { pdfFile = null },
            title = { Text("PDF 已生成") },
            text = {
                Column {
                    Text(file.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$total 页 · ${String.format("%.0f", file.length() / 1024f)} KB" +
                                (if (withOcr > 0) " · $withOcr 页含文字层" else ""),
                        fontSize = 12.sp,
                        color = InkSub
                    )
                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { shareTo(file, ShareHelper.WECHAT) },
                        enabled = weChatInstalled,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (weChatInstalled) "发送到微信" else "未安装微信") }

                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { shareTo(file, ShareHelper.QQ) },
                        enabled = qqInstalled,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (qqInstalled) "发送到 QQ" else "未安装 QQ") }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { shareTo(file, null) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("更多应用") }
                }
            },
            confirmButton = {
                TextButton(onClick = { pdfFile = null }) { Text("关闭") }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这一页？") },
            text = { Text("第 ${index + 1} 页将从文档中移除，原图同时删除。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val removed = index
                    ScanStore.removeAt(index)
                    if (pages.isEmpty()) {
                        onBack()
                    } else {
                        selectedId = pages[removed.coerceAtMost(pages.lastIndex)].id
                    }
                }) { Text("删除", color = Color(0xFFD93025)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) Color.White else InkSub
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            fontSize = 12.sp,
            color = fg,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    hint: String,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                valueText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(32.dp)
        )
        Text(hint, fontSize = 10.sp, color = InkSub, lineHeight = 14.sp)
    }
}

@Composable
private fun EmptyDoc(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("还没有拍摄任何页面", fontSize = 15.sp, color = InkSub)
        Spacer(Modifier.height(14.dp))
        Button(onClick = onBack) { Text("去拍照") }
    }
}

/** 预览用源图缓存（长边 1400），避免每次调参都从磁盘解码 */
private object PreviewCache {
    private val map = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 3
    }

    suspend fun load(file: File): Bitmap? = withContext(Dispatchers.IO) {
        val key = file.absolutePath
        val cached = synchronized(map) { map[key] }
        if (cached != null && !cached.isRecycled) return@withContext cached

        val bmp = ImageIO.decodeOriented(file, 1400)
        if (bmp != null) synchronized(map) { map[key] = bmp }
        bmp
    }
}

/** 透视校正结果缓存：同一页同一四边形只算一次，拖动滑块时不重复 warp */
private object WarpCache {
    private val map = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > 3
    }

    suspend fun load(src: Bitmap, quad: Quad, file: File): Bitmap = withContext(Dispatchers.Default) {
        val key = file.absolutePath + "#" + quad.points().joinToString(",") {
            "${(it.x * 1000).toInt()}/${(it.y * 1000).toInt()}"
        }
        val cached = synchronized(map) { map[key] }
        if (cached != null && !cached.isRecycled) return@withContext cached

        val warped = PerspectiveWarp.warp(src, quad, 1400)
        if (warped !== src) synchronized(map) { map[key] = warped }
        warped
    }
}
