package com.wallpaper.live3d

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wallpaper.live3d.config.ConfigManager
import com.wallpaper.live3d.ui.AppBridge
import com.wallpaper.live3d.ui.WebViewSetup
import com.wallpaper.live3d.util.PickerRoute
import com.wallpaper.live3d.util.PreviousWallpaper
import com.wallpaper.live3d.util.SensorHelper
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
    private lateinit var appBridge: AppBridge
    private lateinit var sensorHelper: SensorHelper

    // 图片选择器
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { appBridge.onImageSelected(it) }
    }

    // 恢复原来的壁纸：从相册选一张，直接设成桌面静态壁纸
    private val restoreImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            notifyRestore(false, null)   // 取消选择，不提示
            return@registerForActivityResult
        }
        changeHomeWallpaper { wm ->
            contentResolver.openInputStream(uri)?.use { wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM) }
                ?: throw IllegalStateException("无法打开图片")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 清掉没被配置引用的旧图片和旧深度图（启动时选中的就是正在用的那张）
        val config = ConfigManager(this)
        WallpaperFiles.cleanUnreferenced(
            File(filesDir, "wallpapers"), File(filesDir, "depth_cache"),
            config.getString(ConfigManager.KEY_CURRENT_IMAGE), config.getString(ConfigManager.KEY_DEPTH_FILE),
        )
        sensorHelper = SensorHelper(this, config)

        webView = findViewById(R.id.webview)

        // 全面屏：内容画到系统栏下面，根布局按系统栏和刘海留出 padding（露出的是 --ink 底色）
        val root = findViewById<View>(R.id.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        // 页面加载前的底色和页面一致，避免白屏闪一下
        webView.setBackgroundColor(0xFF10161E.toInt())

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
        // 本应用还不是壁纸：先记下现在的桌面壁纸，"恢复原来的壁纸"用
        if (!isOurWallpaperActive()) {
            ConfigManager(this).setPreviousWallpaper(
                WallpaperManager.getInstance(this).wallpaperInfo?.component?.flattenToString()
            )
        }
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

    /** 动态壁纸的名字；已卸载或查不到时返回 null */
    fun wallpaperLabel(component: String): String? = try {
        val name = ComponentName.unflattenFromString(component) ?: return null
        packageManager.getServiceInfo(name, 0).loadLabel(packageManager).toString()
    } catch (_: Exception) {
        null
    }

    private fun isSystemPackage(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
    } catch (_: Exception) {
        false
    }

    /**
     * 原来是动态壁纸：打开它的系统预览页（小米上第三方的会被拦，改开动态壁纸列表）。
     * @return 打开了系统页面返回 true；没有记录到动态壁纸或它已卸载返回 false
     */
    fun restorePreviousLive(): Boolean {
        val prev = ConfigManager(this).previousWallpaper() as? PreviousWallpaper.Live ?: return false
        val component = ComponentName.unflattenFromString(prev.component) ?: return false
        val label = wallpaperLabel(prev.component) ?: return false
        for (route in WallpaperPicker.restoreRoutes(isXiaomi(), isSystemPackage(component.packageName))) {
            val intent = when (route) {
                PickerRoute.CHANGE_LIVE_WALLPAPER -> Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                PickerRoute.LIVE_WALLPAPER_CHOOSER -> Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                PickerRoute.SET_WALLPAPER_CHOOSER -> continue
            }
            try {
                startActivity(intent)
                if (route == PickerRoute.LIVE_WALLPAPER_CHOOSER) {
                    Toast.makeText(this, "在列表里选「$label」，再点「设置壁纸」", Toast.LENGTH_LONG).show()
                }
                return true
            } catch (e: Exception) {
                android.util.Log.w("[MainActivity]", "无法打开 $route: ${e.message}")
            }
        }
        return false
    }

    /** 原来是静态图片：从相册选一张设成桌面壁纸（结果 → window.onRestoreDone） */
    fun pickRestoreImage() {
        restoreImageLauncher.launch("image/*")
    }

    /** 恢复系统默认的桌面壁纸（锁屏不动） */
    fun restoreDefaultWallpaper() {
        changeHomeWallpaper { wm -> wm.clear(WallpaperManager.FLAG_SYSTEM) }
    }

    /** 在后台线程改桌面壁纸（只改桌面，锁屏不动），完成后通知页面 */
    private fun changeHomeWallpaper(action: (WallpaperManager) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val error = try {
                action(WallpaperManager.getInstance(this@MainActivity))
                null
            } catch (e: Throwable) {
                android.util.Log.e("[MainActivity]", "恢复壁纸失败", e)
                e.message ?: e.javaClass.simpleName
            }
            withContext(Dispatchers.Main) { notifyRestore(error == null, error) }
        }
    }

    /** window.onRestoreDone(ok, message)：message 为 null 表示用户取消，不用提示 */
    private fun notifyRestore(ok: Boolean, message: String?) {
        val msg = message?.let { org.json.JSONObject.quote(it) } ?: "null"
        webView.evaluateJavascript("window.onRestoreDone && window.onRestoreDone($ok, $msg)", null)
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
}
