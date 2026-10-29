package com.wallpaper.live3d.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WallpaperFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun names(dir: File) = (dir.list() ?: emptyArray()).sorted()

    @Test
    fun newName_format() {
        assertEquals("wp_1700000000000.jpg", WallpaperFiles.newName("wp", "jpg", 1700000000000))
    }

    @Test
    fun clean_keepsReferenced_removesOthers() {
        val w = tmp.newFolder("wallpapers")
        val d = tmp.newFolder("depth_cache")
        for (n in listOf("wp_1.jpg", "wp_2.jpg", "pick_3.png")) File(w, n).writeText("x")
        for (n in listOf("depth_1.png", "depth_2.png", "index.json")) File(d, n).writeText("x")

        WallpaperFiles.cleanUnreferenced(w, d, "wp_1.jpg", "depth_1.png")

        assertEquals(listOf("wp_1.jpg"), names(w))
        assertEquals(listOf("depth_1.png", "index.json"), names(d))
    }

    @Test
    fun clean_emptyKeepsDeletesAll() {
        val w = tmp.newFolder("wallpapers")
        val d = tmp.newFolder("depth_cache")
        File(w, "wp_1.jpg").writeText("x")
        File(d, "depth_1.png").writeText("x")

        WallpaperFiles.cleanUnreferenced(w, d, "", "")
        WallpaperFiles.cleanUnreferenced(File(tmp.root, "missing"), File(tmp.root, "missing2"), "", "")

        assertEquals(emptyList<String>(), names(w))
        assertEquals(emptyList<String>(), names(d))
    }
}
