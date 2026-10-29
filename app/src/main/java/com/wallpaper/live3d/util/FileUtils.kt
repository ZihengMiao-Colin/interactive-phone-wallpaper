package com.wallpaper.live3d.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File

/**
 * ============================================================================
 * FileUtils — 文件操作工具
 * ============================================================================
 *
 * 对应 Electron preload.js 中的 readFileAsDataURL / saveDataURLAsFile / fileExists。
 * Android 上主要通过 WebViewAssetLoader URL 加载图片，但 Data URL 操作
 * 仍用于深度图保存等场景。
 */
object FileUtils {

    /**
     * 将文件读取为 Base64 Data URL。
     * (非主要路径 — 图片优先使用 WebViewAssetLoader)
     */
    fun readFileAsDataURL(filePath: String): String? {
        return try {
            val file = File(filePath)
            if (!file.exists()) return null
            val bytes = file.readBytes()
            val extension = file.extension.lowercase()
            val mime = when (extension) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                else -> "image/png"
            }
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:$mime;base64,$base64"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将 Content URI 读取为 Base64 Data URL。
     * 用于用户选择图片后的快速预览。
     */
    fun readUriAsDataURL(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bytes = inputStream.readBytes()
            inputStream.close()
            val mime = context.contentResolver.getType(uri) ?: "image/png"
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:$mime;base64,$base64"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将 Content URI 复制到内部存储。
     * @param targetRelativePath 相对于 context.filesDir 的路径
     */
    fun copyUriToInternal(context: Context, uri: Uri, targetRelativePath: String) {
        val targetFile = File(context.filesDir, targetRelativePath)
        targetFile.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * 从 Content URI 获取原始文件名。
     */
    fun getFileName(context: Context, uri: Uri): String {
        var name = "image_${System.currentTimeMillis()}.jpg"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val displayName = cursor.getString(idx)
                    if (!displayName.isNullOrBlank()) name = displayName
                }
            }
        }
        // 确保有扩展名
        if (!name.contains(".")) {
            name += ".jpg"
        }
        return name
    }

    /**
     * 将 Base64 Data URL 保存为文件。
     */
    fun saveDataURLAsFile(dataUrl: String, filePath: String): Boolean {
        return try {
            val base64Data = if (dataUrl.contains("base64,")) {
                dataUrl.substringAfter("base64,")
            } else {
                dataUrl
            }
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            val file = File(filePath)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            true
        } catch (e: Exception) {
            false
        }
    }
}
