package com.scanpdf.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.scanpdf.app.data.ScanStore
import com.scanpdf.app.io.ImageIO
import com.scanpdf.app.ui.theme.BrandBlue
import com.scanpdf.app.ui.theme.CanvasBg
import com.scanpdf.app.ui.theme.InkSub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CameraScreen(onOpenEditor: (Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        PermissionGate(onRequest = { launcher.launch(Manifest.permission.CAMERA) })
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val pages = ScanStore.pages

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var flashOn by remember { mutableStateOf(false) }
    var burst by remember { mutableStateOf(false) }
    var interval by remember { mutableStateOf(2f) }
    var busy by remember { mutableStateOf(false) }
    var toastText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toastText) {
        toastText?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            toastText = null
        }
    }

    LaunchedEffect(flashOn, imageCapture) {
        imageCapture?.flashMode =
            if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    fun shoot() {
        val capture = imageCapture ?: run {
            toastText = "相机尚未就绪"
            return
        }
        if (busy) return
        busy = true
        val file = ScanStore.captureFile(ctx)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    busy = false
                    val added = ScanStore.addPage(file)
                    // 后台自动检测纸张边缘（串行执行，不阻塞连拍）
                    scope.launch { ScanStore.detectCrop(added) }
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    runCatching { file.delete() }
                    toastText = "拍摄失败：${exception.message}"
                }
            }
        )
    }

    // 连拍：按固定间隔连续拍摄，拍完自动进入下一页
    LaunchedEffect(burst) {
        while (burst) {
            shoot()
            delay((interval * 1000).toLong())
        }
    }

    DisposableEffect(Unit) {
        onDispose { provider?.unbindAll() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val previewView = PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    try {
                        val cameraProvider = future.get()
                        provider = cameraProvider

                        val preview = Preview.Builder().build().apply {
                            setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .setFlashMode(
                                if (flashOn) ImageCapture.FLASH_MODE_ON
                                else ImageCapture.FLASH_MODE_OFF
                            )
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(
                                        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                                    )
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            Size(2560, 1920),
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                        )
                                    )
                                    .build()
                            )
                            .build()

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture
                        )
                        imageCapture = capture
                    } catch (e: Exception) {
                        toastText = "相机启动失败：${e.message}"
                    }
                }, ContextCompat.getMainExecutor(context))
                previewView
            }
        )

        // 取景引导框
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 130.dp)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            )
        }

        // 顶部状态
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.38f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "连续扫描",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "已拍 ${pages.size} 页 · 对准纸张，尽量充满取景框",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 11.sp
                )
            }
            TextButton(onClick = { flashOn = !flashOn }) {
                Text(
                    if (flashOn) "闪光灯 开" else "闪光灯 关",
                    color = if (flashOn) Color(0xFFFFD666) else Color.White,
                    fontSize = 12.sp
                )
            }
        }

        // 底部控制区
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(top = 12.dp, bottom = 20.dp)
        ) {
            if (pages.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(pages) { index, item ->
                        PageThumb(file = item.file) { onOpenEditor(index) }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            if (burst) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "间隔 ${String.format("%.1f", interval)}s",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                    Slider(
                        value = interval,
                        onValueChange = { interval = it },
                        valueRange = 0.8f..5f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(onClick = { burst = !burst }) {
                        Text(
                            if (burst) "连拍中" else "连拍",
                            color = if (burst) BrandBlue else Color.White,
                            fontSize = 13.sp,
                            fontWeight = if (burst) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                    Text(
                        if (burst) "点击停止" else "定时连拍",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp
                    )
                }

                // 快门
                Box(
                    Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .border(3.dp, Color.White, CircleShape)
                        .clickable { shoot() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(
                                if (busy) Color.White.copy(alpha = 0.45f) else Color.White
                            )
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = {
                        if (pages.isEmpty()) toastText = "先拍一页再调整"
                        else onOpenEditor(pages.lastIndex)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "调整并导出",
                            tint = Color.White
                        )
                    }
                    Text(
                        "调整/导出",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PageThumb(
    file: File,
    selected: Boolean = false,
    size: Dp = 54.dp,
    onClick: () -> Unit
) {
    var bmp by remember(file.absolutePath) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    LaunchedEffect(file.absolutePath) {
        bmp = withContext(Dispatchers.IO) { ImageIO.decodeOriented(file, 220) }
    }

    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .border(
                1.5.dp,
                if (selected) BrandBlue else Color.White.copy(alpha = 0.3f),
                RoundedCornerShape(8.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        bmp?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(CanvasBg)
            .padding(36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("需要相机权限", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "用于连续拍摄纸质文档，并在本机合成 PDF",
            fontSize = 13.sp,
            color = InkSub,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(22.dp))
        Button(onClick = onRequest) { Text("授予相机权限") }
        Spacer(Modifier.height(12.dp))
        Text(
            "若已拒绝，请到「设置 → 应用 → 扫描成文 → 权限」中手动开启",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
