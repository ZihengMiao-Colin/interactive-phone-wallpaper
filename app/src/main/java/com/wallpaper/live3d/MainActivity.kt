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
import com.wallpaper.live3d.util.CurrentWallpaper
import com.wallpaper.live3d.util.PickerRoute
import com.wallpaper.live3d.util.PreviousWallpaper
import com.wallpaper.live3d.util.RestoreAction
import com.wallpaper.live3d.util.RestorePlan
import com.wallpaper.live3d.util.WallpaperBackup
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

    // 备份原来的壁纸：设本应用之前，让用户从相册选出现在用的那张图，存一份到 App 私有目录
    private val backupImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            notifyBackup(null, null)   // 取消选择，不提示
            return@registerForActivityResult
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                val ext = (contentResolver.getType(uri)?.substringAfter('/') ?: "jpg")
                    .takeIf { it.length <= 5 && it.all(Char::isLetterOrDigit) } ?: "jpg"
                val name = contentResolver.openInputStream(uri)?.use { WallpaperBackup.save(it, backupDir, ext) }
                    ?: throw IllegalStateException("无法打开图片")
                Pair(name, null)
            } catch (e: Throwable) {
                android.util.Log.e("[MainActivity]", "备份原壁纸失败", e)
                Pair(null, e.message ?: e.javaClass.simpleName)
            }
            withContext(Dispatchers.Main) { notifyBackup(result.first, result.second) }
        }
    }

    /** 原来那张静态壁纸的备份目录（WebViewAssetLoader 的 /local/original_wallpaper/） */
    val backupDir: File get() = File(filesDir, "original_wallpaper")

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

    /** 当前的桌面壁纸是本应用、别的动态壁纸，还是静态图片 */
    fun currentWallpaperKind(): CurrentWallpaper = RestorePlan.currentKind(
        WallpaperManager.getInstance(this).wallpaperInfo?.component?.flattenToString(),
        ComponentName(this, LiveWallpaperService::class.java).flattenToString(),
    )

    /** 从相册选出现在用的壁纸，存成备份（结果 → window.onBackupDone） */
    fun pickBackupImage() {
        backupImageLauncher.launch("image/*")
    }

    /**
     * 恢复原来的壁纸（只改桌面，锁屏不动）。
     * @return "live"（打开了原动态壁纸的预览页）/ "backup"（正在设备份，结果 → window.onRestoreDone）/
     *         "settings"（没有备份，打开了系统壁纸设置）/ "failed"
     */
    fun restorePrevious(): String {
        val previous = ConfigManager(this).previousWallpaper()
        val backup = WallpaperBackup.current(backupDir)
        return when (RestorePlan.decide(previous, backup != null)) {
            RestoreAction.OPEN_PREVIOUS_LIVE -> if (restorePreviousLive()) "live" else "failed"
            RestoreAction.SET_BACKUP -> {
                changeHomeWallpaper { wm -> backup!!.inputStream().use { wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM) } }
                "backup"
            }
            RestoreAction.OPEN_SYSTEM_SETTINGS -> if (openSystemWallpaperSettings()) "settings" else "failed"
        }
    }

    /** 打开系统的壁纸设置：小米是"系统个性化"页（可以从外部打开的链接），别的手机用通用壁纸选择器 */
    private fun openSystemWallpaperSettings(): Boolean {
        val intents = buildList {
            if (isXiaomi()) add(Intent(Intent.ACTION_VIEW, Uri.parse("theme://zhuti.xiaomi.com/personalize")))
            add(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "选择壁纸"))
        }
        for (intent in intents) {
            try {
                startActivity(intent)
                Toast.makeText(this, "没有备份原图，请在这里选回原来的壁纸", Toast.LENGTH_LONG).show()
                return true
            } catch (e: Exception) {
                android.util.Log.w("[MainActivity]", "无法打开壁纸设置: ${e.message}")
            }
        }
        return false
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

    /** window.onRestoreDone(ok, message) */
    private fun notifyRestore(ok: Boolean, message: String?) {
        val msg = message?.let { org.json.JSONObject.quote(it) } ?: "null"
        webView.evaluateJavascript("window.onRestoreDone && window.onRestoreDone($ok, $msg)", null)
    }

    /** window.onBackupDone(name, error)：两个都是 null 表示用户取消了选图 */
    private fun notifyBackup(name: String?, error: String?) {
        val n = name?.let { org.json.JSONObject.quote(it) } ?: "null"
        val e = error?.let { org.json.JSONObject.quote(it) } ?: "null"
        webView.evaluateJavascript("window.onBackupDone && window.onBackupDone($n, $e)", null)
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
