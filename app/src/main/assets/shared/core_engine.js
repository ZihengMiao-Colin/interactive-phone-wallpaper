/**
 * ============================================================================
 * shared/core_engine.js — Pixi.js 深度视差渲染引擎（壁纸和 App 预览共用）
 * ============================================================================
 *
 * 原理和桌面版 renderer/parallax-scene.js 一样：全屏精灵挂一个自定义滤镜。
 * 深度 t 的内容沿倾斜方向挪 tilt × (t − focus) × strength（带软截断），
 * 深度等于对焦深度的地方完全不动，离得越远挪得越多，形成伪 3D 视差。
 * 片元着色器用光线步进找出每个像素实际显示的图片位置，近处的内容挪过来会盖住远处的。
 *
 * 输入：
 *   - setSensorTarget()  ← 原生 SensorManager 推送的归一化倾斜 [-1, 1]
 *   - setDesktopOffset() ← 桌面左右滑动偏移 (onOffsetsChanged)
 * 生命周期：pause / resume / destroy，WebGL context lost / restored 时重新加载纹理。
 *
 * 依赖：PIXI、WallpaperConfig、ImageLoader、Shared.parallax、着色器源码 (shared/shaders.js)
 */

'use strict';

window.CoreEngine = (() => {
    /** 背景深度视差滤镜（uniform 和桌面版一致） */
    class DepthParallaxFilter extends PIXI.Filter {
        constructor(imageTexture, depthTexture) {
            super(PARALLAX_VERTEX_SHADER, PARALLAX_FRAGMENT_SHADER, {
                uImage: imageTexture,
                uDepth: depthTexture,
                uDepthSize: new Float32Array([1.0, 1.0]),
                uMouse: new Float32Array([0.0, 0.0]),
                uStrength: 0.025,
                uFocus: 0.50,
                uCoverScale: new Float32Array([1.0, 1.0]),
                uCoverOffset: new Float32Array([0.0, 0.0]),
            });
        }
    }

    // ==================== 内部状态 ====================

    let app = null;              // PIXI.Application
    let mode = 'wallpaper';      // 'wallpaper' | 'preview'
    let container = null;        // 预览模式下画布所在的元素
    let screenSprite = null;     // 全屏精灵（本身是白色贴图，画面完全由滤镜采样得到）
    let filter = null;
    let bgTexture = null;
    let depthTexture = null;
    let bgWidth = 0;
    let bgHeight = 0;
    let view = 'image';          // 'image' 显示原图，'depth' 显示深度图

    let targetX = 0, targetY = 0;
    let currentX = 0, currentY = 0;
    let desktopOffsetX = 0;

    let glLost = false;
    let bgBaseUrl = null;        // 原始背景图 URL（不带时间戳）
    let depthBaseUrl = null;     // 原始深度图 URL（不带时间戳）

    // ==================== 空闲停帧 ====================
    // 倾斜不变、画面已经停稳时停掉 ticker，最后一帧一直留在屏幕上；有新输入或参数变化再唤醒。
    // 和桌面版 renderer/main.js 的做法一样。

    /** 渲染帧率上限（手机屏幕多是 90 / 120Hz，壁纸不需要满帧） */
    const MAX_FPS = 60;
    /** 连续空闲这么多帧（约半秒）后停帧，让倾斜停下后的平滑过渡走完 */
    const IDLE_GRACE_FRAMES = 30;
    const idleTracker = new Shared.framePacing.IdleTracker({ graceFrames: IDLE_GRACE_FRAMES });
    /** pause() 之后到 resume() 之前不自己唤醒（壁纸不可见） */
    let paused = false;
    /** 累计渲染帧数（调试和验证停帧用） */
    let frameCount = 0;

    /** 画面可能要变了：重新开始出帧（已经在跑时只重置空闲计数） */
    function wake() {
        idleTracker.reset();
        if (app && !paused && !glLost && !app.ticker.started) app.ticker.start();
    }

    /** 每帧末尾调用：满足空闲条件就停掉 ticker */
    function sleepIfIdle() {
        const { isSettled } = Shared.framePacing;
        const settled = isSettled(targetX, currentX) && isSettled(targetY, currentY);
        if (idleTracker.frame(settled)) app.ticker.stop();
    }

    // ==================== 尺寸与 Cover ====================

    /** 画面尺寸（CSS 像素）：预览按容器，壁纸按窗口 */
    function viewSize() {
        if (mode === 'preview' && container && container.clientWidth > 0 && container.clientHeight > 0) {
            return { w: container.clientWidth, h: container.clientHeight };
        }
        return { w: window.innerWidth, h: window.innerHeight };
    }

    /** 按当前尺寸更新精灵大小和 Cover 映射（原图以 Cover 方式铺满画面） */
    function updateCover() {
        const { w, h } = viewSize();
        if (!w || !h) return;
        if (screenSprite) {
            screenSprite.width = w;
            screenSprite.height = h;
        }
        if (filter) {
            const cover = Shared.parallax.coverTransform(w, h, bgWidth || w, bgHeight || h);
            // 必须整体赋值数组，才能触发 Pixi UniformGroup 的脏标记
            filter.uniforms.uCoverScale = [cover.scaleX, cover.scaleY];
            filter.uniforms.uCoverOffset = [cover.offsetX, cover.offsetY];
        }
    }

    /** 预览模式没有 resizeTo，尺寸变了要手动调整渲染器 */
    function fitRenderer() {
        if (!app) return;
        if (mode === 'preview') {
            const { w, h } = viewSize();
            if (w && h && (app.screen.width !== w || app.screen.height !== h)) app.renderer.resize(w, h);
        }
        updateCover();
    }

    // ==================== 纹理 ====================

    function makeTexture(source) {
        const texture = PIXI.Texture.from(source);
        texture.baseTexture.wrapMode = PIXI.WRAP_MODES.CLAMP;
        return texture;
    }

    /** 给 URL 加时间戳，防止 WebView 拿到旧文件（data: URL 不需要） */
    function bust(url) {
        if (!url || url.indexOf('data:') === 0) return url;
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + 't=' + Date.now();
    }

    /**
     * 加载（或更换）壁纸与深度图。首次调用建好精灵和滤镜，之后只替换纹理并释放旧纹理。
     * @param {string} bgUrl - 加载失败会抛错
     * @param {string|null} depthUrl - 为 null 或加载失败时用占位深度图
     */
    async function load(bgUrl, depthUrl) {
        const bgImg = await ImageLoader.loadImage(bgUrl);
        let depthImg = null;
        if (depthUrl) {
            try {
                depthImg = await ImageLoader.loadImage(depthUrl);
            } catch (err) {
                console.warn('[CoreEngine] 深度图加载失败，改用占位深度图');
            }
        }
        if (!app) return;   // 加载期间被销毁了
        bgWidth = bgImg.naturalWidth || bgImg.width;
        bgHeight = bgImg.naturalHeight || bgImg.height;
        if (!depthImg) depthImg = ImageLoader.createFallbackDepthImage(bgWidth, bgHeight);

        const oldBg = bgTexture;
        const oldDepth = depthTexture;
        bgTexture = makeTexture(bgImg);
        depthTexture = makeTexture(depthImg);
        // 光线步进在分支和循环里读深度图，读的时候算不出该用哪级 mipmap，所以深度图不要 mipmap
        depthTexture.baseTexture.mipmap = PIXI.MIPMAP_MODES.OFF;

        if (!filter) {
            screenSprite = new PIXI.Sprite(PIXI.Texture.WHITE);
            filter = new DepthParallaxFilter(bgTexture, depthTexture);
            screenSprite.filters = [filter];
            app.stage.addChild(screenSprite);
        }
        filter.uniforms.uImage = view === 'depth' ? depthTexture : bgTexture;
        filter.uniforms.uDepth = depthTexture;
        // 深度图的像素尺寸（光线步进按它决定走几步）
        filter.uniforms.uDepthSize = [depthImg.naturalWidth || depthImg.width, depthImg.naturalHeight || depthImg.height];
        fitRenderer();

        // 新纹理已经挂上滤镜，旧纹理可以连同 GPU 资源一起释放
        if (oldBg) oldBg.destroy(true);
        if (oldDepth) oldDepth.destroy(true);

        // 立刻出一帧：之后可能马上被暂停或停帧，保证画面不留空白
        app.renderer.render(app.stage);
        wake();
        console.log('[CoreEngine] 已加载壁纸 ' + bgWidth + '×' + bgHeight
            + '，画面 ' + app.screen.width + '×' + app.screen.height);
    }

    // ==================== 动画循环 ====================

    /**
     * 每帧更新（Pixi ticker 驱动）。
     * @param {number} delta - 以 60fps 为 1 的帧时间倍数，保证不同帧率下跟手速度一致
     */
    function updateLoop(delta) {
        const k = 1 - Math.pow(1 - WallpaperConfig.smoothness, delta);
        currentX += (targetX - currentX) * k;
        currentY += (targetY - currentY) * k;

        if (filter) {
            // 不取反：方向和旧版 DisplacementFilter 一致（采样偏移 = +tilt × (depth − 0.5) × 系数）
            filter.uniforms.uMouse = [currentX + desktopOffsetX * WallpaperConfig.desktopOffsetWeight, currentY];
            filter.uniforms.uStrength = Shared.parallax.strengthFromMultiplier(WallpaperConfig.parallaxMultiplier);
            filter.uniforms.uFocus = Number(WallpaperConfig.focusDepth);
        }
        frameCount++;
        sleepIfIdle();
    }

    // ==================== 公开 API ====================

    return {
        /**
         * 初始化渲染引擎。已有实例时先销毁。
         * @param {string} bgUrl - 背景图 URL
         * @param {string|null} depthUrl - 深度图 URL，null 时用占位深度图
         * @param {'wallpaper'|'preview'} engineMode
         * @param {HTMLElement} [previewContainer] - 预览模式的画布容器，默认 #preview-container
         * @returns {Promise<void>}
         */
        init(bgUrl, depthUrl, engineMode, previewContainer) {
            if (app) this.destroy();
            mode = engineMode === 'preview' ? 'preview' : 'wallpaper';
            container = mode === 'preview'
                ? (previewContainer || document.getElementById('preview-container'))
                : null;
            bgBaseUrl = bgUrl;
            depthBaseUrl = depthUrl;

            const { w, h } = viewSize();
            const options = {
                width: w,
                height: h,
                backgroundColor: 0x000000,
                antialias: true,
                resolution: window.devicePixelRatio || 1,
                autoDensity: true,
            };
            if (mode === 'wallpaper') options.resizeTo = window;
            app = new PIXI.Application(options);
            app.ticker.maxFPS = MAX_FPS;
            paused = false;
            idleTracker.reset();

            // ---- WebGL Context 丢失处理（Android 后台会回收 GPU） ----
            // 用 on 属性而不是 addEventListener，destroy() 置 null 就能挡住旧 canvas 的异步事件
            const canvas = app.view;
            canvas.onwebglcontextlost = (e) => {
                if (!app) return;
                e.preventDefault();
                glLost = true;
                app.ticker.stop();
                console.warn('[WebGL] Context lost, 渲染已暂停');
            };
            canvas.onwebglcontextrestored = () => {
                if (!app) return;
                console.log('[WebGL] Context restored, 正在恢复纹理...');
                load(bust(bgBaseUrl), bust(depthBaseUrl)).then(() => {
                    glLost = false;
                    wake();
                    console.log('[WebGL] 完全恢复');
                });
            };

            if (mode === 'preview' && container) {
                // 旧界面的静态占位 canvas
                const staticCanvas = document.getElementById('preview-canvas');
                if (staticCanvas) staticCanvas.remove();
                container.appendChild(canvas);
            } else {
                document.body.appendChild(canvas);
            }

            app.ticker.add(updateLoop);
            return load(bgUrl, depthUrl).then(() => {
                console.log('[CoreEngine] 初始化完成 (' + mode + ' 模式)');
            });
        },

        /** 预览模式：把画布挪到另一个容器（切页签时用），按新容器尺寸重算 */
        mount(el) {
            if (mode !== 'preview' || !el) return;
            container = el;
            if (app && app.view.parentNode !== el) el.appendChild(app.view);
            fitRenderer();
            wake();
        },

        /** 窗口或容器尺寸变化 */
        resize() {
            fitRenderer();
            wake();
        },

        /**
         * 更新视差参数（写进 WallpaperConfig，未初始化时也可以调用）
         * @param {{parallaxMultiplier?: number, smoothness?: number, focusDepth?: number}} p
         */
        setParams(p) {
            if (!p) return;
            for (const key of ['parallaxMultiplier', 'smoothness', 'focusDepth']) {
                if (p[key] !== undefined && p[key] !== null) WallpaperConfig[key] = Number(p[key]);
            }
            wake();
        },

        /** 显示原图或深度图（深度图视图同样有视差） */
        setView(next) {
            view = next === 'depth' ? 'depth' : 'image';
            if (filter) filter.uniforms.uImage = view === 'depth' ? depthTexture : bgTexture;
            wake();
        },

        /**
         * 倾斜输入
         * @param {number} x - 归一化的 X 轴倾斜 [-1, 1]
         * @param {number} y - 归一化的 Y 轴倾斜 [-1, 1]
         */
        setSensorTarget(x, y) {
            // NaN 进来以后插值永远是 NaN：画面坏掉，停帧判断也永远不成立
            if (!Number.isFinite(x) || !Number.isFinite(y)) return;
            if (x === targetX && y === targetY) return;
            targetX = x;
            targetY = y;
            wake();
        },

        /** 桌面滑动偏移 (Engine.onOffsetsChanged 推送)，xOffset ∈ [0, 1] */
        setDesktopOffset(xOffset) {
            const next = (xOffset - 0.5) * 2;  // 中心化到 [-1, 1]
            if (next === desktopOffsetX) return;
            // 桌面偏移没有插值，直接生效；唤醒出几帧把新偏移画出来
            desktopOffsetX = next;
            wake();
        },

        /** 暂停渲染（壁纸不可见） */
        pause() {
            paused = true;
            if (app) app.ticker.stop();
        },

        /** 恢复渲染 */
        resume() {
            paused = false;
            wake();
        },

        /** 累计渲染帧数（调试和验证停帧用） */
        getFrameCount() { return frameCount; },

        /** 当前壁纸原图尺寸 */
        getImageSize() { return { width: bgWidth, height: bgHeight }; },

        /**
         * 更换壁纸图片（热替换纹理，不重建 Pixi 实例）
         * @returns {Promise<void>}
         */
        reloadImages(newBgUrl, newDepthUrl) {
            bgBaseUrl = newBgUrl;
            depthBaseUrl = newDepthUrl;
            if (!app) return Promise.resolve();
            return load(bust(newBgUrl), bust(newDepthUrl));
        },

        /**
         * 销毁 Pixi 实例，释放 WebGL 上下文和显存。
         * MainActivity.onStop 时调用，防止和壁纸服务的 WebView 同时占两份显存。
         */
        destroy() {
            if (app) {
                const canvas = app.view;
                if (canvas) {
                    canvas.onwebglcontextlost = null;
                    canvas.onwebglcontextrestored = null;
                }
                app.ticker.stop();
                app.stage.removeChildren();
                // destroy(true) 删除 canvas 并释放 WebGL 上下文
                app.destroy(true, { children: true, texture: true, baseTexture: true });
                app = null;
            }
            screenSprite = null;
            filter = null;
            bgTexture = null;
            depthTexture = null;
            glLost = false;
            console.log('[CoreEngine] 已销毁，GPU 资源已释放');
        },

        /** Pixi Application（调试用） */
        getApp() { return app; },
    };
})();
