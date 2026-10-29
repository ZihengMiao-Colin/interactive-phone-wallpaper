package com.wallpaper.live3d.wallpaper

import android.content.Context
import android.webkit.JavascriptInterface
import com.wallpaper.live3d.config.ConfigManager
import com.wallpaper.live3d.depth.DepthCacheManager

/**
 * ============================================================================
 * WallpaperBridge — 壁纸 WebView 的 @JavascriptInterface 桥接
 * ============================================================================
 *
 * 暴露为 "AndroidBridge" 给 wallpaper_render.js 调用。
 *
 * 关键原则:
 *   - 所有方法同步返回 String
 *   - 不传输任何图片数据 (图片通过 WebViewAssetLoader URL 加载)
 *   - 只暴露壁纸服务所需的最小 API 集
 */
class WallpaperBridge(private val context: Context) {

    private val configManager = ConfigManager(context)
    private val depthCacheManager = DepthCacheManager(context)

    // ==================== 配置读写 ====================

    /**
     * 返回完整配置 JSON。
     * 包含: parallaxMultiplier, smoothness, deadZoneDeg, maxTiltDeg,
     *       currentImage, depthFile
     */
    @JavascriptInterface
    fun getConfig(): String {
        return configManager.getAll().toString()
    }

    /**
     * 保存部分配置 (JSON 格式)。
     * 示例: {"parallaxMultiplier": 45, "smoothness": 0.12}
     */
    @JavascriptInterface
    fun saveConfig(json: String) {
        try {
            val config = org.json.JSONObject(json)
            val editor = configManager.edit()
            if (config.has("parallaxMultiplier"))
                editor.putInt("parallaxMultiplier", config.getInt("parallaxMultiplier"))
            if (config.has("smoothness"))
                editor.putFloat("smoothness", config.getDouble("smoothness").toFloat())
            if (config.has("deadZoneDeg"))
                editor.putFloat("deadZoneDeg", config.getDouble("deadZoneDeg").toFloat())
            if (config.has("maxTiltDeg"))
                editor.putFloat("maxTiltDeg", config.getDouble("maxTiltDeg").toFloat())
            if (config.has("currentImage"))
                editor.putString("currentImage", config.getString("currentImage"))
            if (config.has("depthFile"))
                editor.putString("depthFile", config.getString("depthFile"))
            editor.apply()
        } catch (e: Exception) {
            android.util.Log.e("[WallpaperBridge]", "saveConfig error", e)
        }
    }

    // ==================== 深度图缓存 (仅限元数据，不传图片数据) ====================

    /**
     * 返回当前壁纸对应的深度图缓存文件名 (不含路径)。
     * 前端通过 WebViewAssetLoader 构建完整 URL:
     *   https://appassets.androidplatform.net/local/depth_cache/{fileName}
     *
     * @param imageName 壁纸文件名
     * @return 深度图缓存文件名，或空字符串
     */
    @JavascriptInterface
    fun getDepthFileName(imageName: String): String {
        return depthCacheManager.getDepthFileName(imageName) ?: ""
    }

    /**
     * 检查给定壁纸是否有缓存的深度图。
     */
    @JavascriptInterface
    fun hasDepthMap(imageName: String): Boolean {
        return depthCacheManager.hasDepthMap(imageName)
    }

    // ==================== 工具 ====================

    @JavascriptInterface
    fun log(message: String) {
        android.util.Log.d("[WallpaperJS]", message)
    }

    /** 调试版返回 true（壁纸页据此每秒打一次帧数） */
    @JavascriptInterface
    fun isDebug(): Boolean = com.wallpaper.live3d.BuildConfig.DEBUG

    @JavascriptInterface
    fun notifyReady() {
        android.util.Log.i("[Wallpaper]", "JS rendering engine ready")
    }

    /**
     * 返回 WebViewAssetLoader 的基础 URL。
     * JS 端用此构建图片 URL:
     *   baseUrl + "wallpapers/current.jpg"
     */
    @JavascriptInterface
    fun getAssetBaseUrl(): String {
        return "https://${LiveWallpaperService.ASSET_DOMAIN}${LiveWallpaperService.ASSET_PATH_PREFIX}"
    }
}
