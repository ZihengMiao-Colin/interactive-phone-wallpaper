# 手机版：修割裂、新界面、空闲停帧 — 设计

> 2026-10-29。范围由用户确认（修割裂 + 新界面 + 空闲停帧，外加两处直接相关的修复）。界面方案用户交给助手决定，mockup 在 `.superpowers/mockups/phone-ui.html`（不进 git），等用户确认。
>
> 桌面项目：`C:\Users\24418\Desktop\2\壁纸`（下文简称"桌面"，路径都相对它）。手机项目：本仓库。

## 为什么做

- 手机版（Android，Kotlin 外壳 + WebView + Pixi 7.4.2）能正常用，用户对陀螺仪的手感满意。**主要问题是壁纸动起来有明显割裂。**
- 读代码判断的原因，桌面都已经解决过（桌面提交 `7624578`"升级 2.5D 自定义视差着色器，消除动态割裂与画面拉伸变形"）：
  1. 渲染用 Pixi 自带的 `DisplacementFilter`，按深度硬推像素，深度跳变处画面被扯开。
  2. 深度图后处理把前景往里腐蚀，过渡带落在主体身上，主体轮廓一动就撕。桌面改成"近处轮廓往外扩，再做高斯"，过渡带整个落在背景上。
- 手机代码停在 2026-07 初。桌面之后做了界面改版和空闲停帧，用户希望手机这一轮也有这两样，其他桌面功能不要。

## 目标

1. 动起来不再割裂：渲染公式和深度图后处理与桌面 v1 一致。
2. 新界面：用桌面改版后的视觉风格，按手机竖屏重排（见 mockup）。
3. 空闲时停止渲染：手机静止、画面停稳后不再出帧，桌面壁纸和 App 里的预览都是。
4. 在 App 里调参数、换壁纸后，桌面壁纸马上跟着变（新界面成立的前提）。

## 不做

- 自定义元素、天气、视频特效、唱片机、默认壁纸图库。
- 已知但这轮不碰：模型在 assets 和 `filesDir` 各存一份、`assets/app/transformers.min.js` 没被引用、深度缓存索引（AI 生成的深度图没写进 `depth_cache/index.json`）。
- 不换技术栈（不改 Compose、不改原生 GL 渲染），JS 不引入框架和构建步骤。
- 不做浅色主题、多语言、平板 / 横屏布局。

## 反目标（做成这样就算失败）

- 陀螺仪手感变差：方向反了、变迟钝或更抖。
- 静止时桌面壁纸还在持续出帧；或者停帧后一拿起手机，画面要顿一下才跟上。
- 在 App 里选图、裁剪、生成深度图的过程中，桌面壁纸被改掉。
- 现有功能在新界面里找不到：选图、裁剪、生成深度图、调参数、设为壁纸、电池优化引导、小米提示。

## 约束

- 两个独立仓库，不建共享依赖。从桌面复制过来的文件，文件头注明来源和"改动要两边同步"。
- 两边 Pixi 都是 7.4.2，桌面的着色器可以直接用。
- 模型：Depth Anything V2 Small fp32（就是桌面根目录的 `depth_anything_v2_small_ir9.onnx`，`.onnx` 不进 git），固定 518×518 输入，ONNX Runtime Android 1.17.3，CPU。
- 构建：`./gradlew assembleDebug`（AGP 9.0、Gradle 9.3、compileSdk 36、minSdk 24）。
- 真机验证由用户做；如果 adb 连着手机，助手也可以装上去测。
- 壁纸服务和 App 在同一个进程（manifest 里没有 `android:process`）。

## 设计

### 1. 渲染：换成桌面的着色器

- 新文件 `assets/shared/shaders.js`：复制桌面 `renderer/shaders.js` 的背景部分（`PARALLAX_VERTEX_SHADER`、`PARALLAX_GLSL`、`PARALLAX_FRAGMENT_SHADER`），元素着色器不要。
- 新文件 `assets/shared/parallax-math.js`：原样复制桌面 `shared/parallax-math.js`（UMD，挂在 `window.Shared.parallax`）。
- `assets/shared/core_engine.js`：去掉 `DisplacementFilter` 和两个精灵的结构，改成桌面 `renderer/parallax-scene.js` 的做法：一个全屏白色精灵挂 `DepthParallaxFilter`。uniform 有 `uImage`、`uDepth`、`uMouse`、`uStrength`、`uFocus`、`uCoverScale`、`uCoverOffset`；Cover 用 `Shared.parallax.coverTransform` 算，强度用 `strengthFromMultiplier`。预览模式按容器尺寸算 Cover。
- 新参数**对焦深度** `focusDepth`（0~1，默认 0.5）：这个深度的内容不动。
- **方向**：旧 `DisplacementFilter` 的采样偏移等价于 `+(depth − 0.5) × current × 系数`，也就是桌面公式里 `uMouse = +current`。桌面传的是 `−current`（鼠标右移时近景向左），手机要传 `+current`（不取反），这样方向和现在一样。这是推导出来的，要在真机上确认。
- **强度**：沿用桌面换算（`multiplier / 1000 × 1.25`）。滑块范围还是 5~100，默认值从 15 改成桌面的 10。老用户存的值直接沿用。真机上默认值明显偏大或偏小时按手感调，并告诉用户。
- **桌面滑动偏移**（`onOffsetsChanged`）：并进输入，`uMouse.x` 加上 `desktopOffsetX × 权重`，权重让效果和现在差不多弱。
- 软截断、`highp`、采样坐标 clamp 照搬桌面。
- 保留现有的 WebGL context lost / restored 处理、`pause` / `resume` / `destroy`、`reloadImages`。换纹理时 `destroy(true)` 旧纹理（桌面做法），代替现在的 `PIXI.utils.destroyTextureCache()`。
- 没有深度图时仍用占位深度图。

