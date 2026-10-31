package com.wallpaper.live3d.config

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * ============================================================================
 * ConfigManager — SharedPreferences 封装（App 和壁纸服务共用）
 * ============================================================================
 *
 * 持久化键值:
 *   parallaxMultiplier (int)   - 视差强度, 默认 10 (5~100)
 *   smoothness (float)         - 跟手程度, 默认 0.05 (0.01~0.30)
 *   focusDepth (float)         - 对焦深度, 默认 0.5 (0~1)
 *   deadZoneDeg (float)        - 静止范围, 默认 3.0 (0~10)
 *   maxTiltDeg (float)         - 最大倾斜, 默认 30.0 (10~60)
 *   blurRadius (int)           - 边缘平滑, 默认 5 (0~30)
 *   blurRadiusVersion (int)    - 边缘平滑默认值从 8 改成 5 的标记（见 blurRadius()）
 *   currentImage (string)      - 桌面壁纸正在用的图片文件名（只有"应用壁纸"时写）
 *   depthFile (string)         - 桌面壁纸正在用的深度图文件名（同上）
 *   hideDebugPanel (boolean)   - 隐藏调试面板, 默认 true
 *
 * SharedPreferences 在进程内是单例：不同 ConfigManager 实例写的改动，
 * 其他实例注册的监听器都能收到（壁纸服务靠这个实时同步）。
 */
class ConfigManager(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    companion object {
        private const val PREFS_NAME = "wallpaper3d_config"

        // 键名
        const val KEY_PARALLAX = "parallaxMultiplier"
        const val KEY_SMOOTHNESS = "smoothness"
        const val KEY_FOCUS = "focusDepth"
        const val KEY_DEAD_ZONE = "deadZoneDeg"
        const val KEY_MAX_TILT = "maxTiltDeg"
        const val KEY_BLUR = "blurRadius"
        const val KEY_CURRENT_IMAGE = "currentImage"
        const val KEY_DEPTH_FILE = "depthFile"
        const val KEY_HIDE_DEBUG = "hideDebugPanel"
        private const val KEY_BLUR_VERSION = "blurRadiusVersion"

        /**
         * 边缘平滑的旧默认值。视差改成光线步进后默认值改成 5（和桌面一致）；
         * 旧版本"恢复推荐值"会把 8 存进去，没有 blurRadiusVersion 标记时存着的 8 当成没调过，读成新默认值。
         * 保存边缘平滑时一起写标记，之后用户自己调回 8 不会再被改。
         */
        private const val LEGACY_BLUR_DEFAULT = 8
        private const val BLUR_VERSION = 2

        /** 参数的默认值和范围；isInt 的存成 Int，其余存成 Float */
        private class Param(val default: Double, val min: Double, val max: Double, val isInt: Boolean)

        private val PARAMS = linkedMapOf(
            KEY_PARALLAX to Param(10.0, 5.0, 100.0, true),
            KEY_SMOOTHNESS to Param(0.05, 0.01, 0.30, false),
            KEY_FOCUS to Param(0.5, 0.0, 1.0, false),
            KEY_DEAD_ZONE to Param(3.0, 0.0, 10.0, false),
            KEY_MAX_TILT to Param(30.0, 10.0, 60.0, false),
            KEY_BLUR to Param(5.0, 0.0, 30.0, true),
        )

        /** 界面能改的参数 */
        val PARAM_KEYS: Set<String> = PARAMS.keys

        /** 渲染引擎要用的参数（变了要推给壁纸页） */
        val RENDER_KEYS: Set<String> = setOf(KEY_PARALLAX, KEY_SMOOTHNESS, KEY_FOCUS)

        /** 参数值钳到范围内，整数参数四舍五入；不是参数键或不是数字返回 null */
        fun sanitize(key: String, value: Double): Number? {
            val p = PARAMS[key] ?: return null
            if (value.isNaN()) return null
            val v = value.coerceIn(p.min, p.max)
            return if (p.isInt) Math.round(v).toInt() else v.toFloat()
        }
    }

    /** 读一个参数的当前值（Int 或 Float） */
    private fun param(key: String): Number {
        if (key == KEY_BLUR) return blurRadius()
        val p = PARAMS.getValue(key)
        return if (p.isInt) prefs.getInt(key, p.default.toInt()) else prefs.getFloat(key, p.default.toFloat())
    }

    /** 边缘平滑：没有新版本标记、存着的还是旧默认值 8 的，读成新默认值 */
    private fun blurRadius(): Int {
        val default = PARAMS.getValue(KEY_BLUR).default.toInt()
        val v = prefs.getInt(KEY_BLUR, default)
        val migrated = prefs.getInt(KEY_BLUR_VERSION, 0) == BLUR_VERSION
        return if (!migrated && v == LEGACY_BLUR_DEFAULT) default else v
    }

    /** 返回所有配置 */
    fun getAll(): JSONObject = JSONObject().apply {
        for (key in PARAM_KEYS) put(key, param(key))
        // 必须写明 this@ConfigManager：apply 里直接写 getString 会调到 JSONObject.getString（抛异常）
        put(KEY_CURRENT_IMAGE, this@ConfigManager.getString(KEY_CURRENT_IMAGE))
        put(KEY_DEPTH_FILE, this@ConfigManager.getString(KEY_DEPTH_FILE))
        put(KEY_HIDE_DEBUG, prefs.getBoolean(KEY_HIDE_DEBUG, true))
    }

    /** 推给渲染引擎的参数 {parallaxMultiplier, smoothness, focusDepth} */
    fun paramsJson(): JSONObject = JSONObject().apply {
        for (key in RENDER_KEYS) put(key, param(key))
    }

    /** 保存界面传来的参数：只认 PARAM_KEYS，按范围钳住；currentImage / depthFile 等一律忽略 */
    fun applyParams(json: JSONObject) {
        val editor = prefs.edit()
        for (key in PARAM_KEYS) {
            if (!json.has(key)) continue
            when (val v = sanitize(key, json.optDouble(key, Double.NaN))) {
                is Int -> editor.putInt(key, v)
                is Float -> editor.putFloat(key, v)
                else -> {}
            }
        }
        if (json.has(KEY_BLUR)) editor.putInt(KEY_BLUR_VERSION, BLUR_VERSION)
        editor.apply()
    }

    /** 应用壁纸：一次写入图片和深度图，壁纸服务只会收到一组改动 */
    fun setWallpaper(image: String, depthFile: String) {
        prefs.edit()
            .putString(KEY_CURRENT_IMAGE, image)
            .putString(KEY_DEPTH_FILE, depthFile)
            .apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** 获取 SharedPreferences.Editor 用于部分更新 */
    fun edit(): SharedPreferences.Editor = prefs.edit()

    /** 读取单个字符串值 */
    fun getString(key: String, default: String = ""): String = prefs.getString(key, default) ?: default

    /** 读取单个浮点值 */
    fun getFloat(key: String, default: Float = 0f): Float = prefs.getFloat(key, default)
}
