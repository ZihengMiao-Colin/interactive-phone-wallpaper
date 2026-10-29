package com.wallpaper.live3d.wallpaper

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.DisplayMetrics
import android.view.Display
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import com.wallpaper.live3d.config.ConfigManager
import com.wallpaper.live3d.ui.WebViewSetup
import com.wallpaper.live3d.util.SensorHelper

/**
 * ============================================================================
 * LiveWallpaperService — 基于 VirtualDisplay + Presentation 的动态壁纸服务
 * ============================================================================
 *
 * 核心架构:
 *   - VirtualDisplay 将 Surface 暴露为虚拟屏幕，获得完整硬件加速
 *   - WallpaperPresentation 在虚拟屏幕上运行 WebView (支持 WebGL / Pixi.js)
 *   - SensorManager (TYPE_GRAVITY) 原生采样 → evaluateJavascript 推送 JS
 *   - onOffsetsChanged 桌面滑动偏移 → 附加水平视差
 *   - onVisibilityChanged → WebView onResume/onPause + 传感器停启
 *
 * 与旧实现的区别:
 *   - 不再使用 TYPE_APPLICATION_OVERLAY (需要悬浮窗权限且不稳定)
 *   - 不再使用软件渲染的 Canvas 绘制 (无 WebGL 支持，Pixi.js 会崩溃)
 *   - VirtualDisplay 为 WebView 提供了完整的硬件加速 GPU 上下文
 */
