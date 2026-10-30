package com.wallpaper.live3d.util

/** 打开系统动态壁纸设置的几种方式 */
enum class PickerRoute {
    /** 直接进本应用的动态壁纸预览页（ACTION_CHANGE_LIVE_WALLPAPER） */
    CHANGE_LIVE_WALLPAPER,

    /** 系统的动态壁纸列表，用户在里面选本应用（ACTION_LIVE_WALLPAPER_CHOOSER） */
    LIVE_WALLPAPER_CHOOSER,

    /** 通用"设置壁纸"选择器（ACTION_SET_WALLPAPER） */
    SET_WALLPAPER_CHOOSER,
}

object WallpaperPicker {

    /**
     * 按顺序尝试的方式（前一种启动失败才用下一种）。
     *
     * 小米（澎湃 OS）的 LiveWallpaperChange 对第三方壁纸要检查小米自定义 AppOp 10045，
     * 默认不允许，它会打一行 "No permission to change wall paper" 然后直接关掉——
     * 启动本身是成功的，没有异常，也就退不到下一种。所以小米上直接打开动态壁纸列表。
     */
    fun routes(isXiaomi: Boolean): List<PickerRoute> =
        if (isXiaomi) {
            listOf(PickerRoute.LIVE_WALLPAPER_CHOOSER, PickerRoute.SET_WALLPAPER_CHOOSER)
        } else {
            listOf(PickerRoute.CHANGE_LIVE_WALLPAPER, PickerRoute.LIVE_WALLPAPER_CHOOSER, PickerRoute.SET_WALLPAPER_CHOOSER)
        }

    /**
     * 恢复原来的动态壁纸时的打开方式。小米只对第三方壁纸查 AppOp 10045，
     * 系统自带的（Mi Wallpaper、主题壁纸等）可以直接进它的预览页。
     */
    fun restoreRoutes(isXiaomi: Boolean, isSystemPackage: Boolean): List<PickerRoute> =
        if (isXiaomi && !isSystemPackage) {
            listOf(PickerRoute.LIVE_WALLPAPER_CHOOSER)
        } else {
            listOf(PickerRoute.CHANGE_LIVE_WALLPAPER, PickerRoute.LIVE_WALLPAPER_CHOOSER)
        }
}
