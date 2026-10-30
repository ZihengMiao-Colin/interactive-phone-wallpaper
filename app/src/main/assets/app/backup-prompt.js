/**
 * backup-prompt.js — 设本应用为壁纸之前，提示备份原来那张静态壁纸
 *
 * 安卓不允许普通应用读当前壁纸的图片，所以要"恢复原来的壁纸"只能让用户先从相册选出那张图存一份。
 * 当前壁纸是别的动态壁纸时不用备份（原生层会记下它是哪个），本应用已经是壁纸时也不问。
 *
 * BackupPrompt.beforeSetWallpaper() → Promise<boolean>：true 继续设置，false 用户取消。
 */

'use strict';

window.BackupPrompt = (() => {
    const $ = (id) => document.getElementById(id);

    let resolveOpen = null;
    let bound = false;

    function close(result) {
        $('backup-sheet').hidden = true;
        const resolve = resolveOpen;
        resolveOpen = null;
        if (resolve) resolve(result);
    }

    /** 已有备份时显示那张图，主按钮变成"用这张备份" */
    function render() {
        const name = Bridge.getBackup();
        $('backup-current').hidden = !name;
        $('backup-use').hidden = !name;
        if (name) $('backup-thumb').src = Bridge.localUrl('original_wallpaper', name) + '?t=' + Date.now();
        const pick = $('backup-pick');
        pick.querySelector('span').textContent = name ? '重新选择…' : '从相册选择…';
        pick.classList.toggle('primary', !name);
    }

    /** MainActivity 存好备份后调用：name 和 error 都是 null 表示用户取消了选图，留在菜单里 */
    window.onBackupDone = (name, error) => {
        if (!resolveOpen) return;
        if (name) {
            Shell.toast('已备份原来的壁纸', 'ok');
            close(true);
        } else if (error) {
            Shell.toast('备份失败：' + error, 'error');
        }
    };

    function bindOnce() {
        if (bound) return;
        bound = true;
        $('backup-use').addEventListener('click', () => close(true));
        $('backup-pick').addEventListener('click', () => Bridge.pickBackupImage());
        $('backup-skip').addEventListener('click', () => {
            // 不备份：旧备份可能已经过时，删掉，以后恢复时改为打开系统壁纸设置
            Bridge.clearBackup();
            close(true);
        });
        $('backup-cancel').addEventListener('click', () => close(false));
        $('backup-sheet').addEventListener('click', (e) => {
            if (e.target === $('backup-sheet')) close(false);   // 点遮罩等于取消
        });
    }

    function beforeSetWallpaper() {
        if (Bridge.getCurrentWallpaperKind() !== 'static') return Promise.resolve(true);
        bindOnce();
        if (resolveOpen) close(false);
        render();
        $('backup-sheet').hidden = false;
        return new Promise((resolve) => { resolveOpen = resolve; });
    }

    return { beforeSetWallpaper };
})();