### 2. 深度图后处理：把桌面算法移植到 Kotlin

- 新文件 `depth/DepthProcessing.kt`：纯函数，不依赖 Android API，方便 JVM 单元测试。从桌面 `src/depth/processing.js` 移植：
  - `depthOutputSize`：保持原图比例，最长边不超过 1920。
  - `resizeRgb8`：Pillow 兼容的 bicubic（8 位定点），把图缩到 518×518；`toModelInput`：ImageNet 归一化，按单精度逐步取整。
  - `postprocessDepth`：归一化 → `resizeFloat`（浮点 bicubic）放大到输出尺寸 → `dilateMax(blurSupport(σ))` 近处外扩 → `gaussianBlur(σ)`（三次均值滤波）→ 量化（四舍六入五取偶）。
- `DepthEstimator` 改成：Bitmap → RGBA 字节 → `resizeRgb8` → 推理 → `postprocessDepth` → 8 位灰度 PNG。删掉 `applyEdgeSmoothing`、腐蚀和旧的放大。
- **边缘平滑** σ 由界面传进来，存在配置 `blurRadius`（默认 8，范围和桌面滑块一致）。
- **正确性**：JVM 单元测试，同样的输入数组，Kotlin 结果要和桌面 `processing.js` 逐值一致。对照数据可以复用桌面 `test/depth-processing.test.js` 里 Pillow 生成的数据，也可以写个小 Node 脚本用桌面代码新生成，放到 `app/src/test/resources/`。
- Android 解码 JPEG 和 Chromium 可能差几个像素值，所以只要求"同样的像素进来，结果和桌面一致"，不要求同一张 JPEG 端到端逐像素一致。
- 防内存溢出（助手的决定）：手机拍的大图裁完可能有 4000×8000 以上，整张转成 RGBA 要一百多 MB。解码时用 `inSampleSize` 把最长边控制在 4096 以内，输出深度图尺寸仍按原图比例算（最长边 1920）。
- 推理仍在 `Dispatchers.IO`。分阶段（准备 / 推理 / 平滑）把进度报给界面，用来驱动 mockup ② 的进度条；百分比按阶段估计就行。
- 旧深度图不自动迁移，用户重新点"生成深度图"才换成新算法（和桌面一致）。
- **去掉 JS 端的"传统 CV 算法"降级**（`generateSimpleDepthMap`、30 秒超时降级、`AppBridge.saveDepthMap` 的 Base64 路径）：它生成的深度图质量差，本身就会割裂。AI 失败时显示原因，可以重试。

### 3. 空闲时停止渲染

- 新文件 `assets/shared/frame-pacing.js`：原样复制桌面 `shared/frame-pacing.js`。
- `core_engine.js` 的主循环照桌面 `renderer/main.js` 写：每帧插值后，`target` 和 `current` 都收敛（`isSettled`）并且连续 30 帧（约 0.5 秒）就 `ticker.stop()`，最后一帧留在屏幕上。
- `wake()` 的时机：收到新的传感器目标、桌面偏移变化、参数变化、换图、尺寸变化、`resume`、WebGL 恢复。暂停（壁纸不可见）期间不自己唤醒。换图后先立刻渲染一帧（桌面做法），避免停帧时画面是空的。
- 帧率上限 60（`ticker.maxFPS = 60`，和桌面一致；手机屏幕多是 90 / 120Hz）。
- **传感器去抖**（Kotlin `SensorHelper`）：新的归一化值和**上次推送的值**相差小于阈值（先取 0.01 左右，真机调）就不推。慢慢倾斜时仍会按阈值一步步推，JS 插值让画面连续。这样静止时几乎不调用 `evaluateJavascript`，引擎也不会被噪声一直叫醒。
- 壁纸上的触摸输入（`onTouchEvent` 推手指位置，松手推 0）保持现状。但有了去抖之后，松手后陀螺仪可能很久都不推新值，画面会停在中间。所以触摸结束时要立刻补推一次当前的陀螺仪值；去抖比较的基准也要跟着更新成补推的值。
- App 里的预览用同一个引擎，同样停帧。
- 验证方法：调试版每秒在 logcat 打一次帧数（或用 `chrome://inspect` 看），手机平放 3 秒后应为 0 帧，拿起来马上恢复。

