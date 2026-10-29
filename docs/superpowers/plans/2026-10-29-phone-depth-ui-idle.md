# 手机版修割裂、新界面、空闲停帧 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让手机版壁纸动起来不再割裂，换成桌面风格的新界面，静止时停止渲染，并且调参数、换壁纸后桌面壁纸实时跟着变。

**Architecture:** 保持 Kotlin 外壳 + WebView + Pixi 7.4.2 的结构。渲染公式（着色器）、视差数学、停帧判断、配置节流从桌面项目原样复制成 `assets/shared/` 下的脚本；深度图后处理从桌面 JS 移植到 Kotlin 纯函数，用 JVM 单元测试对照桌面结果；界面按桌面 `settings/` 的拆法重写成几个普通脚本，带一个浏览器里可跑的假桥接，没有手机也能看界面。

**Tech Stack:** Kotlin（AGP 9.0、Gradle 9.3、JDK 17、JUnit 4）、ONNX Runtime Android 1.17.3、Android WebView、Pixi.js 7.4.2、普通 JS / CSS（不用框架和构建步骤）。

**Spec:** `docs/superpowers/specs/2026-10-29-phone-depth-ui-idle-design.md`（界面 mockup：`.superpowers/mockups/phone-ui.html`，不进 git）

**路径简写：** `kt/` = `app/src/main/java/com/wallpaper/live3d/`，`assets/` = `app/src/main/assets/`，`ktest/` = `app/src/test/java/com/wallpaper/live3d/`，`desk/` = 桌面项目 `C:/Users/24418/Desktop/2/壁纸/`。命令都在仓库根目录用 Git Bash 执行。

## Global Constraints

- 注释用中文；代码、变量名、命令、报错保持英文。提交信息沿用桌面风格（`feat:` / `fix:` / `docs:` + 中文），结尾加 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`。
- 从桌面复制的文件：文件头加一行 `// 复制自桌面项目 <desk/ 下的路径>，改动要两边同步`，其余逐字节一致（用 `diff` 核对只差这一行）。
- 新写的 JS 跟桌面同一种写法：`'use strict'`、`const`、IIFE 返回对象挂在 `window` 上；不引入框架和构建步骤。
- 配置默认值和范围：`parallaxMultiplier` 10（5~100，整数）、`smoothness` 0.05（0.01~0.30）、`focusDepth` 0.5（0~1）、`deadZoneDeg` 3.0（0~10）、`maxTiltDeg` 30.0（10~60）、`blurRadius` 8（0~30，整数）。
- 渲染：帧率上限 60；连续 30 帧收敛才停帧；传感器去抖阈值 0.01（归一化单位）；桌面滑动偏移权重 0.33。
- 深度图：模型输入 518×518；输出最长边 1920；解码时最长边压到 4096 以内（`inSampleSize` 取 2 的幂）。
- 文件名：选图复制成 `pick_<毫秒时间戳>.<原扩展名>`，裁剪后 `wp_<时间戳>.jpg`，深度图 `depth_<时间戳>.png`。配置里 `currentImage` / `depthFile` 只在"应用壁纸"时写。
- 界面用词：跟手程度（原平滑度）、静止范围（原陀螺仪死区）、最大倾斜（原最大倾斜角）；边缘平滑显示 `8px`，0 显示"关闭"。
- 恢复推荐值：视差强度 10、跟手程度 0.05、对焦深度 0.5、静止范围 3°、最大倾斜 30°、边缘平滑 8。
- 版本号：`versionName "1.1.0"`、`versionCode 2`。
- 这轮不碰：自定义元素 / 天气 / 视频特效 / 唱片机 / 图库，`transformers.min.js`，模型复制两份，悬浮窗权限代码。

## Review Focus

1. **壁纸的 `pauseTimers()` 是整个进程全局的**：打开 App 时壁纸变成不可见、调用 `pauseTimers()`，会把 App 页面的 `setTimeout` 也冻住，节流保存和 Toast 都不触发。期望：App 里拖滑块能保存，Toast 会消失。→ Task 5 删掉壁纸里的 `pauseTimers/resumeTimers`，Task 6 在 `MainActivity.onResume` 补 `resumeTimers()`。
2. **在桌面上摸屏幕再松手**：画面应回到当前陀螺仪对应的位置，不能停在中间不动（去抖后陀螺仪可能很久不推新值）。→ Task 4 的 `TiltDebouncer` 测试和 `SensorHelper.pushLatest()`。
3. **选了一张超大的图**（一边 8000px 以上）：生成深度图不能内存溢出。→ Task 2 的 `sampleSizeFor` 测试。
4. **裁剪或生成深度图的中途切回 App**（从图片选择器、系统壁纸页或后台回来）：预览不能空白，裁剪界面不被打断，生成结果照常送达。→ Task 8 浏览器里在裁剪中、生成中各调用一次 `window.onAppResume()`。
5. **点了"应用壁纸"但在系统预览页退出没设上**：回到 App 后顶栏要显示"未设为桌面壁纸"。→ Task 8 用假桥接 `?active=0` 测回到前台时刷新状态。

---

### Task 1: 深度图后处理移植到 Kotlin

**Files:**
- Create: `kt/depth/DepthProcessing.kt`
- Test: `ktest/depth/DepthProcessingTest.kt`

**Interfaces:**
- Produces（`object DepthProcessing`，纯函数，不依赖 Android API）：
  - `const val MODEL_INPUT_SIZE = 518`、`DEPTH_MAX_SIDE = 1920`、`DEFAULT_BLUR = 8`
  - `fun roundHalfEven(x: Double): Int`
  - `fun depthOutputSize(width: Int, height: Int, maxSide: Int = DEPTH_MAX_SIDE): Pair<Int, Int>`
  - `fun argbToRgb(argb: IntArray): ByteArray` —— `Bitmap.getPixels` 的结果转成每像素 3 字节
  - `fun resizeRgb8(rgb: ByteArray, inW: Int, inH: Int, outW: Int, outH: Int): ByteArray` —— 输入就是 RGB（桌面版吃 RGBA，这里先经 `argbToRgb`）
  - `fun resizeFloat(data: FloatArray, inW: Int, inH: Int, outW: Int, outH: Int): FloatArray`
  - `fun toModelInput(rgb: ByteArray, pixelCount: Int): FloatArray`
  - `fun boxSizes(sigma: Double, passes: Int = 3): IntArray`、`fun blurSupport(sigma: Double): Int`
  - `fun gaussianBlur(data: FloatArray, width: Int, height: Int, sigma: Double): FloatArray`
  - `fun dilateMax(data: FloatArray, width: Int, height: Int, radius: Int): FloatArray`
  - `fun postprocessDepth(raw: FloatArray, rawW: Int, rawH: Int, outW: Int, outH: Int, smoothRadius: Double, invert: Boolean = false): ByteArray` —— 8 位灰度，白 = 近

