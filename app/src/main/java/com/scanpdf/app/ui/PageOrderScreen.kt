package com.scanpdf.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.scanpdf.app.data.ScanStore
import com.scanpdf.app.ui.theme.InkSub
import kotlin.math.roundToInt

/**
 * 页序管理：长按某页拖动，松手后落到目标位置。
 * 用固定行高做位移换算，简单可靠（不做实时插入预览）。
 */
@Composable
fun PageOrderScreen(onBack: () -> Unit) {
    val pages = ScanStore.pages
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    val rowHeight = 84.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("调整页序", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("长按页面拖动，松手后生效", fontSize = 11.sp, color = InkSub)
            }
            TextButton(onClick = onBack) { Text("完成") }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            itemsIndexed(pages, key = { _, item -> item.id }) { i, item ->
                val dragging = i == dragIndex
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .pointerInput(item.id, i) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    dragIndex = i
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                },
                                onDragEnd = {
                                    val target = (i + (dragOffset / rowHeightPx).roundToInt())
                                        .coerceIn(0, pages.lastIndex)
                                    ScanStore.move(i, target)
                                    dragIndex = -1
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    dragIndex = -1
                                    dragOffset = 0f
                                }
                            )
                        }
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (dragging) Color.White else Color.White.copy(alpha = 0.65f),
                    shadowElevation = if (dragging) 10.dp else 0.dp
                ) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${i + 1}",
                            fontSize = 13.sp,
                            color = InkSub,
                            modifier = Modifier.width(26.dp)
                        )
                        PageThumb(file = item.file, size = 54.dp) { }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("第 ${i + 1} 页", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                buildString {
                                    append(if (item.cropEnabled) "已裁边" else "整页")
                                    if (item.ocr != null) append(" · 已识别文字")
                                },
                                fontSize = 10.sp,
                                color = InkSub
                            )
                        }
                        Icon(Icons.Default.DragHandle, contentDescription = "拖动", tint = InkSub)
                    }
                }
            }
        }
    }
}
