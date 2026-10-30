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
    // 安卓 13 起普通应用读不到当前壁纸的图片，所以原来是静态图片时只能让用户从相册选回那张图（或恢复系统默认）；
    // 原来是动态壁纸时跳到它的系统预览页。只改桌面，锁屏不动。

    /** 设置本应用之前的桌面壁纸，渲染时刷新 */
    let previous = { kind: 'unknown' };

    const RESTORE_TEXT = {
        static: '原来是静态图片：从相册选回那张图，或恢复系统默认',
        unknown: '没有记录到原来的壁纸：可以从相册选一张，或恢复系统默认',
    };

    function renderRestore() {
        previous = Bridge.getPreviousWallpaper();
        const active = App.state.status.wallpaperActive;
        $('sys-restore-btn').disabled = !active;
        if (!active) $('sys-restore-text').textContent = '本应用不是当前桌面壁纸，不用恢复';
        else if (previous.kind === 'live') $('sys-restore-text').textContent = '原来是动态壁纸「' + previous.label + '」';
        else $('sys-restore-text').textContent = RESTORE_TEXT[previous.kind] || RESTORE_TEXT.unknown;
    }

    function showSheet(show) {
        $('restore-sheet').hidden = !show;
    }

    function restore() {
        if (previous.kind === 'live') {
            // 系统页面里点"设置壁纸"才真正换，回来时 onAppResume 会刷新状态
            if (!Bridge.restorePreviousLive()) Shell.toast('打不开原来的动态壁纸，可能已经卸载', 'error');
            return;
        }
        $('restore-sheet-note').textContent = (previous.kind === 'static'
            ? '读不到你原来那张静态壁纸，可以从相册选回它。'
            : '没有记录到原来的壁纸，可以从相册选一张。') + '只换桌面，锁屏不变。';
        showSheet(true);
    }

    /** MainActivity 改完桌面壁纸后调用：message 为 null 且 ok 为 false 表示用户取消了选图 */
    window.onRestoreDone = (ok, message) => {
        showSheet(false);
        if (ok) Shell.toast('已恢复桌面壁纸', 'ok');
        else if (message) Shell.toast('恢复失败：' + message, 'error');
        App.refreshStatus();
    };

    App.registerPage({
        name: 'system',
        init() {
            $('sys-wp-btn').addEventListener('click', () => Bridge.openLiveWallpaperPicker());
            $('sys-batt-btn').addEventListener('click', () => Bridge.requestBatteryOptimizationBypass());
            $('sys-restore-btn').addEventListener('click', restore);
            $('restore-pick').addEventListener('click', () => {
                showSheet(false);
                Bridge.pickRestoreImage();
            });
            $('restore-default').addEventListener('click', () => {
                showSheet(false);
                Bridge.restoreDefaultWallpaper();
            });
            $('restore-cancel').addEventListener('click', () => showSheet(false));
            $('restore-sheet').addEventListener('click', (e) => {
                if (e.target === $('restore-sheet')) showSheet(false);   // 点遮罩关闭
            });
            App.on('status', render);
            App.on('applied', render);
            render();
        },
    });
})();