- [ ] **Step 1: 写失败的测试**

把 `desk/test/depth-processing.test.js` 的每个用例移植成 JUnit 4 方法，名字和断言一一对应：
`depthOutputSize_keepsSmall_shrinksLarge`、`roundHalfEven_matchesPython`、`dilateMax_matchesBruteForce`（r ∈ {0,1,2,5,9,40}，31×23 随机图，`java.util.Random(1)` 生成即可）、`gaussianBlur_constantUnchanged_zeroSigmaIdentity`、`gaussianBlur_impulse_sumSymmetryStd`、`gaussianBlur_supportIsBlurSupport`、`postprocess_noSmooth_normalizes`、`postprocess_subjectKeepsDepth_transitionOnBackground`、`postprocess_invertAfterSmooth`、`postprocess_flatIsBlack_sizeScaled`、`resizeRgb8_down_matchesPillow`、`resizeRgb8_up_matchesPillow`、`resizeRgb8_sameSize_dropsAlpha`、`resizeFloat_up_matchesPillow`、`resizeFloat_down_matchesPillow`、`toModelInput_imagenetNchw`。

- 期望数组从桌面测试第 151–188 行原样复制；`sampleRgba()` / `samplePlane()` / `step()` 按桌面公式重写。
- `resizeRgb8_*` 先把 `sampleRgba()` 去掉透明通道再调用。
- 字节比较用 `and 0xFF`；浮点比较容差和桌面一样（`resizeFloat` 1e-6，`toModelInput` 1e-5）。

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.wallpaper.live3d.depth.DepthProcessingTest"`
Expected: 编译失败，`Unresolved reference: DepthProcessing`

- [ ] **Step 3: 在 `kt/depth/DepthProcessing.kt` 实现上面的签名**

逐个函数照搬 `desk/src/depth/processing.js`，保证数值逐位一致：
- JS 里存进 `Float32Array` 或经 `Math.fround` 的值用 `Float`；累加和系数用 `Double`。
- 8 位定点累加用 `Long`，取整用 `shr 22`（等价于 `Math.floor(sum / 2^22)`）。
- `Math.trunc` 用 `toInt()`；`roundHalfEven` 用 `Math.rint`。
- `boxLine` 的滑动窗口加减顺序不能改。
- 文件头注明"移植自桌面 `src/depth/processing.js`，算法改动要两边同步"。

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.wallpaper.live3d.depth.DepthProcessingTest"`
Expected: `BUILD SUCCESSFUL`，16 个测试全过

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/wallpaper/live3d/depth/DepthProcessing.kt app/src/test
git commit -m "feat: 深度图后处理移植桌面算法（近处外扩 + 高斯），JVM 测试对照桌面结果"
```

---

### Task 2: DepthEstimator 换用新后处理

**Files:**
- Modify: `kt/depth/DepthProcessing.kt`（加 `sampleSizeFor`）
- Modify: `kt/depth/DepthEstimator.kt`（整条流程重写，删掉 `preprocess`、`postprocess`、`applyEdgeSmoothing`、`erodeGrayscale`、`gaussianBlurSeparable`、`makeGaussianKernel1D`、`hashFileName`）
- Modify: `kt/ui/AppBridge.kt`（`generateAIDepthMap` 加参数和进度回调）
- Modify: `assets/app/app_ui.js`（旧界面临时适配：调用时传 8）
- Test: `ktest/depth/DepthProcessingTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `DepthProcessing`
- Produces:
  - `DepthProcessing.DECODE_MAX_SIDE = 4096`
  - `fun sampleSizeFor(width: Int, height: Int, maxSide: Int = DECODE_MAX_SIDE): Int` —— 2 的幂
  - `suspend fun DepthEstimator.estimateDepth(imageFile: File, cacheDir: File, blurRadius: Int, onProgress: (percent: Int, text: String) -> Unit): String?` —— 返回 `depth_<时间戳>.png`，失败返回 null（原因在 `lastError`）
  - `AppBridge.generateAIDepthMap(imageName: String, blurRadius: Int)`，在主线程调 JS 的 `window.onDepthProgress(percent, text)`（参数用 `JSONObject.quote`），结果仍然走 `window.onAIDepthReady(fileName, error)`

- [ ] **Step 1: 写失败的测试 `sampleSizeFor_capsLongestSide`**

```kotlin
assertEquals(1, DepthProcessing.sampleSizeFor(4096, 2000))
assertEquals(2, DepthProcessing.sampleSizeFor(4097, 100))
assertEquals(2, DepthProcessing.sampleSizeFor(8000, 6000))
assertEquals(4, DepthProcessing.sampleSizeFor(12000, 9000))
assertEquals(1, DepthProcessing.sampleSizeFor(1080, 2340))
```

- [ ] **Step 2: 运行，确认失败**（`Unresolved reference: sampleSizeFor`）

Run: `./gradlew testDebugUnitTest --tests "com.wallpaper.live3d.depth.DepthProcessingTest"`

- [ ] **Step 3: 实现 `sampleSizeFor`，并按下面的流程重写 `estimateDepth`**

1. 先只读尺寸（`inJustDecodeBounds`），得到原图 `origW × origH`，再用 `inSampleSize = sampleSizeFor(origW, origH)` 解码。
2. `getPixels` → `argbToRgb` → `resizeRgb8(…, 518, 518)` → `toModelInput`。
3. 推理，输入名用 `session.inputNames.first()`。
4. 输出拷成 `FloatArray`，宽高取 shape 的最后两维。
5. `postprocessDepth(raw, rawW, rawH, outW, outH, blurRadius.toDouble())`，其中 `(outW, outH) = depthOutputSize(origW, origH)`，用原图尺寸，不用解码后的尺寸。
6. 灰度转 `ARGB_8888`（r = g = b），PNG 存到 `cacheDir/depth_<System.currentTimeMillis()>.png`。

