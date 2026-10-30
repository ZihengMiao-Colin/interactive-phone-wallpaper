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

/** 打开系统壁纸设置（让用户自己换回原来的壁纸）的方式 */
enum class SettingsRoute {
    /** 小米"系统个性化"页：theme://zhuti.xiaomi.com/personalize（主题应用对外开放的链接） */
    XIAOMI_PERSONALIZE,

    /** 安卓标准的"设置壁纸"（Intent.ACTION_SET_WALLPAPER） */
    SET_WALLPAPER,
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
     * "恢复原来的壁纸"打开系统壁纸设置的方式（按顺序，前一种打不开才用下一种）。
     * 小米：系统个性化页；其他手机：安卓标准的"设置壁纸"，各品牌会打开自己的壁纸设置
     * （有多个应用能处理时系统会先弹列表让用户选）。
     */
    fun systemSettingsRoutes(isXiaomi: Boolean): List<SettingsRoute> =
        if (isXiaomi) listOf(SettingsRoute.XIAOMI_PERSONALIZE, SettingsRoute.SET_WALLPAPER) else listOf(SettingsRoute.SET_WALLPAPER)
}
