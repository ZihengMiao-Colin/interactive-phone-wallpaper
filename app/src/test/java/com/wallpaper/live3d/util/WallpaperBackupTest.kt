package com.wallpaper.live3d.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WallpaperBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun save_writesContentAndReturnsName() {
        val dir = File(tmp.root, "original_wallpaper")
        val name = WallpaperBackup.save("abc".byteInputStream(), dir, "jpg", now = 42)
        assertEquals("backup_42.jpg", name)
        assertArrayEquals("abc".toByteArray(), File(dir, name).readBytes())
        assertEquals(name, WallpaperBackup.current(dir)?.name)
    }

    @Test
    fun save_replacesOldBackup() {
        val dir = tmp.newFolder("original_wallpaper")
        WallpaperBackup.save("old".byteInputStream(), dir, "png", now = 1)
        val name = WallpaperBackup.save("new".byteInputStream(), dir, "jpg", now = 2)
        assertEquals(listOf(name), dir.list()!!.toList())
    }

    @Test
    fun clear_removesBackup() {
        val dir = tmp.newFolder("original_wallpaper")
        WallpaperBackup.save("x".byteInputStream(), dir, "jpg", now = 1)
        WallpaperBackup.clear(dir)
        assertNull(WallpaperBackup.current(dir))
        assertNull(WallpaperBackup.current(File(tmp.root, "missing")))
    }
}