进度点：5「正在读取图片」、15「正在准备模型」、30「正在分析深度」、80「正在平滑边缘」、95「正在保存」。所有 Bitmap 和 Tensor 用完就释放。`AppBridge` 这一步仍然写 `KEY_DEPTH_FILE`（Task 6 再去掉）；`app_ui.js` 的调用改成 `AppBridge.generateAIDepthMap(currentWallpaperFile, 8)`。

- [ ] **Step 4: 运行测试，然后构建**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 提交**

```bash
git add -A app/src
git commit -m "feat: 深度图生成换用新后处理，输出最长边 1920，大图解码降采样，分阶段报进度"
```

---

### Task 3: 渲染换成桌面的视差着色器

**Files:**
- Create: `assets/shared/parallax-math.js`（复制 `desk/shared/parallax-math.js`）
- Create: `assets/shared/shaders.js`（复制 `desk/renderer/shaders.js` 中的 `PARALLAX_VERTEX_SHADER`、`PARALLAX_GLSL`、`PARALLAX_FRAGMENT_SHADER` 三段，元素着色器不要；只复制部分时文件头写"节选自"）
- Modify: `assets/shared/core_engine.js`（渲染核心重写，生命周期部分保留）
- Modify: `assets/shared/config.js`（默认值：`parallaxMultiplier: 10`，新增 `focusDepth: 0.5`、`blurRadius: 8`，`desktopParallax: 5` 换成 `desktopOffsetWeight: 0.33`）
- Modify: `assets/wallpaper/wallpaper.html`、`assets/app/app.html`（脚本顺序：pixi → config → image_loader → parallax-math → shaders → core_engine → 页面脚本）
- Create: `dev/sample.jpg`、`dev/sample-depth.png`（竖屏 9:19.5，从 `desk/wallpapers/milky-way-pier.jpg` 和 `desk/wallpapers/depth/milky-way-pier.png` 居中裁切，宽 540 左右）、`dev/sample-src.jpg`（`milky-way-pier.jpg` 缩到宽 1280，给裁剪用）
- Create（不进 git，`.claude/` 已忽略）：`.claude/launch.json`，配置名 `phone-dev`，`runtimeExecutable` 用 `C:/Users/24418/Desktop/2/壁纸/venv/Scripts/python.exe`，参数 `-m http.server 8766`，端口 8766

**Interfaces:**
- Produces（`window.CoreEngine`）：
  - `init(bgUrl: string, depthUrl: string|null, mode: 'wallpaper'|'preview', container?: HTMLElement): Promise<void>` —— 预览模式没给 `container` 时用 `#preview-container`（旧界面还在用）
  - `mount(container: HTMLElement): void` —— 把 canvas 挪进容器，按容器尺寸重设渲染尺寸和 Cover
  - `setParams(p: {parallaxMultiplier?: number, smoothness?: number, focusDepth?: number}): void` —— 写进 `WallpaperConfig`，没初始化时也能调
  - `setView(view: 'image'|'depth'): void` —— `uImage` 换成深度图纹理 / 换回原图
  - 保留：`setSensorTarget(x, y)`、`setDesktopOffset(xOffset)`、`reloadImages(bg, depth)`、`resize()`、`pause()`、`resume()`、`destroy()`、`getApp()`

- [ ] **Step 1: 复制两个共享文件，核对和桌面一致**

Run: `diff desk/shared/parallax-math.js app/src/main/assets/shared/parallax-math.js`（`desk/` 换成实际路径）
Expected: 只差文件头那一行来源注释

- [ ] **Step 2: 重写 `core_engine.js` 的渲染部分**

- 按 `desk/renderer/parallax-scene.js` 的做法：一个全屏 `PIXI.Sprite(PIXI.Texture.WHITE)` 挂 `DepthParallaxFilter`。uniform 的名字和初值照抄。Cover 用 `Shared.parallax.coverTransform`，用容器尺寸（预览）或 `window.inner*`（壁纸）算。
- 每帧：`uMouse = [currentX + desktopOffsetX × WallpaperConfig.desktopOffsetWeight, currentY]`（**不取反**，保持旧 `DisplacementFilter` 的方向），`uStrength = Shared.parallax.strengthFromMultiplier(parallaxMultiplier)`，`uFocus = focusDepth`。数组 uniform 要整体赋值。
- 换纹理时先挂新纹理，再 `destroy(true)` 旧纹理，去掉 `PIXI.utils.destroyTextureCache()`。
- WebGL lost / restored 的处理照旧，恢复时重新加载两张纹理。

- [ ] **Step 3: 语法检查**

Run: `for f in app/src/main/assets/shared/*.js; do node --check "$f" || echo "FAIL $f"; done`
Expected: 没有 FAIL

- [ ] **Step 4: 浏览器里验证**

用 `preview_start` 启动 `phone-dev`，打开 `http://localhost:8766/app/src/main/assets/wallpaper/wallpaper.html`。在页面里执行：
1. `startWallpaper('/dev/sample.jpg', '/dev/sample-depth.png')`，截图：画面铺满、没有拉伸。
2. `CoreEngine.setSensorTarget(1, 0)`，等 1 秒再截图：近处的栈桥和远处的银河往相反方向错开，栈桥轮廓没有被扯开。
3. `CoreEngine.setView('depth')`，截图：显示的是深度图。

控制台没有报错。

- [ ] **Step 5: 构建并提交**

Run: `./gradlew assembleDebug`，Expected: `BUILD SUCCESSFUL`

```bash
git add app/src/main/assets dev
git commit -m "feat: 渲染换成桌面的 2.5D 视差着色器，新增对焦深度"
```

---

### Task 4: 空闲停帧和传感器去抖

**Files:**
- Create: `assets/shared/frame-pacing.js`（复制 `desk/shared/frame-pacing.js`）
- Modify: `assets/shared/core_engine.js`、`assets/wallpaper/wallpaper.html`（引入 frame-pacing）、`assets/app/app.html`（同上）、`assets/wallpaper/wallpaper_render.js`（调试版每秒打一次帧数）
- Create: `kt/util/TiltMath.kt`
- Modify: `kt/util/SensorHelper.kt`、`kt/wallpaper/LiveWallpaperService.kt`（`onTouchEvent`）、`kt/wallpaper/WallpaperBridge.kt`（加 `isDebug()`）
- Test: `ktest/util/TiltMathTest.kt`

