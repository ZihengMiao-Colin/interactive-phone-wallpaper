package com.wallpaper.live3d.util

import java.io.File

/**
 * 壁纸图片和深度图的文件命名与清理。
 *
 * 选图、裁剪、生成深度图都写新文件（名字带时间戳），从不覆盖桌面壁纸正在用的那张；
 * 没被配置引用的旧文件在 App 启动时清掉。
 */
object WallpaperFiles {

    /** "${prefix}_${时间戳}.$ext"，例如 wp_1700000000000.jpg */
    fun newName(prefix: String, ext: String, now: Long = System.currentTimeMillis()): String =
        "${prefix}_${now}.$ext"

    /**
     * 删掉 wallpapersDir 里除 keepImage 以外的文件，删掉 depthDir 里除 keepDepth 以外的 *.png
     * （depthDir 里的 index.json 等其他文件不动）。目录不存在时什么都不做。
     */
    fun cleanUnreferenced(wallpapersDir: File, depthDir: File, keepImage: String, keepDepth: String) {
        wallpapersDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != keepImage) f.delete()
        }
        depthDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".png") && f.name != keepDepth) f.delete()
        }
    }
}
