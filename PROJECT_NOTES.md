# Kirie 壁纸 · Android 版 — 项目笔记

> 最近更新：2026-11-24（方舟风格界面、夜间 / 日间主题、改名 Kirie 壁纸、K 字图标，见 §5.3）
> 用途：后续开发的"地图"。改动代码后请顺手更新本文件。
> 桌面版：`C:\Users\24418\Desktop\2\壁纸`（Electron，Kirie 壁纸），很多算法和界面从那边移植过来，见 §3。

## 1. 一句话概括

Kirie 壁纸（和桌面版同名，Kirie 是日语"切り絵"，剪纸画）的 Android 版，动态壁纸：选一张图片 → 按屏幕比例裁剪 → 手机本地用 Depth Anything V2（ONNX Runtime，CPU）生成深度图 → 壁纸服务里的 WebView 用 Pixi.js 自定义 2.5D 视差着色器全屏绘制 → 重力传感器驱动视差，倾斜手机时画面按远近错开。

## 2. 技术栈与运行

| 项 | 现状 |
|---|---|
| 外壳 | Kotlin，AGP 9.0、Gradle 9.3、JDK 17，compileSdk / targetSdk 36，minSdk 24 |
| 界面和渲染 | WebView + Pixi.js 7.4.2（和桌面同版本），普通 JS / CSS，不用框架和构建步骤；界面样式用了 `color-mix()` 和 `:has()`，要求 WebView（Chromium）111+ |
| 动态壁纸 | `WallpaperService` + VirtualDisplay + Presentation 承载 WebView（这样才有 WebGL） |
| 深度模型 | Depth Anything V2 Small fp32，就是桌面根目录的 `depth_anything_v2_small_ir9.onnx`，复制到 `app/src/main/assets/models/depth_anything_v2_small.onnx`（约 100MB，不进 git） |
| 测试 | JVM 单元测试（JUnit 4）：`./gradlew testDebugUnitTest` |

- 构建：`./gradlew assembleDebug`，APK 在 `app/build/outputs/apk/debug/`。装到手机：`adb install -r app/build/outputs/apk/debug/app-debug.apk`。
- 停帧检查：调试版壁纸每秒往 logcat 打一次帧数，`adb logcat -s "[WallpaperJS]"`，手机放平几秒后应为 `fps=0`。
- **浏览器开发预览**（没有手机也能看界面和渲染）：`.claude/launch.json` 里的 `phone-dev`（用桌面项目 venv 的 Python 在仓库根目录起 `http.server 8766`），打开
  - 主界面：`http://localhost:8766/app/src/main/assets/app/app.html`（手机视口看）
  - 壁纸页：`http://localhost:8766/app/src/main/assets/wallpaper/wallpaper.html`，在控制台执行 `startWallpaper('/dev/sample.jpg', '/dev/sample-depth.png')`
  - 不在 App 里时 `app/bridge.js` 用假实现，数据来自 `dev/`；URL 参数：`?fail=depth`（生成失败）、`?active=0`（本应用不是壁纸）、`?xiaomi=0`（非小米）、`?theme=dark|light|system`（界面主题）、`?dark=0`（模拟系统是浅色）
  - 浏览器窗格隐藏时 rAF 被节流、截图可能错位，验证时用 DOM 测量或手动推 ticker（`CoreEngine.getApp().ticker.update(t)`）；换页的渐显动画也会卡在半透明，截图前 `document.getAnimations().forEach(a => a.finish())`
  - `http.server` 会让浏览器缓存旧的 CSS / JS，改完先 `fetch(url, { cache: "reload" })` 刷一遍再重新加载

## 3. 文件地图

