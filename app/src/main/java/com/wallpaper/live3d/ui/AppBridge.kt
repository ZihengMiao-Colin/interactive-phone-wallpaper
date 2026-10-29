package com.wallpaper.live3d.ui

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.wallpaper.live3d.MainActivity
import com.wallpaper.live3d.config.ConfigManager
import com.wallpaper.live3d.depth.DepthCacheManager
import com.wallpaper.live3d.depth.DepthEstimator
import com.wallpaper.live3d.util.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * ============================================================================
 * AppBridge — 主界面 WebView 的 @JavascriptInterface 桥接
 * ============================================================================
 *
 * 暴露为 "AppBridge" 给 app_ui.js 调用。
 *
 * 包含 WallpaperBridge 的所有配置方法 + UI 特有方法:
 *   - 图片选择 (openImagePicker → ActivityResultContract)
 *   - 深度图管理 (save/has depth map)
 *   - 设置壁纸 (setAsWallpaper)
 *   - 系统适配 (电池优化、悬浮窗权限、平台检测)
 *
 * 关键原则:
 *   - 不传输 Base64 图片数据 (图片通过 WebViewAssetLoader URL 加载)
 *   - saveDepthMap 接收 Base64 但仅在 Kotlin 侧解码写入文件
 */
class AppBridge(
    private val activity: MainActivity,
    private val webView: WebView
) {
    private val configManager = ConfigManager(activity)
    private val depthCacheManager = DepthCacheManager(activity)
    private val depthEstimator = DepthEstimator(activity)
    private val scope = CoroutineScope(Dispatchers.Main)

    // ==================== 配置 ====================

    @JavascriptInterface
    fun getConfig(): String {
        return configManager.getAll().toString()
    }

    /** 保存参数（只认参数键，currentImage / depthFile 要走 applyWallpaper） */
    @JavascriptInterface
    fun saveConfig(json: String) {
        try {
            configManager.applyParams(JSONObject(json))
        } catch (e: Exception) {
            android.util.Log.e("[AppBridge]", "saveConfig error", e)
        }
    }

    // ==================== 图片选择 ====================

    /**
     * 打开系统文件选择器。
     * 结果通过 ActivityResultContract 回调 → onImageSelected → JS callback
     */
    @JavascriptInterface
    fun openImagePicker() {
        activity.runOnUiThread {
            activity.openImagePicker()
        }
    }

    /**
     * 用户选择图片后由 MainActivity 调用。
     * 将图片复制到内部存储，然后通知 JS 更新预览。
     */
    fun onImageSelected(uri: Uri) {
        val fileName = FileUtils.getFileName(activity, uri)
        val targetDir = java.io.File(activity.filesDir, "wallpapers")
        if (!targetDir.exists()) targetDir.mkdirs()

        val targetFile = java.io.File(targetDir, fileName)
        try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            android.util.Log.d("[AppBridge]", "Image copied to: ${targetFile.absolutePath}")

            // 通知 JS (仅传文件名，图片通过 WebViewAssetLoader URL 加载)
            webView.evaluateJavascript(
                "window.onImageSelected?.('$fileName', '')", null
            )
        } catch (e: Exception) {
            android.util.Log.e("[AppBridge]", "Failed to copy image", e)
            webView.evaluateJavascript(
                "alert('图片保存失败: ${e.message?.replace("'", "\\'")}')", null
            )
        }
    }

    // ==================== 图片裁剪保存 ====================

    /**
     * 保存裁剪后的壁纸图片。
     * 由 JS 裁剪确认后调用，Base64 Data URL 仅在 Kotlin 侧解码写入文件。
     *
     * @param originalFileName 原始文件名 (用于生成裁剪后的文件名)
     * @param croppedDataUrl   裁剪后的 JPEG Base64 Data URL
     * @return 保存后的文件名 (如 "cropped_image_123.jpg")，失败返回空字符串
     */
    @JavascriptInterface
    fun saveCroppedImage(originalFileName: String, croppedDataUrl: String): String {
        return try {
            val base64Data = if (croppedDataUrl.contains("base64,")) {
                croppedDataUrl.substringAfter("base64,")
            } else {
                croppedDataUrl
            }
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)

            // 生成裁剪后的文件名: cropped_<原始文件名>
            val croppedFileName = "cropped_$originalFileName"
            val targetDir = java.io.File(activity.filesDir, "wallpapers")
            if (!targetDir.exists()) targetDir.mkdirs()
            val targetFile = java.io.File(targetDir, croppedFileName)
            targetFile.writeBytes(bytes)

            // 更新配置：裁剪后的图片成为当前壁纸
            configManager.edit()
                .putString(ConfigManager.KEY_CURRENT_IMAGE, croppedFileName)
                .putString(ConfigManager.KEY_DEPTH_FILE, "")
                .apply()

            android.util.Log.d("[AppBridge]", "Cropped image saved: $croppedFileName (${bytes.size} bytes)")
            croppedFileName
        } catch (e: Exception) {
            android.util.Log.e("[AppBridge]", "saveCroppedImage error", e)
            ""
        }
    }

    // ==================== 深度图管理 ====================

    @JavascriptInterface
    fun hasDepthMap(imageName: String): Boolean {
        return depthCacheManager.hasDepthMap(imageName)
    }

    /**
     * 保存生成的深度图到缓存。
     * @param imageName 原始壁纸文件名
     * @param depthDataUrl Base64 Data URL (仅在 Kotlin 侧解码，不传回 JS)
     * @return 实际保存的深度图文件名 (如 "depth_1a2b3c4d.png")，失败返回空字符串
     */
    @JavascriptInterface
    fun saveDepthMap(imageName: String, depthDataUrl: String): String {
        return try {
            val fileName = depthCacheManager.saveDepthMap(imageName, depthDataUrl)
            android.util.Log.d("[AppBridge]", "Depth map saved for $imageName → $fileName")
            fileName
        } catch (e: Exception) {
            android.util.Log.e("[AppBridge]", "saveDepthMap error", e)
            ""
        }
    }

    /**
     * 使用 Depth Anything V2 AI 模型生成深度图。
     * 在后台线程执行 ONNX 推理，进度通过 window.onDepthProgress(percent, text) 通知，
     * 完成后通过 window.onAIDepthReady(fileName, error) 通知。
     *
     * @param imageName 壁纸文件名
     * @param blurRadius 边缘平滑半径（像素）
     */
    @JavascriptInterface
    fun generateAIDepthMap(imageName: String, blurRadius: Int) {
        android.util.Log.d("[AppBridge]", "generateAIDepthMap: $imageName (blur $blurRadius)")
        scope.launch {
            try {
                val wallpapersDir = java.io.File(activity.filesDir, "wallpapers")
                val imageFile = java.io.File(wallpapersDir, imageName)
                if (!imageFile.exists()) {
                    android.util.Log.e("[AppBridge]", "图片文件不存在: ${imageFile.absolutePath}")
                    fireAIDepthReady(null, "图片文件不存在")
                    return@launch
                }

                val cacheDir = java.io.File(activity.filesDir, "depth_cache")
                val depthFileName = depthEstimator.estimateDepth(imageFile, cacheDir, blurRadius) { percent, text ->
                    activity.runOnUiThread {
                        webView.evaluateJavascript(
                            "window.onDepthProgress && window.onDepthProgress($percent, ${JSONObject.quote(text)})", null
                        )
                    }
                }

                if (depthFileName != null) {
                    // 更新配置
                    configManager.edit()
                        .putString(ConfigManager.KEY_DEPTH_FILE, depthFileName)
                        .apply()

                    android.util.Log.d("[AppBridge]", "AI 深度图生成成功: $depthFileName")
                    fireAIDepthReady(depthFileName, null)
                } else {
                    val errMsg = depthEstimator.lastError ?: "ONNX 推理失败 (无详细错误)"
                    android.util.Log.e("[AppBridge]", "AI 深度估计失败: $errMsg")
                    fireAIDepthReady(null, errMsg)
                }
            } catch (e: Exception) {
                android.util.Log.e("[AppBridge]", "generateAIDepthMap 异常", e)
                fireAIDepthReady(null, e.message ?: "未知异常")
            }
        }
    }

    /**
     * 安全地触发 JS 回调。使用 JSONObject.quote() 确保错误消息中的
     * 特殊字符（\、换行、引号等）不会破坏 JavaScript 字符串语法。
     */
    private fun fireAIDepthReady(fileName: String?, error: String?) {
        val js = buildString {
            append("window.onAIDepthReady(")
            if (fileName != null) {
                append(JSONObject.quote(fileName))
            } else {
                append("null")
            }
            append(", ")
            if (error != null) {
                append(JSONObject.quote(error))
            } else {
                append("null")
            }
            append(")")
        }
        webView.evaluateJavascript(js, null)
    }

    /**
     * 获取已缓存的深度图文件名 (用于构建 WebViewAssetLoader URL)。
     * @param imageName 原始壁纸文件名
     * @return 深度图文件名，无缓存则返回空字符串
     */
    @JavascriptInterface
    fun getDepthFileName(imageName: String): String {
        return depthCacheManager.getDepthFileName(imageName) ?: ""
    }

    // ==================== 设置壁纸 ====================

    @JavascriptInterface
    fun setAsWallpaper() {
        activity.runOnUiThread {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_SET_WALLPAPER)
                activity.startActivity(android.content.Intent.createChooser(intent, "选择壁纸"))
            } catch (e: Exception) {
                android.util.Log.e("[AppBridge]", "setAsWallpaper error", e)
            }
        }
    }

    // ==================== 系统适配 ====================

    @JavascriptInterface
    fun requestBatteryOptimizationBypass() {
        activity.runOnUiThread {
            activity.requestBatteryOptimizationBypass()
        }
    }

    @JavascriptInterface
    fun requestOverlayPermission() {
        activity.runOnUiThread {
            activity.requestOverlayPermission()
        }
    }

    /**
     * 返回平台信息 JSON。
     * JS 端用于 MIUI 检测、API 级别适配等。
     */
    @JavascriptInterface
    fun getPlatformInfo(): String {
        // 获取设备真实屏幕分辨率（px）
        val displayMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager?.defaultDisplay?.getRealMetrics(displayMetrics)
            ?: activity.windowManager?.defaultDisplay?.getMetrics(displayMetrics)

        val info = JSONObject().apply {
            put("apiLevel", android.os.Build.VERSION.SDK_INT)
            put("manufacturer", android.os.Build.MANUFACTURER)
            put("model", android.os.Build.MODEL)
            put("screenWidth", displayMetrics.widthPixels)
            put("screenHeight", displayMetrics.heightPixels)
            put("hasOverlayPermission",
                android.provider.Settings.canDrawOverlays(activity))
            put("isXiaomi",
                android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true))
        }
        return info.toString()
    }

    // ==================== 工具 ====================

    @JavascriptInterface
    fun log(message: String) {
        android.util.Log.d("[MainUI]", message)
    }
}
