# 手机版方舟风格界面、日间主题、改名 Kirie 壁纸 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 手机版的界面、名字、图标和桌面 Kirie 壁纸一致，并支持夜间 / 日间 / 跟随系统三种主题。

**Architecture:** 复制桌面 `ui/tokens.css` 和字体当底子，日间用 `<html data-theme="light">` 切换；`app.css` / `app.html` 照桌面 `settings/settings.css` 的组件重写外观，JS 逻辑不动。主题存在 `ConfigManager`，`<head>` 里的 `theme.js` 在首次绘制前定主题，原生层负责系统栏、底色和启动画面。

**Tech Stack:** Kotlin（AGP 9.0、minSdk 24、targetSdk 36）、WebView 里的普通 HTML / CSS / JS、JUnit 4。

**Spec:** `docs/superpowers/specs/2026-11-24-ark-ui-kirie-design.md`

## Global Constraints

- 桌面路径 `C:\Users\24418\Desktop\2\壁纸`；复制来的文件头写来源和"改动要两边同步"。
- 包名 `com.wallpaper.live3d`、prefs 文件名 `wallpaper3d_config`、类名、版本号都不改。
- 直角；不用毛玻璃、发光、多层阴影；青色只给选中 / 主要操作 / 色条 / 链接；黄色只在警示斜纹；红色只表示危险。
- 夜间色值、日间色值和桌面 `ui/tokens.css` 一字不差。
- 触控尺寸：按钮 44px（`.sm` 36px）、分段每段 36px、开关 48×28、滑块整体 32px 高 / 竖条 10×24、滑块宽 100%。
- 间距：页面左右 16px、面板内边距 16px、分组之间 28px；正文 14px / 行高 1.7。
- 要求 WebView（Chromium）111+（`color-mix()`、`:has()`）。
- 名字：`Kirie 壁纸`；副标题 `互动 3D 壁纸`；壁纸说明 `互动 3D 视差动态壁纸`。
- 不出现明日方舟的名称、术语、Logo。

## Review Focus

- 主题切换时页面不重新加载：生成中的深度图、选中未应用的图还在（Task 4 手动核对步骤）。
- 日间主题下状态栏 / 导航栏图标是深色，系统栏后面的底色是日间 `--ink`（Task 4）。
- 预览、裁剪框上的浮层压在亮图上也看得清：固定夜间颜色，不随日间变浅（Task 2、Task 3 截图核对用亮色示例图）。
- 375px 宽没有横向滚动；"界面主题"分段按钮在窄屏换行而不是挤压（Task 4 检查 `scrollWidth`）。
- "跟随系统"在系统切深浅时实时跟着变（Task 4 的 adb 步骤：`adb shell cmd uimode night yes|no`）。

---

### Task 1: 基础样式、字体、主题脚本

**Files:**
- Replace: `app/src/main/assets/ui/tokens.css`（来自桌面 `ui/tokens.css`）
- Create: `app/src/main/assets/ui/fonts/`（复制桌面 `ui/fonts/` 整个目录：`noto-serif-sc.css`、`noto-serif-sc/`、`saira-wdth.woff2`、两份 `OFL-*.txt`）
- Create: `app/src/main/assets/ui/icon.svg`（复制桌面 `assets/icon.svg`）
- Create: `app/src/main/assets/app/theme.js`
- Modify: `app/src/main/assets/app/app.html`（`<head>` 里引入 `theme.js`）

**Interfaces:**
- Produces: `window.Theme.resolve(theme: 'dark'|'light'|'system', systemDark: boolean): 'dark'|'light'`；`window.Theme.apply(theme: string): 'dark'|'light'`（写 `document.documentElement.dataset.theme`，非法值当 `dark`）；`window.Theme.setSystemDark(dark: boolean)`；`window.onSystemThemeChange(dark: boolean)`（= `setSystemDark` 后按当前设置重新 `apply`）。
- Produces: CSS 类 `.panel` `.float` `.hz` `.en` `.btn(.primary|.contrast|.ghost|.danger|.sm|.icon|.block)` `.switch` `.seg` `.chip(.ok|.accent|.warn|.error)`、`input[type=range](.depth)`、变量 `--f-data --f-serif --f-display --bar --bar-lg --inv --bg-2 --caution --shadow-1..3 --ease`。

