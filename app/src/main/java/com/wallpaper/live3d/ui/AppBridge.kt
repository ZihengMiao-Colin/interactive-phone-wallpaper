package com.wallpaper.live3d.ui

import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.wallpaper.live3d.BuildConfig
import com.wallpaper.live3d.MainActivity
import com.wallpaper.live3d.config.ConfigManager
import com.wallpaper.live3d.depth.DepthEstimator
import com.wallpaper.live3d.util.FileUtils
import com.wallpaper.live3d.util.WallpaperFiles
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
 * 暴露为 "AppBridge" 给 assets/app/bridge.js 调用。
 *
 * 选择和应用分开：
 *   - 选图、裁剪、生成深度图都只产生新文件（文件名带时间戳），不写配置；
 *   - 只有 applyWallpaper 写 currentImage / depthFile，壁纸服务监听到后马上换。
 *
 * Kotlin → JS 回调（调用前都判断函数是否存在）：
 *   window.onImageSelected(pickedName)
 *   window.onDepthProgress(percent, text)
 *   window.onAIDepthReady(fileName | null, error | null)
 *   window.onSensorTilt(x, y) / window.onAppResume()   ← 由 MainActivity 推送
 */
class AppBridge(
    private val activity: MainActivity,
    private val webView: WebView
) {
    private val configManager = ConfigManager(activity)
    private val depthEstimator = DepthEstimator(activity)
    private val scope = CoroutineScope(Dispatchers.Main)

    private val wallpapersDir get() = File(activity.filesDir, "wallpapers")
    private val depthDir get() = File(activity.filesDir, "depth_cache")

    /** JS 传来的文件名只允许是单纯的文件名，不能带路径跳出目录 */
    private fun safeName(name: String): String? =
        name.takeIf { it.isNotEmpty() && !it.contains('/') && !it.contains('\\') && !it.startsWith(".") }

    private fun callJs(script: String) {
        activity.runOnUiThread { webView.evaluateJavascript(script, null) }
    }

    // ==================== 配置 ====================

    /** 参数 + currentImage + depthFile */
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

    // ==================== 选图与裁剪 ====================

    /** 打开系统图片选择器，结果 → onImageSelected → window.onImageSelected(pickedName) */
    @JavascriptInterface
    fun openImagePicker() {
        activity.runOnUiThread { activity.openImagePicker() }
    }

    /** 用户选了图片：复制成 pick_<时间戳>.<扩展名>，交给 JS 裁剪 */
    fun onImageSelected(uri: Uri) {
        val ext = FileUtils.getFileName(activity, uri)
            .substringAfterLast('.', "jpg").lowercase()
            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) } ?: "jpg"
        val name = WallpaperFiles.newName("pick", ext)
        try {
            wallpapersDir.mkdirs()
            activity.contentResolver.openInputStream(uri)?.use { input ->
                File(wallpapersDir, name).outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("无法打开图片")
            webView.evaluateJavascript("window.onImageSelected && window.onImageSelected(${JSONObject.quote(name)})", null)
        } catch (e: Exception) {
            android.util.Log.e("[AppBridge]", "Failed to copy image", e)
            File(wallpapersDir, name).delete()
            webView.evaluateJavascript(
                "window.onImageSelected && window.onImageSelected(null, ${JSONObject.quote(e.message ?: "图片保存失败")})", null
            )
        }
    }

    /**
     * 保存裁剪结果（JPEG Data URL）为 wp_<时间戳>.jpg，删掉 pick 文件。不写配置。
     * @return 新文件名，失败返回空字符串
     */
    @JavascriptInterface
    fun saveCroppedImage(pickedName: String, croppedDataUrl: String): String {
        return try {
            val base64 = croppedDataUrl.substringAfter("base64,", croppedDataUrl)
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val name = WallpaperFiles.newName("wp", "jpg")
            wallpapersDir.mkdirs()
            File(wallpapersDir, name).writeBytes(bytes)
            discardPickedImage(pickedName)
            android.util.Log.d("[AppBridge]", "Cropped image saved: $name (${bytes.size} bytes)")
            name
        } catch (e: Throwable) {
            // 大图的 base64 很大，OutOfMemoryError 也要接住，界面提示保存失败而不是崩溃
            android.util.Log.e("[AppBridge]", "saveCroppedImage error", e)
            ""
        }
    }

    /** 取消裁剪（或裁剪完）时删掉选图时复制的 pick 文件 */
    @JavascriptInterface
    fun discardPickedImage(pickedName: String) {
        val name = safeName(pickedName) ?: return
        if (name.startsWith("pick_")) File(wallpapersDir, name).delete()
    }

    // ==================== 深度图 ====================

    /**
     * 使用 Depth Anything V2 AI 模型生成深度图（不写配置）。
     * 进度通过 window.onDepthProgress(percent, text) 通知，
     * 完成后通过 window.onAIDepthReady(fileName, error) 通知。
     */
    @JavascriptInterface
    fun generateAIDepthMap(imageName: String, blurRadius: Int) {
        android.util.Log.d("[AppBridge]", "generateAIDepthMap: $imageName (blur $blurRadius)")
        scope.launch {
            try {
                val name = safeName(imageName)
                val imageFile = name?.let { File(wallpapersDir, it) }
                if (imageFile == null || !imageFile.exists()) {
                    fireAIDepthReady(null, "图片文件不存在")
                    return@launch
                }
                val depthFileName = depthEstimator.estimateDepth(imageFile, depthDir, blurRadius) { percent, text ->
                    callJs("window.onDepthProgress && window.onDepthProgress($percent, ${JSONObject.quote(text)})")
                }
                if (depthFileName != null) {
                    fireAIDepthReady(depthFileName, null)
                } else {
                    fireAIDepthReady(null, depthEstimator.lastError ?: "ONNX 推理失败 (无详细错误)")
                }
            } catch (e: Exception) {
                android.util.Log.e("[AppBridge]", "generateAIDepthMap 异常", e)
                fireAIDepthReady(null, e.message ?: "未知异常")
            }
        }
    }

    /** 用 JSONObject.quote() 转义，错误消息里的特殊字符不会破坏 JS 语法 */
    private fun fireAIDepthReady(fileName: String?, error: String?) {
        val f = fileName?.let { JSONObject.quote(it) } ?: "null"
        val e = error?.let { JSONObject.quote(it) } ?: "null"
        callJs("window.onAIDepthReady && window.onAIDepthReady($f, $e)")
    }

    // ==================== 应用壁纸 ====================

    /**
     * 写配置（壁纸服务监听到后马上换）。
     * @return 本应用已是当前动态壁纸返回 true；否则打开本应用的动态壁纸预览页并返回 false
     */
    @JavascriptInterface
    fun applyWallpaper(imageName: String, depthFile: String): Boolean {
        val image = safeName(imageName) ?: return false
        configManager.setWallpaper(image, safeName(depthFile) ?: "")
        if (activity.isOurWallpaperActive()) return true
        activity.runOnUiThread { activity.openLiveWallpaperPicker() }
        return false
    }

    /** 跳到本应用的动态壁纸预览页（设置页"设为壁纸 / 重新设置"） */
    @JavascriptInterface
    fun openLiveWallpaperPicker() {
        activity.runOnUiThread { activity.openLiveWallpaperPicker() }
    }

    /** {"wallpaperActive": Boolean, "batteryOptimizationIgnored": Boolean} */
    @JavascriptInterface
    fun getStatus(): String = JSONObject().apply {
        put("wallpaperActive", activity.isOurWallpaperActive())
        put("batteryOptimizationIgnored", activity.isIgnoringBatteryOptimizations())
    }.toString()

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
     * JS 端用于小米提示、预览比例、"关于"等。
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
            put("androidVersion", android.os.Build.VERSION.RELEASE)
            put("manufacturer", android.os.Build.MANUFACTURER)
            put("model", android.os.Build.MODEL)
            put("versionName", BuildConfig.VERSION_NAME)
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
