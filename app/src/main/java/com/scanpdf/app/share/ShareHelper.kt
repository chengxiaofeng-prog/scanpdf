package com.scanpdf.app.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 文件分享：FileProvider 授权 Uri + ACTION_SEND。
 * - 指定包名可直达微信 / QQ 的"发送给朋友"面板
 * - 指定失败（未安装/不支持）时回退到系统分享面板
 */
object ShareHelper {

    const val WECHAT = "com.tencent.mm"
    const val QQ = "com.tencent.mobileqq"

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun sharePdf(ctx: Context, file: File, pkg: String? = null): Boolean {
        if (!file.exists()) return false

        val uri: Uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        } catch (e: Exception) {
            return false
        }

        val base = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_TEXT, file.name)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return try {
            val target = if (pkg.isNullOrBlank()) {
                grantToAll(ctx, base, uri)
                Intent.createChooser(base, "发送 PDF 到")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                Intent(base).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(target)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** chooser 场景下，部分机型不会自动把读权限授给每个候选应用，这里显式授予一遍。 */
    private fun grantToAll(ctx: Context, intent: Intent, uri: Uri) {
        runCatching {
            ctx.packageManager.queryIntentActivities(intent, 0).forEach {
                ctx.grantUriPermission(
                    it.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }
}
