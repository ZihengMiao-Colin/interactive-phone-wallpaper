package com.wallpaper.live3d.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RestorePlanTest {

    @Test
    fun currentKind() {
        val ours = "com.wallpaper.live3d/com.wallpaper.live3d.wallpaper.LiveWallpaperService"
        // 静态图片时 WallpaperManager.wallpaperInfo 是 null
        assertEquals(CurrentWallpaper.STATIC, RestorePlan.currentKind(null, ours))
        assertEquals(CurrentWallpaper.OURS, RestorePlan.currentKind(ours, ours))
        assertEquals(CurrentWallpaper.LIVE, RestorePlan.currentKind("com.miui.miwallpaper/com.miui.miwallpaper.MiWallpaper", ours))
    }

    @Test
    fun liveWallpaper_opensItsPreview_evenWithStaleBackup() {
        assertEquals(RestoreAction.OPEN_PREVIOUS_LIVE, RestorePlan.decide(PreviousWallpaper.Live("a/b"), hasBackup = true))
        assertEquals(RestoreAction.OPEN_PREVIOUS_LIVE, RestorePlan.decide(PreviousWallpaper.Live("a/b"), hasBackup = false))
    }

    @Test
    fun staticWithBackup_setsBackupInOneTap() {
        assertEquals(RestoreAction.SET_BACKUP, RestorePlan.decide(PreviousWallpaper.Static, hasBackup = true))
        assertEquals(RestoreAction.SET_BACKUP, RestorePlan.decide(PreviousWallpaper.Unknown, hasBackup = true))
    }

    @Test
    fun noBackup_opensSystemWallpaperSettings() {
        assertEquals(RestoreAction.OPEN_SYSTEM_SETTINGS, RestorePlan.decide(PreviousWallpaper.Static, hasBackup = false))
        assertEquals(RestoreAction.OPEN_SYSTEM_SETTINGS, RestorePlan.decide(PreviousWallpaper.Unknown, hasBackup = false))
    }
}
