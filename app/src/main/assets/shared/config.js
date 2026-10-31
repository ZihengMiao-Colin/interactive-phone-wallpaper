/**
 * ============================================================================
 * shared/config.js — 壁纸配置默认值
 * ============================================================================
 *
 * 实际值由 Kotlin 的 ConfigManager 保存，页面启动时读进来覆盖这里。
 */

'use strict';
window.WallpaperConfig = {
    /** 视差强度 —— 换算成着色器强度见 Shared.parallax.strengthFromMultiplier */
    parallaxMultiplier: 10,   // 范围: 5..100

    /** 跟手程度 —— Lerp 插值的比率 (0~1)，越小越平滑 */
    smoothness: 0.05,         // 范围: 0.01..0.30

    /** 对焦深度 —— 这个深度的内容不动 (0 远 ~ 1 近) */
    focusDepth: 0.5,

    /** 静止范围 (度) —— 倾斜小于这个角度时画面不动 */
    deadZoneDeg: 3.0,

    /** 最大倾斜 (度) —— 倾斜到这个角度时视差达到最大 */
    maxTiltDeg: 30.0,

    /** 边缘平滑 (像素) —— 生成深度图时用 */
    blurRadius: 5,

    /** 桌面左右滑动偏移叠加到倾斜输入上的权重（和旧版 5px / 15px 的比例相当） */
    desktopOffsetWeight: 0.33,

    /** 是否隐藏调试面板 */
    hideDebugPanel: true,
};