**Interfaces:**
- Produces（JS）：`CoreEngine.getFrameCount(): number`（累计渲染帧数，调试和验证用）。`setSensorTarget` 值不变时不唤醒。
- Produces（Kotlin）：
  - `object TiltMath { const val BASE_TILT_DEG = 45.0; fun normalize(gx: Float, gy: Float, deadZoneDeg: Double, maxTiltDeg: Double): FloatArray }` —— 返回 `[x, y]`，算法就是现在 `SensorHelper` 里那段（y 先减去 45° 基准再取反）
  - `class TiltDebouncer(private val threshold: Float = 0.01f) { fun accept(x: Float, y: Float): Boolean; fun reset(x: Float, y: Float) }` —— 和上次接受（或 reset）的值比，任一轴差 ≥ 阈值才返回 true 并记下新值；第一次总是 true
  - `SensorHelper(context: Context, config: ConfigManager)`：死区和最大倾斜每次事件从配置读；`fun pushLatest()` 把最近一次算出的值（没有就是 0,0）直接推给回调，并 `reset` 去抖基准
  - `WallpaperBridge.isDebug(): Boolean` = `BuildConfig.DEBUG`

- [ ] **Step 1: 写失败的测试 `TiltMathTest`**

```kotlin
val g = 9.81
fun gs(deg: Double) = (g * sin(Math.toRadians(deg))).toFloat()
val base = gs(45.0)
// normalize_neutralIsZero
assertArrayEquals(floatArrayOf(0f, 0f), TiltMath.normalize(0f, base, 3.0, 30.0), 1e-4f)
// normalize_insideDeadZoneIsZero
assertEquals(0f, TiltMath.normalize(gs(2.0), base, 3.0, 30.0)[0], 1e-4f)
// normalize_saturatesAtMaxTilt
assertEquals(1f, TiltMath.normalize(gs(30.0), base, 3.0, 30.0)[0], 1e-4f)
assertEquals(1f, TiltMath.normalize(gs(60.0), base, 3.0, 30.0)[0], 1e-4f)
assertEquals(-1f, TiltMath.normalize(-gs(60.0), base, 3.0, 30.0)[0], 1e-4f)
// normalize_forwardTiltIsNegativeY
assertEquals(-1f, TiltMath.normalize(0f, base + gs(30.0), 3.0, 30.0)[1], 1e-4f)
// debouncer_threshold
val d = TiltDebouncer(0.01f)
assertTrue(d.accept(0f, 0f)); assertFalse(d.accept(0.005f, 0f))
assertTrue(d.accept(0.012f, 0f)); assertFalse(d.accept(0.015f, 0f))
// debouncer_resetMovesBaseline
d.reset(0.5f, 0.5f); assertFalse(d.accept(0.505f, 0.5f)); assertTrue(d.accept(0.52f, 0.5f))
```

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.wallpaper.live3d.util.TiltMathTest"`
Expected: `Unresolved reference: TiltMath`

- [ ] **Step 3: 实现 `TiltMath` / `TiltDebouncer`，改 `SensorHelper`**

- `SensorHelper` 保留 33ms 节流；每次事件先 `normalize` 并存成"最新值"，`debouncer.accept` 为 true 时才回调。
- `LiveWallpaperService.onTouchEvent` 的 `ACTION_UP` / `ACTION_CANCEL` 改成 `sensorHelper?.pushLatest()`，不再推 `(0, 0)`。
- 构造 `SensorHelper` 的地方都传 `ConfigManager`。

- [ ] **Step 4: 运行，确认通过**

Run: `./gradlew testDebugUnitTest --tests "com.wallpaper.live3d.util.TiltMathTest"`
Expected: 7 个断言组全过

- [ ] **Step 5: JS 端停帧**

照 `desk/renderer/main.js` 的"空闲停帧"一节：
- `IdleTracker({ graceFrames: 30 })`，`app.ticker.maxFPS = 60`。
- 每帧末尾 `target` 和 `current` 都 `isSettled` 且计满 30 帧就 `ticker.stop()`。
- `wake()` 的调用点：`setSensorTarget`（值变了）、`setDesktopOffset`、`setParams`、`setView`、`reloadImages`、`resize`、`resume`、WebGL 恢复、`mount`。`pause()` 之后到 `resume()` 之前 `wake()` 不启动 ticker。
- 加载或换纹理后立即 `app.renderer.render(app.stage)` 一次。

`wallpaper_render.js`：`AndroidBridge.isDebug()` 为 true 时每秒 `AndroidBridge.log('fps=' + 这一秒的帧数)`。

- [ ] **Step 6: 浏览器里验证停帧**

同 Task 3 打开 wallpaper.html，`startWallpaper(...)` 后执行：
1. `CoreEngine.setSensorTarget(0.4, 0)`，等 2 秒，记下 `a = CoreEngine.getFrameCount()`；再等 1 秒读 `b`。Expected: `a === b`（已停帧）。
2. `CoreEngine.setSensorTarget(-0.4, 0.2)`，0.5 秒后读 `c`。Expected: `c > b`（已唤醒）。

- [ ] **Step 7: 构建并提交**

Run: `./gradlew testDebugUnitTest assembleDebug`，Expected: `BUILD SUCCESSFUL`

```bash
git add -A app/src
git commit -m "feat: 空闲时停止渲染、帧率上限 60，传感器去抖，死区和最大倾斜读配置"
```

---

### Task 5: 配置整理和壁纸实时同步

**Files:**
- Modify: `kt/config/ConfigManager.kt`
- Modify: `kt/ui/AppBridge.kt`（`saveConfig` 改调 `applyParams`）
- Modify: `kt/wallpaper/WallpaperBridge.kt`（删掉 `saveConfig`、`getDepthFileName`、`hasDepthMap`，壁纸页没用到）
- Modify: `kt/wallpaper/LiveWallpaperService.kt`
- Modify: `assets/wallpaper/wallpaper_render.js`（启动时读 `focusDepth`，参数统一走 `CoreEngine.setParams`）

