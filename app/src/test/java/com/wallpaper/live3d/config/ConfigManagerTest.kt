package com.wallpaper.live3d.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigManagerTest {

    @Test
    fun sanitize_intParamsRoundAndClamp() {
        assertEquals(100, ConfigManager.sanitize(ConfigManager.KEY_PARALLAX, 150.0))
        assertEquals(5, ConfigManager.sanitize(ConfigManager.KEY_PARALLAX, 1.0))
        assertEquals(13, ConfigManager.sanitize(ConfigManager.KEY_PARALLAX, 12.6))
        assertEquals(5, ConfigManager.sanitize(ConfigManager.KEY_BLUR, 5.0))
        assertEquals(30, ConfigManager.sanitize(ConfigManager.KEY_BLUR, 99.0))
    }

    @Test
    fun sanitize_floatParamsClamp() {
        assertEquals(0.30f, ConfigManager.sanitize(ConfigManager.KEY_SMOOTHNESS, 0.5))
        assertEquals(0.05f, ConfigManager.sanitize(ConfigManager.KEY_SMOOTHNESS, 0.05))
        assertEquals(0f, ConfigManager.sanitize(ConfigManager.KEY_FOCUS, -1.0))
        assertEquals(60f, ConfigManager.sanitize(ConfigManager.KEY_MAX_TILT, 90.0))
        assertEquals(3f, ConfigManager.sanitize(ConfigManager.KEY_DEAD_ZONE, 3.0))
    }

    @Test
    fun sanitize_rejectsNonParamsAndNaN() {
        assertNull(ConfigManager.sanitize(ConfigManager.KEY_CURRENT_IMAGE, 1.0))
        assertNull(ConfigManager.sanitize("unknown", 1.0))
        assertNull(ConfigManager.sanitize(ConfigManager.KEY_DEAD_ZONE, Double.NaN))
    }

    @Test
    fun getAll_includesWallpaperFiles() {
        val config = ConfigManager(FakeSharedPreferences())
        config.setWallpaper("wp_1.jpg", "depth_1.png")
        val all = config.getAll()
        assertEquals("wp_1.jpg", all.getString(ConfigManager.KEY_CURRENT_IMAGE))
        assertEquals("depth_1.png", all.getString(ConfigManager.KEY_DEPTH_FILE))
        assertEquals(10, all.getInt(ConfigManager.KEY_PARALLAX))
    }

    @Test
    fun getAll_freshInstallHasEmptyWallpaper() {
        val all = ConfigManager(FakeSharedPreferences()).getAll()
        assertEquals("", all.getString(ConfigManager.KEY_CURRENT_IMAGE))
        assertEquals("", all.getString(ConfigManager.KEY_DEPTH_FILE))
    }

    @Test
    fun blur_freshInstallDefaultsTo5() {
        val all = ConfigManager(FakeSharedPreferences()).getAll()
        assertEquals(5, all.getInt(ConfigManager.KEY_BLUR))
    }

    @Test
    fun blur_oldDefault8ReadsAs5() {
        // 旧版本"恢复推荐值"会把 8 存进去，当成没调过
        val prefs = FakeSharedPreferences()
        prefs.edit().putInt(ConfigManager.KEY_BLUR, 8).apply()
        assertEquals(5, ConfigManager(prefs).getAll().getInt(ConfigManager.KEY_BLUR))
    }

    @Test
    fun blur_oldCustomValueKept() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putInt(ConfigManager.KEY_BLUR, 12).apply()
        assertEquals(12, ConfigManager(prefs).getAll().getInt(ConfigManager.KEY_BLUR))
    }

    @Test
    fun blur_savedAfterMigrationKeeps8() {
        // 改成 5 之后用户自己调回 8，不能再被改掉
        val prefs = FakeSharedPreferences()
        prefs.edit().putInt(ConfigManager.KEY_BLUR, 8).apply()
        val config = ConfigManager(prefs)
        config.applyParams(JSONObject().put(ConfigManager.KEY_BLUR, 8))
        assertEquals(8, config.getAll().getInt(ConfigManager.KEY_BLUR))
    }

    @Test
    fun keySets() {
        assertEquals(6, ConfigManager.PARAM_KEYS.size)
        assertEquals(
            setOf(ConfigManager.KEY_PARALLAX, ConfigManager.KEY_SMOOTHNESS, ConfigManager.KEY_FOCUS),
            ConfigManager.RENDER_KEYS,
        )
    }
}
