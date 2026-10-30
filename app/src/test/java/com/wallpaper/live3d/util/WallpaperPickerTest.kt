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
    fun restore_xiaomiSystemWallpaper_goesToPreview() {
        // 小米只对第三方壁纸查 AppOp 10045，系统自带的（如 Mi Wallpaper）可以直接进预览页
        assertEquals(PickerRoute.CHANGE_LIVE_WALLPAPER, WallpaperPicker.restoreRoutes(isXiaomi = true, isSystemPackage = true).first())
    }

    @Test
    fun restore_xiaomiThirdPartyWallpaper_usesList() {
        assertEquals(
            listOf(PickerRoute.LIVE_WALLPAPER_CHOOSER),
            WallpaperPicker.restoreRoutes(isXiaomi = true, isSystemPackage = false),
        )
    }

    @Test
    fun restore_others_previewThenList() {
        assertEquals(
            listOf(PickerRoute.CHANGE_LIVE_WALLPAPER, PickerRoute.LIVE_WALLPAPER_CHOOSER),
            WallpaperPicker.restoreRoutes(isXiaomi = false, isSystemPackage = false),
        )
    }

    @Test
    fun others_tryDirectPreviewFirst() {
        assertEquals(
            listOf(PickerRoute.CHANGE_LIVE_WALLPAPER, PickerRoute.LIVE_WALLPAPER_CHOOSER, PickerRoute.SET_WALLPAPER_CHOOSER),
            WallpaperPicker.routes(isXiaomi = false),
        )
    }
}