- [ ] **Step 1: 复制字体、图标、tokens**；tokens 文件头改成"复制自桌面 ui/tokens.css，改动要两边同步；手机版的不同：…"（列出 spec §1 的四点）。
- [ ] **Step 2: 改 tokens**：`@media (prefers-color-scheme: light) { :root:not([data-theme="dark"]) {…} }` 换成 `:root[data-theme="light"] {…}`；`--font: system-ui, "Noto Sans CJK SC", sans-serif`，`--f-*` 去掉 Windows 字体名、`--f-serif` 去掉 SimSun；按 Global Constraints 放大触控尺寸；删 `kbd`、`.halftone`。
- [ ] **Step 3: 写 `theme.js`**：在 `<head>` 同步执行。原生时 `JSON.parse(AppBridge.getConfig()).theme` 和 `JSON.parse(AppBridge.getPlatformInfo()).systemDark`；浏览器时读 `?theme=`（默认 `dark`）和 `?dark=0|1`（默认 1）。立即 `apply`。
- [ ] **Step 4: 验证**：`preview_start phone-dev`，打开 `app.html?theme=light` 和 `?theme=system&dark=0`，`document.documentElement.dataset.theme === 'light'`；`getComputedStyle(document.body).backgroundColor` 是 `rgb(245, 245, 245)`；`?theme=bogus` 得到 `dark`；控制台没有报错。
- [ ] **Step 5: 提交** `feat: 手机版换上桌面的方舟风格 tokens、字体和主题脚本`

### Task 2: 全局布局和壁纸页

**Files:**
- Modify: `app/src/main/assets/app/app.css`（全局、页面、壁纸页、预览、状态和进度）
- Modify: `app/src/main/assets/app/app.html`（`<title>`、壁纸页）
- Modify: `app/src/main/assets/app/wallpaper-page.js`（只改类名：状态标签、`.btn.contrast`）

**Interfaces:**
- Consumes: Task 1 的 CSS 类和变量。
- Produces: 布局类 `.topbar` `.brand`（图标 + `b` + `.sub`）`.page-head`（`h1` + `.en`）`.group-head`（`h2`）`.brk` `.field` `.field-head .val` `.note` `.ends` `.btn-row` `.status(.none|.ready|.busy|.error)` `.progress`；页面换页动画 `.page:not([hidden])` 用 `fade-up`。

- [ ] **Step 1: 全局**：`body` 14px / 1.7 / `tabular-nums` / `font-kerning: normal`；`h1,h2 { font-feature-settings: "palt" }`；直角；`.page` 显示时 `animation: fade-up .32s var(--ease)`；`.val`、分辨率、版本号用 `--f-data`。
- [ ] **Step 2: 壁纸页顶栏**：`<header class="topbar"><span class="brand"><img src="../ui/icon.svg" alt=""><b>Kirie</b><span class="sub">互动 3D 壁纸</span></span><span class="chip" id="wp-status-chip">…</span></header>`，照桌面 `.titlebar`（图标 22px、`b` Saira 600 16px、`.sub` 11px `--text-2` 左边 1px `--line-strong` 竖线）；`<title>Kirie 壁纸</title>`。
- [ ] **Step 3: 预览**：直角、1px `--line-strong`、去阴影和圆角；外面包一层放 `.brk`（四角 L 形，照桌面）；`.hint` / `.veil span` 固定夜间色：`rgba(15,15,16,.8)` 底、`#f0f0f0` 字、`.hint` 左边 4px `#18d1ff` 条；`.empty-state` 用 `--bg-2` 底、`--text-2` 字。
- [ ] **Step 4: 预览下方和卡片**：`.card` → `.panel`（内边距 16px）；分辨率 Saira；"使用中"保持 `.chip.accent`；状态行用图标 + 文字（颜色 ready=`--success`、busy=`--accent-text`、error=`--danger`）；`.progress` 换桌面分段进度条（高 8px、`mask: repeating-linear-gradient(90deg,#000 0 6px,transparent 6px 8px)`、填充 `--accent-text`、宽 100%）；`#wp-apply` 改 `.btn.contrast.block`，在 `wallpaper-page.js` 里找有没有覆盖 className 的地方。
- [ ] **Step 5: 验证**：夜间、日间各截图（375×812）：生成前、生成中、生成完成、`?fail=depth`；`document.documentElement.scrollWidth <= innerWidth`；控制台没有报错；和桌面设置窗口截图并排看色条、按钮、标签。
- [ ] **Step 6: 提交** `feat: 手机版壁纸页换成方舟风格`；**截图给用户看方向，等确认再做 Task 3**。

