package com.wallpaper.live3d.config

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * ============================================================================
 * ConfigManager — SharedPreferences 封装
 * ============================================================================
 *
 * 替代 Electron 的 electron-store / store.js，使用 Android SharedPreferences。
 *
 * 持久化键值:
 *   parallaxMultiplier (int)   - 视差强度, 默认 15
 *   smoothness (float)         - 平滑度, 默认 0.05
 *   deadZoneDeg (float)        - 陀螺仪死区, 默认 3.0
 *   maxTiltDeg (float)         - 最大倾斜角, 默认 30.0
 *   currentImage (string)      - 当前壁纸文件名
 *   depthFile (string)         - 深度图缓存文件名
 *   hideDebugPanel (boolean)   - 隐藏调试面板, 默认 true
 */
class ConfigManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "wallpaper3d_config"

        // 键名
        const val KEY_PARALLAX = "parallaxMultiplier"
        const val KEY_SMOOTHNESS = "smoothness"
        const val KEY_DEAD_ZONE = "deadZoneDeg"
        const val KEY_MAX_TILT = "maxTiltDeg"
        const val KEY_CURRENT_IMAGE = "currentImage"
        const val KEY_DEPTH_FILE = "depthFile"
        const val KEY_HIDE_DEBUG = "hideDebugPanel"

        // 默认值
        val DEFAULTS = JSONObject().apply {
            put(KEY_PARALLAX, 15)
            put(KEY_SMOOTHNESS, 0.05)
            put(KEY_DEAD_ZONE, 3.0)
            put(KEY_MAX_TILT, 30.0)
            put(KEY_CURRENT_IMAGE, "")
            put(KEY_DEPTH_FILE, "")
            put(KEY_HIDE_DEBUG, true)
        }
    }

    /** 返回所有配置的 JSON 字符串 */
    fun getAll(): JSONObject {
        return JSONObject().apply {
            put(KEY_PARALLAX, prefs.getInt(KEY_PARALLAX, DEFAULTS.getInt(KEY_PARALLAX)))
            put(KEY_SMOOTHNESS,
                prefs.getFloat(KEY_SMOOTHNESS, DEFAULTS.getDouble(KEY_SMOOTHNESS).toFloat()).toDouble())
            put(KEY_DEAD_ZONE,
                prefs.getFloat(KEY_DEAD_ZONE, DEFAULTS.getDouble(KEY_DEAD_ZONE).toFloat()).toDouble())
            put(KEY_MAX_TILT,
                prefs.getFloat(KEY_MAX_TILT, DEFAULTS.getDouble(KEY_MAX_TILT).toFloat()).toDouble())
            put(KEY_CURRENT_IMAGE,
                prefs.getString(KEY_CURRENT_IMAGE, DEFAULTS.getString(KEY_CURRENT_IMAGE)) ?: "")
            put(KEY_DEPTH_FILE,
                prefs.getString(KEY_DEPTH_FILE, DEFAULTS.getString(KEY_DEPTH_FILE)) ?: "")
            put(KEY_HIDE_DEBUG,
                prefs.getBoolean(KEY_HIDE_DEBUG, DEFAULTS.getBoolean(KEY_HIDE_DEBUG)))
        }
    }

    /** 获取 SharedPreferences.Editor 用于部分更新 */
    fun edit(): SharedPreferences.Editor {
        return prefs.edit()
    }

    /** 读取单个字符串值 */
    fun getString(key: String, default: String = ""): String {
        return prefs.getString(key, default) ?: default
    }

    /** 读取单个整数值 */
    fun getInt(key: String, default: Int = 0): Int {
        return prefs.getInt(key, default)
    }

    /** 读取单个浮点值 */
    fun getFloat(key: String, default: Float = 0f): Float {
        return prefs.getFloat(key, default)
    }

    /** 读取单个布尔值 */
    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return prefs.getBoolean(key, default)
    }
}
