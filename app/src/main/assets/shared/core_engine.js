/**
 * ============================================================================
 * shared/core_engine.js — Pixi.js 核心渲染引擎
 * ============================================================================
 *
 * 从 app.js 提取并适配 Android 环境：
 *   - Pixi.js 初始化 + DisplacementFilter + Cover 缩放 + Lerp 动画循环
 *   - WebGL context lost/restored 处理（Android 后台 GPU 回收防护）
 *   - setSensorTarget() → 接收原生 SensorManager 推送的归一化偏移
 *   - setDesktopOffset() → 接收 onOffsetsChanged 桌面滑动偏移
 *   - pause/resume/destroy 生命周期管理
 *
 * 依赖：PIXI (全局), WallpaperConfig, ImageLoader
 */

'use strict';
window.CoreEngine = (function() {
    'use strict';

    // ==================== 内部状态 ====================

    var app = null;              // PIXI.Application
    var bgSprite = null;         // 背景精灵
    var depthSprite = null;      // 深度图精灵 (renderable=false)
    var displacementFilter = null;
    var container = null;

    var targetX = 0, targetY = 0;
    var currentX = 0, currentY = 0;

    var bgNaturalWidth = 0;
    var bgNaturalHeight = 0;

    // WebGL 生命周期
    var glLost = false;
    var bgBaseUrl = null;        // 原始背景图 URL（不带时间戳）
    var depthBaseUrl = null;     // 原始深度图 URL（不带时间戳）

    // 桌面滑动偏移
    var desktopOffsetX = 0;

    // ==================== Cover 缩放 ====================

    function getCoverScale(imgW, imgH, containerW, containerH) {
        var scaleX = containerW / imgW;
        var scaleY = containerH / imgH;
        return Math.max(scaleX, scaleY);  // Cover = 取最大值
    }

    function applyCoverScale() {
        if (!bgSprite || !depthSprite) return;
        var scale = getCoverScale(
            bgNaturalWidth, bgNaturalHeight,
            app.screen.width, app.screen.height
        );
        bgSprite.scale.set(scale);
        depthSprite.scale.set(scale);
    }

    // ==================== WebGL 纹理恢复 ====================

    function restoreTextures() {
        var t = Date.now();

        var bgUrl = bgBaseUrl;
        var depthUrl = depthBaseUrl;

        // 仅当有有效 URL 时才重新加载（data: URL 不需时间戳）
        if (bgUrl && bgUrl.indexOf('data:') !== 0) {
            bgUrl = bgUrl + '?t=' + t;
        }
        if (depthUrl && depthUrl.indexOf('data:') !== 0) {
            depthUrl = depthUrl + '?t=' + t;
        }

        console.log('[WebGL] 恢复纹理: ' + bgUrl);

        return Promise.all([
            ImageLoader.loadImage(bgUrl).catch(function() { return null; }),
            ImageLoader.loadImage(depthUrl).catch(function() { return null; }),
        ]).then(function(results) {
            var bgImg = results[0];
            var depthImg = results[1];

            if (bgImg) {
                var bgTex = PIXI.Texture.from(bgImg);
                bgSprite.texture = bgTex;
                bgNaturalWidth = bgImg.naturalWidth || bgImg.width;
                bgNaturalHeight = bgImg.naturalHeight || bgImg.height;
            }

            if (depthImg) {
                var depthTex = PIXI.Texture.from(depthImg);
                depthTex.baseTexture.wrapMode = PIXI.WRAP_MODES.CLAMP;
                depthSprite.texture = depthTex;
            } else {
                // 深度图加载失败，用灰色占位图
                var fallback = ImageLoader.createFallbackDepthImage(
                    bgNaturalWidth || 1920,
                    bgNaturalHeight || 1080
                );
                depthSprite.texture = PIXI.Texture.from(fallback);
                depthSprite.texture.baseTexture.wrapMode = PIXI.WRAP_MODES.CLAMP;
            }

            applyCoverScale();
            console.log('[WebGL] 纹理恢复完成');
        });
    }

    // ==================== Lerp 动画循环 ====================

    function updateLoop(delta) {
        var s = 1 - Math.pow(1 - WallpaperConfig.smoothness, delta);
        currentX += (targetX - currentX) * s;
        currentY += (targetY - currentY) * s;

        if (displacementFilter) {
            displacementFilter.scale.x =
                currentX * WallpaperConfig.parallaxMultiplier
                + desktopOffsetX * WallpaperConfig.desktopParallax;
            displacementFilter.scale.y =
                currentY * WallpaperConfig.parallaxMultiplier;
        }
    }

    // ==================== 公开 API ====================

    return {

        /**
         * 初始化 Pixi.js 渲染引擎。
         *
         * @param {string} bgUrl    - 背景图 URL (WebViewAssetLoader 地址 或 Data URL)
         * @param {string} depthUrl - 深度图 URL (可以为 null，则使用灰色占位图)
         * @param {string} mode     - 'wallpaper' | 'preview'
         * @returns {Promise}
         */
        init: function(bgUrl, depthUrl, mode) {
            var self = this;
            bgBaseUrl = bgUrl;
            depthBaseUrl = depthUrl;

            // 如果已有实例，先销毁
            if (app) {
                this.destroy();
            }

            // 预览模式：画布放入 #preview-container，尺寸匹配容器
            // 壁纸模式：画布放入 body，全屏渲染
            var isPreview = (mode === 'preview');
            var previewContainer = isPreview
                ? document.getElementById('preview-container')
                : null;

            var pixiWidth = (previewContainer && previewContainer.clientWidth > 0)
                ? previewContainer.clientWidth
                : window.innerWidth;
            var pixiHeight = (previewContainer && previewContainer.clientHeight > 0)
                ? previewContainer.clientHeight
                : window.innerHeight;

            // 创建 Pixi.js Application
            // 预览模式不使用 resizeTo（容器固定 220px 高），避免与 CSS 冲突
            var pixiOpts = {
                width: pixiWidth,
                height: pixiHeight,
                backgroundColor: 0x000000,
                antialias: true,
                resolution: window.devicePixelRatio || 1,
                autoDensity: true,
            };
            if (!isPreview) {
                pixiOpts.resizeTo = window;
            }
            app = new PIXI.Application(pixiOpts);

            // 获取 canvas 引用用于 WebGL 事件监听
            var canvas = app.view;

            // ---- WebGL Context 丢失处理 ----
            // 使用 onwebglcontextlost 而非 addEventListener，
            // 这样 destroy() 可以通过置 null 来阻止旧 canvas 的异步事件影响新实例
            canvas.onwebglcontextlost = function(e) {
                if (!app) return;  // 已被 destroy() 清理，忽略异步残留事件
                e.preventDefault();
                glLost = true;
                if (app.ticker) app.ticker.stop();
                console.warn('[WebGL] Context lost, 渲染已暂停');
            };

            canvas.onwebglcontextrestored = function() {
                if (!app) return;
                console.log('[WebGL] Context restored, 正在恢复纹理...');
                restoreTextures().then(function() {
                    glLost = false;
                    if (app && app.ticker) app.ticker.start();
                    console.log('[WebGL] 完全恢复');
                });
            };

            // 添加到页面（预览模式放入容器，壁纸模式放入 body）
            if (mode === 'preview') {
                var previewContainer = document.getElementById('preview-container');
                if (previewContainer) {
                    // 移除静态占位 canvas（HTML 中的 #preview-canvas，仅首次需要）
                    var staticCanvas = document.getElementById('preview-canvas');
                    if (staticCanvas) staticCanvas.remove();
                    // 注意: 不要使用 innerHTML = '' — 会销毁 #preview-placeholder，
                    // 导致后续 onImageSelected 找不到该元素而崩溃
                    previewContainer.appendChild(canvas);
                } else {
                    console.warn('[CoreEngine] 找不到 #preview-container，回退到 body');
                    document.body.appendChild(canvas);
                }
            } else {
                document.body.appendChild(canvas);
            }

            // 加载图片
            var loadBg = ImageLoader.loadImage(bgUrl);
            var loadDepth = depthUrl
                ? ImageLoader.loadImage(depthUrl)
                : Promise.resolve(null);

            return Promise.all([loadBg, loadDepth]).then(function(results) {
                var bgImg = results[0];
                var depthImg = results[1];

                // 缓存原始尺寸
                bgNaturalWidth = bgImg.naturalWidth || bgImg.width;
                bgNaturalHeight = bgImg.naturalHeight || bgImg.height;

                // 深度图为空时生成占位图
                if (!depthImg) {
                    console.warn('[CoreEngine] 深度图未提供，使用灰色占位图（无深度视差效果）');
                    depthImg = ImageLoader.createFallbackDepthImage(bgNaturalWidth, bgNaturalHeight);
                }

                // 创建纹理
                var bgTexture = PIXI.Texture.from(bgImg);
                var depthTexture = PIXI.Texture.from(depthImg);
                depthTexture.baseTexture.wrapMode = PIXI.WRAP_MODES.CLAMP;

                // 创建精灵
                bgSprite = new PIXI.Sprite(bgTexture);
                bgSprite.anchor.set(0.5, 0.5);

                depthSprite = new PIXI.Sprite(depthTexture);
                depthSprite.anchor.set(0.5, 0.5);
                depthSprite.renderable = false;  // 只提供纹理数据给滤镜

                // 创建容器
                container = new PIXI.Container();
                container.x = app.screen.width / 2;
                container.y = app.screen.height / 2;
                container.addChild(depthSprite);
                container.addChild(bgSprite);

                // Cover 缩放
                applyCoverScale();

                // DisplacementFilter
                displacementFilter = new PIXI.DisplacementFilter(depthSprite, 0);
                container.filters = [displacementFilter];

                // 添加到舞台
                app.stage.addChild(container);

                // 启动动画循环
                app.ticker.add(updateLoop);

                console.log('[CoreEngine] 初始化完成 (' + mode + ' 模式) '
                    + app.screen.width + 'x' + app.screen.height);
            });
        },

        /** 窗口 resize */
        resize: function() {
            if (container && app) {
                container.x = app.screen.width / 2;
                container.y = app.screen.height / 2;
            }
            applyCoverScale();
        },

        /**
         * 原生传感器输入 (SensorManager → evaluateJavascript 推送)
         * @param {number} x - 归一化的 X 轴倾斜 [-1, 1]
         * @param {number} y - 归一化的 Y 轴倾斜 [-1, 1]
         */
        setSensorTarget: function(x, y) {
            targetX = x;
            targetY = y;
        },

        /**
         * 桌面滑动偏移 (Engine.onOffsetsChanged 推送)
         * @param {number} xOffset - 桌面水平偏移 [0.0, 1.0]
         */
        setDesktopOffset: function(xOffset) {
            desktopOffsetX = (xOffset - 0.5) * 2;  // 中心化到 [-1, 1]
        },

        /** 暂停渲染 */
        pause: function() {
            if (app && app.ticker) {
                app.ticker.stop();
            }
        },

        /** 恢复渲染 */
        resume: function() {
            if (app && app.ticker && !glLost) {
                app.ticker.start();
            }
        },

        /**
         * 更换壁纸图片（热替换纹理，不需要重建 Pixi.js 实例）。
         * @param {string} newBgUrl   - 新背景图 URL
         * @param {string} newDepthUrl - 新深度图 URL
         * @returns {Promise}
         */
        reloadImages: function(newBgUrl, newDepthUrl) {
            bgBaseUrl = newBgUrl;
            depthBaseUrl = newDepthUrl;

            // 清理 Pixi 纹理缓存
            PIXI.utils.destroyTextureCache();

            var t = Date.now();
            var bgLoadUrl = newBgUrl;
            var depthLoadUrl = newDepthUrl;

            if (bgLoadUrl && bgLoadUrl.indexOf('data:') !== 0) {
                bgLoadUrl = bgLoadUrl + '?t=' + t;
            }
            if (depthLoadUrl && depthLoadUrl.indexOf('data:') !== 0) {
                depthLoadUrl = depthLoadUrl + '?t=' + t;
            }

            if (app && app.ticker) app.ticker.stop();

            return Promise.all([
                ImageLoader.loadImage(bgLoadUrl),
                ImageLoader.loadImage(depthLoadUrl).catch(function() { return null; }),
            ]).then(function(results) {
                var bgImg = results[0];
                var depthImg = results[1];

                bgNaturalWidth = bgImg.naturalWidth || bgImg.width;
                bgNaturalHeight = bgImg.naturalHeight || bgImg.height;

                if (!depthImg) {
                    depthImg = ImageLoader.createFallbackDepthImage(bgNaturalWidth, bgNaturalHeight);
                }

                bgSprite.texture = PIXI.Texture.from(bgImg);
                var depthTex = PIXI.Texture.from(depthImg);
                depthTex.baseTexture.wrapMode = PIXI.WRAP_MODES.CLAMP;
                depthSprite.texture = depthTex;

                applyCoverScale();

                if (app && app.ticker) app.ticker.start();
                console.log('[CoreEngine] 壁纸已热替换');
            });
        },

        /**
         * 销毁 Pixi.js 实例，释放 WebGL 上下文和 GPU 显存。
         * 在 MainActivity.onStop/onDestroy 时调用，防止双 WebView 显存冲突。
         */
        destroy: function() {
            if (app) {
                // 先移除 WebGL 事件监听，防止 destroy() 触发的异步 context lost
                // 事件影响后续新创建的实例
                var canvas = app.view;
                if (canvas) {
                    canvas.onwebglcontextlost = null;
                    canvas.onwebglcontextrestored = null;
                }
                if (app.ticker) app.ticker.stop();
                if (app.stage) app.stage.removeChildren();
                // destroy(true) 删除 canvas 并释放 WebGL 上下文
                app.destroy(true, { children: true, texture: true });
                app = null;
            }
            bgSprite = null;
            depthSprite = null;
            displacementFilter = null;
            container = null;
            glLost = false;
            console.log('[CoreEngine] 已销毁，GPU 资源已释放');
        },

        /** 获取 Pixi Application 引用（调试用） */
        getApp: function() { return app; },
    };
})();
