package com.wallpaper.live3d.util

/**
 * 用户在设置本应用之前的桌面壁纸（"恢复原来的壁纸"用）。
 *
 * 安卓 13 起普通应用读不到当前壁纸的图片（要"所有文件访问"权限），所以静态图片只能记下"是静态的"，
 * 恢复时让用户从相册选回那张图；动态壁纸记下组件名，恢复时跳到它的系统预览页。
 */
sealed class PreviousWallpaper {
    /** 没有记录（比如这个功能加入之前就设过本应用） */
    object Unknown : PreviousWallpaper()

    /** 静态图片 */
    object Static : PreviousWallpaper()

    /** 动态壁纸，component 是 ComponentName.flattenToString() */
    data class Live(val component: String) : PreviousWallpaper()

    companion object {
        /** 存储格式：没有这个键 = Unknown，空串 = Static，其余 = Live 的组件名 */
        fun decode(stored: String?): PreviousWallpaper = when {
            stored == null -> Unknown
            stored.isEmpty() -> Static
            else -> Live(stored)
        }

        /** @param component 当时的 WallpaperManager.wallpaperInfo 组件名；静态图片时为 null */
        fun encode(component: String?): String = component ?: ""
    }
}
