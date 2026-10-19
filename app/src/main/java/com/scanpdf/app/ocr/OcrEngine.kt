package com.scanpdf.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 一行 OCR 结果，坐标归一化到 0..1（相对识别所用的那张图） */
data class OcrLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class OcrResult(val text: String, val lines: List<OcrLine>) {
    val isEmpty: Boolean get() = text.isBlank() || lines.isEmpty()
}

/**
 * 离线文字识别：ML Kit 中文模型（含拉丁字母与数字），不需要联网。
 * 首次调用会初始化本地模型，耗时略长。
 */
object OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                val w = bitmap.width.toFloat().coerceAtLeast(1f)
                val h = bitmap.height.toFloat().coerceAtLeast(1f)
                val lines = ArrayList<OcrLine>(32)

                for (block in text.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        val content = line.text.trim()
                        if (content.isEmpty()) continue
                        lines.add(
                            OcrLine(
                                text = content,
                                left = box.left / w,
                                top = box.top / h,
                                right = box.right / w,
                                bottom = box.bottom / h
                            )
                        )
                    }
                }
                if (cont.isActive) cont.resume(OcrResult(text.text, lines))
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resumeWithException(e)
            }
    }
}
