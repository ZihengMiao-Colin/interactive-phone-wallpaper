package com.wallpaper.live3d.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * ============================================================================
 * WebViewSetup — WebView 统一配置 (WebViewAssetLoader + 硬件加速)
 * ============================================================================
 *
 * 核心职责:
 *   1. 配置 WebViewAssetLoader 将 https://appassets.androidplatform.net/ 域名
 *      映射到本地 assets/ 和 filesDir，实现图片零拷贝加载
 *   2. 启用硬件加速 (WebGL 必需)
 *   3. 统一 JavaScript / DOM Storage 设置
 *
 * JS 端使用示例:
 *   // 加载 assets/ 中的打包文件
 *   fetch("https://appassets.androidplatform.net/assets/wallpaper/wallpaper.html")
 *
 *   // 加载内部存储中的用户文件
 *   PIXI.Texture.from("https://appassets.androidplatform.net/local/wallpapers/current.jpg")
 *   PIXI.Texture.from("https://appassets.androidplatform.net/local/depth_cache/depth_abc.png")
 */
object WebViewSetup {

    private const val ASSET_DOMAIN = "appassets.androidplatform.net"

    /**
     * 创建 WebViewAssetLoader。
     * 暴露给需要自定义 WebViewClient 的调用方 (如 WallpaperPresentation)。
     */
    fun createAssetLoader(context: Context): WebViewAssetLoader {
        return WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            // 打包资源: https://appassets.../assets/wallpaper/pixi.min.js
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            // 本地文件: https://appassets.../local/wallpapers/current.jpg
            .addPathHandler("/local/", WebViewAssetLoader.InternalStoragePathHandler(
                context, context.filesDir
            ))
            .build()
    }

    fun apply(context: Context, webView: WebView) {
        val assetLoader = createAssetLoader(context)

        webView.apply {
            // JavaScript 支持
            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 关闭 file:// 访问 (安全加固)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // 硬件加速 (WebGL 必需)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // 拦截 HTTP 请求 → WebViewAssetLoader 处理
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    return assetLoader.shouldInterceptRequest(request.url)
                }
            }
        }
    }
}