**Interfaces:**
- Consumes: Task 3 的 `CoreEngine.setParams`
- Produces（`ConfigManager`）：
  - `KEY_FOCUS = "focusDepth"`、`KEY_BLUR = "blurRadius"`；`DEFAULTS` 按 Global Constraints
  - `val PARAM_KEYS: Set<String>`（6 个参数键）、`val RENDER_KEYS = setOf(KEY_PARALLAX, KEY_SMOOTHNESS, KEY_FOCUS)`
  - `fun applyParams(json: JSONObject)` —— 只写 `PARAM_KEYS`，按范围钳住；`parallaxMultiplier`、`blurRadius` 用 `putInt`，其余用 `putFloat`；未知键和 `currentImage` / `depthFile` 一律忽略
  - `fun setWallpaper(image: String, depthFile: String)` —— 一次 `apply()` 写两个键
  - `fun paramsJson(): JSONObject` —— `{parallaxMultiplier, smoothness, focusDepth}`
  - `fun registerListener(l)` / `unregisterListener(l)`（`SharedPreferences` 是进程内单例，不同 `ConfigManager` 实例的改动都能收到）
  - `getAll()` 补上 `focusDepth`、`blurRadius`

- [ ] **Step 1: 改 `ConfigManager` 和两个 Bridge**

- [ ] **Step 2: `LiveWallpaperService` 加同步**

- 监听器存成字段（`SharedPreferences` 只弱引用监听器），`onCreate` 注册、`onDestroy` 注销。
- `RENDER_KEYS` 变了 → `pushToJs("CoreEngine.setParams(${paramsJson()})")`。
- `currentImage` / `depthFile` 变了 → `mainHandler.removeCallbacks(initRunnable); mainHandler.post(initRunnable)`，两个键一起变时只重载一次。`initRunnable` 调 `pushWallpaperInit()`，并记下推过的 `(image, depth)`。
- `onVisibilityChanged(true)`：先推一次参数；配置里的 `(image, depth)` 和上次推的不同就再 `pushWallpaperInit()`。
- `WallpaperPresentation.handlePause/handleResume` 删掉 `pauseTimers()` / `resumeTimers()`，只保留实例的 `onPause()` / `onResume()`（Review Focus 1）。

- [ ] **Step 3: 确认全局暂停已去掉，构建**

Run: `grep -rn "pauseTimers" app/src/main/java; ./gradlew assembleDebug`
Expected: grep 没有输出；`BUILD SUCCESSFUL`

- [ ] **Step 4: 提交**

```bash
git add -A app/src
git commit -m "feat: 调参数、换壁纸后桌面壁纸实时同步；配置写入集中到 ConfigManager"
```

---

### Task 6: App 桥接：选择和应用分开、文件命名、生命周期

**Files:**
- Create: `kt/util/WallpaperFiles.kt`
- Modify: `kt/ui/AppBridge.kt`、`kt/MainActivity.kt`、`kt/depth/DepthEstimator.kt`（文件名改用 `WallpaperFiles.newName`）
- Modify: `assets/app/app_ui.js`（旧界面临时适配，Task 8 会删掉）
- Test: `ktest/util/WallpaperFilesTest.kt`

**Interfaces:**
- Consumes: Task 5 的 `ConfigManager.setWallpaper`、Task 4 的 `SensorHelper`、Task 2 的 `estimateDepth`
- Produces（`object WallpaperFiles`）：
  - `fun newName(prefix: String, ext: String, now: Long = System.currentTimeMillis()): String` → `"${prefix}_${now}.$ext"`
  - `fun cleanUnreferenced(wallpapersDir: File, depthDir: File, keepImage: String, keepDepth: String)` —— 删掉 `wallpapersDir` 里除 `keepImage` 以外的文件，删掉 `depthDir` 里除 `keepDepth` 以外的 `*.png`（`index.json` 等其他文件不动）
- Produces（`AppBridge`，Task 7 的 `bridge.js` 照这个包装；之前的 `hasDepthMap`、`getDepthFileName`、`saveDepthMap` 删掉）：

| 方法 | 说明 |
|---|---|
| `getConfig(): String` | 参数 + `currentImage` + `depthFile` |
| `saveConfig(json: String)` | → `applyParams` |
| `openImagePicker()` | 复制成 `pick_<ts>.<ext>` 后调 `window.onImageSelected(pickedName)` |
| `saveCroppedImage(pickedName, dataUrl): String` | 存成 `wp_<ts>.jpg` 并返回文件名，删掉 pick 文件，不写配置；失败返回 `""` |
| `discardPickedImage(pickedName)` | 取消裁剪时删 pick 文件 |
| `generateAIDepthMap(imageName, blurRadius)` | 不再写配置 |
| `applyWallpaper(image, depth): Boolean` | `setWallpaper` 后：本应用已是当前动态壁纸返回 true；否则 `openLiveWallpaperPicker()` 并返回 false |
| `openLiveWallpaperPicker()` | `ACTION_CHANGE_LIVE_WALLPAPER` + `EXTRA_LIVE_WALLPAPER_COMPONENT`（`LiveWallpaperService`）→ 失败退 `ACTION_LIVE_WALLPAPER_CHOOSER` → 再失败退 `ACTION_SET_WALLPAPER` 选择器 |
| `getStatus(): String` | `{"wallpaperActive": Boolean, "batteryOptimizationIgnored": Boolean}` |
| `getPlatformInfo(): String` | 现有字段 + `versionName`、`androidVersion`（`Build.VERSION.RELEASE`） |
| `requestBatteryOptimizationBypass()`、`requestOverlayPermission()`、`log(msg)` | 不变 |

- Produces（Kotlin → JS）：`window.onImageSelected(pickedName)`、`window.onDepthProgress(percent, text)`、`window.onAIDepthReady(fileName|null, error|null)`、`window.onSensorTilt(x, y)`、`window.onAppResume()`。调用前都判断函数是否存在。

- [ ] **Step 1: 写失败的测试 `WallpaperFilesTest`**（JUnit `TemporaryFolder`）

- `newName_format`：`assertEquals("wp_1700000000000.jpg", WallpaperFiles.newName("wp", "jpg", 1700000000000))`
- `clean_keepsReferenced_removesOthers`：建 `wp_1.jpg`、`wp_2.jpg`、`pick_3.png` 和 `depth_1.png`、`depth_2.png`、`index.json`；`cleanUnreferenced(w, d, "wp_1.jpg", "depth_1.png")` 之后，wallpapers 只剩 `wp_1.jpg`，depth 只剩 `depth_1.png`、`index.json`
- `clean_emptyKeepsDeletesAll`：keep 都是 `""` 时两个目录里的图片都被删掉，目录不存在时不抛异常

