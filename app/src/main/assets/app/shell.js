/**
 * shell.js — 外壳：底部页签切换、页签栏上方的提示条（Toast）
 */

'use strict';

window.Shell = (() => {
    const TOAST_MS = { ok: 3000, info: 3000, error: 6000 };
    const TOAST_ICON = { ok: 'circle-check', info: 'info', error: 'circle-alert' };

    let current = 'wallpaper';
    const tabListeners = [];
    let toastTimer = null;

    /** @param {'wallpaper'|'parallax'|'system'} name */
    function showTab(name) {
        current = name;
        for (const page of document.querySelectorAll('.page')) page.hidden = page.dataset.page !== name;
        for (const btn of document.querySelectorAll('.tabbar [data-tab]')) {
            const on = btn.dataset.tab === name;
            btn.classList.toggle('on', on);
            btn.setAttribute('aria-selected', String(on));
        }
        for (const cb of tabListeners) cb(name);
    }

    function onTabChange(cb) {
        tabListeners.push(cb);
    }

    /**
     * 提示条：成功 / 普通 3 秒，错误 6 秒，新的顶掉旧的。
     * @param {string} message
     * @param {'ok'|'error'|'info'} [kind]
     */
    function toast(message, kind = 'info') {
        const el = document.getElementById('toast');
        el.className = 'toast glass ' + kind;
        el.replaceChildren(Icons.svg(TOAST_ICON[kind] || 'info'), Controls.el('span', null, message));
        el.hidden = false;
        void el.offsetWidth;   // 重新触发淡入
        el.classList.add('show');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => {
            el.classList.remove('show');
            toastTimer = setTimeout(() => { el.hidden = true; }, 250);
        }, TOAST_MS[kind] || 3000);
    }

    function init() {
        document.querySelector('.tabbar').addEventListener('click', (e) => {
            const btn = e.target.closest('[data-tab]');
            if (btn && btn.dataset.tab !== current) showTab(btn.dataset.tab);
        });
        showTab('wallpaper');
    }

    return { init, showTab, onTabChange, toast, current: () => current };
})();