### Task 3: 视差页、设置页、页签栏、提示条、裁剪

**Files:**
- Modify: `app/src/main/assets/app/app.css`、`app/src/main/assets/app/app.html`
- Modify: `app/src/main/assets/app/shell.js`（提示条类名 `'toast ' + kind`，去掉 `glass`）

**Interfaces:**
- Consumes: Task 2 的 `.page-head` `.group-head` `.panel`。

- [ ] **Step 1: 视差页**：`<div class="page-head"><h1>视差<span class="en">Parallax</span></h1>` + 右边"恢复推荐值"ghost；分组标题 `.group-head h2`（画面、陀螺仪）；卡片 `.panel`。页面标题照桌面：`border-left: var(--bar-lg) solid var(--accent)`、`font: 900 24px/1.1 var(--f-serif)`、`letter-spacing: -0.05em`、`.en` 13px / 700 / 0.08em。
- [ ] **Step 2: 设置页**：`设置 System` 标题；分组标题；列表 `.panel`，行首图标方块直角；小米提示照桌面 `.desktop-notice`（左边 8px `.hz` 斜纹、`--bg-2` 底、`b` 单独一行 `--text`）；"关于"第一行 `<img src="../ui/icon.svg">` + **Kirie 壁纸** + 版本号（Saira）。
- [ ] **Step 3: 页签栏**：去掉 `.pill`；选中项 `--surface` 底、顶边 4px `--accent` 条（`::before`）、图标 `--accent-text`、文字 `--text` 700；未选 `--text-2`。
- [ ] **Step 4: 提示条**：照桌面 `.toast`（`--bar-c`：info=`--accent`、ok=`--success`、error=`--danger`；`border-image: linear-gradient(to right, var(--bar-c) 4px, var(--line-strong) 4px) 1 1 1 4`；`--shadow-3`），位置不变。
- [ ] **Step 5: 裁剪**：顶栏 `--ink` 跟主题；"确认" `.btn.contrast.sm`、"取消" `.btn.ghost`；`#crop-area` 黑底；`#crop-box` 1px `#18d1ff` 框 + 三分线 `rgba(255,255,255,.22)`；四个 `.crop-handle` 改成框内 20px 的 L 形角（3px `#18d1ff`，照桌面 `.crop-handle.nw/ne/sw/se`，类名 tl/tr/bl/br 不变）；遮罩 `rgba(15,15,16,.66)`；说明条 `.float`。
- [ ] **Step 6: 验证**：两套主题各截图：视差页、设置页（`?xiaomi=0` 和默认）、三种提示条、裁剪；无横向滚动；`grep -n "glass\|border-radius" app/src/main/assets/app/*.css app/src/main/assets/app/*.js` 只剩有意保留的（没有）。
- [ ] **Step 7: 提交** `feat: 手机版视差页、设置页、页签栏、提示条、裁剪换成方舟风格`

### Task 4: 界面主题设置

**Files:**
- Modify: `app/src/main/java/com/wallpaper/live3d/config/ConfigManager.kt`
- Test: `app/src/test/java/com/wallpaper/live3d/config/ConfigManagerTest.kt`
- Modify: `app/src/main/java/com/wallpaper/live3d/ui/AppBridge.kt`、`MainActivity.kt`、`AndroidManifest.xml`
- Modify: `app/src/main/res/values/colors.xml`、Create: `app/src/main/res/values-night/colors.xml`
- Modify: `app/src/main/assets/app/bridge.js`、`system-page.js`、`app.html`、`app.css`

