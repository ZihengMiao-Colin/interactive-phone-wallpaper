/**
 * ============================================================================
 * app/app_ui.js — 主界面 WebView 逻辑
 * ============================================================================
 *
 * 在 MainActivity 的 WebView 中运行。职责：
 *   1. 图片选择 (通过 AppBridge → 系统文件选择器)
 *   2. Pixi.js 预览渲染 (CoreEngine 'preview' 模式)
 *   3. 深度图生成 (可选 Transformers.js 集成)
 *   4. 设置壁纸 (AppBridge.setAsWallpaper → 系统壁纸选择器)
 *   5. 配置滑块与持久化
 *   6. MIUI 电池优化引导
 */

(function() {
    'use strict';

    var $ = function(id) { return document.getElementById(id); };

    var ASSET_BASE = 'https://appassets.androidplatform.net/local/';
    var WALLPAPER_DIR = ASSET_BASE + 'wallpapers/';
    var DEPTH_CACHE_DIR = ASSET_BASE + 'depth_cache/';

    // 状态
    var currentWallpaperFile = null;
    var currentDepthFile = null;
    var isGenerating = false;
    var previewActive = false;

    // ==================== 初始化 ====================

    document.addEventListener('DOMContentLoaded', function() {
        // 加载配置
        loadConfig();

        // 检查平台信息（MIUI 检测等）
        checkPlatform();

        // 绑定 UI 事件
        bindUI();

        console.log('[AppUI] 主界面初始化完成');
    });

    function loadConfig() {
        var configJson = '{}';
        if (window.AppBridge && typeof AppBridge.getConfig === 'function') {
            try {
                configJson = AppBridge.getConfig();
            } catch (e) {}
        }

        try {
            var config = JSON.parse(configJson);
            if (config.parallaxMultiplier) {
                WallpaperConfig.parallaxMultiplier = config.parallaxMultiplier;
                $('parallax-strength').value = config.parallaxMultiplier;
                $('parallax-value').textContent = config.parallaxMultiplier;
            }
            if (config.smoothness !== undefined) {
                WallpaperConfig.smoothness = config.smoothness;
                $('smoothness').value = Math.round(config.smoothness * 100);
                $('smoothness-value').textContent = config.smoothness.toFixed(2);
            }
            if (config.currentImage) {
                currentWallpaperFile = config.currentImage;
            }
            if (config.depthFile) {
                currentDepthFile = config.depthFile;
            }
            if (config.deadZoneDeg) {
                WallpaperConfig.deadZoneDeg = config.deadZoneDeg;
                $('deadzone').value = config.deadZoneDeg;
                $('deadzone-value').textContent = config.deadZoneDeg.toFixed(1) + '°';
            }
            if (config.maxTiltDeg) {
                WallpaperConfig.maxTiltDeg = config.maxTiltDeg;
                $('maxtilt').value = config.maxTiltDeg;
                $('maxtilt-value').textContent = config.maxTiltDeg + '°';
            }
        } catch (e) {}

        // 如果有上次使用的壁纸，自动加载预览
        if (currentWallpaperFile) {
            loadPreview(currentWallpaperFile, currentDepthFile);
        }
    }

    /** 设备真实屏幕分辨率 (px)，裁剪时用作锁定比例 */
    var deviceScreenW = 0;
    var deviceScreenH = 0;

    function checkPlatform() {
        if (window.AppBridge && typeof AppBridge.getPlatformInfo === 'function') {
            try {
                var info = JSON.parse(AppBridge.getPlatformInfo());
                // 缓存设备分辨率
                deviceScreenW = info.screenWidth || 0;
                deviceScreenH = info.screenHeight || 0;
                console.log('[AppUI] 设备分辨率: ' + deviceScreenW + 'x' + deviceScreenH);

                if (info.isXiaomi) {
                    $('miui-hint').style.display = 'block';
                    $('platform-hint').textContent =
                        '检测到 ' + info.manufacturer + ' ' + info.model + ' (API ' + info.apiLevel + ')';
                } else {
                    $('platform-hint').textContent =
                        info.manufacturer + ' ' + info.model;
                }
            } catch (e) {}
        }
    }

    // ==================== UI 绑定 ====================

    function bindUI() {
        // 滑块
        $('parallax-strength').addEventListener('input', function() {
            var val = parseInt(this.value, 10);
            WallpaperConfig.parallaxMultiplier = val;
            $('parallax-value').textContent = val;
            saveConfigDebounced();
        });

        $('smoothness').addEventListener('input', function() {
            var val = parseInt(this.value, 10) / 100;
            WallpaperConfig.smoothness = val;
            $('smoothness-value').textContent = val.toFixed(2);
            saveConfigDebounced();
        });

        $('deadzone').addEventListener('input', function() {
            var val = parseFloat(this.value);
            WallpaperConfig.deadZoneDeg = val;
            $('deadzone-value').textContent = val.toFixed(1) + '°';
            saveConfigDebounced();
        });

        $('maxtilt').addEventListener('input', function() {
            var val = parseInt(this.value, 10);
            WallpaperConfig.maxTiltDeg = val;
            $('maxtilt-value').textContent = val + '°';
            saveConfigDebounced();
        });

        // 选择图片
        $('btn-select-image').addEventListener('click', function() {
            if (window.AppBridge && typeof AppBridge.openImagePicker === 'function') {
                AppBridge.openImagePicker();
            } else {
                alert('图片选择仅在 Android 应用中可用');
            }
        });

        // 生成深度图
        $('btn-generate-depth').addEventListener('click', function() {
            generateDepthMap();
        });

        // 设为壁纸
        $('btn-set-wallpaper').addEventListener('click', function() {
            if (window.AppBridge && typeof AppBridge.applyWallpaper === 'function') {
                // 保存当前配置
                saveConfig();
                AppBridge.applyWallpaper(currentWallpaperFile, currentDepthFile || '');
            } else {
                alert('设置壁纸功能仅在 Android 应用中可用');
            }
        });

        // 裁剪按钮
        $('btn-crop-confirm').addEventListener('click', confirmCrop);
        $('btn-crop-cancel').addEventListener('click', cancelCrop);

        // 电池优化
        $('btn-battery-opt').addEventListener('click', function() {
            if (window.AppBridge && typeof AppBridge.requestBatteryOptimizationBypass === 'function') {
                AppBridge.requestBatteryOptimizationBypass();
            } else {
                alert('请手动前往: 设置 → 应用 → 互动3D壁纸 → 电池 → 无限制');
            }
        });
    }

    // ==================== 预览加载 ====================

    function loadPreview(wallpaperFile, depthFile) {
        var bgUrl = WALLPAPER_DIR + wallpaperFile;
        var depthUrl = depthFile ? (DEPTH_CACHE_DIR + depthFile) : null;

        // 记录当前选中的图片（按钮判断靠它）
        window.currentSelectedImage = wallpaperFile;

        // 隐藏占位提示
        $('preview-placeholder').style.display = 'none';

        // 启用「生成深度图」按钮并更新文字
        $('btn-generate-depth').disabled = false;
        $('btn-generate-depth').textContent = depthFile ? '重新生成深度图' : '生成深度图';

        // 启用「设为壁纸」按钮
        $('btn-set-wallpaper').disabled = false;

        // 即时反馈
        $('image-info').textContent = '正在加载: ' + wallpaperFile + '...';

        // 将 CoreEngine 的 canvas 移到预览容器中
        CoreEngine.init(bgUrl, depthUrl, 'preview').then(function() {
            previewActive = true;
            $('btn-set-wallpaper').disabled = false;

            // 预览模式：用触摸模拟传感器
            setupPreviewTouch();

            // 图片信息
            var app = CoreEngine.getApp();
            if (app) {
                $('image-info').textContent = '壁纸: ' + wallpaperFile
                    + ' | 窗口: ' + app.screen.width + 'x' + app.screen.height;
            } else {
                $('image-info').textContent = '壁纸: ' + wallpaperFile;
            }
        }).catch(function(err) {
            console.error('[AppUI] 预览加载失败:', err);
            $('image-info').textContent = '加载预览失败: ' + err.message;
        });
    }

    function setupPreviewTouch() {
        // 在预览区域内触摸移动 → 模拟传感器输入
        var container = $('preview-container');

        container.addEventListener('touchmove', function(e) {
            e.preventDefault();
            var touch = e.touches[0];
            var rect = container.getBoundingClientRect();
            var relX = (touch.clientX - rect.left) / rect.width;
            var relY = (touch.clientY - rect.top) / rect.height;
            var x = relX * 2 - 1;
            var y = relY * 2 - 1;
            CoreEngine.setSensorTarget(x, y);
        }, { passive: false });

        container.addEventListener('touchend', function() {
            CoreEngine.setSensorTarget(0, 0);
        });
    }

    // ==================== 深度图生成 ====================

    var _aiTimeoutId = null;  // AI 推理超时计时器

    function generateDepthMap() {
        if (isGenerating || !currentWallpaperFile) return;

        // 始终重新生成深度图（用户主动点击按钮即重新生成）
        console.log('[AppUI] 开始生成深度图: ' + currentWallpaperFile);
        isGenerating = true;
        $('btn-generate-depth').disabled = true;
        $('btn-generate-depth').textContent = 'AI 深度估计中...';
        $('depth-progress').style.display = 'block';
        $('depth-progress-text').textContent = '正在运行 Depth Anything V2...';

        // 优先尝试 AI 深度估计 (ONNX Runtime)
        if (window.AppBridge && typeof AppBridge.generateAIDepthMap === 'function') {
            console.log('[AppUI] 使用 AI 深度估计 (Depth Anything V2)');
            // 设置超时：30 秒内没有回调则降级
            clearTimeout(_aiTimeoutId);
            _aiTimeoutId = setTimeout(function() {
                if (!isGenerating) return;  // 已经完成了
                console.warn('[AppUI] AI 深度估计超时 (30s)，降级到 CV 算法');
                $('image-info').textContent = '[AI 超时] 30 秒未响应，降级使用传统 CV 深度估计';
                generateSimpleDepthMap();
            }, 30000);
            AppBridge.generateAIDepthMap(currentWallpaperFile, 8);
        } else {
            // 降级：传统 CV 算法
            console.log('[AppUI] AppBridge.generateAIDepthMap 不可用，降级到 CV 算法');
            generateSimpleDepthMap();
        }
    }

    /**
     * AI 深度估计完成回调 (由 AppBridge 在后台线程完成后调用)。
     * @param {string|null} depthFileName - 深度图文件名
     * @param {string|null} error - 错误信息
     */
    window.onAIDepthReady = function(depthFileName, error) {
        clearTimeout(_aiTimeoutId);  // 清除超时计时器

        if (error || !depthFileName) {
            var errDetail = error || 'unknown';
            console.warn('[AppUI] AI 深度估计失败: ' + errDetail + '，降级到 CV 算法');
            $('btn-generate-depth').textContent = '正在生成深度图...';
            $('depth-progress-text').textContent = 'AI 未就绪 (' + errDetail + ')，使用传统算法...';
            $('image-info').textContent = '[AI 失败] ' + errDetail + ' — 降级使用传统 CV 深度估计';
            // 降级到传统 CV
            generateSimpleDepthMap();
            return;
        }

        console.log('[AppUI] AI 深度估计完成: ' + depthFileName);
        currentDepthFile = depthFileName;
        $('depth-progress-text').textContent = 'AI 深度图已生成 (Depth Anything V2)';
        loadPreview(currentWallpaperFile, currentDepthFile);
        finishGeneration();
    };

    function generateSimpleDepthMap() {
        console.log('[AppUI] 使用 CV 深度图生成 (高分辨率 + 大模糊 → 平滑轮廓)');

        var bgUrl = WALLPAPER_DIR + currentWallpaperFile + '?t=' + Date.now();
        ImageLoader.loadImage(bgUrl).then(function(bgImg) {
            var imgW = bgImg.naturalWidth || bgImg.width;
            var imgH = bgImg.naturalHeight || bgImg.height;

            // ================================================================
            // 第 1 步：先做大模糊 → 抹掉纹理细节，只保留光影轮廓
            // ================================================================
            // 模糊半径按图片尺寸自适应：长边 / 80，保证人物/背景大块可见但纹理消失
            var blurRadius = Math.max(8, Math.round(Math.max(imgW, imgH) / 80));

            var canvas = document.createElement('canvas');
            canvas.width = imgW;
            canvas.height = imgH;
            var ctx = canvas.getContext('2d');

            // 先画原图
            ctx.drawImage(bgImg, 0, 0, imgW, imgH);
            // 重度模糊 → 纹理全部消失，只留大色块/光影
            ctx.filter = 'blur(' + blurRadius + 'px)';
            ctx.drawImage(canvas, 0, 0);
            ctx.filter = 'none';

            // ================================================================
            // 第 2 步：提取亮度 + 全范围归一化
            // ================================================================
            var imgData = ctx.getImageData(0, 0, imgW, imgH);
            var dp = imgData.data;
            var lumMin = 255, lumMax = 0;

            // 第一遍：找 min/max
            for (var i = 0; i < dp.length; i += 4) {
                var lum = 0.299 * dp[i] + 0.587 * dp[i + 1] + 0.114 * dp[i + 2];
                if (lum < lumMin) lumMin = lum;
                if (lum > lumMax) lumMax = lum;
            }

            var range = lumMax - lumMin;
            if (range < 1) range = 1;

            // 第二遍：归一化 + 反转 (暗→白/近景, 亮→黑/远景)
            for (var k = 0; k < dp.length; k += 4) {
                var lum2 = 0.299 * dp[k] + 0.587 * dp[k + 1] + 0.114 * dp[k + 2];
                var normalized = (lum2 - lumMin) / range;
                var depth = Math.round((1 - normalized) * 255);
                dp[k] = depth;
                dp[k + 1] = depth;
                dp[k + 2] = depth;
                dp[k + 3] = 255;
            }
            ctx.putImageData(imgData, 0, 0);

            // ================================================================
            // 第 2.5 步：形态学腐蚀 → 收缩前景边缘 2px
            // 目的：让前景边缘像素被判为背景，视差拉伸时扯的是背景不是人脸
            // ================================================================
            erodeDepthMap(ctx, imgW, imgH, 2);

            // ================================================================
            // 第 3 步：高斯模糊收尾 → 把硬边缘变成渐变斜坡
            // ================================================================
            ctx.filter = 'blur(3px)';
            ctx.drawImage(canvas, 0, 0);
            ctx.filter = 'none';

            console.log('[AppUI] 深度图已生成 (' + imgW + 'x' + imgH
                + ', 模糊半径 ' + blurRadius + 'px'
                + ', 亮度范围 ' + lumMin + '–' + lumMax + ' → 全范围 0–255)');

            saveDepthAndReload(canvas);
        }).catch(function(err) {
            console.error('[AppUI] 深度图生成失败:', err);
            $('image-info').textContent = '深度图生成失败: ' + (err.message || '图片加载错误');
            finishGeneration();
        });
    }

    /**
     * 灰度形态学腐蚀：对深度图前景（亮区）向内收缩 radius 像素。
     * 算法：每个像素取邻域内最小值 → 亮区缩小、暗区扩大。
     * 这样人物边缘的像素会被判定为"背景"，视差拉伸时就不会露出人脸轮廓。
     *
     * @param {CanvasRenderingContext2D} ctx - 深度图 canvas 上下文
     * @param {number} w - 画布宽度
     * @param {number} h - 画布高度
     * @param {number} radius - 腐蚀半径 (px)，推荐 2
     */
    function erodeDepthMap(ctx, w, h, radius) {
        var imgData = ctx.getImageData(0, 0, w, h);
        var src = imgData.data;
        var dst = new Uint8ClampedArray(src.length);

        for (var y = 0; y < h; y++) {
            var y0 = Math.max(0, y - radius);
            var y1 = Math.min(h - 1, y + radius);
            for (var x = 0; x < w; x++) {
                var minVal = 255;
                var x0 = Math.max(0, x - radius);
                var x1 = Math.min(w - 1, x + radius);
                for (var ny = y0; ny <= y1; ny++) {
                    var rowBase = ny * w * 4;
                    for (var nx = x0; nx <= x1; nx++) {
                        var v = src[rowBase + nx * 4];  // R 通道 (灰度图三通道相同)
                        if (v < minVal) minVal = v;
                    }
                }
                var idx = (y * w + x) * 4;
                dst[idx] = dst[idx + 1] = dst[idx + 2] = minVal;
                dst[idx + 3] = 255;
            }
        }

        // 写回
        for (var i = 0; i < src.length; i++) {
            src[i] = dst[i];
        }
        ctx.putImageData(imgData, 0, 0);
    }

    /** 将深度图 canvas 保存并刷新预览 */
    function saveDepthAndReload(canvas) {
        var depthDataUrl = canvas.toDataURL('image/png');

        if (window.AppBridge && typeof AppBridge.saveDepthMap === 'function') {
            var savedFileName = AppBridge.saveDepthMap(currentWallpaperFile, depthDataUrl);
            if (savedFileName) {
                currentDepthFile = savedFileName;
                console.log('[AppUI] 深度图已保存: ' + savedFileName);
            } else {
                console.error('[AppUI] 深度图保存失败 (saveDepthMap 返回空)');
                finishGeneration();
                return;
            }
        } else {
            console.warn('[AppUI] AppBridge.saveDepthMap 不可用，无法持久化深度图');
        }

        loadPreview(currentWallpaperFile, currentDepthFile);
        finishGeneration();
    }


    function finishGeneration() {
        isGenerating = false;
        $('btn-generate-depth').disabled = false;
        $('btn-generate-depth').textContent = currentDepthFile ? '重新生成深度图' : '生成深度图';
        $('depth-progress').style.display = 'none';

        // 保存配置
        saveConfig();

        $('image-info').textContent = '壁纸: ' + currentWallpaperFile
            + (currentDepthFile ? ' (已有深度图)' : '');
    }

    // ==================== 配置持久化 ====================

    var saveTimer = null;
    function saveConfigDebounced() {
        if (saveTimer) clearTimeout(saveTimer);
        saveTimer = setTimeout(saveConfig, 300);
    }

    function saveConfig() {
        if (!window.AppBridge || typeof AppBridge.saveConfig !== 'function') return;

        var config = {
            parallaxMultiplier: WallpaperConfig.parallaxMultiplier,
            smoothness: WallpaperConfig.smoothness,
            deadZoneDeg: WallpaperConfig.deadZoneDeg,
            maxTiltDeg: WallpaperConfig.maxTiltDeg,
            currentImage: currentWallpaperFile || '',
            depthFile: currentDepthFile || '',
        };

        try {
            AppBridge.saveConfig(JSON.stringify(config));
        } catch (e) {
            console.error('[AppUI] 保存配置失败:', e);
        }
    }

    // ==================== Kotlin → JS 回调 ====================

    /**
     * AppBridge.onImageSelected 会调用此函数。
     * 流程：选择图片 → 裁剪界面 → 用户确认 → 加载预览。
     *
     * @param {string} fileName  - 保存到内部存储的文件名
     * @param {string} dataUrl   - (保留参数，当前未使用)
     */
    window.onImageSelected = function(fileName, dataUrl) {
        console.log('[AppUI] 图片已选择: ' + fileName);

        // 保存之前的状态（取消裁剪时需要恢复）
        window._previousWallpaperFile = currentWallpaperFile;
        window._previousDepthFile = currentDepthFile;

        // 进入裁剪流程
        showCropper(fileName);
    };

    // ==================== 图片裁剪 (纯原生实现) ====================

    // 裁剪状态
    var cropState = {
        imgEl: null,      // 裁剪图片元素
        imgNaturalW: 0,   // 图片原始宽
        imgNaturalH: 0,   // 图片原始高
        imgDisplayW: 0,   // 图片在屏幕上的显示宽
        imgDisplayH: 0,   // 图片在屏幕上的显示高
        imgOffsetX: 0,    // 图片在 crop-area 中的水平偏移
        imgOffsetY: 0,    // 图片在 crop-area 中的垂直偏移
        cropW: 0,         // 裁剪框宽 (图片像素)
        cropH: 0,         // 裁剪框高 (图片像素)
        cropX: 0,         // 裁剪框左上角 X (图片像素)
        cropY: 0,         // 裁剪框左上角 Y (图片像素)
        aspectRatio: 1,   // 锁定比例
        touchMode: null,  // 'move' | 'resize' | null
        touchStart: null, // 起始触摸信息
    };

    /** 显示裁剪界面 */
    function showCropper(fileName) {
        currentWallpaperFile = fileName;
        currentDepthFile = null;

        // 隐藏主界面，显示裁剪界面
        $('main-content').style.display = 'none';
        $('crop-overlay').style.display = 'flex';

        // 通过 WebViewAssetLoader 加载图片
        var imgUrl = WALLPAPER_DIR + fileName + '?t=' + Date.now();
        var cropImage = $('crop-image');
        cropImage.src = imgUrl;

        cropImage.onload = function() {
            initCropUI(cropImage);
        };
        cropImage.onerror = function() {
            console.error('[AppUI] 裁剪图片加载失败，跳过裁剪');
            cancelCrop(true);
        };
    }

    /** 初始化裁剪 UI：计算图片布局 + 设置裁剪框 + 绑定触摸 */
    function initCropUI(imgEl) {
        var area = $('crop-area');
        var areaW = area.clientWidth;
        var areaH = area.clientHeight;
        var imgNatW = imgEl.naturalWidth;
        var imgNatH = imgEl.naturalHeight;

        // 锁定裁剪比例为设备屏幕比例
        var screenW = deviceScreenW || window.innerWidth;
        var screenH = deviceScreenH || window.innerHeight;
        var aspectRatio = screenW / screenH;

        // 图片适配：contain 模式（完整显示在 crop-area 内）
        var scale = Math.min(areaW / imgNatW, areaH / imgNatH);
        var displayW = imgNatW * scale;
        var displayH = imgNatH * scale;
        var offsetX = (areaW - displayW) / 2;
        var offsetY = (areaH - displayH) / 2;

        // 设置图片位置和尺寸
        imgEl.style.width = displayW + 'px';
        imgEl.style.height = displayH + 'px';
        imgEl.style.left = offsetX + 'px';
        imgEl.style.top = offsetY + 'px';

        // 初始裁剪框：取图片 80% 区域，居中
        var cropW, cropH;
        if (displayW / displayH > aspectRatio) {
            // 图片比屏幕宽 → 裁剪框高度撑满，宽度按比例
            cropH = displayH * 0.8;
            cropW = cropH * aspectRatio;
        } else {
            // 图片比屏幕窄/等宽 → 裁剪框宽度撑满，高度按比例
            cropW = displayW * 0.8;
            cropH = cropW / aspectRatio;
        }
        var cropX = (displayW - cropW) / 2;
        var cropY = (displayH - cropH) / 2;

        // 保存状态
        cropState.imgEl = imgEl;
        cropState.imgNaturalW = imgNatW;
        cropState.imgNaturalH = imgNatH;
        cropState.imgDisplayW = displayW;
        cropState.imgDisplayH = displayH;
        cropState.imgOffsetX = offsetX;
        cropState.imgOffsetY = offsetY;
        cropState.cropW = cropW;
        cropState.cropH = cropH;
        cropState.cropX = cropX;
        cropState.cropY = cropY;
        cropState.aspectRatio = aspectRatio;
        cropState.touchMode = null;

        // 初次渲染
        renderCropBox();

        // 绑定触摸事件
        var area2 = $('crop-area');
        // 移除旧监听器 (简单方式：替换克隆节点)
        var newArea = area2.cloneNode(true);
        area2.parentNode.replaceChild(newArea, area2);

        newArea.addEventListener('touchstart', onCropTouchStart, { passive: false });
        newArea.addEventListener('touchmove', onCropTouchMove, { passive: false });
        newArea.addEventListener('touchend', onCropTouchEnd);
        newArea.addEventListener('touchcancel', onCropTouchEnd);

        // 重新获取 image 引用 (cloneNode 后 DOM 变了)
        cropState.imgEl = newArea.querySelector('#crop-image');
        // 恢复图片样式
        cropState.imgEl.style.width = displayW + 'px';
        cropState.imgEl.style.height = displayH + 'px';
        cropState.imgEl.style.left = offsetX + 'px';
        cropState.imgEl.style.top = offsetY + 'px';

        console.log('[AppUI] 裁剪 UI 已初始化, 图片: ' + imgNatW + 'x' + imgNatH
            + ', 显示: ' + Math.round(displayW) + 'x' + Math.round(displayH)
            + ', 裁剪框: ' + Math.round(cropW) + 'x' + Math.round(cropH)
            + ', 锁定比例: ' + aspectRatio.toFixed(4));
    }

    /** 更新遮罩和裁剪框的 DOM 位置 */
    function renderCropBox() {
        var s = cropState;
        var boxEl = $('crop-box');
        if (!boxEl) return;

        // 裁剪框位置 (相对于 crop-area)
        var left = s.imgOffsetX + s.cropX;
        var top = s.imgOffsetY + s.cropY;
        var w = s.cropW;
        var h = s.cropH;

        boxEl.style.left = left + 'px';
        boxEl.style.top = top + 'px';
        boxEl.style.width = w + 'px';
        boxEl.style.height = h + 'px';

        // 四个遮罩
        $('crop-mask-top').style.top = '0';
        $('crop-mask-top').style.height = top + 'px';

        $('crop-mask-bottom').style.top = (top + h) + 'px';
        $('crop-mask-bottom').style.bottom = '0';

        $('crop-mask-left').style.top = top + 'px';
        $('crop-mask-left').style.height = h + 'px';
        $('crop-mask-left').style.left = '0';
        $('crop-mask-left').style.width = left + 'px';

        $('crop-mask-right').style.top = top + 'px';
        $('crop-mask-right').style.height = h + 'px';
        $('crop-mask-right').style.left = (left + w) + 'px';
        $('crop-mask-right').style.right = '0';
    }

    /** 将触摸坐标转为图片像素坐标 */
    function touchToImagePos(touch) {
        var area = $('crop-area');
        var rect = area.getBoundingClientRect();
        var s = cropState;
        return {
            x: touch.clientX - rect.left - s.imgOffsetX,
            y: touch.clientY - rect.top - s.imgOffsetY,
        };
    }

    /** 钳制裁剪框在图片范围内 */
    function clampCropBox() {
        var s = cropState;
        if (s.cropX < 0) s.cropX = 0;
        if (s.cropY < 0) s.cropY = 0;
        if (s.cropX + s.cropW > s.imgDisplayW) s.cropX = s.imgDisplayW - s.cropW;
        if (s.cropY + s.cropH > s.imgDisplayH) s.cropY = s.imgDisplayH - s.cropH;
        // 如果裁剪框大于图片，缩到图片大小
        if (s.cropW > s.imgDisplayW) {
            s.cropW = s.imgDisplayW;
            s.cropH = s.cropW / s.aspectRatio;
            s.cropX = 0;
        }
        if (s.cropH > s.imgDisplayH) {
            s.cropH = s.imgDisplayH;
            s.cropW = s.cropH * s.aspectRatio;
            s.cropY = 0;
        }
    }

    // ---- 触摸事件 ----

    function onCropTouchStart(e) {
        if (e.touches.length === 1) {
            // 单指 → 移动裁剪框
            e.preventDefault();
            var pos = touchToImagePos(e.touches[0]);
            cropState.touchMode = 'move';
            cropState.touchStart = {
                x: pos.x,
                y: pos.y,
                cropX: cropState.cropX,
                cropY: cropState.cropY,
            };
        } else if (e.touches.length === 2) {
            // 双指 → 缩放裁剪框
            e.preventDefault();
            var p1 = touchToImagePos(e.touches[0]);
            var p2 = touchToImagePos(e.touches[1]);
            var cx = (p1.x + p2.x) / 2;
            var cy = (p1.y + p2.y) / 2;
            var dist = Math.sqrt(Math.pow(p2.x - p1.x, 2) + Math.pow(p2.y - p1.y, 2));
            cropState.touchMode = 'resize';
            cropState.touchStart = {
                cx: cx,           // 缩放中心
                cy: cy,
                dist: dist,       // 初始指距
                cropW: cropState.cropW,
                cropH: cropState.cropH,
                cropX: cropState.cropX,
                cropY: cropState.cropY,
            };
        }
    }

    function onCropTouchMove(e) {
        e.preventDefault();
        if (!cropState.touchMode || !cropState.touchStart) return;
        var s = cropState;
        var ts = s.touchStart;

        if (s.touchMode === 'move') {
            var pos = touchToImagePos(e.touches[0]);
            var dx = pos.x - ts.x;
            var dy = pos.y - ts.y;
            s.cropX = ts.cropX + dx;
            s.cropY = ts.cropY + dy;
            clampCropBox();
            renderCropBox();
        } else if (s.touchMode === 'resize' && e.touches.length === 2) {
            var p1b = touchToImagePos(e.touches[0]);
            var p2b = touchToImagePos(e.touches[1]);
            var cx = (p1b.x + p2b.x) / 2;
            var cy = (p1b.y + p2b.y) / 2;
            var dist = Math.sqrt(Math.pow(p2b.x - p1b.x, 2) + Math.pow(p2b.y - p1b.y, 2));
            var scale = dist / Math.max(ts.dist, 1);
            // 新尺寸
            var newW = ts.cropW * scale;
            var newH = ts.cropH * scale;
            // 以缩放中心为锚点
            var anchorXRatio = (ts.cx - ts.cropX) / ts.cropW;  // 缩放中心在裁剪框中的相对位置
            var anchorYRatio = (ts.cy - ts.cropY) / ts.cropH;
            s.cropW = newW;
            s.cropH = newH;
            s.cropX = ts.cx - newW * anchorXRatio;
            s.cropY = ts.cy - newH * anchorYRatio;
            clampCropBox();
            renderCropBox();
        }
    }

    function onCropTouchEnd(e) {
        cropState.touchMode = null;
        cropState.touchStart = null;
    }

    // ---- 确认 / 取消 ----

    /** 确认裁剪：将裁剪区域绘制到 canvas → 保存 → 继续流程 */
    function confirmCrop() {
        var s = cropState;
        var imgEl = $('crop-image');
        if (!imgEl || !imgEl.complete) return;

        // 从图片显示坐标映射回原始像素坐标
        var scaleX = s.imgNaturalW / s.imgDisplayW;
        var scaleY = s.imgNaturalH / s.imgDisplayH;
        var srcX = Math.round(s.cropX * scaleX);
        var srcY = Math.round(s.cropY * scaleY);
        var srcW = Math.round(s.cropW * scaleX);
        var srcH = Math.round(s.cropH * scaleY);

        // 边界保护
        srcX = Math.max(0, srcX);
        srcY = Math.max(0, srcY);
        srcW = Math.min(s.imgNaturalW - srcX, srcW);
        srcH = Math.min(s.imgNaturalH - srcY, srcH);

        var canvas = document.createElement('canvas');
        canvas.width = srcW;
        canvas.height = srcH;
        var ctx = canvas.getContext('2d');
        ctx.drawImage(imgEl, srcX, srcY, srcW, srcH, 0, 0, srcW, srcH);

        // 裁剪结果为 JPEG Base64
        var croppedDataUrl = canvas.toDataURL('image/jpeg', 0.92);
        var savedFileName = currentWallpaperFile;

        // 通过 AppBridge 保存裁剪后的图片
        if (window.AppBridge && typeof AppBridge.saveCroppedImage === 'function') {
            try {
                var result = AppBridge.saveCroppedImage(currentWallpaperFile, croppedDataUrl);
                if (result) {
                    savedFileName = result;
                    console.log('[AppUI] 裁剪图片已保存: ' + savedFileName
                        + ' (' + srcW + 'x' + srcH + 'px)');
                }
            } catch (e) {
                console.error('[AppUI] saveCroppedImage 失败:', e);
            }
        }

        // 清理裁剪 UI
        cleanupCropUI();

        // 继续正常流程
        applySelectedImage(savedFileName);
    }

    /** 取消裁剪：恢复之前的状态 */
    function cancelCrop(skipRestore) {
        if (window.AppBridge && typeof AppBridge.discardPickedImage === 'function' && currentWallpaperFile) {
            AppBridge.discardPickedImage(currentWallpaperFile);
        }
        cleanupCropUI();

        // 恢复之前的状态
        if (!skipRestore && window._previousWallpaperFile) {
            currentWallpaperFile = window._previousWallpaperFile;
            currentDepthFile = window._previousDepthFile;
            loadPreview(currentWallpaperFile, currentDepthFile);
        }
    }

    /** 清理裁剪 UI 状态 */
    function cleanupCropUI() {
        cropState.imgEl = null;
        cropState.touchMode = null;
        cropState.touchStart = null;
        $('crop-overlay').style.display = 'none';
        $('main-content').style.display = '';
    }

    /** 应用选中的图片（裁剪确认后 / 降级直通） */
    function applySelectedImage(fileName) {
        currentWallpaperFile = fileName;
        currentDepthFile = null;

        // 即时 UI 反馈
        $('preview-placeholder').style.display = 'none';
        $('image-info').textContent = '正在加载: ' + fileName + '...';

        // 加载预览
        loadPreview(currentWallpaperFile, currentDepthFile);
        saveConfig();
    }

})();
