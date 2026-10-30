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
    // 原来是动态壁纸 → 跳到它的系统预览页；原来是静态图片并且设之前备份过 → 一键换回；
    // 没有备份 → 打开系统壁纸设置（小米是"系统个性化"页）让用户自己选。只改桌面，锁屏不动。
    // 安卓 13 起普通应用读不到当前壁纸的图片，所以静态图片要靠设之前的备份（见 backup-prompt.js）。

    function renderRestore() {
        const previous = Bridge.getPreviousWallpaper();
        const active = App.state.status.wallpaperActive;
        $('sys-restore-btn').disabled = !active;
        let text;
        if (!active) text = '本应用不是当前桌面壁纸，不用恢复';
        else if (previous.kind === 'live') text = '原来是动态壁纸「' + previous.label + '」，点恢复跳到它的设置页';
        else if (Bridge.getBackup()) text = '已备份原来的壁纸，点恢复一键换回';
        else text = '没有备份原图，点恢复打开系统壁纸设置自己选';
        $('sys-restore-text').textContent = text;
    }

    function restore() {
        // 跳到系统页面的两种情况，回来时 onAppResume 会刷新状态；"backup" 的结果走 onRestoreDone
        if (Bridge.restorePrevious() === 'failed') Shell.toast('打不开系统壁纸设置', 'error');
    }

    /** MainActivity 把备份设成桌面壁纸后调用 */
    window.onRestoreDone = (ok, message) => {
        if (ok) Shell.toast('已换回原来的壁纸', 'ok');
        else Shell.toast('恢复失败：' + (message || '未知错误'), 'error');
        App.refreshStatus();
    };

    /** 设为壁纸：本应用还不是壁纸时，先问要不要备份原来的静态壁纸 */
    async function setAsWallpaper() {
        if (!App.state.status.wallpaperActive && !(await BackupPrompt.beforeSetWallpaper())) return;
        Bridge.openLiveWallpaperPicker();
    }

    App.registerPage({
        name: 'system',
        init() {
            $('sys-wp-btn').addEventListener('click', setAsWallpaper);
            $('sys-batt-btn').addEventListener('click', () => Bridge.requestBatteryOptimizationBypass());
            $('sys-restore-btn').addEventListener('click', restore);
            App.on('status', render);
            App.on('applied', render);
            render();
        },
    });
})();