**Interfaces:**
- Consumes: Task 1 的 `Theme.apply` / `onSystemThemeChange`。
- Produces（Kotlin）：`ConfigManager.KEY_THEME = "theme"`；`ConfigManager.sanitizeTheme(value: String?): String`（companion，`dark|light|system`，其他 → `dark`）；`ConfigManager.resolveDark(theme: String, systemDark: Boolean): Boolean`（companion）；实例方法 `theme(): String`、`setTheme(value: String)`；`getAll()` 带 `theme`；`AppBridge.setTheme(value: String)`（`@JavascriptInterface`）；`getPlatformInfo()` 加 `systemDark: Boolean`；`MainActivity.applyTheme()`。
- Produces（JS）：`Bridge.setTheme(value)`。

- [ ] **Step 1: 写失败的测试**（`ConfigManagerTest`，用现有 `FakeSharedPreferences`）：
  - `theme defaults to dark`：新配置 `theme() == "dark"`，`getAll().getString("theme") == "dark"`
  - `setTheme round trips`：`setTheme("light")` 后 `theme() == "light"`；`"system"` 同理
  - `invalid theme falls back to dark`：`sanitizeTheme("blue") == "dark"`、`sanitizeTheme(null) == "dark"`；prefs 里直接写 `"blue"` 时 `theme() == "dark"`
  - `resolveDark`：`("dark", false) → true`、`("light", true) → false`、`("system", true) → true`、`("system", false) → false`
  - `saveConfig ignores theme`：`applyParams(JSONObject("""{"theme":"light"}"""))` 后 `theme() == "dark"`
- [ ] **Step 2: 跑** `./gradlew testDebugUnitTest --tests "*ConfigManagerTest*"`，预期编译失败（没有 `theme`）。
- [ ] **Step 3: 实现 ConfigManager**，再跑测试全过。
- [ ] **Step 4: 原生层**：
  - `colors.xml`（日间）/ `values-night/colors.xml`（夜间）放 `ink`、`background`，色值抄 tokens（夜间 `#141516` / `#0f0f10`，日间 `#eef0f1` / `#f5f5f5`），注释指向 tokens；主题 `windowBackground` 引用 `@color/background`。
  - `MainActivity.applyTheme()`：`dark = ConfigManager.resolveDark(config.theme(), systemDark())`；设 `isAppearanceLightStatusBars/NavigationBars = !dark`、根布局底色 `ink`、WebView 底色 `background`（用代码里的两套常量，不读资源，资源会被 per-app night mode 影响）；API 31+ 调 `UiModeManager.setApplicationNightMode`（dark → `MODE_NIGHT_YES`、light → `MODE_NIGHT_NO`、system → `MODE_NIGHT_AUTO`）。`onCreate` 里在 `setContentView` 后调用。
  - `systemDark()` 读 `Resources.getSystem().configuration.uiMode`（不受 per-app night mode 影响）。
  - 清单 `MainActivity` 的 `configChanges` 加 `uiMode`；`onConfigurationChanged` 里 `applyTheme()` 并 `evaluateJavascript("window.onSystemThemeChange && onSystemThemeChange(<systemDark>)")`。
  - `AppBridge.setTheme(value)`：`configManager.setTheme(value)`，`runOnUiThread { activity.applyTheme() }`；`getPlatformInfo()` 加 `systemDark`。