```
app/src/main/
├── java/com/wallpaper/live3d/
│   ├── MainActivity.kt          主界面：WebView、全面屏 inset、界面主题（系统栏和底色）、前台时陀螺仪推给预览、回前台通知页面、跳动态壁纸预览页
│   ├── config/ConfigManager.kt  SharedPreferences：参数（带范围钳位）、界面主题、setWallpaper、监听器（壁纸服务实时同步靠它）
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
    ├── ui/tokens.css            配色（夜间 / 日间）、基础控件 — 复制自桌面 ui/tokens.css，和桌面的不同写在文件头
    ├── ui/icons.js              Lucide 图标雪碧图 + 滑块填充（基于桌面 ui/icons.js，只留用到的）
    ├── ui/fonts/                思源宋体（按字切片）、Saira 和许可证 — 复制自桌面 ui/fonts/
    ├── ui/icon.svg              软件图标（K 字）— 复制自桌面 assets/icon.svg
    ├── app/                     主界面
    │   ├── app.html / app.css   结构和布局（三个页签 + 裁剪 + 提示条），外观照桌面 settings/settings.css
    │   ├── theme.js             界面主题：<head> 里同步执行，首次绘制前写 <html data-theme>
    │   ├── bridge.js            包装 AppBridge；浏览器里用假实现
    │   ├── main.js              共享状态（applied / selected）、事件、页面注册
    │   ├── shell.js / controls.js   页签、提示条 / 滑块、分段按钮
    │   ├── preview.js           实时预览（陀螺仪 + 手指），在两个页签之间挪画布
    │   ├── cropper.js           裁剪（单指移动、双指缩放、比例锁定为屏幕）
    │   └── wallpaper-page.js / parallax-page.js / system-page.js
    ├── wallpaper/               壁纸服务里的页面（wallpaper.html、wallpaper_render.js、pixi.min.js）
    └── models/                  ONNX 模型（不进 git）
app/src/main/res/                名字（strings.xml）、启动图标（drawable/ic_launcher_*，K 字取自桌面 assets/icon.svg）、
                                 启动底色（values/ 夜间、values-notnight-v31/ 日间）
dev/                             浏览器开发预览用的示例图和深度图
docs/superpowers/                设计文档和实施计划
```

**和桌面同步的文件**（文件头有来源注释，改一边要改另一边）：`shared/parallax-math.js`、`frame-pacing.js`、`config-sync.js`（内容逐字复制，只多一行注释；桌面有的文件是 CRLF，核对用 `diff --strip-trailing-cr`）、`shared/shaders.js`（节选）、`DepthProcessing.kt`（移植，测试里的 Pillow 对照数据也来自桌面）。`ui/tokens.css`（复制后改了主题选择器、触控尺寸、字体和悬停，见文件头）、`ui/fonts/`、`ui/icon.svg`（原样复制）、`res/drawable/ic_launcher_foreground.xml`（路径取自桌面 `assets/icon.svg`）；`ui/icons.js`、`app/app.css` 是照桌面改的。夜间 / 日间的 `--ink`、`--bg` 还写在 `res/values*/colors.xml` 和 `MainActivity` 里，改色要几处一起改。

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

验证：单元测试 32 个全过（DepthProcessing 对照桌面 Pillow 数据、倾斜归一化和去抖、配置钳位、文件清理）；浏览器里走过主界面全部流程和停帧逻辑。**真机（2026-10-29，小米 24129PN74C / Android 16 / WebView 156）**：第一次装上去所有按钮都没反应——`ConfigManager.getAll()` 在 `JSONObject().apply {}` 里写的 `getString` 调到了 `JSONObject.getString`，启动时抛异常，页面脚本整个没跑起来（已修，`ConfigManagerTest.getAll_*` 覆盖）。修好后通过 WebView 远程调试在手机页面里点过：页签切换、原图 / 深度图切换、重新生成深度图（新算法，约 1 秒出结果）都正常，静止时预览 0 帧。小米默认不允许 adb 模拟点击，自动测试走的是 WebView 调试协议。第二个问题：在小米上"设为桌面壁纸"没反应——澎湃 OS 的系统动态壁纸选择器 `LiveWallpaperChange`（`ACTION_CHANGE_LIVE_WALLPAPER`）对第三方壁纸检查小米自定义 AppOp 10045，默认不允许，打一行 `No permission to change wall paper` 就关掉，启动本身是成功的，代码里的降级接不住。现在小米上改为打开动态壁纸列表（`ACTION_LIVE_WALLPAPER_CHOOSER`，不查这个权限）并用 Toast 提示选哪个（`util/WallpaperPicker.kt`）。设成壁纸后 logcat 里静止时 `fps=0`、动起来约 55 帧。下面的清单里 1~6 还要人在手机上设成壁纸后看：

1. 同一张图用新算法重新生成后，前后左右倾斜：主体轮廓不撕，没有明显拉丝（和旧版对比）。
2. 陀螺仪方向和手感跟旧版一样（方向是按旧 DisplacementFilter 推导的）。
3. 平放 3 秒后壁纸停帧（logcat `fps=0`），拿起来马上跟上。
4. 选新图、裁剪、生成深度图过程中桌面壁纸不变；点"应用壁纸"后回到桌面已经换了。
5. 在视差页调参数后回到桌面，壁纸已经按新参数动。
6. 本应用还不是壁纸时，"应用壁纸"直接进入本应用的动态壁纸预览页。
7. 从后台回到 App，预览正常显示。
8. 小米手机上提示出现，电池优化跳转可用。

## 5.1 恢复原来的壁纸（2026-10-29）

