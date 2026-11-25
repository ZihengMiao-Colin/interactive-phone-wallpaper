package com.wallpaper.live3d

import android.app.UiModeManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.wallpaper.live3d.config.ConfigManager
import com.wallpaper.live3d.ui.AppBridge
import com.wallpaper.live3d.ui.WebViewSetup
import com.wallpaper.live3d.util.PickerRoute
import com.wallpaper.live3d.util.SensorHelper
import com.wallpaper.live3d.util.SettingsRoute
import com.wallpaper.live3d.util.WallpaperPicker
import com.wallpaper.live3d.util.WallpaperFiles
import com.wallpaper.live3d.wallpaper.LiveWallpaperService
import java.io.File

/**
 * ============================================================================
 * MainActivity — 壁纸配置主界面
 * ============================================================================
 *
 * 托管一个 WebView 运行 app.html。
 * 提供: 图片选择、深度图生成、实时预览（陀螺仪驱动）、应用壁纸、系统适配引导。
 *
 * 关键: onStop 中销毁 Pixi 释放 GPU 显存，
 *      防止与 WallpaperService WebView 双份显存占用被 MIUI 杀进程；回到前台时页面自己重建预览。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var root: View
    private lateinit var configManager: ConfigManager
    private lateinit var appBridge: AppBridge
    private lateinit var sensorHelper: SensorHelper

    // 图片选择器
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { appBridge.onImageSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 清掉没被配置引用的旧图片和旧深度图（启动时选中的就是正在用的那张）
        val config = ConfigManager(this)
        configManager = config
        WallpaperFiles.cleanUnreferenced(
            File(filesDir, "wallpapers"), File(filesDir, "depth_cache"),
            config.getString(ConfigManager.KEY_CURRENT_IMAGE), config.getString(ConfigManager.KEY_DEPTH_FILE),
        )
        // 上一版"设之前备份原壁纸"留下的照片副本，已经不用了
        File(filesDir, "original_wallpaper").deleteRecursively()
        sensorHelper = SensorHelper(this, config)

        webView = findViewById(R.id.webview)

        // 全面屏：内容画到系统栏下面，根布局按系统栏和刘海留出 padding（露出的是 --ink 底色）
        root = findViewById(R.id.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        // 系统栏图标深浅、底色按界面主题设；页面加载前的底色和页面一致，避免闪一下
        applyTheme(syncNightMode = true)

        // 统一 WebView 配置 (含 WebViewAssetLoader)
        WebViewSetup.apply(this, webView)

        // 注入 AppBridge
        appBridge = AppBridge(this, webView)
        webView.addJavascriptInterface(appBridge, "AppBridge")

        // 开发者调试
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // 加载主界面 WebView 内容
        webView.loadUrl("https://appassets.androidplatform.net/assets/app/app.html")
    }

    // ==================== 生命周期 ====================

    override fun onResume() {
        super.onResume()
        // 保险：任何 WebView 调过 pauseTimers() 都会冻住整个进程的定时器
        webView.resumeTimers()
        // App 在前台时预览也跟着陀螺仪动
        sensorHelper.start { x, y ->
            webView.evaluateJavascript("window.onSensorTilt && window.onSensorTilt($x,$y)", null)
        }
        webView.evaluateJavascript("window.onAppResume && window.onAppResume()", null)
    }

    override fun onPause() {
        sensorHelper.stop()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // 通知页面进了后台（之后不再自己建引擎），再销毁 Pixi.js 实例，释放 WebGL 上下文
        webView.evaluateJavascript(
            "if(window.onAppStop) onAppStop(); if(window.CoreEngine) CoreEngine.destroy();", null
        )
    }

    override fun onDestroy() {
        // 彻底销毁 WebView，释放 GPU 资源
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AppBridge")
            webView.destroy()
        }
        super.onDestroy()
    }

    /**
     * 清单里 configChanges 含 uiMode：系统切深浅（或应用自己的深浅变了）时不重建 Activity、不重新加载页面，
     * 在这里换系统栏和底色，再告诉页面系统现在的深浅（"跟随系统"时页面跟着变）
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme(syncNightMode = false)
        webView.evaluateJavascript("window.onSystemThemeChange && onSystemThemeChange(${isSystemDark()})", null)
    }

    // ==================== 界面主题 ====================

    /** 系统当前是不是深色。读系统资源，不受 setApplicationNightMode 设的应用自己的深浅影响 */
    fun isSystemDark(): Boolean =
        (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /**
     * 按配置的界面主题设状态栏 / 导航栏图标深浅、系统栏后面的底色（--ink）和 WebView 底色（--bg）。
     * 页面自己的颜色由 theme.js 管。
     *
     * syncNightMode：Android 12 起顺带把应用自己的深浅告诉系统（会一直记着），下次冷启动的启动画面
     * 按它挑 res/values 或 res/values-notnight-v31 里的底色。只在启动和用户换主题时传 true：
     * 这一步本身会触发 onConfigurationChanged，在那里再设会来回触发。
     */
    fun applyTheme(syncNightMode: Boolean) {
        val theme = configManager.theme()
        val dark = ConfigManager.resolveDark(theme, isSystemDark())
        root.setBackgroundColor(if (dark) INK_DARK else INK_LIGHT)
        webView.setBackgroundColor(if (dark) BG_DARK else BG_LIGHT)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        if (syncNightMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                when (theme) {
                    "light" -> UiModeManager.MODE_NIGHT_NO
                    "system" -> UiModeManager.MODE_NIGHT_AUTO
                    else -> UiModeManager.MODE_NIGHT_YES
                }
            )
        }
    }

    // ==================== AppBridge 回调 ====================

    /** 打开系统图片选择器 */
    fun openImagePicker() {
        imagePickerLauncher.launch("image/*")
    }

    /** 本应用是不是当前的动态壁纸 */
    fun isOurWallpaperActive(): Boolean = try {
        WallpaperManager.getInstance(this).wallpaperInfo?.component ==
            ComponentName(this, LiveWallpaperService::class.java)
    } catch (_: Exception) {
        false
    }

    /** 是否已关闭电池优化 */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * 打开系统的动态壁纸设置：一般手机直接进本应用的预览页，小米上打开动态壁纸列表（原因见 WallpaperPicker）。
     * 启动失败时按顺序退到下一种。
     */
    fun openLiveWallpaperPicker() {
        for (route in WallpaperPicker.routes(isXiaomi())) {
            val intent = when (route) {
                PickerRoute.CHANGE_LIVE_WALLPAPER -> Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this, LiveWallpaperService::class.java),
                )
                PickerRoute.LIVE_WALLPAPER_CHOOSER -> Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                PickerRoute.SET_WALLPAPER_CHOOSER -> Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "选择壁纸")
            }
            try {
                startActivity(intent)
                if (route == PickerRoute.LIVE_WALLPAPER_CHOOSER) {
                    // 列表里有很多动态壁纸，告诉用户点哪个（原生 Toast，盖在系统列表上面也看得到）
                    Toast.makeText(this, "在列表里选「${getString(R.string.app_name)}」，再点「设置壁纸」", Toast.LENGTH_LONG).show()
                }
                return
            } catch (e: Exception) {
                android.util.Log.w("[MainActivity]", "无法打开 $route: ${e.message}")
            }
        }
        Toast.makeText(this, "无法打开壁纸设置，请在系统设置里选择动态壁纸", Toast.LENGTH_LONG).show()
    }

    // ==================== 恢复原来的壁纸 ====================

    private fun isXiaomi() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    /**
     * 打开系统的壁纸设置，让用户自己换回原来的壁纸（安卓不允许普通应用读当前壁纸，没法自动换回）。
     * 小米：系统个性化页；其他手机：安卓标准的"设置壁纸"，各品牌打开自己的壁纸设置。
     * @return 打开了返回 true
     */
    fun openSystemWallpaperSettings(): Boolean {
        for (route in WallpaperPicker.systemSettingsRoutes(isXiaomi())) {
            val intent = when (route) {
                SettingsRoute.XIAOMI_PERSONALIZE -> Intent(Intent.ACTION_VIEW, Uri.parse("theme://zhuti.xiaomi.com/personalize"))
                SettingsRoute.SET_WALLPAPER -> Intent(Intent.ACTION_SET_WALLPAPER)
            }
            try {
                startActivity(intent)
                Toast.makeText(this, "在这里选回你原来的壁纸", Toast.LENGTH_LONG).show()
                return true
            } catch (e: Exception) {
                android.util.Log.w("[MainActivity]", "无法打开 $route: ${e.message}")
            }
        }
        return false
    }

    /** 跳转电池优化设置 */
    fun requestBatteryOptimizationBypass() {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            // 某些 ROM 可能不支持此 Intent
            Toast.makeText(
                this,
                "请手动前往: 设置 → 应用 → 互动3D壁纸 → 电池 → 无限制",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 打开悬浮窗权限设置 */
    fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        // 和 assets/ui/tokens.css 的 --ink / --bg 一致（夜间 / 日间），改了要两边同步
        const val INK_DARK = 0xFF141516.toInt()
        const val BG_DARK = 0xFF0F0F10.toInt()
        const val INK_LIGHT = 0xFFEEF0F1.toInt()
        const val BG_LIGHT = 0xFFF5F5F5.toInt()
    }
}
