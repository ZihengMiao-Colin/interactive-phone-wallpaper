/**
 * ============================================================================
 * wallpaper/wallpaper_render.js — 壁纸服务 WebView 入口
 * ============================================================================
 *
 * 在 WallpaperService 的 WebView 中运行。极轻量：无 Transformers.js、无 UI。
 * 职责：
 *   1. 接收 Kotlin 推送的 init 命令 (CoreEngine.init + 监听器绑定)
 *   2. 接收原生 SensorManager (evaluateJavascript) 推送的倾斜数据
 *   3. 接收 onOffsetsChanged 桌面滑动偏移
 *   4. 处理生命周期回调 (pause/resume)
 *   5. 触摸降级 (touchmove → CoreEngine.setSensorTarget)
 *
 * 启动流程:
 *   - 页面加载时: 从 AndroidBridge 读取配置参数 (视差强度等)
 *   - onPageFinished 后: Kotlin 推送 startWallpaper(bgUrl, depthUrl) 触发渲染
 *   - 避免 JS 自动 init 与 Kotlin 推送 init 冲突导致双次初始化
 */

(function() {
    'use strict';

    var ASSET_BASE = 'https://appassets.androidplatform.net/local/';
    var WALLPAPER_DIR = ASSET_BASE + 'wallpapers/';
    var DEPTH_CACHE_DIR = ASSET_BASE + 'depth_cache/';

    // 当前壁纸文件名
    var currentWallpaperFile = '';
    var currentDepthFile = null;
    var isInitialized = false;

    console.log('[Wallpaper] 壁纸服务 WebView 启动');

    // ==================== 配置加载 (页面加载时执行) ====================

    function loadConfigParams() {
        var configJson = '{}';
        if (window.AndroidBridge && typeof AndroidBridge.getConfig === 'function') {
            try {
                configJson = AndroidBridge.getConfig();
            } catch (e) {
                console.warn('[Wallpaper] getConfig 失败:', e);
            }
        }

        try {
            var config = JSON.parse(configJson);
            if (config.parallaxMultiplier) WallpaperConfig.parallaxMultiplier = config.parallaxMultiplier;
            if (config.smoothness !== undefined) WallpaperConfig.smoothness = config.smoothness;
            if (config.currentImage) currentWallpaperFile = config.currentImage;
            if (config.depthFile) currentDepthFile = config.depthFile;
        } catch (e) {
            console.warn('[Wallpaper] 配置解析失败, 使用默认值');
        }

        console.log('[Wallpaper] 配置已加载: ' + currentWallpaperFile
            + ' (深度图: ' + (currentDepthFile || '无') + ')');
    }

    // ==================== 触摸降级 ====================

    function setupTouchFallback() {
        window.addEventListener('touchmove', function(e) {
            e.preventDefault();
            var touch = e.touches[0];
            var x = (touch.clientX / window.innerWidth) * 2 - 1;
            var y = (touch.clientY / window.innerHeight) * 2 - 1;
            CoreEngine.setSensorTarget(x, y);
        }, { passive: false });

        window.addEventListener('touchend', function() {
            CoreEngine.setSensorTarget(0, 0);
        });
    }

    // ==================== 壁纸初始化 (由 Kotlin onPageFinished 调用) ====================

    /**
     * 启动壁纸渲染。由 Kotlin 在 onPageFinished 时通过 evaluateJavascript 调用。
     *
     * @param {string} bgUrl    - 完整的背景图 URL (Kotlin 侧拼接)
     * @param {string} depthUrl - 完整的深度图 URL，可以为 "null" 字符串
     */
    window.startWallpaper = function(bgUrl, depthUrl) {
        if (isInitialized) {
            console.log('[Wallpaper] 已初始化，热替换壁纸');
            CoreEngine.reloadImages(bgUrl, depthUrl === 'null' ? null : depthUrl);
            return;
        }

        console.log('[Wallpaper] 启动渲染: ' + bgUrl);

        var depth = (depthUrl && depthUrl !== 'null') ? depthUrl : null;

        CoreEngine.init(bgUrl, depth, 'wallpaper').then(function() {
            isInitialized = true;
            console.log('[Wallpaper] 渲染引擎就绪');

            // 设置监听器 (仅首次 init 需要)
            setupTouchFallback();

            window.addEventListener('resize', function() {
                CoreEngine.resize();
            });

            // 通知原生层渲染就绪
            if (window.AndroidBridge && typeof AndroidBridge.notifyReady === 'function') {
                AndroidBridge.notifyReady();
            }

            console.log('[Wallpaper] 启动完成，等待传感器数据...');
        }).catch(function(err) {
            console.error('[Wallpaper] 初始化失败:', err);

            // 深度图缺失时用占位图重试
            if (depth === null) {
                console.warn('[Wallpaper] 重试: 使用灰色占位深度图');
                CoreEngine.init(bgUrl, null, 'wallpaper').then(function() {
                    isInitialized = true;
                    setupTouchFallback();
                    window.addEventListener('resize', function() {
                        CoreEngine.resize();
                    });
                    if (window.AndroidBridge && typeof AndroidBridge.notifyReady === 'function') {
                        AndroidBridge.notifyReady();
                    }
                }).catch(function(e2) {
                    console.error('[Wallpaper] 二次初始化也失败:', e2);
                });
            }
        });
    };

    // 壁纸热替换（用户换了壁纸后，Kotlin 通知 JS 重新加载）
    window.reloadWallpaperImages = function(newWallpaperFile, newDepthFile) {
        currentWallpaperFile = newWallpaperFile || currentWallpaperFile;
        currentDepthFile = newDepthFile || null;

        var bgUrl = WALLPAPER_DIR + currentWallpaperFile;
        var depthUrl = currentDepthFile
            ? (DEPTH_CACHE_DIR + currentDepthFile)
            : null;

        console.log('[Wallpaper] 热替换壁纸: ' + currentWallpaperFile);
        CoreEngine.reloadImages(bgUrl, depthUrl);
    };

    // ==================== 调试：每秒帧数 ====================

    // 调试版每秒往 logcat 打一次这一秒渲染了几帧，静止时应为 0（adb logcat -s "[WallpaperJS]"）
    function startFpsLog() {
        if (!window.AndroidBridge || typeof AndroidBridge.isDebug !== 'function') return;
        try {
            if (!AndroidBridge.isDebug()) return;
        } catch (e) {
            return;
        }
        var last = CoreEngine.getFrameCount();
        setInterval(function() {
            var now = CoreEngine.getFrameCount();
            AndroidBridge.log('fps=' + (now - last));
            last = now;
        }, 1000);
    }

    // ==================== 自动加载配置 ====================

    // 页面加载时读取配置参数 (不触发 CoreEngine.init)
    // CoreEngine.init 由 Kotlin 在 onPageFinished 后显式推送
    loadConfigParams();
    startFpsLog();

})();