- [ ] **Step 5: 页面**：设置页最上面加分组"界面"：行"界面主题"，说明"只换这个应用的颜色，桌面上的壁纸不受影响"，`<div class="seg" id="sys-theme">` 三个按钮 `data-value="dark|light|system"` 文字 夜间 / 日间 / 跟随系统；宽度不够时（`flex-wrap`）分段按钮换到说明下面占满一行。`system-page.js` 用 `Controls.segmented` 绑定：点了 `Theme.apply(v)`、`Bridge.setTheme(v)`、`App.state.config.theme = v`。`bridge.js` 原生加 `setTheme`，假实现记到 `config.theme` 并 log；假实现 `getConfig` 带 `theme`（来自 `?theme=`），`getPlatformInfo` 带 `systemDark`（来自 `?dark=`）。
- [ ] **Step 6: 验证（浏览器）**：设置页点三个选项，`dataset.theme` 立即变、页面不重载（`performance.getEntriesByType('navigation').length` 不变、壁纸页选中的图还在）；`?dark=0` 时"跟随系统"得到 `light`；调用 `onSystemThemeChange(true)` 后变 `dark`；375px 下 `scrollWidth <= innerWidth`。
- [ ] **Step 7: 跑全部单元测试** `./gradlew testDebugUnitTest`，全过。
- [ ] **Step 8: 提交** `feat: 手机版加界面主题：夜间、日间、跟随系统`

### Task 5: 名字和图标

**Files:**
- Modify: `app/src/main/res/values/strings.xml`、`app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/drawable/ic_launcher_background.xml`、`ic_launcher_foreground.xml`
- Modify: `app/src/main/java/com/wallpaper/live3d/MainActivity.kt`（电池优化提示）
- Modify: `README.md`、`README.en.md`、`PROJECT_NOTES.md`

- [ ] **Step 1: 字符串**：`app_name` = `Kirie 壁纸`，`wallpaper_description` = `互动 3D 视差动态壁纸`；删掉 `btn_*`、`miui_hint`（先 `grep -rn "R.string\.\|@string/" app/src/main` 确认没人用）。电池优化提示改成 `"请手动前往: 设置 → 应用 → ${getString(R.string.app_name)} → 电池 → 无限制"`。
- [ ] **Step 2: 图标**：背景 `<solid android:color="#0f0f10"/>`；前景 108×108 的 `vector`，三条 `path` 从桌面 `assets/icon.svg` 换算：K 组外框是 256 坐标里 x 39–217、y 22–234（中心 128,128），用 `<group android:scaleX/scaleY/translateX/translateY>` 缩放到高 50dp、中心放在 (54,54)（缩放 `50/212`，平移 `54 - 128×s`），三层颜色 `#0d5a6e`、`#18d1ff`、`#ffffff`，路径数据原样照抄。`colors.xml` 删 `primary`、`text_primary`、`text_secondary`（先 grep 确认没人用）。
- [ ] **Step 3: 文档**：README 两份标题 `# Kirie 壁纸 · Android 版`（英文 `# Kirie Wallpaper · Android`），开头一句说明名字（Kirie 是日语"切り絵"，剪纸画，几层纸前后叠出纵深），使用步骤里"选 Kirie 壁纸"；不写原名。`PROJECT_NOTES.md`：最近更新、§1 名字、§3 文件地图（`ui/fonts/`、`ui/icon.svg`、`app/theme.js`、`values-night/`）、同步文件列表加 `ui/tokens.css`（注明手机版不同之处）和 `ui/fonts/`、`ui/icon.svg`、WebView 111+ 要求、"已知"加 API 24/25 没有启动图标。
- [ ] **Step 4: 验证**：`./gradlew assembleDebug` 成功；用 `aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk | grep application-label` 看到 `Kirie 壁纸`。
- [ ] **Step 5: 提交** `feat: 手机版改名 Kirie 壁纸，换成 K 字图标`

### Task 6: 整体验证

- [ ] **Step 1:** `./gradlew testDebugUnitTest assembleDebug` 全过。
- [ ] **Step 2:** 浏览器预览两套主题、375×812 和 412×915，三个页签 + 裁剪 + 提示条截图；对照 skill 的"交付前检查"逐条过一遍（写进最后的汇报）。
- [ ] **Step 3:** `adb devices` 有手机时：`adb install -r app/build/outputs/apk/debug/app-debug.apk`，看图标、名字、两套主题、切主题不重载、`adb shell cmd uimode night yes|no` 时"跟随系统"跟着变、状态栏图标深浅、冷启动底色。没有手机就在汇报里写明这些要用户在真机上看。
- [ ] **Step 4:** 不合并 `main`，把分支和截图交给用户。
