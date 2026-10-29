package com.wallpaper.live3d.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * ============================================================================
 * PermissionHelper — 权限检查与引导
 * ============================================================================
 *
 * Android 动态壁纸需要的特殊权限:
 *   - SYSTEM_ALERT_WINDOW (悬浮窗): Overlay 渲染路径所需
 *   - REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: MIUI 防杀进程
 *   - 传感器权限: 无需显式请求 (TYPE_GRAVITY 不受限)
 */
object PermissionHelper {

    /** 是否有悬浮窗权限 (API 23+) */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true  // API < 23 默认允许
        }
    }

    /** 是否已忽略电池优化 */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /** 跳转电池优化设置的 Intent */
    fun getBatteryOptimizationIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
    }

    /** 跳转悬浮窗权限设置的 Intent */
    fun getOverlayPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    /** 是否为小米/MIUI 设备 */
    fun isXiaomi(): Boolean {
        return Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    }

    /** 是否为华为设备 */
    fun isHuawei(): Boolean {
        return Build.MANUFACTURER.equals("Huawei", ignoreCase = true) ||
                Build.MANUFACTURER.equals("Honor", ignoreCase = true)
    }

    /** 是否需要特殊的电池优化引导 (小米/华为等) */
    fun needsBatteryOptimizationGuide(): Boolean {
        return isXiaomi() || isHuawei()
    }
}
