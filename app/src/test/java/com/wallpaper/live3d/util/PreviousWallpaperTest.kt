package com.wallpaper.live3d.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviousWallpaperTest {

    @Test
    fun decode_nothingRecordedIsUnknown() {
        assertEquals(PreviousWallpaper.Unknown, PreviousWallpaper.decode(null))
    }

    @Test
    fun decode_emptyIsStatic() {
        assertEquals(PreviousWallpaper.Static, PreviousWallpaper.decode(""))
    }

    @Test
    fun decode_componentIsLive() {
        assertEquals(
            PreviousWallpaper.Live("com.miui.miwallpaper/com.miui.miwallpaper.MiWallpaper"),
            PreviousWallpaper.decode("com.miui.miwallpaper/com.miui.miwallpaper.MiWallpaper"),
        )
    }

    @Test
    fun encode_roundTrips() {
        // 静态图片的 WallpaperManager.wallpaperInfo 是 null，存成空串
        assertEquals("", PreviousWallpaper.encode(null))
        assertEquals("a/b", PreviousWallpaper.encode("a/b"))
        assertEquals(PreviousWallpaper.Live("a/b"), PreviousWallpaper.decode(PreviousWallpaper.encode("a/b")))
    }
}