- 设置页"恢复原来的壁纸"：只在本应用是当前桌面壁纸时可点，点了打开系统的壁纸设置，让用户自己选回原来的壁纸（`MainActivity.openSystemWallpaperSettings`、`WallpaperPicker.systemSettingsRoutes`）。
  - 小米：`theme://zhuti.xiaomi.com/personalize`（主题应用对外开放的链接，打开"系统个性化"页）；打不开再退到下面的标准方式。
  - 其他手机：安卓标准的 `Intent.ACTION_SET_WALLPAPER`，各品牌打开自己的壁纸设置（Pixel 是"壁纸与风格"，三星是壁纸设置，华为 / OPPO / vivo 一般是主题或壁纸应用）；有多个应用能处理时系统先弹列表让用户选。只在小米上实测过。
- 为什么不能自动换回：安卓 13 起普通应用读不到当前壁纸的图片（真机实测 `getWallpaperFile` / `getDrawable` 抛 SecurityException，要"所有文件访问"权限，用户不要额外权限）；小米的静态壁纸服务也不在可选的动态壁纸列表里。
- 走过的弯路：第一版有"恢复系统默认壁纸"，用户以为是恢复自己的壁纸，结果换成了小米默认图；第二版改成"设之前从相册备份原图、之后一键换回"，用户觉得流程不好用。都已去掉，第二版留在手机里的备份照片启动时会删掉。

## 5.2 同步桌面的光线步进视差（2026-10-30）

桌面版把视差改成光线步进、边缘平滑默认值从 8 改成 5（桌面 `PROJECT_NOTES.md` §9 阶段 8，提交 `86eebac`），这里跟着同步：
- 着色器：每个像素从最近一层往最远一层找第一个碰到的表面，前景挪动时盖住背景，不再被拉扯、对折出重影。`shared/shaders.js` 重新从桌面节选，`shared/parallax-math.js` 逐字复制（多了 JS 版 `rayMarchUV`，桌面有单元测试，这里只是带着）。
- `core_engine.js`：滤镜加 `uDepthSize`（深度图像素尺寸，决定走几步），深度图关掉 mipmap（循环里读纹理算不出 mipmap 级别）。"深度图"视图也走同一个着色器。
- 边缘平滑默认 5：`ConfigManager`、`shared/config.js`、视差页"恢复推荐值"、滑块初始值、`DepthProcessing.DEFAULT_BLUR`、浏览器预览的假数据。旧版本"恢复推荐值"会把 8 存进 SharedPreferences，所以没有 `blurRadiusVersion` 标记时存着的 8 读成 5；保存边缘平滑时写上标记，之后用户自己调回 8 不会再被改（`ConfigManagerTest.blur_*`）。滑块下面的说明改成"太小时毛发、胡须这类细的东西容易被切断，太大时主体周围一圈背景会跟着晃"。
- 验证：单元测试 42 个全过，`assembleDebug` 通过；浏览器预览里壁纸页（手机视口、强度 30、倾斜到底）和主界面都正常渲染，没有报错。**没上真机**（当时没连手机）。
- 要在真机上看的：①性能——每个像素最多读约 47 次深度图（原来 2 次），默认强度 10 时约 20 次；手机 GPU 比电脑弱，倾斜时看 logcat 帧数有没有明显掉（原来约 55 帧）。②前景边缘：同一张图按 5 重新生成深度图后倾斜到底，主体不该再有对折出的重影。已经生成过的深度图还是旧值，要重新生成才换。

## 5.3 方舟风格界面、夜间 / 日间主题、改名 Kirie 壁纸（2026-11-24）

