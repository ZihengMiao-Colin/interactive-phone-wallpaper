package com.wallpaper.live3d.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WallpaperPickerTest {

    @Test
    fun xiaomi_skipsChangeLiveWallpaper() {
        // 澎湃 OS 的 LiveWallpaperChange 要求第三方壁纸有小米 AppOp 10045，否则直接 finish（没有异常，退不到下一种）
        val routes = WallpaperPicker.routes(isXiaomi = true)
        assertEquals(PickerRoute.LIVE_WALLPAPER_CHOOSER, routes.first())
        assertFalse(PickerRoute.CHANGE_LIVE_WALLPAPER in routes)
    }

    @Test
    fun systemSettings_xiaomiOpensPersonalizeFirst() {
        // 小米的"系统个性化"页（theme://zhuti.xiaomi.com/personalize）打不开时退到标准的"设置壁纸"
        assertEquals(
            listOf(SettingsRoute.XIAOMI_PERSONALIZE, SettingsRoute.SET_WALLPAPER),
            WallpaperPicker.systemSettingsRoutes(isXiaomi = true),
        )
    }

    @Test
    fun systemSettings_othersUseStandardSetWallpaper() {
        assertEquals(listOf(SettingsRoute.SET_WALLPAPER), WallpaperPicker.systemSettingsRoutes(isXiaomi = false))
    }

    @Test
    fun others_tryDirectPreviewFirst() {
        assertEquals(
            listOf(PickerRoute.CHANGE_LIVE_WALLPAPER, PickerRoute.LIVE_WALLPAPER_CHOOSER, PickerRoute.SET_WALLPAPER_CHOOSER),
            WallpaperPicker.routes(isXiaomi = false),
        )
    }
}
