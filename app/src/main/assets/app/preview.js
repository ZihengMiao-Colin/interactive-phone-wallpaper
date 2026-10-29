/**
 * preview.js — App 里的实时视差预览（壁纸页和视差页共用一个渲染引擎，切页签时挪画布）
 *
 * 输入：
 *   - 陀螺仪：MainActivity 推 window.onSensorTilt(x, y)，手指没按在预览上时生效
 *   - 手指：按住预览拖动时按相对位置驱动，松手回到陀螺仪
 *   - 没有深度图时不转发任何输入，画面不动，显示"生成深度图后可以预览视差"
 */

'use strict';

window.Preview = (() => {
    /** 第一次触摸或显示这么久之后，顶部提示淡出 */
    const HINT_MS = 4000;

    let container = null;
    const containers = new Set();
    let shown = { image: '', depth: '' };
    let touching = false;
    let tilt = [0, 0];
    let hintTimer = null;
    /**
     * App 在后台（onStop 已经销毁引擎）：show() 只记下要显示什么，不建引擎，
     * 不然后台完成的深度图会在后台重新占一份显存；回前台时再建。
     */
    let suspended = false;
    App.on('stop', () => { suspended = true; });
    App.on('resume', () => { suspended = false; });

    function hasDepth() {
        return !!shown.depth;
    }

    function forward(x, y) {
        if (hasDepth()) CoreEngine.setSensorTarget(x, y);
    }

    function hideHint() {
        clearTimeout(hintTimer);
        for (const el of containers) el.classList.add('hint-gone');
    }

    /** 各容器上的遮罩状态：没有图片 / 没有深度图 */
    function paintContainers() {
        for (const el of containers) {
            el.classList.toggle('empty', !shown.image);
            el.classList.toggle('no-depth', !!shown.image && !hasDepth());
        }
    }

    function bindTouch(el) {
        function drive(e) {
            const t = e.touches[0];
            if (!t) return;
            const rect = el.getBoundingClientRect();
            touching = true;
            hideHint();
            forward(((t.clientX - rect.left) / rect.width) * 2 - 1, ((t.clientY - rect.top) / rect.height) * 2 - 1);
        }
        function release() {
            touching = false;
            forward(tilt[0], tilt[1]);
        }
        el.addEventListener('touchstart', drive, { passive: true });
        el.addEventListener('touchmove', (e) => { e.preventDefault(); drive(e); }, { passive: false });
        el.addEventListener('touchend', release);
        el.addEventListener('touchcancel', release);
    }

    window.onSensorTilt = (x, y) => {
        tilt = [x, y];
        if (!touching) forward(x, y);
    };

    /** 把预览挂到这个容器（第一次挂时绑定触摸） */
    function mount(el) {
        container = el;
        if (!containers.has(el)) {
            containers.add(el);
            bindTouch(el);
            paintContainers();
        }
        if (CoreEngine.getApp()) CoreEngine.mount(el);
    }

    /**
     * 显示一张图（引擎没有时初始化，有时热替换纹理）。
     * @returns {Promise<{width: number, height: number}|null>} 原图尺寸；没有图片时 null
     */
    async function show(image, depth) {
        shown = { image: image || '', depth: depth || '' };
        paintContainers();
        if (!shown.image) {
            CoreEngine.destroy();
            return null;
        }
        if (suspended) return null;
        const bg = Bridge.localUrl('wallpapers', shown.image);
        const dep = shown.depth ? Bridge.localUrl('depth_cache', shown.depth) : null;
        if (CoreEngine.getApp()) await CoreEngine.reloadImages(bg, dep);
        else await CoreEngine.init(bg, dep, 'preview', container);

        if (hasDepth()) {
            forward(tilt[0], tilt[1]);
            clearTimeout(hintTimer);
            hintTimer = setTimeout(hideHint, HINT_MS);
        } else {
            CoreEngine.setSensorTarget(0, 0);
        }
        return CoreEngine.getImageSize();
    }

    /** 回到前台时用：onStop 已经销毁了引擎，按当前图片重建 */
    function rebuild() {
        return show(shown.image, shown.depth);
    }

    function setView(view) {
        CoreEngine.setView(view);
    }

    return { mount, show, rebuild, setView, hasDepth };
})();
