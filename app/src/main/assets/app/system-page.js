/**
 * system-page.js — "设置"页：动态壁纸状态、电池优化、小米提示、关于
 */

'use strict';

(() => {
    const $ = (id) => document.getElementById(id);

    function render() {
        const { status, applied, platform } = App.state;

        // 动态壁纸：没应用过图片时，系统壁纸页里只会是一片黑，先引导去壁纸页
        const btn = $('sys-wp-btn');
        if (!applied.image) {
            $('sys-wp-text').textContent = '先在"壁纸"页应用一张图片';
            btn.textContent = '设为壁纸';
            btn.disabled = true;
        } else {
            $('sys-wp-text').textContent = status.wallpaperActive ? '正在使用本应用' : '未设为桌面壁纸';
            btn.textContent = status.wallpaperActive ? '重新设置' : '设为壁纸';
            btn.disabled = false;
        }

        renderRestore();

        // 电池优化
        const ignored = status.batteryOptimizationIgnored;
        $('sys-batt-text').textContent = ignored ? '已关闭' : '未关闭 · 壁纸可能被系统清理';
        $('sys-batt-btn').hidden = ignored;

        $('sys-miui').hidden = !platform.isXiaomi;
        $('sys-version').textContent = platform.versionName || '';
        $('sys-device').textContent = [platform.manufacturer, platform.model].filter(Boolean).join(' ')
            + (platform.androidVersion ? ' · Android ' + platform.androidVersion : '');
    }

    // ==================== 恢复原来的壁纸 ====================
    // 安卓不允许普通应用读当前壁纸的图片，没法自动换回原来的壁纸；
    // 这里直接打开系统的壁纸设置（小米是"系统个性化"页，其他手机是系统的"设置壁纸"），让用户自己选回去。

    function renderRestore() {
        const active = App.state.status.wallpaperActive;
        $('sys-restore-btn').disabled = !active;
        $('sys-restore-text').textContent = active
            ? '打开系统壁纸设置，在里面选回原来的壁纸'
            : '本应用不是当前桌面壁纸，不用恢复';
    }

    function restore() {
        // 回来时 onAppResume 会刷新状态
        if (!Bridge.openSystemWallpaperSettings()) Shell.toast('打不开系统壁纸设置', 'error');
    }

    App.registerPage({
        name: 'system',
        init() {
            $('sys-wp-btn').addEventListener('click', () => Bridge.openLiveWallpaperPicker());
            $('sys-batt-btn').addEventListener('click', () => Bridge.requestBatteryOptimizationBypass());
            $('sys-restore-btn').addEventListener('click', restore);
            App.on('status', render);
            App.on('applied', render);
            render();
        },
    });
})();
