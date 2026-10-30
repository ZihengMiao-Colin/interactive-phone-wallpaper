# Interactive 3D Wallpaper · Android

[简体中文](README.md) | **English**

A pseudo-3D parallax live wallpaper for Android phones: pick an image, an AI model generates its depth map right on the phone, and as you tilt the phone the scene shifts by distance, like looking at a 3D scene through a window.

The Windows version lives in [interactive-desktop-wallpaper](https://github.com/ZihengMiao-Colin/interactive-desktop-wallpaper); the parallax shader and depth-map algorithm of this Android version are ported from it.

## Features

- **Depth parallax wallpaper**: Depth Anything V2 generates the depth map on the phone (no network), and a custom shader offsets the image by depth. Parallax strength, smoothing, focus depth and edge smoothing are adjustable.
  - The depth map grows the outline of near objects outward before smoothing, so the subject stays intact when tilting and the stretching falls on the background instead of tearing the subject.
- **Gyroscope driven**: tilting the phone produces parallax, plus the home-screen page-swipe offset; a "rest zone" (to ignore hand shake) and "max tilt" are adjustable.
- **Live preview in the app**: a phone-shaped preview follows the gyroscope, or your finger while you press it; switch between the image and its depth map.
- **Live sync**: parameter changes and newly applied images show up on the home screen immediately. Picking, cropping and generating a depth map never touch the wallpaper in use; only "Apply wallpaper" does.
- **Battery friendly**: rendering stops once the phone is still and the image has settled (0 fps at rest), at most 60 fps while moving, and pauses while the wallpaper is not visible.
- **Restore your previous wallpaper**: one tap in Settings opens the system wallpaper settings so you can pick your old wallpaper again (on Xiaomi it opens "System personalization").
- **Xiaomi / HyperOS tweaks**: battery-optimization shortcut, lock-screen note, a working live-wallpaper entry (see "Xiaomi phones" below).

## Requirements

- Android 7.0 (API 24) or later with OpenGL ES 2.0; works without a gyroscope (it just won't react to tilting).
- Tested on a Xiaomi 24129PN74C, Android 16 (HyperOS), WebView 156. Other brands use standard Android APIs but have not been tested.

## Building

Requires Android Studio (or JDK 17 + the Android SDK).

1. **Get the model** (about 100 MB, not in the repo): the fp32 ONNX model of Depth Anything V2 Small, placed at
   `app/src/main/assets/models/depth_anything_v2_small.onnx`.
   You can copy `depth_anything_v2_small_ir9.onnx` from the root of the Windows project (rename it), or export it with that project's `export_onnx.py`.
   The app still builds without the model; generating a depth map will just report a failure.
2. Build the debug APK:

```bash
./gradlew assembleDebug
```

3. Install it on the phone (USB debugging enabled):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can also open the project in Android Studio and run it from there.

## Usage

1. Open the app, tap "选择图片" (Choose image) and crop it to the screen ratio.
2. Tap "生成深度图" (Generate depth map); it takes a few seconds (the first run unpacks the model and is slower).
3. Tap "应用壁纸" (Apply wallpaper), choose "互动3D壁纸" in the system page, then tap "Set wallpaper".
4. Tune strength, smoothing, focus depth etc. on the "视差" (Parallax) tab; the home screen updates live.
5. To go back to your old wallpaper: on the "设置" (Settings) tab tap "恢复原来的壁纸" (Restore previous wallpaper) and pick it again in the system page that opens.

### Xiaomi phones

- **"Set as wallpaper" opens the live-wallpaper list**: HyperOS's "open this live wallpaper's preview" entry checks a Xiaomi-specific permission (AppOp 10045) that third-party apps don't have by default, and the page just closes. On Xiaomi the app opens the live-wallpaper list instead; choose "互动3D壁纸" there.
- **Lock screen**: Xiaomi manages the lock-screen wallpaper separately and this app only changes the home screen. If the lock screen doesn't change, first apply any system live wallpaper in the Themes app, then set this app again.
- **Killed in the background**: on the Settings tab tap "去关闭" to turn off battery optimization.

## Development

Unit tests (JVM: depth post-processing matching the Windows version's Pillow reference data value for value, gyroscope normalization and debouncing, config, file cleanup, etc.):

```bash
./gradlew testDebugUnitTest
```

**Browser preview**: see the UI and rendering without a phone. Start a static server in the repo root:

```bash
python -m http.server 8766
```

- Main UI: `http://localhost:8766/app/src/main/assets/app/app.html` (use the browser's phone viewport). Outside the app, `app/bridge.js` uses a fake implementation with data from `dev/`; query parameters `?fail=depth` (generation fails), `?active=0` (this app is not the wallpaper), `?xiaomi=0` (not a Xiaomi phone).
- Wallpaper page: `http://localhost:8766/app/src/main/assets/wallpaper/wallpaper.html`, then run `startWallpaper('/dev/sample.jpg', '/dev/sample-depth.png')` in the console.

**On-device debugging**: debug builds enable WebView remote debugging, so you can inspect the pages from `chrome://inspect` in desktop Chrome. The wallpaper logs its frames per second to logcat:

```bash
adb logcat -s "[WallpaperJS]"
```

## Project layout

| Path | Contents |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | Main screen: WebView, edge-to-edge, gyroscope while in the foreground, opening system wallpaper pages |
| `app/src/main/java/.../wallpaper/` | Live wallpaper service: VirtualDisplay + WebView, sensors, pushing config changes to the wallpaper page live |
| `app/src/main/java/.../depth/` | Depth-map generation: ONNX inference, Pillow-compatible resizing and post-processing (ported from the Windows version) |
| `app/src/main/java/.../ui/`, `config/`, `util/` | JS bridge, config storage, gyroscope, file naming and cleanup, Xiaomi handling |
| `app/src/main/assets/shared/` | Render engine, parallax shader, idle-frame logic (shared by the wallpaper and the in-app preview; partly copied from the Windows version) |
| `app/src/main/assets/app/`, `ui/` | Main UI: Wallpaper / Parallax / Settings tabs, cropping, preview |
| `app/src/main/assets/wallpaper/` | Page loaded inside the wallpaper service |
| `app/src/test/` | JVM unit tests |
| `dev/` | Sample image and depth map for the browser preview |
| `docs/superpowers/` | Design documents and implementation plans |

For architecture details, the mapping to the Windows version and known issues, see [PROJECT_NOTES.md](PROJECT_NOTES.md) (Chinese).

## Technical notes

- **Parallax formula**: `offset = tilt × (depth − focus) × strength` with a soft clamp, the same GLSL as the Windows version; objects at the focus depth don't move.
- **Depth maps**: the model takes a fixed 518×518 input; the output is upscaled to at most 1920 on the long side, near outlines are dilated and then smoothed with three box blurs approximating a Gaussian. The Kotlin port matches the Windows JS value for value (the unit tests contain Pillow-generated reference data). Very large images are downsampled while decoding to avoid running out of memory.
- **WebGL inside a wallpaper**: the `WallpaperService` surface is handed to a VirtualDisplay, and a Presentation on it hosts the WebView, which is what makes WebGL work.
- **Idle frames**: the native side debounces the gyroscope (changes under 0.01 are not sent), and the renderer stops its ticker 30 frames after the tilt settles; new input, parameter changes or a new image wake it up.
- **Why the previous wallpaper can't be restored automatically**: since Android 13, regular apps can't read the current wallpaper image (it needs "All files access"), so the restore feature opens the system wallpaper settings and lets you pick it.