- [ ] **Step 2: 运行，确认失败**

Run: `./gradlew testDebugUnitTest --tests "com.wallpaper.live3d.util.WallpaperFilesTest"`

- [ ] **Step 3: 实现 `WallpaperFiles`，按上表改 `AppBridge`**

- [ ] **Step 4: 改 `MainActivity`**

- `onCreate` 加载页面前，用配置里的 `currentImage` / `depthFile` 调 `cleanUnreferenced`。
- `onResume`：`webView.resumeTimers()`，启动 `SensorHelper` 推 `window.onSensorTilt(x, y)`，然后调 `window.onAppResume()`。
- `onPause`：停 `SensorHelper`。
- `onStop` 销毁 Pixi 的做法保留。
- 新增 `isOurWallpaperActive()`（`WallpaperManager.getInstance(this).wallpaperInfo?.component == ComponentName(this, LiveWallpaperService::class.java)`）和 `isIgnoringBatteryOptimizations()`。

- [ ] **Step 5: 旧界面临时适配**

`app_ui.js` 只改三处：
- "设为系统壁纸"改调 `AppBridge.applyWallpaper(currentWallpaperFile, currentDepthFile || '')`。
- 取消裁剪时调 `AppBridge.discardPickedImage(...)`。
- 去掉 `getDepthFileName` 的调用。

- [ ] **Step 6: 运行测试并构建**

Run: `./gradlew testDebugUnitTest assembleDebug`，Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 提交**

```bash
git add -A app/src
git commit -m "feat: 选图和应用分开，裁剪和深度图文件名唯一并清理旧文件，应用壁纸直达本应用预览页"
```

---

### Task 7: 新界面基础：样式、图标、外壳、桥接

**Files:**
- Create: `assets/ui/tokens.css`（从 `desk/ui/tokens.css` 复制后改动，所以不算逐字节复制：`--font` 换成 `system-ui, "Noto Sans CJK SC", sans-serif`，按钮高 42px，滑块拇指 20px；文件头注明"基于桌面 ui/tokens.css"）
- Create: `assets/ui/icons.js`（沿用 `desk/ui/icons.js` 的结构，包括 `UIControls.paintRange`；只留 image、image-plus、layers、settings、sparkles、check、circle-check、loader-circle、rotate-ccw、battery、smartphone、triangle-alert、x，`loader-circle` 从 lucide-static@0.460.0 取）
- Create: `assets/shared/config-sync.js`（复制 `desk/shared/config-sync.js`）
- Create: `assets/app/bridge.js`、`assets/app/main.js`、`assets/app/shell.js`、`assets/app/controls.js`、`assets/app/app.css`
- Modify: `assets/app/app.html`（重写为新骨架，不再加载 `app_ui.js` / `app_style.css`）
- Modify: `kt/MainActivity.kt`、`app/src/main/res/layout/activity_main.xml`、`app/src/main/res/values/colors.xml`、`themes.xml`（全面屏适配）

**Interfaces:**
- Produces（`window.Bridge`）：`isNative`、`getConfig(): object`、`saveConfig(partial)`、`openImagePicker()`、`saveCroppedImage(picked, dataUrl): string`、`discardPickedImage(picked)`、`generateDepth(image, blurRadius)`、`applyWallpaper(image, depth): boolean`、`openLiveWallpaperPicker()`、`getStatus(): {wallpaperActive, batteryOptimizationIgnored}`、`getPlatformInfo(): object`、`requestBatteryOptimizationBypass()`、`localUrl(dir: 'wallpapers'|'depth_cache', name): string`、`log(msg)`。
  - 有 `window.AppBridge` 时转调 Task 6 的接口（JSON 字符串在这里解析）。
  - 没有时用假实现，给浏览器开发用：初始配置 `currentImage: 'sample.jpg'`、`depthFile: 'sample-depth.png'`；`sample*` 文件映射到 `/dev/`；选图回调 `sample-src.jpg`；裁剪结果的 dataUrl 存在内存里按 `wp_<ts>.jpg` 取；生成深度图约 2 秒内分阶段回调 `onDepthProgress`，最后回调 `onAIDepthReady('sample-depth.png', null)`。
  - 假实现的 URL 参数：`?fail=depth`（生成失败）、`?active=0`（本应用不是壁纸）、`?xiaomi=0`（非小米）。
- Produces（`window.App`，在 `main.js`）：
  - `App.state = { config, applied: {image, depth}, selected: {image, depth}, status, platform }`
  - `App.on(event, cb)` / `App.emit(event)`，事件有 `'selected'`、`'applied'`、`'status'`、`'config'`
  - `App.isSelectedApplied(): boolean`
  - `App.registerPage({ name, init() })` —— 页面脚本加载时注册；`DOMContentLoaded` 时读配置、状态、平台信息，`selected = applied`，再依次 `init()`
  - `App.saver` = `Shared.configSync.createConfigSaver(Bridge.saveConfig, 100)`
  - `window.onAppResume` 刷新 `status` 并 `emit('status')`，其余处理由页面订阅
- Produces（`window.Shell`）：`showTab(name: 'wallpaper'|'parallax'|'system')`、`onTabChange(cb)`、`toast(message, kind?: 'ok'|'error'|'info')`（ok / info 3 秒，error 6 秒，新的顶掉旧的）
- Produces（`window.Controls`）：`bindRange(input, valueEl, format, onInput)`、`setRange(input, value)`、`segmented(container, onChange) → { set }`（照搬桌面 `settings/controls.js` 的这三个）

- [ ] **Step 1: 写好上面这些文件**

`app.html` 的结构按 mockup：三个 `<section class="page" data-page="...">`，底部 `<nav class="tabbar">` 三个按钮，`#toast`，`#crop-overlay`（内容 Task 8 填）。脚本顺序：
1. pixi
2. shared：config → image_loader → parallax-math → shaders → frame-pacing → config-sync → core_engine
3. ui/icons
4. app：bridge → controls → shell → main
5. 各页面

- [ ] **Step 2: 全面屏适配（targetSdk 36 在 Android 15+ 上强制全面屏）**

