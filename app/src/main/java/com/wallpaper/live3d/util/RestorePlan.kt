package com.wallpaper.live3d.util

/** 当前的桌面壁纸是什么 */
enum class CurrentWallpaper { OURS, LIVE, STATIC }

/** "恢复原来的壁纸"要做的事 */
enum class RestoreAction {
    /** 原来是动态壁纸：打开它的系统预览页 */
    OPEN_PREVIOUS_LIVE,

    /** 原来是静态图片并且备份过：直接把备份设成桌面壁纸 */
    SET_BACKUP,

    /** 没有备份：打开系统的壁纸设置（小米是"系统个性化"页），让用户自己选回去 */
    OPEN_SYSTEM_SETTINGS,
}

object RestorePlan {

    /**
     * @param current 当前的 WallpaperManager.wallpaperInfo 组件名（静态图片时为 null）
     * @param ours 本应用壁纸服务的组件名
     */
    fun currentKind(current: String?, ours: String): CurrentWallpaper = when (current) {
        null -> CurrentWallpaper.STATIC
        ours -> CurrentWallpaper.OURS
        else -> CurrentWallpaper.LIVE
    }

    /** 原来是动态壁纸时优先跳回它（这时的备份是更早以前的，不能用） */
    fun decide(previous: PreviousWallpaper, hasBackup: Boolean): RestoreAction = when {
        previous is PreviousWallpaper.Live -> RestoreAction.OPEN_PREVIOUS_LIVE
        hasBackup -> RestoreAction.SET_BACKUP
        else -> RestoreAction.OPEN_SYSTEM_SETTINGS
    }
}
