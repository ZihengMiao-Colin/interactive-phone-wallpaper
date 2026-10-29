/**
 * icons.js — 界面图标 + 滑块填充（基于桌面项目 ui/icons.js，只留手机界面用到的图标）
 *
 * 图标来自 Lucide v0.460.0（lucide-static），ISC License：
 * Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as part of Feather (MIT).
 * All other copyright (c) for Lucide are held by Lucide Contributors 2022.
 *
 * 加载时把 <symbol id="i-<名字>"> 雪碧图插到页面里，HTML 直接写
 * <svg class="i"><use href="#i-check"/></svg>，JS 里用 Icons.svg('check')。
 * battery / smartphone / triangle-alert 是手机版新加的，同样来自 lucide-static@0.460.0。
 * 新增图标：从 lucide-static@0.460.0 的 icons/<名字>.svg 复制里面的元素，包成一条 <symbol>。
 */

'use strict';

const Icons = (() => {
    const SYMBOLS = [
        "<symbol id=\"i-image\" viewBox=\"0 0 24 24\"><rect width=\"18\" height=\"18\" x=\"3\" y=\"3\" rx=\"2\" ry=\"2\" /><circle cx=\"9\" cy=\"9\" r=\"2\" /><path d=\"m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21\" /></symbol>",
        "<symbol id=\"i-image-plus\" viewBox=\"0 0 24 24\"><path d=\"M16 5h6\" /><path d=\"M19 2v6\" /><path d=\"M21 11.5V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7.5\" /><path d=\"m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21\" /><circle cx=\"9\" cy=\"9\" r=\"2\" /></symbol>",
        "<symbol id=\"i-layers\" viewBox=\"0 0 24 24\"><path d=\"m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z\" /><path d=\"m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65\" /><path d=\"m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65\" /></symbol>",
        "<symbol id=\"i-sparkles\" viewBox=\"0 0 24 24\"><path d=\"M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z\" /><path d=\"M20 3v4\" /><path d=\"M22 5h-4\" /><path d=\"M4 17v2\" /><path d=\"M5 18H3\" /></symbol>",
        "<symbol id=\"i-settings\" viewBox=\"0 0 24 24\"><path d=\"M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z\" /><circle cx=\"12\" cy=\"12\" r=\"3\" /></symbol>",
        "<symbol id=\"i-wand-sparkles\" viewBox=\"0 0 24 24\"><path d=\"m21.64 3.64-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2 1.2 0 0 0 1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72\" /><path d=\"m14 7 3 3\" /><path d=\"M5 6v4\" /><path d=\"M19 14v4\" /><path d=\"M10 2v2\" /><path d=\"M7 8H3\" /><path d=\"M21 16h-4\" /><path d=\"M11 3H9\" /></symbol>",
        "<symbol id=\"i-check\" viewBox=\"0 0 24 24\"><path d=\"M20 6 9 17l-5-5\" /></symbol>",
        "<symbol id=\"i-x\" viewBox=\"0 0 24 24\"><path d=\"M18 6 6 18\" /><path d=\"m6 6 12 12\" /></symbol>",
        "<symbol id=\"i-rotate-ccw\" viewBox=\"0 0 24 24\"><path d=\"M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8\" /><path d=\"M3 3v5h5\" /></symbol>",
        "<symbol id=\"i-circle-check\" viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\" /><path d=\"m9 12 2 2 4-4\" /></symbol>",
        "<symbol id=\"i-circle-alert\" viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\" /><line x1=\"12\" x2=\"12\" y1=\"8\" y2=\"12\" /><line x1=\"12\" x2=\"12.01\" y1=\"16\" y2=\"16\" /></symbol>",
        "<symbol id=\"i-info\" viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"10\" /><path d=\"M12 16v-4\" /><path d=\"M12 8h.01\" /></symbol>",
        "<symbol id=\"i-loader-circle\" viewBox=\"0 0 24 24\"><path d=\"M21 12a9 9 0 1 1-6.219-8.56\" /></symbol>",
        "<symbol id=\"i-battery\" viewBox=\"0 0 24 24\"><rect width=\"16\" height=\"10\" x=\"2\" y=\"7\" rx=\"2\" ry=\"2\" /><line x1=\"22\" x2=\"22\" y1=\"11\" y2=\"13\" /></symbol>",
        "<symbol id=\"i-smartphone\" viewBox=\"0 0 24 24\"><rect width=\"14\" height=\"20\" x=\"5\" y=\"2\" rx=\"2\" ry=\"2\" /><path d=\"M12 18h.01\" /></symbol>",
        "<symbol id=\"i-triangle-alert\" viewBox=\"0 0 24 24\"><path d=\"m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3\" /><path d=\"M12 9v4\" /><path d=\"M12 17h.01\" /></symbol>",
    ];
    const NS = 'http://www.w3.org/2000/svg';

    function inject() {
        if (document.getElementById('ui-icon-sprite')) return;
        const holder = document.createElement('div');
        holder.innerHTML = `<svg id="ui-icon-sprite" xmlns="${NS}" width="0" height="0" style="position:absolute" aria-hidden="true">${SYMBOLS.join('')}</svg>`;
        document.body.prepend(holder.firstChild);
    }

    /** 生成一个图标节点 */
    function svg(name, className = 'i') {
        const node = document.createElementNS(NS, 'svg');
        node.setAttribute('class', className);
        node.setAttribute('aria-hidden', 'true');
        const use = document.createElementNS(NS, 'use');
        use.setAttribute('href', '#i-' + name);
        node.appendChild(use);
        return node;
    }

    if (document.body) inject();
    else document.addEventListener('DOMContentLoaded', inject);

    return { svg };
})();

/** 滑块已走过的部分用强调色：CSS 读 --p。用户拖动时自动更新，代码里赋值后要手动调用 paintRange */
const UIControls = (() => {
    function paintRange(input) {
        const min = Number(input.min || 0);
        const max = Number(input.max || 100);
        const p = max > min ? ((Number(input.value) - min) / (max - min)) * 100 : 0;
        input.style.setProperty('--p', p + '%');
    }

    document.addEventListener('input', (e) => {
        if (e.target instanceof HTMLInputElement && e.target.type === 'range') paintRange(e.target);
    });

    return { paintRange };
})();