- 根 `FrameLayout` 用 `ViewCompat.setOnApplyWindowInsetsListener` 把系统栏和刘海的 inset 设成 padding。
- 根背景改为 `#0b0f15`（`--ink`），`windowBackground` 和 `webView.setBackgroundColor` 用 `#10161e`（`--bg`），避免白屏闪一下。

- [ ] **Step 3: 语法检查和构建**

Run: `for f in app/src/main/assets/app/*.js app/src/main/assets/ui/*.js app/src/main/assets/shared/*.js; do node --check "$f" || echo "FAIL $f"; done; ./gradlew assembleDebug`
Expected: 没有 FAIL；`BUILD SUCCESSFUL`

- [ ] **Step 4: 浏览器里验证外壳**

1. 打开 `http://localhost:8766/app/src/main/assets/app/app.html`，`resize_window` 用 mobile 预设。
2. 点三个页签，各自切换。
3. 执行 `Shell.toast('测试', 'ok')`：页签栏上方出现提示，3 秒后消失。
4. 执行 `Shell.toast('错', 'error')`，再马上 `Shell.toast('新', 'ok')`：只剩"新"。
5. 控制台没有报错。

- [ ] **Step 5: 提交**

```bash
git add -A app/src/main
git commit -m "feat: 新界面骨架：配色和控件、图标、页签和提示条、桥接（含浏览器假实现）、全面屏适配"
```

---

### Task 8: 壁纸页、实时预览、裁剪

**Files:**
- Create: `assets/app/preview.js`、`assets/app/wallpaper-page.js`、`assets/app/cropper.js`
- Modify: `assets/app/app.html`（壁纸页和裁剪界面的结构）、`assets/app/app.css`
- Delete: `assets/app/app_ui.js`、`assets/app/app_style.css`

**Interfaces:**
- Consumes: Task 3 的 `CoreEngine`，Task 7 的 `Bridge` / `App` / `Shell` / `Controls`
- Produces（`window.Preview`）：
  - `show(image: string, depth: string): Promise<{width: number, height: number}>` —— 用 `Bridge.localUrl` 初始化或热替换预览
  - `mount(container: HTMLElement)`、`setView('image'|'depth')`、`rebuild(): Promise<void>`（用 `App.state.selected` 重建，回到前台时用）、`hasDepth(): boolean`
  - 输入规则：
    - `window.onSensorTilt(x, y)` 记为最新倾斜值；手指没按在预览上、并且有深度图时才转给 `CoreEngine.setSensorTarget`。
    - 预览上 `touchstart` / `touchmove` 按相对位置驱动；`touchend` 时推最新倾斜值。
    - 没有深度图时不转发任何输入，画面不动，并显示遮罩"生成深度图后可以预览视差"。
  - 预览容器的宽高比 = `platform.screenWidth / screenHeight`。顶部提示"倾斜手机，或按住拖动预览"，第一次触摸或 4 秒后淡出。
- Produces（`window.Cropper`）：`open(pickedName: string, aspect: number): Promise<string|null>`（返回 JPEG dataUrl，取消返回 null）、`isOpen(): boolean`。裁剪逻辑从旧 `app_ui.js` 的 `showCropper` ~ `cleanupCropUI` 搬过来，交互不变，DOM 和样式按 mockup ⑤。图片加载失败返回 null，并提示"图片打不开"。

- [ ] **Step 1: 实现 `preview.js` 和 `cropper.js`**

- [ ] **Step 2: 实现 `wallpaper-page.js`（按 mockup ① ②）**

- 顶栏胶囊：`status.wallpaperActive` 时显示"已设为桌面壁纸"（ok 样式），否则显示"未设为桌面壁纸"。
- 预览下面：分辨率（`Preview.show` 的结果）；`isSelectedApplied()` 时加"使用中"标签；"原图 | 深度图"分段按钮只在有深度图时出现，换图时回到"原图"。
- 深度图状态四种：已就绪 / 未生成 / 生成中（进度条 + `onDepthProgress` 的文字）/ 失败（显示原因）。
- 边缘平滑：范围 0~30，用 `App.saver.save({blurRadius})` 保存；`config` 事件时回填（视差页"恢复推荐值"会改它）。
- 按钮状态：

| 按钮 | 禁用条件 | 文字 |
|---|---|---|
| 选择图片 | 生成中 | 选择图片 |
| 生成深度图 | 生成中，或没有选中图片 | 有深度图时"重新生成"，否则"生成深度图" |
| 应用壁纸 | 生成中，或没有深度图，或 `isSelectedApplied()` | `isSelectedApplied()` 时"正在使用这张"，否则"应用壁纸" |

- 流程：
  - 选择 → `Bridge.openImagePicker()` → `onImageSelected(picked)` → `Cropper.open(picked, aspect)`。
    - 确认：`saveCroppedImage`（返回空串就提示错误）→ `selected = {image, depth: ''}` → `emit('selected')` → `Preview.show`。
    - 取消：`discardPickedImage(picked)`。
  - 生成：`Bridge.generateDepth(selected.image, App.state.config.blurRadius)`，记下这次是为哪张图生成的；`onAIDepthReady` 回来时如果那张图已经不是选中的就忽略。成功时 `selected.depth = file`，切到"深度图"视图，提示"深度图已生成"；失败时进入失败状态，提示原因。不做任何算法降级。
  - 应用：`Bridge.applyWallpaper` → `applied = {...selected}` → `emit('applied')`；返回 true 时提示"已应用到桌面壁纸"。
- 订阅 `status`：`Cropper.isOpen()` 时不动；否则 `Preview.rebuild()` 并刷新胶囊。
- 切页签时把预览挂回这一页的容器。

- [ ] **Step 3: 删掉旧文件，语法检查和构建**

Run: `git rm app/src/main/assets/app/app_ui.js app/src/main/assets/app/app_style.css; for f in app/src/main/assets/app/*.js; do node --check "$f" || echo "FAIL $f"; done; ./gradlew assembleDebug`
Expected: 没有 FAIL；`BUILD SUCCESSFUL`

- [ ] **Step 4: 浏览器里走一遍（mobile 视口，每步截图和 mockup 对比）**

