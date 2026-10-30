package com.wallpaper.live3d.util

import java.io.File
import java.io.InputStream

/**
 * 原来那张静态壁纸的备份（用户在设本应用之前从相册选出来的，存在 App 私有目录）。
 * 目录里只留一份：backup_<时间戳>.<扩展名>。
 */
object WallpaperBackup {

    /** 存一份新备份并删掉旧的，返回文件名 */
    fun save(input: InputStream, dir: File, ext: String, now: Long = System.currentTimeMillis()): String {
        dir.mkdirs()
        val name = "backup_$now.$ext"
        val target = File(dir, name)
        target.outputStream().use { input.copyTo(it) }
        dir.listFiles()?.forEach { if (it.isFile && it.name != name) it.delete() }
        return name
    }

    /** 当前的备份；没有时返回 null */
    fun current(dir: File): File? =
        dir.listFiles()?.filter { it.isFile && it.name.startsWith("backup_") }?.maxByOrNull { it.lastModified() }

    fun clear(dir: File) {
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }
}
