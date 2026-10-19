package com.scanpdf.app.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scanpdf.app.doc.Quad
import com.scanpdf.app.doc.QuadDetector
import com.scanpdf.app.io.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

private val FrameBlue = Color(0xFF4DA3FF)

/**
 * 手动边缘校正：在原图上拖动四个圆点对齐纸张四角，再按该四边形做透视校正。
 *
 * 为什么不共用自动检测做最终结果：Hough 在光斑/阴影/低对比纸面上偏差常有几十像素，
 * 手动四点对齐是唯一稳定可控的方式。自动检测只作为初始值，省掉大部分拖动。
 */
@Composable
fun CropEditorScreen(
    file: File,
    initial: Quad?,
    onCancel: () -> Unit,
    onConfirm: (Quad) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var quad by remember { mutableStateOf(initial ?: Quad.FULL) }
    var dragging by remember { mutableIntStateOf(-1) }
    var autoBusy by remember { mutableStateOf(false) }

    LaunchedEffect(file.absolutePath) {
        val b = withContext(Dispatchers.IO) { ImageIO.decodeOriented(file, 1600) }
        bmp = b
        loading = false
        if (initial == null && b != null) {
            // 没有既有四角时，用自动检测当初始值（只作起点，不直接采信）
            val auto = withContext(Dispatchers.Default) { QuadDetector.detect(b) }
            if (auto != null && auto.isSane()) quad = auto
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF11151A))) {

        // ---------- 顶栏 ----------
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1F26))
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "调整图像边缘",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text("拖动四个圆点，对齐纸张的四个角", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            }
            TextButton(onClick = {
                val q = quad.normalized()
                if (!q.isSane()) {
                    Toast.makeText(ctx, "四点位置不合理，请重新调整", Toast.LENGTH_SHORT).show()
                } else {
                    onConfirm(q)
                }
            }) { Text("完成", color = FrameBlue, fontSize = 15.sp) }
        }

        // ---------- 图像 + 四角 ----------
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val density = LocalDensity.current
            val areaW = with(density) { maxWidth.toPx() }
            val areaH = with(density) { maxHeight.toPx() }
            val handleR = with(density) { 10.dp.toPx() }
            val touchR = with(density) { 38.dp.toPx() }

            val image = bmp
            if (image == null || loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FrameBlue, strokeWidth = 2.dp)
                }
            } else {
                // 等比铺满可见区域（Fit）
                val scale = min(areaW / image.width, areaH / image.height)
                val dw = image.width * scale
                val dh = image.height * scale
                val ox = (areaW - dw) / 2f
                val oy = (areaH - dh) / 2f
                val img = image.asImageBitmap()

                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(image, dw, dh, ox, oy) {
                            detectDragGestures(
                                onDragStart = { pos ->
                                    var best = -1
                                    var bestDist = Float.MAX_VALUE
                                    quad.points().forEachIndexed { i, p ->
                                        val d = hypot(ox + p.x * dw - pos.x, oy + p.y * dh - pos.y)
                                        if (d < bestDist) {
                                            bestDist = d
                                            best = i
                                        }
                                    }
                                    dragging = if (bestDist <= touchR) best else -1
                                },
                                onDrag = { change, amount ->
                                    if (dragging >= 0) {
                                        change.consume()
                                        quad = quad.moved(dragging, amount.x / dw, amount.y / dh)
                                    }
                                },
                                onDragEnd = { dragging = -1 },
                                onDragCancel = { dragging = -1 }
                            )
                        }
                ) {
                    drawImage(
                        image = img,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(image.width, image.height),
                        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                        dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                        filterQuality = FilterQuality.Medium
                    )

                    val pts = quad.points().map { Offset(ox + it.x * dw, oy + it.y * dh) }
                    val outline = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        lineTo(pts[1].x, pts[1].y)
                        lineTo(pts[2].x, pts[2].y)
                        lineTo(pts[3].x, pts[3].y)
                        close()
                    }
                    // 四边形外侧压暗由描边+角点表达，内部保持原亮度，便于判断对齐
                    drawPath(outline, FrameBlue, style = Stroke(width = 2.dp.toPx()))

                    pts.forEachIndexed { i, o ->
                        val active = dragging == i
                        drawCircle(
                            color = if (active) Color.White else FrameBlue,
                            radius = if (active) handleR + 3.dp.toPx() else handleR,
                            center = o
                        )
                        drawCircle(
                            color = Color.White,
                            radius = if (active) handleR + 3.dp.toPx() else handleR,
                            center = o,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }
        }

        // ---------- 底部操作 ----------
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1F26))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                "提示：镜头越正、纸张越平，校正后越接近真实比例。四角顺序会自动修正，不必担心拖乱。",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { quad = Quad.FULL },
                    modifier = Modifier.weight(1f)
                ) { Text("整页", fontSize = 13.sp) }

                OutlinedButton(
                    onClick = {
                        val b = bmp
                        if (b == null || autoBusy) return@OutlinedButton
                        autoBusy = true
                        scope.launch {
                            val auto = withContext(Dispatchers.Default) { QuadDetector.detect(b) }
                            autoBusy = false
                            if (auto != null && auto.isSane()) {
                                quad = auto
                            } else {
                                Toast.makeText(ctx, "自动检测没找到纸张，请手动拖四点", Toast.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    },
                    enabled = !autoBusy && bmp != null,
                    modifier = Modifier.weight(1f)
                ) { Text(if (autoBusy) "检测中…" else "重新自动检测", fontSize = 13.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "当前选区：${(quad.areaRatio() * 100).roundToInt()}% 画面",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp
            )
        }
    }
}