1. 打开 app.html：显示 `sample.jpg`，有"使用中"标签，"应用壁纸"显示"正在使用这张"且禁用；执行 `onSensorTilt(0.8, 0)` 后画面错开。
2. 选择图片 → 出现裁剪界面 → 确认：预览换成裁好的图并显示遮罩，状态"未生成"，"应用壁纸"禁用。
3. 生成：进度条走完，切到深度图视图，"应用壁纸"可点；点击后提示"已应用到桌面壁纸"，标签变"使用中"。
4. Review Focus 4（裁剪中）：再选一次图，裁剪界面打开时执行 `onAppResume()`：裁剪界面还在，确认后照常。
5. Review Focus 4（生成中）：生成中执行 `onAppResume()`：进度继续，结果照常送达，预览不空白。
6. 加 `?fail=depth` 重开：生成失败显示原因，"生成深度图"可以重试。
7. Review Focus 5：加 `?active=0` 重开，点"应用壁纸"后执行 `onAppResume()`：胶囊显示"未设为桌面壁纸"。

控制台没有报错。

- [ ] **Step 5: 提交**

```bash
git add -A app/src/main/assets
git commit -m "feat: 新界面壁纸页：手机形状实时预览（陀螺仪 + 手指）、生成进度、选图和应用分开、新样式裁剪"
```

---

### Task 9: 视差页和设置页

**Files:**
- Create: `assets/app/parallax-page.js`、`assets/app/system-page.js`
- Modify: `assets/app/app.html`、`assets/app/app.css`

**Interfaces:**
- Consumes: Task 7 的 `App` / `Bridge` / `Controls` / `Shell`，Task 8 的 `Preview.mount`
- Produces: 无新接口。

- [ ] **Step 1: 实现 `parallax-page.js`（mockup ③）**

| 滑块 | 范围 | 显示 | 写入 |
|---|---|---|---|
| 视差强度 | 5~100，步长 1 | `10` | `parallaxMultiplier` |
| 跟手程度 | 1~30（÷100） | `0.05` | `smoothness` |
| 对焦深度 | 0~100（÷100），深度色带，两端"远 / 近" | `0.50` | `focusDepth` |
| 静止范围 | 0~10，步长 0.5 | `3.0°` | `deadZoneDeg` |
| 最大倾斜 | 10~60，步长 1 | `30°` | `maxTiltDeg` |

- 每项下面一行说明，文字照 mockup。
- 拖动时更新 `App.state.config`；前三项同时调 `CoreEngine.setParams`；用 `App.saver.save` 保存。
- "恢复推荐值"：按 Global Constraints 的值设好六项（含边缘平滑）、整体保存、`emit('config')`、提示"已恢复推荐值"。
- 切到这一页时 `Preview.mount(小预览容器)`。

- [ ] **Step 2: 实现 `system-page.js`（mockup ④）**

- 动态壁纸：`wallpaperActive` 时显示"正在使用本应用"，按钮"重新设置"；否则显示"未设为桌面壁纸"，按钮"设为壁纸"。都调 `Bridge.openLiveWallpaperPicker()`。`applied.image` 为空时按钮禁用，说明改为"先在"壁纸"页应用一张图片"。
- 电池优化：已关闭时显示"已关闭"；否则显示"未关闭 · 壁纸可能被系统清理"和按钮"去关闭"。
- 小米 / HyperOS 提示：只在 `platform.isXiaomi` 时显示，文字照 mockup。
- 关于：`versionName`；`manufacturer model · Android androidVersion`。
- 订阅 `status` 刷新。

- [ ] **Step 3: 语法检查和构建**

Run: `for f in app/src/main/assets/app/*.js; do node --check "$f" || echo "FAIL $f"; done; ./gradlew assembleDebug`
Expected: 没有 FAIL；`BUILD SUCCESSFUL`

- [ ] **Step 4: 浏览器里验证（mobile 视口）**

1. 视差页：拖强度到 60，`onSensorTilt(0.8, 0)` 后错开幅度明显变大；`Bridge.getConfig().parallaxMultiplier === 60`。
2. 点"恢复推荐值"：六项回到推荐值，回壁纸页看边缘平滑显示 `8px`。
3. 设置页：默认显示小米提示；加 `?xiaomi=0` 后不显示。
4. 来回切页签，预览挂到对应页面并继续动。

截图和 mockup ③ ④ 对比；控制台没有报错。

- [ ] **Step 5: 提交**

```bash
git add -A app/src/main/assets
git commit -m "feat: 新界面视差页（对焦深度、陀螺仪参数）和设置页"
```

---

### Task 10: 收尾：清理、版本号、项目笔记、整体验证

**Files:**
- Delete: `kt/depth/DepthCacheManager.kt`（Task 5、6 之后已经没人引用；删之前 `grep -rn DepthCacheManager app/src` 确认）
- Modify: `app/build.gradle.kts`（`versionCode = 2`、`versionName = "1.1.0"`）
- Create: `PROJECT_NOTES.md`：
  - 项目地图（每个文件一行）和桌面对应表（哪些文件复制自桌面、改动要同步）。
  - 浏览器开发预览怎么开（`phone-dev`、URL、假桥接参数）。
  - 这一轮做了什么。
  - 真机检查清单及结果。
  - 已知但没做的事：模型存两份、`transformers.min.js`。

- [ ] **Step 1: 清理和改版本号**

- [ ] **Step 2: 整体验证**

Run: `./gradlew clean testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`，DepthProcessingTest / TiltMathTest / WallpaperFilesTest 全过

Run: `for f in app/src/main/assets/{app,ui,shared,wallpaper}/*.js; do case "$f" in *.min.js) ;; *) node --check "$f" || echo "FAIL $f";; esac; done`
Expected: 没有 FAIL

Run：对四个复制文件（`parallax-math.js`、`frame-pacing.js`、`config-sync.js`、`shaders.js` 节选）分别和桌面原文件 `diff`。
Expected: 只差来源注释（`shaders.js` 另外只少了元素着色器那部分）

再在浏览器里把 Task 8、9 的步骤快速走一遍。

- [ ] **Step 3: 真机**

`adb devices` 有设备时：`adb install -r app/build/outputs/apk/debug/app-debug.apk`，按设计文档第 6 节的 8 条逐条测，结果写进 `PROJECT_NOTES.md`；停帧用 `adb logcat -s "[WallpaperJS]"` 看 `fps=0`。没有设备时把 8 条清单交给用户，标明"未在真机验证"。

- [ ] **Step 4: 提交**

```bash
git add -A
git commit -m "chore: 版本号 1.1.0，删除不再使用的深度缓存索引，新增项目笔记"
```
