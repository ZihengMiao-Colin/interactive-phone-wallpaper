package com.wallpaper.live3d.wallpaper

import android.content.Context
import android.webkit.JavascriptInterface
import com.wallpaper.live3d.config.ConfigManager

/**
 * ============================================================================
 * WallpaperBridge — 壁纸 WebView 的 @JavascriptInterface 桥接
 * ============================================================================
 *
 * 暴露为 "AndroidBridge" 给 wallpaper_render.js 调用。
 *
 * 关键原则:
 *   - 不传输任何图片数据 (图片通过 WebViewAssetLoader URL 加载)
 *   - 只暴露壁纸服务所需的最小 API 集；配置只读，写配置是 App 的事
 */
class WallpaperBridge(context: Context) {

    private val configManager = ConfigManager(context)

    /** 返回完整配置 JSON（参数 + currentImage + depthFile） */
    @JavascriptInterface
    fun getConfig(): String {
        return configManager.getAll().toString()
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
