# 互动3D壁纸 · Android 版 — 项目笔记

> 最近更新：2026-10-29（1.1.0：修割裂、新界面、空闲停帧）
> 用途：后续开发的"地图"。改动代码后请顺手更新本文件。
> 桌面版：`C:\Users\24418\Desktop\2\壁纸`（Electron，v1.0.0 已发布），很多算法和界面从那边移植过来，见 §3。

## 1. 一句话概括

Android 动态壁纸：选一张图片 → 按屏幕比例裁剪 → 手机本地用 Depth Anything V2（ONNX Runtime，CPU）生成深度图 → 壁纸服务里的 WebView 用 Pixi.js 自定义 2.5D 视差着色器全屏绘制 → 重力传感器驱动视差，倾斜手机时画面按远近错开。

## 2. 技术栈与运行

| 项 | 现状 |
|---|---|
| 外壳 | Kotlin，AGP 9.0、Gradle 9.3、JDK 17，compileSdk / targetSdk 36，minSdk 24 |
| 界面和渲染 | WebView + Pixi.js 7.4.2（和桌面同版本），普通 JS / CSS，不用框架和构建步骤 |
| 动态壁纸 | `WallpaperService` + VirtualDisplay + Presentation 承载 WebView（这样才有 WebGL） |
| 深度模型 | Depth Anything V2 Small fp32，就是桌面根目录的 `depth_anything_v2_small_ir9.onnx`，复制到 `app/src/main/assets/models/depth_anything_v2_small.onnx`（约 100MB，不进 git） |
| 测试 | JVM 单元测试（JUnit 4）：`./gradlew testDebugUnitTest` |

- 构建：`./gradlew assembleDebug`，APK 在 `app/build/outputs/apk/debug/`。装到手机：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。
- 停帧检查：调试版壁纸每秒往 logcat 打一次帧数，`adb logcat -s "[WallpaperJS]"`，手机放平几秒后应为 `fps=0`。
- **浏览器开发预览**（没有手机也能看界面和渲染）：`.claude/launch.json` 里的 `phone-dev`（用桌面项目 venv 的 Python 在仓库根目录起 `http.server 8766`），打开
  - 主界面：`http://localhost:8766/app/src/main/assets/app/app.html`（手机视口看）
  - 壁纸页：`http://localhost:8766/app/src/main/assets/wallpaper/wallpaper.html`，在控制台执行 `startWallpaper('/dev/sample.jpg', '/dev/sample-depth.png')`
  - 不在 App 里时 `app/bridge.js` 用假实现，数据来自 `dev/`；URL 参数：`?fail=depth`（生成失败）、`?active=0`（本应用不是壁纸）、`?xiaomi=0`（非小米）
  - 浏览器窗格隐藏时 rAF 被节流、截图可能错位，验证时用 DOM 测量或手动推 ticker（`CoreEngine.getApp().ticker.update(t)`）

## 3. 文件地图