目标：手机和桌面看起来是同一个软件（设计 `docs/superpowers/specs/2026-11-24-ark-ui-kirie-design.md`，计划 `docs/superpowers/plans/2026-11-24-ark-ui-kirie.md`）。桌面后来加的功能（元素、天气、视频特效、唱片机等）不跟。
- **界面**：`ui/tokens.css` 换成桌面那份（用户个人 skill `ark-style-ui` 的色值：黑白灰 + 青色，直角，色条加细框，黑白反转），按手指放大（按钮 44px、分段 36px、滑块竖条 10×24）。悬停样式收进 `@media (hover: hover)`，不然手机上点完按钮会一直停在悬停色。`app.css` 照桌面组件改：壁纸页顶栏是桌面标题栏的样子（K 图标 + Kirie + "互动 3D 壁纸"），视差 / 设置页是"8px 色条 + 宋体 + 英文小字"的页面标题，分组标题 4px 色条，预览四角 L 形细线，"应用壁纸"和裁剪"确认"是黑白反转按钮，小米提示用警示斜纹，提示条是实底加左边色条，页签栏选中项顶边一条主色。压在图片上的东西（预览提示、裁剪框）固定用夜间颜色。
- **主题**：设置页最上面"界面主题"：夜间（默认）/ 日间 / 跟随系统，存在配置 `theme`。`app/theme.js` 在 `<head>` 里读 `AppBridge.getConfig()` 和 `getPlatformInfo().systemDark`，首次绘制前写好 `<html data-theme>`；点了马上换，不重新加载页面。原生层 `MainActivity.applyTheme()` 换状态栏 / 导航栏图标深浅、根布局和 WebView 底色；清单 `configChanges` 加了 `uiMode`，系统切深浅时不重建 Activity，`onConfigurationChanged` 里调 `window.onSystemThemeChange(dark)`。`systemDark` 读 `Resources.getSystem()`，不受应用自己的深浅影响。
- **启动底色**：`values/colors.xml` 一律夜间，`values-notnight-v31/colors.xml` 放日间；Android 12 起 `UiModeManager.setApplicationNightMode` 把用户选的主题告诉系统（系统会记着），启动画面按它挑。Android 12 以下启动画面只能跟系统深浅，默认主题是夜间，所以那里一律夜间（选了日间的人启动那一下是深色）。
- **名字和图标**：`app_name` = Kirie 壁纸，壁纸说明"互动 3D 视差动态壁纸"，删了没用的旧字符串和颜色。自适应图标：底色 `#0f0f10`，前景是桌面 `assets/icon.svg` 的三个 K（路径原样，缩放 50/212 放进 66dp 安全区）。包名 `com.wallpaper.live3d`、prefs 文件名都没改（改包名等于换一个应用，已装的会丢设置）。
- `AppBridge.setTheme(value)` 返回系统当前是不是深色：主题强制成夜间 / 日间时系统切深浅不会触发 `onConfigurationChanged`，页面里记的系统深浅会过期，改选"跟随系统"时用返回值。
- **真机（2026-11-24，小米 24129PN74C / Android 16 / WebView 157）**：三个页面夜间、日间都正常，宋体和 Saira 都加载了；切主题不重新加载页面（选中的图还在），日间时状态栏 / 导航栏图标变深色、底色是日间 `--ink`；"跟随系统"时 `adb shell cmd uimode night no|yes` 界面马上跟着变。从"日间"切到"跟随系统"（系统深色）时 `setTheme` 返回的 `systemDark` 是应用自己被强制的浅色（`Resources.getSystem()` 带着应用自己的深浅），约 10ms 后 `onConfigurationChanged` 把它纠正成深色，肉眼看不出。冷启动：选日间时小米开应用动画里的窗口是浅色、选夜间是深色。应用详情和系统动态壁纸列表里都是"Kirie 壁纸"和 K 图标。没测：设成桌面壁纸后的视差手感（这轮没动渲染）。测试方法：`adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>` 后用 DevTools 协议的 `Runtime.evaluate` 点页面（小米默认不让 adb 模拟点击）。

## 6. 已知但没做

- 模型在 assets 和 `filesDir/models/` 各存一份（每次初始化还会重新复制），首次生成慢、多占约 100MB。
- `assets/app/transformers.min.js`（约 900KB）没有被引用，可以删。
- 默认视差强度 10、去抖阈值 0.01、桌面滑动偏移权重 0.33 是按桌面和推算定的，真机手感不对再调。
- 审查时记下、没修的小问题：`SensorHelper.start()` 不重置去抖基准（壁纸页重新加载后手机静止时画面停在中间，动一下就好）；`CoreEngine.load` 没有序号保护（极快连续换图可能显示旧图）；`resizeRgb8` 多复制一份 RGB；缺少后处理随机输入的桌面逐值对照测试；`wallpaper_render.js` 的 `setupTouchFallback` 是死代码；回前台时即使引擎还在也会重新上传纹理；裁剪框四角看起来能拖其实不能。
- 旧代码就有、这轮没碰：选图在主线程复制（云端大图可能卡顿）；不支持 HEIC；没有处理 WebView 渲染进程崩溃（`onRenderProcessGone`）。
- 启动图标只有 `mipmap-anydpi-v26`（自适应图标），minSdk 24，Android 7.x 的手机上没有对应图标（一直如此）。
- 界面样式要求 WebView 111+（`color-mix()`、`:has()`），更老的 WebView 上部分底色和标签会变透明。
- 悬浮窗权限相关的旧代码（`SYSTEM_ALERT_WINDOW`、`requestOverlayPermission`）界面上没有入口，没动。