### 4. 参数和壁纸实时同步

**配置**
- 新增 `focusDepth`（默认 0.5）、`blurRadius`（默认 8）；`parallaxMultiplier` 默认改为 10。
- `ConfigManager` 提供统一的写入方法（比如 `applyJson`），`AppBridge` 和 `WallpaperBridge` 不再各自写一遍同样的代码。
- `SensorHelper` 的死区和最大倾斜改成读配置（现在写死在 3° / 30°，滑块调了不生效）。

**壁纸服务跟着变**
- `WallpaperEngine` 注册 `SharedPreferences.OnSharedPreferenceChangeListener`（用字段强引用保存，`onDestroy` 时注销；系统壁纸预览和正式壁纸可能同时有两个 engine，各管各的）：
  - 强度、跟手程度、对焦深度变了 → 推给 JS（新接口，比如 `CoreEngine.setParams({...})`），并唤醒。
  - 死区、最大倾斜变了 → `SensorHelper` 用新值。
  - `currentImage` / `depthFile` 变了 → 推 `startWallpaper(bg, depth)` 热替换。
  - 要保证回到桌面时壁纸已经是最新的。壁纸不可见时是直接推，还是先记下来、等可见时再推，实现时定。

**"选择"和"应用"分开**（桌面阶段 1 修过同样的问题。有了实时同步之后不分开的话，选图那一刻桌面壁纸就会被换掉）
- 配置里的 `currentImage` / `depthFile` 只表示"桌面壁纸正在用的"，只有点"应用壁纸"才写。
- 选图、裁剪、生成深度图只改界面里的"选中图片"状态：`saveCroppedImage` 不再写配置；`generateAIDepthMap` 不再写配置，只把深度图文件名回调给界面。
- 裁剪后的文件名要唯一（比如带时间戳），深度图文件名跟着裁剪后的文件走，保证不会覆盖桌面正在用的那张。启动时删掉既不在配置里、也不是当前选中的旧图和旧深度图。
- App 启动时，选中的就是正在用的。
- "应用壁纸"要有深度图才能点（mockup ②）。
- 点"应用壁纸"：写配置 → 如果本应用已经是当前动态壁纸，提示"已应用到桌面壁纸"（壁纸通过同步马上换）；如果不是，直接跳到本应用的动态壁纸预览页（`WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER` + `EXTRA_LIVE_WALLPAPER_COMPONENT`）。跳不过去时退到 `ACTION_LIVE_WALLPAPER_CHOOSER`，再不行退到现在的通用选择器。
- 判断"本应用是不是当前动态壁纸"：`WallpaperManager.getInstance(ctx).wallpaperInfo?.component`。App 回到前台时重新查（从系统壁纸页返回后状态可能变了）。

### 5. 新界面

mockup：`.superpowers/mockups/phone-ui.html`（五个画面：壁纸页正在使用、壁纸页换新图生成中、视差页、设置页、裁剪）。

**视觉**
- 颜色、圆角变量和基础控件（按钮、滑块、深度色带、分段按钮）从桌面 `ui/tokens.css` 复制过来，触控尺寸放大：按钮高至少 40px，滑块拇指 20px。
- 字体用系统字体（`system-ui, "Noto Sans CJK SC", sans-serif`），不打包字体。
- 图标从桌面 `ui/icons.js` 挑需要的 Lucide 图标，做成离线雪碧图。不用 emoji。
- 操作反馈用页签栏上方的 Toast（毛玻璃），代替 `alert()`。

**结构**：底部三个页签"壁纸 / 视差 / 设置"，内容区各自滚动。

**壁纸页**（mockup ① ②）
- 顶栏：标题 + 状态胶囊（"已设为桌面壁纸" / "未设为桌面壁纸"）。
- 手机形状的实时预览：按设备屏幕比例，高度约占屏幕一半。App 在前台时 `MainActivity` 也开 `SensorHelper` 推给预览，所以倾斜手机就能看到效果；手指按住拖动时改为跟手指，松手回到陀螺仪。顶部提示"倾斜手机，或按住拖动预览"，第一次交互后淡出。没有深度图时预览不动，显示"生成深度图后可以预览视差"。
- 预览下面：分辨率；选中的就是正在用的时候加"使用中"标签；"原图 | 深度图"切换（有深度图才出现）。
- 操作卡片：
  - 深度图状态：已就绪 / 未生成 / 生成中（进度条和阶段文字）/ 失败（原因）。
  - "边缘平滑"滑块，说明"越大主体边缘越不容易撕裂，重新生成深度图后生效"。
  - "选择图片""生成深度图"（有深度图时叫"重新生成"），生成期间都禁用。
  - 主按钮"应用壁纸"；选中的就是正在用的时候显示"正在使用这张"并禁用。

