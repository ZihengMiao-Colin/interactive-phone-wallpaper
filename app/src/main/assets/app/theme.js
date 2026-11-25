/**
 * theme.js — 界面主题：夜间 / 日间 / 跟随系统
 *
 * 放在 <head> 里同步执行，第一次画出来之前就把 <html data-theme="dark|light"> 定好，不闪一下深色。
 * 设置从配置的 theme 来，系统深浅从 getPlatformInfo().systemDark 来（App 里直接读 AppBridge，
 * 这时 bridge.js 还没加载）；系统切深浅时 MainActivity 调 window.onSystemThemeChange(dark)。
 * 浏览器开发预览：?theme=dark|light|system，?dark=0|1 模拟系统深浅（默认深色）。
 */

'use strict';

window.Theme = (() => {
    const VALID = ['dark', 'light', 'system'];

    let setting = 'dark';
    let systemDark = true;

    function normalize(theme) {
        return VALID.includes(theme) ? theme : 'dark';
    }

    /**
     * @param {string} theme 设置里选的：dark | light | system（其他值当 dark）
     * @param {boolean} sysDark 系统当前是不是深色
     * @returns {'dark'|'light'}
     */
    function resolve(theme, sysDark) {
        const t = normalize(theme);
        if (t === 'system') return sysDark ? 'dark' : 'light';
        return t;
    }

    /** 按设置换主题，返回实际用的 dark / light */
    function apply(theme) {
        setting = normalize(theme);
        const actual = resolve(setting, systemDark);
        document.documentElement.dataset.theme = actual;
        return actual;
    }

    function setSystemDark(dark) {
        systemDark = !!dark;
        apply(setting);
    }

    function readInitial() {
        const native = window.AppBridge;
        if (native) {
            try {
                return {
                    theme: JSON.parse(native.getConfig()).theme,
                    systemDark: JSON.parse(native.getPlatformInfo()).systemDark !== false,
                };
            } catch (e) {
                return { theme: 'dark', systemDark: true };
            }
        }
        const q = new URLSearchParams(location.search);
        return { theme: q.get('theme') || 'dark', systemDark: q.get('dark') !== '0' };
    }

    const initial = readInitial();
    systemDark = initial.systemDark;
    apply(initial.theme);

    window.onSystemThemeChange = (dark) => setSystemDark(dark);

    return { resolve, apply, setSystemDark, setting: () => setting };
})();