```
app/src/main/
├── java/com/wallpaper/live3d/
│   ├── MainActivity.kt          主界面：WebView、全面屏 inset、前台时陀螺仪推给预览、回前台通知页面、跳动态壁纸预览页
│   ├── config/ConfigManager.kt  SharedPreferences：参数（带范围钳位）、setWallpaper、监听器（壁纸服务实时同步靠它）
│   ├── depth/
│   │   ├── DepthProcessing.kt   深度图数值处理（纯函数）— 移植自桌面 src/depth/processing.js，结果逐值一致
│   │   └── DepthEstimator.kt    解码（大图降采样）→ 518×518 → ONNX → 后处理 → depth_<时间戳>.png，分阶段报进度
│   ├── ui/AppBridge.kt          主界面 JS 桥：选图 / 裁剪 / 生成 / 应用壁纸 / 状态，文件名校验
│   ├── ui/WebViewSetup.kt       WebViewAssetLoader：/assets/ → assets，/local/ → filesDir
│   ├── util/SensorHelper.kt     重力传感器：节流 + 去抖，死区 / 最大倾斜每次读配置，pushLatest
│   ├── util/TiltMath.kt         倾斜归一化、TiltDebouncer（纯函数，有测试）
│   ├── util/WallpaperFiles.kt   文件命名（带时间戳）和清理没被引用的旧文件
│   └── wallpaper/
│       ├── LiveWallpaperService.kt  壁纸服务：VirtualDisplay + WebView、传感器、配置变化实时推给 JS
│       └── WallpaperBridge.kt       壁纸页 JS 桥（只读配置、日志、isDebug）
└── assets/
    ├── shared/                  主界面预览和壁纸共用
    │   ├── core_engine.js       渲染引擎：视差滤镜、Cover、空闲停帧、帧率上限 60、WebGL 丢失恢复
    │   ├── shaders.js           视差着色器（节选自桌面 renderer/shaders.js）
    │   ├── parallax-math.js     Cover 映射、视差公式（复制自桌面）
    │   ├── frame-pacing.js      空闲停帧判断（复制自桌面）
    │   ├── config-sync.js       配置节流保存（复制自桌面）
    │   ├── config.js            默认参数
    │   └── image_loader.js      图片加载、占位深度图
    ├── ui/tokens.css、ui/icons.js   配色、控件、Lucide 图标（基于桌面 ui/，按触控放大）
    ├── app/                     主界面
    │   ├── app.html / app.css   结构和布局（三个页签 + 裁剪 + 提示条）
    │   ├── bridge.js            包装 AppBridge；浏览器里用假实现
    │   ├── main.js              共享状态（applied / selected）、事件、页面注册
    │   ├── shell.js / controls.js   页签、提示条 / 滑块、分段按钮
    │   ├── preview.js           实时预览（陀螺仪 + 手指），在两个页签之间挪画布
    │   ├── cropper.js           裁剪（单指移动、双指缩放、比例锁定为屏幕）
    │   └── wallpaper-page.js / parallax-page.js / system-page.js
    ├── wallpaper/               壁纸服务里的页面（wallpaper.html、wallpaper_render.js、pixi.min.js）
    └── models/                  ONNX 模型（不进 git）
dev/                             浏览器开发预览用的示例图和深度图
docs/superpowers/                设计文档和实施计划
```

**和桌面同步的文件**（文件头有来源注释，改一边要改另一边）：`shared/parallax-math.js`、`frame-pacing.js`、`config-sync.js`（内容逐字复制，只多一行注释；桌面有的文件是 CRLF，核对用 `diff --strip-trailing-cr`）、`shared/shaders.js`（节选）、`DepthProcessing.kt`（移植，测试里的 Pillow 对照数据也来自桌面）。`ui/tokens.css`、`ui/icons.js` 是在桌面版基础上改的。

## 4. 核心流程

- **选择和应用分开**：选图（复制成 `pick_*`）→ 裁剪（存成 `wp_*.jpg`，删 pick）→ 生成深度图（`depth_*.png`）都只改界面里的"选中"状态；点"应用壁纸"才写配置 `currentImage` / `depthFile`。本应用已是壁纸时壁纸服务监听到配置变化马上热替换；不是时跳到本应用的动态壁纸预览页。App 启动时删掉没被配置引用的旧图片和深度图。
- **参数同步**：视差页拖滑块 → 预览马上变 + 节流保存 → 壁纸服务的 SharedPreferences 监听器把强度 / 跟手程度 / 对焦深度推给壁纸页；静止范围 / 最大倾斜由 SensorHelper 每次事件读配置。壁纸不可见期间的变化在变可见时补推。
- **空闲停帧**：传感器去抖（变化 < 0.01 不推），引擎在倾斜收敛后再过 30 帧停 ticker，有新输入、参数变化、换图、resume 时唤醒。
- **生命周期**：`MainActivity.onStop` 销毁 Pixi 释放显存，回前台时 `window.onAppResume()` → 页面重建预览、刷新状态。壁纸服务暂停时**不能**调 `WebView.pauseTimers()`（进程全局，会冻住 App 页面的定时器）。