**视差页**（mockup ③）
- 上面是小一点的同一个实时预览。
- "画面"组：视差强度、跟手程度、对焦深度（深度色带滑块，两端标"远 / 近"）。
- "陀螺仪"组：静止范围（原"陀螺仪死区"）、最大倾斜（原"最大倾斜角"）。
- 每项下面一行白话说明（见 mockup）。右上角"恢复推荐值"，同时恢复边缘平滑（和桌面一致）。推荐值：视差强度 10、跟手程度 0.05、对焦深度 0.5、静止范围 3°、最大倾斜 30°、边缘平滑 8。
- 拖动即时生效：预览马上变，节流保存配置，桌面壁纸通过同步跟着变。

**设置页**（mockup ④）
- 动态壁纸：本应用是不是当前壁纸，按钮"设为壁纸" / "重新设置"（走第 4 节的跳转）。
- 后台运行：电池优化状态（`PowerManager.isIgnoringBatteryOptimizations`），没关闭时显示"去关闭"。
- 小米 / HyperOS 提示：只在小米手机上出现，文字沿用现在的。
- 关于：版本号、设备型号和 Android 版本。

**裁剪**（mockup ⑤）：交互和比例锁定都不变，只换样式。

**用词**：平滑度 → 跟手程度；陀螺仪死区 → 静止范围；最大倾斜角 → 最大倾斜。

**生命周期**：`onStop` 销毁 Pixi 释放显存的做法保留。回到前台时要重建预览（现在回来是一片空白），并重新查询壁纸状态和电池优化状态。

**代码组织**：`app_ui.js`（883 行）参照桌面 `settings/` 拆开，例如 `app/main.js`（入口和共享状态）、`app/shell.js`（页签、Toast）、`app/controls.js`、`app/wallpaper-page.js`、`app/parallax-page.js`、`app/system-page.js`、`app/cropper.js`，样式放 `ui/tokens.css` 和 `app/app.css`，图标放 `ui/icons.js`。普通脚本，不引入框架。

### 6. 测试和验证

- JVM 单元测试（`app/src/test`，JUnit 4 已在依赖里）：
  - `DepthProcessing` 和桌面结果对照。
  - 传感器归一化和去抖：把 `SensorHelper` 里的计算抽成纯函数再测。
- `parallax-math.js`、`frame-pacing.js` 是桌面测过的文件原样复制，手机不重复测；复制后 diff 一下确认和桌面一致。
- `./gradlew test` 和 `./gradlew assembleDebug` 都要通过。
- 真机检查清单：
  1. 同一张图用新算法重新生成后，前后左右倾斜：主体轮廓不撕，没有明显拉丝（和旧版对比）。
  2. 陀螺仪方向和手感跟旧版一样。
  3. 平放 3 秒后壁纸停帧（帧数为 0），拿起来马上跟上。
  4. 选新图、裁剪、生成深度图过程中桌面壁纸不变；点"应用壁纸"后回到桌面已经换了。
  5. 在视差页调参数后回到桌面，壁纸已经按新参数动。
  6. 本应用还不是壁纸时，"应用壁纸"直接进入本应用的动态壁纸预览页。
  7. 从后台回到 App，预览正常显示。
  8. 小米手机上提示出现，电池优化跳转可用。

### 7. 文档和版本号

- 手机仓库新增 `PROJECT_NOTES.md`：项目地图、和桌面文件的对应关系、这一轮改了什么、真机验证结果。
- 版本号改成 `versionName 1.1.0`、`versionCode 2`（助手的决定）。
- 悬浮窗权限相关的旧代码（`requestOverlayPermission`、`SYSTEM_ALERT_WINDOW`）界面上本来就没有入口，这轮不动。

## 建议顺序

先修割裂（用户最在意，也最好单独验证）：深度图后处理 → 着色器 → 空闲停帧 → 配置和同步、选择 / 应用分开 → 新界面 → 文档。

## 留给实现时决定

- 去抖阈值、桌面偏移权重、默认强度的最终值（真机按手感定，结果告诉用户）。
- 进度条各阶段的百分比。
- JS 文件具体怎么拆、模块之间怎么传状态；预览 canvas 在两个页签之间怎么挂载。
- 对照测试数据怎么来（复用桌面的还是新生成）。
- 提示淡出的时机、动效时长、间距。
- 旧图片和旧深度图清理的具体时机。
