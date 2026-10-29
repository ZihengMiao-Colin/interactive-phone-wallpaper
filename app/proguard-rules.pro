# Wallpaper3D ProGuard Rules

# 保留 @JavascriptInterface 方法 (防止被混淆后 JS 无法调用)
-keepclassmembers class com.wallpaper.live3d.wallpaper.WallpaperBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.wallpaper.live3d.ui.AppBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留 WebView 相关的类
-keep class com.wallpaper.live3d.** { *; }

# WebKit
-dontwarn androidx.webkit.**
-keep class androidx.webkit.** { *; }

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