## 5. 1.1.0 这一轮（2026-10-29）

设计：[docs/superpowers/specs/2026-10-29-phone-depth-ui-idle-design.md](docs/superpowers/specs/2026-10-29-phone-depth-ui-idle-design.md)；计划：[docs/superpowers/plans/2026-10-29-phone-depth-ui-idle.md](docs/superpowers/plans/2026-10-29-phone-depth-ui-idle.md)。

- 修割裂：渲染换成桌面的 2.5D 视差着色器（对焦深度、软截断、highp）；深度图后处理换成桌面的"近处外扩 + 高斯"，输出最长边 1920。去掉了 JS 端质量很差的传统算法降级。
- 新界面：底部"壁纸 / 视差 / 设置"三个页签，手机形状的实时预览（陀螺仪驱动），生成进度，"原图 | 深度图"切换，边缘平滑、对焦深度滑块，提示条代替 alert。
- 空闲停帧 + 帧率上限 60 + 传感器去抖。
- 调参数、换壁纸后桌面壁纸实时同步；应用壁纸直达本应用的动态壁纸预览页。
- 删掉了不再使用的深度缓存索引（`DepthCacheManager`）。
- 整分支审查后修了：静止范围 = 最大倾斜时算出 NaN（壁纸黑屏且满帧）、取消裁剪后预览空白、后台完成深度图时在后台重建渲染引擎、裁剪输出最长边限制 4096 和占位深度图缩小、在系统预览页退出后壁纸页按钮可直接重新设置。

验证：单元测试 32 个全过（DepthProcessing 对照桌面 Pillow 数据、倾斜归一化和去抖、配置钳位、文件清理）；浏览器里走过主界面全部流程和停帧逻辑。**真机上还没验证**，检查清单：

1. 同一张图用新算法重新生成后，前后左右倾斜：主体轮廓不撕，没有明显拉丝（和旧版对比）。
2. 陀螺仪方向和手感跟旧版一样（方向是按旧 DisplacementFilter 推导的）。
3. 平放 3 秒后壁纸停帧（logcat `fps=0`），拿起来马上跟上。
4. 选新图、裁剪、生成深度图过程中桌面壁纸不变；点"应用壁纸"后回到桌面已经换了。
5. 在视差页调参数后回到桌面，壁纸已经按新参数动。
6. 本应用还不是壁纸时，"应用壁纸"直接进入本应用的动态壁纸预览页。
7. 从后台回到 App，预览正常显示。
8. 小米手机上提示出现，电池优化跳转可用。

## 6. 已知但没做

- 模型在 assets 和 `filesDir/models/` 各存一份（每次初始化还会重新复制），首次生成慢、多占约 100MB。
- `assets/app/transformers.min.js`（约 900KB）没有被引用，可以删。
- 默认视差强度 10、去抖阈值 0.01、桌面滑动偏移权重 0.33 是按桌面和推算定的，真机手感不对再调。
- 审查时记下、没修的小问题：`SensorHelper.start()` 不重置去抖基准（壁纸页重新加载后手机静止时画面停在中间，动一下就好）；`CoreEngine.load` 没有序号保护（极快连续换图可能显示旧图）；`resizeRgb8` 多复制一份 RGB；缺少后处理随机输入的桌面逐值对照测试；`wallpaper_render.js` 的 `setupTouchFallback` 是死代码；回前台时即使引擎还在也会重新上传纹理；裁剪框四角看起来能拖其实不能。
- 旧代码就有、这轮没碰：选图在主线程复制（云端大图可能卡顿）；不支持 HEIC；没有处理 WebView 渲染进程崩溃（`onRenderProcessGone`）。
- 悬浮窗权限相关的旧代码（`SYSTEM_ALERT_WINDOW`、`requestOverlayPermission`）界面上没有入口，没动。