class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = WallpaperEngine()

    inner class WallpaperEngine : Engine() {

        // VirtualDisplay + Presentation
        private var virtualDisplay: VirtualDisplay? = null
        private var presentation: WallpaperPresentation? = null

        // WebView 引用 (由 Presentation 创建，Engine 持有用于传感器推送)
        private var webView: WebView? = null

        // 传感器
        private var sensorHelper: SensorHelper? = null
        private var sensorRunning = false

        // 线程安全
        private val mainHandler = Handler(Looper.getMainLooper())
        private val configManager by lazy { ConfigManager(this@LiveWallpaperService) }

        // 屏幕尺寸
        private var displayWidth = 0
        private var displayHeight = 0
        private var displayDensityDpi = 320

        // ==================== Lifecycle: onCreate ====================

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            setTouchEventsEnabled(true)
            surfaceHolder.setFormat(PixelFormat.RGBA_8888)
            // 不调用 setFixedSize — 使用实际屏幕尺寸

            // 获取屏幕尺寸和密度
            val dm = resources.displayMetrics
            displayWidth = dm.widthPixels
            displayHeight = dm.heightPixels
            displayDensityDpi = dm.densityDpi

            // 初始化传感器
            sensorHelper = SensorHelper(this@LiveWallpaperService)
        }

        // ==================== Lifecycle: onSurfaceCreated ====================

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            mainHandler.post {
                createVirtualDisplay(holder)
            }
        }

        // ==================== Lifecycle: onSurfaceChanged ====================

        override fun onSurfaceChanged(
            holder: SurfaceHolder, format: Int, width: Int, height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            // VirtualDisplay 已创建，无需重建
            mainHandler.post {
                pushToJs("if(window.CoreEngine) CoreEngine.resize();")
            }
        }

        // ==================== Lifecycle: onSurfaceDestroyed ====================

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            mainHandler.post {
                destroyVirtualDisplay()
            }
        }

        // ==================== Lifecycle: onDestroy ====================

        override fun onDestroy() {
            stopSensor()
            mainHandler.post {
                destroyVirtualDisplay()
                destroyWebView()
            }
            super.onDestroy()
        }

        // ==================== Lifecycle: onVisibilityChanged ====================

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            mainHandler.post {
                if (visible) {
                    presentation?.handleResume()
                    pushToJs("if(window.CoreEngine) CoreEngine.resume();")
                    startSensor()
                } else {
                    stopSensor()
                    pushToJs("if(window.CoreEngine) CoreEngine.pause();")
                    presentation?.handlePause()
                }
            }
        }

        // ==================== 桌面滑动偏移 ====================

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xStep: Float, yStep: Float,
            xPixels: Int, yPixels: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xStep, yStep, xPixels, yPixels)
            pushToJs("if(window.CoreEngine) CoreEngine.setDesktopOffset($xOffset);")
        }

        // ==================== 触摸事件 (降级输入) ====================

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN) {
                val wv = webView ?: return
                val normX = (event.x / (wv.width.coerceAtLeast(1))).toFloat() * 2f - 1f
                val normY = (event.y / (wv.height.coerceAtLeast(1))).toFloat() * 2f - 1f
                pushToJs("CoreEngine.setSensorTarget($normX,$normY);")
            } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                pushToJs("CoreEngine.setSensorTarget(0,0);")
            }
        }

        // ==================== VirtualDisplay 管理 ====================

        private fun createVirtualDisplay(holder: SurfaceHolder) {
            // 避免重复创建
            if (virtualDisplay != null) return

            try {
                val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

                virtualDisplay = dm.createVirtualDisplay(
                    "Wallpaper3D_VirtualDisplay",
                    displayWidth,
                    displayHeight,
                    displayDensityDpi,
                    holder.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                            or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                )

                virtualDisplay?.let { vd ->
                    // 创建 Presentation 并显示
                    presentation = WallpaperPresentation(
                        this@LiveWallpaperService,
                        vd.display
                    )
                    presentation?.show()
                }
            } catch (e: Exception) {
                android.util.Log.e("[Wallpaper]", "VirtualDisplay 创建失败", e)
            }
        }

        private fun destroyVirtualDisplay() {
            presentation?.let {
                try {
                    it.handleDestroy()
                    it.dismiss()
                } catch (_: Exception) {}
            }
            presentation = null

            virtualDisplay?.let {
                try { it.release() } catch (_: Exception) {}
            }
            virtualDisplay = null

            webView = null
        }

        private fun destroyWebView() {
            // 兜底清理：如果 presentation 未正常销毁
            webView?.let { wv ->
                try {
                    wv.removeJavascriptInterface("AndroidBridge")
                    wv.destroy()
                } catch (_: Exception) {}
            }
            webView = null
        }

        // ==================== 传感器管理 ====================

        private fun startSensor() {
            if (sensorRunning) return
            sensorHelper?.start { x, y ->
                pushToJs("CoreEngine.setSensorTarget($x,$y);")
            }
            sensorRunning = true
        }

        private fun stopSensor() {
            sensorHelper?.stop()
            sensorRunning = false
        }

        // ==================== evaluateJavascript 主线程保护 ====================

        /**
         * 安全地向 WebView 推送 JS 代码。
         * 强制在主线程执行，防止 SensorManager 后台线程调用崩溃。
         */
        private fun pushToJs(script: String) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                webView?.evaluateJavascript(script, null)
            } else {
                mainHandler.post {
                    webView?.evaluateJavascript(script, null)
                }
            }
        }

        /**
         * 从 ConfigManager 读取当前壁纸配置并推送 startWallpaper 到 JS。
         *
         * 这是关键步骤：wallpaper_render.js 在页面加载时只读取配置参数，
         * 不触发 CoreEngine.init。渲染必须由 Kotlin 在 onPageFinished 后
         * 显式推送，确保使用最新的壁纸文件路径。
         */
        private fun pushWallpaperInit() {
            val currentImage = configManager.getString(
                ConfigManager.KEY_CURRENT_IMAGE, ""
            )
            if (currentImage.isEmpty()) {
                android.util.Log.w("[Wallpaper]", "尚未选择壁纸图片，跳过 init")
                return
            }

            val depthFile = configManager.getString(
                ConfigManager.KEY_DEPTH_FILE, ""
            )

            val assetBase = "https://${ASSET_DOMAIN}${ASSET_PATH_PREFIX}"
            val bgUrl = escapeJs("$assetBase/wallpapers/$currentImage")
            val depthUrl = if (depthFile.isNotEmpty()) {
                escapeJs("$assetBase/depth_cache/$depthFile")
            } else {
                "null"
            }

            // 调用 JS 的 startWallpaper(bgUrl, depthUrl)
            // 该函数会处理 CoreEngine.init + 监听器绑定
            val initScript = "window.startWallpaper('$bgUrl', '$depthUrl');"

            android.util.Log.d("[Wallpaper]", "推送 startWallpaper: bg=$bgUrl depth=$depthUrl")
            pushToJs(initScript)
        }

        private fun escapeJs(s: String): String {
            return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        }

        // ==================== WallpaperPresentation ====================

        /**
         * WallpaperPresentation — 在 VirtualDisplay 上承载 WebView
         *
         * 运行在虚拟屏幕上的 Presentation，拥有独立的窗口上下文，
         * WebView 在此获得完整的硬件加速 + WebGL 支持。
         */
        @SuppressLint("SetJavaScriptEnabled")
        inner class WallpaperPresentation(
            outerContext: Context,
            display: Display
        ) : Presentation(outerContext, display) {

            private var presentationWebView: WebView? = null

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)

                val wv = WebView(context)

                // ---- WebView 基础配置 ----
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.allowFileAccess = false
                wv.settings.allowContentAccess = false
                wv.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                wv.settings.mixedContentMode =
                    android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                // 硬件加速 (WebGL 必需)
                wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)

                // ---- WebViewAssetLoader ----
                val assetLoader = WebViewSetup.createAssetLoader(
                    this@LiveWallpaperService
                )

                // ---- WallpaperBridge ----
                val bridge = WallpaperBridge(this@LiveWallpaperService)
                wv.addJavascriptInterface(bridge, "AndroidBridge")

                // ---- WebViewClient: 资产拦截 + onPageFinished ----
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: android.webkit.WebResourceRequest
                    ): android.webkit.WebResourceResponse? {
                        return assetLoader.shouldInterceptRequest(request.url)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        android.util.Log.d("[Wallpaper]", "WebView 页面加载完成: $url")

                        // ★ 关键步骤: 推送壁纸初始化命令 ★
                        // 必须在 onPageFinished 后调用，因为此时 JS 环境才就绪
                        pushWallpaperInit()
                    }
                }

                // 保存引用
                presentationWebView = wv
                webView = wv  // Engine 持有引用，用于传感器推送

                // 将 WebView 设为 Presentation 的内容视图
                setContentView(wv)

                // 加载壁纸 HTML
                wv.loadUrl("https://${ASSET_DOMAIN}/assets/wallpaper/wallpaper.html")
            }

            /** WebView onResume — 由 Engine.onVisibilityChanged 调用 */
            fun handleResume() {
                presentationWebView?.onResume()
                presentationWebView?.resumeTimers()
            }

            /** WebView onPause — 由 Engine.onVisibilityChanged 调用 */
            fun handlePause() {
                presentationWebView?.onPause()
                presentationWebView?.pauseTimers()
            }

            /** 销毁 WebView */
            fun handleDestroy() {
                presentationWebView?.let { wv ->
                    try {
                        wv.removeJavascriptInterface("AndroidBridge")
                        wv.destroy()
                    } catch (_: Exception) {}
                }
                presentationWebView = null
                webView = null
            }
        }
    }

    companion object {
        const val ASSET_DOMAIN = "appassets.androidplatform.net"
        const val ASSET_PATH_PREFIX = "/local/"
    }
}
