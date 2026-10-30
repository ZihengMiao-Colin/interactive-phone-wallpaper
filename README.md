# 互动3D壁纸 · Android 版

**简体中文** | [English](README.en.md)

安卓手机上的伪 3D 视差动态壁纸：选一张图片，手机本地用 AI 生成深度图，倾斜手机时画面按远近错开，像透过窗户看一个立体场景。

电脑版（Windows）在 [interactive-desktop-wallpaper](https://github.com/ZihengMiao-Colin/interactive-desktop-wallpaper)，手机版的视差着色器和深度图算法都移植自那边。

## 功能

- **深度视差壁纸**：Depth Anything V2 在手机本地生成深度图（不联网），自定义着色器按深度偏移画面。可调视差强度、跟手程度、对焦深度、边缘平滑。
  - 深度图把近处物体的轮廓先向外扩再平滑，倾斜时主体边缘保持完整，拉伸落在背景上，不容易出现割裂。
- **陀螺仪驱动**：倾斜手机产生视差，另外叠加桌面左右滑动的偏移；可调"静止范围"（防手抖）和"最大倾斜"。
- **App 内实时预览**：手机形状的预览跟着陀螺仪动，手指按住时改为跟手指；可切换原图 / 深度图。
- **实时同步**：在 App 里调参数、换图后，桌面壁纸马上跟着变。选图、裁剪、生成深度图都不会动到正在用的壁纸，点"应用壁纸"才换。
- **省电**：手机静止、画面停稳后停止渲染（静止时 0 帧），动起来最多 60 帧；壁纸看不见时暂停。
- **恢复原来的壁纸**：设置页一键打开系统的壁纸设置，选回原来的壁纸（小米打开"系统个性化"页）。
- **小米 / 澎湃 OS 适配**：电池优化引导、锁屏说明、动态壁纸设置入口（见下文"小米手机"）。

## 系统要求

- Android 7.0（API 24）及以上，支持 OpenGL ES 2.0；没有陀螺仪也能用（只是不会随倾斜动）。
- 实测机型：小米 24129PN74C，Android 16（澎湃 OS），WebView 156。其他品牌按安卓标准接口实现，没有实测。

## 构建

需要 Android Studio（或 JDK 17 + Android SDK）。

1. **准备模型**（约 100MB，不在仓库里）：Depth Anything V2 Small 的 fp32 ONNX 模型，放到
   `app/src/main/assets/models/depth_anything_v2_small.onnx`。
   可以直接用电脑版项目根目录的 `depth_anything_v2_small_ir9.onnx`（改名复制过来），或用电脑版的 `export_onnx.py` 导出。
   没有模型也能编译，只是生成深度图时会提示失败。
2. 编译调试版：

```bash
./gradlew assembleDebug
```

3. 装到手机（USB 调试打开）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以直接用 Android Studio 打开项目运行。

## 使用

1. 打开 App，点"选择图片"，按屏幕比例裁剪。
2. 点"生成深度图"，大约几秒（第一次要先解压模型，会慢一些）。
3. 点"应用壁纸"，在系统页面里选"互动3D壁纸"，再点"设置壁纸"。
4. 在"视差"页调强度、跟手程度、对焦深度等，桌面壁纸实时跟着变。
5. 想换回原来的壁纸：在"设置"页点"恢复原来的壁纸"，在打开的系统页面里选回去。

### 小米手机

- **设为壁纸会打开动态壁纸列表**：澎湃 OS 的"直接进动态壁纸预览页"入口会检查一个小米自定义权限（AppOp 10045），第三方应用默认没有，页面会直接关掉。所以小米上改为打开动态壁纸列表，在里面选"互动3D壁纸"即可。
- **锁屏**：小米的锁屏壁纸是单独管理的，本应用只改桌面。锁屏壁纸不生效时，先在"个性主题"里应用任意一个系统动态壁纸，再回来重新设置本应用。
- **后台被清理**：在"设置"页点"去关闭"，关闭电池优化。

## 开发

单元测试（JVM，深度图后处理和电脑版的 Pillow 对照数据逐值一致、陀螺仪归一化和去抖、配置、文件清理等）：

```bash
./gradlew testDebugUnitTest
```

**浏览器预览**：不用手机也能看界面和渲染。在仓库根目录起一个静态服务器：

```bash
python -m http.server 8766
```

- 主界面：`http://localhost:8766/app/src/main/assets/app/app.html`（用浏览器的手机视口看）。不在 App 里时 `app/bridge.js` 用假实现，数据来自 `dev/`；可加参数 `?fail=depth`（生成失败）、`?active=0`（本应用不是壁纸）、`?xiaomi=0`（非小米）。
- 壁纸页：`http://localhost:8766/app/src/main/assets/wallpaper/wallpaper.html`，在控制台执行 `startWallpaper('/dev/sample.jpg', '/dev/sample-depth.png')`。

**真机调试**：调试版开启了 WebView 远程调试，可以在电脑 Chrome 打开 `chrome://inspect` 查看页面。壁纸每秒的渲染帧数打在 logcat 里：

```bash
adb logcat -s "[WallpaperJS]"
```

## 目录结构

| 路径 | 内容 |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | 主界面：WebView、全面屏、前台陀螺仪、打开系统壁纸页面 |
| `app/src/main/java/.../wallpaper/` | 动态壁纸服务：VirtualDisplay + WebView、传感器、配置变化实时推给壁纸页 |
| `app/src/main/java/.../depth/` | 深度图生成：ONNX 推理、Pillow 兼容的缩放和后处理（移植自电脑版） |
| `app/src/main/java/.../ui/`、`config/`、`util/` | JS 桥、配置存储、陀螺仪、文件命名和清理、小米适配 |
| `app/src/main/assets/shared/` | 渲染引擎、视差着色器、停帧判断（壁纸和 App 预览共用，部分复制自电脑版） |
| `app/src/main/assets/app/`、`ui/` | 主界面：壁纸 / 视差 / 设置三个页面、裁剪、预览 |
| `app/src/main/assets/wallpaper/` | 壁纸服务里加载的页面 |
| `app/src/test/` | JVM 单元测试 |
| `dev/` | 浏览器预览用的示例图和深度图 |
| `docs/superpowers/` | 设计文档和实施计划 |

更详细的架构说明、和电脑版的对应关系、已知问题见 [PROJECT_NOTES.md](PROJECT_NOTES.md)。

## 技术要点

- **视差公式**：`offset = tilt × (depth − focus) × strength`，带软截断，和电脑版是同一段 GLSL；对焦深度处的物体不动。
- **深度图**：模型固定 518×518 输入，输出放大到最长边 1920，近处轮廓外扩后做三次均值滤波逼近高斯。Kotlin 移植和电脑版 JS 逐值一致（单元测试里有 Pillow 生成的对照数据）。超大图先降采样解码，防内存溢出。
- **壁纸里的 WebGL**：`WallpaperService` 的 Surface 交给 VirtualDisplay，在上面用 Presentation 承载 WebView，才能跑 WebGL。
- **停帧**：原生层对陀螺仪去抖（变化小于 0.01 不推送），渲染端倾斜收敛后再过 30 帧停掉 ticker，有新输入、参数变化、换图时唤醒。
- **为什么不能自动恢复原来的壁纸**：安卓 13 起普通应用读不到当前壁纸的图片（需要"所有文件访问"权限），所以恢复功能是打开系统的壁纸设置让用户自己选。
