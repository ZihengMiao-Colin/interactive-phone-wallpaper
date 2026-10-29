/**
 * cropper.js — 全屏裁剪界面（交互沿用旧版：单指拖动移动，双指捏合缩放，比例锁定为屏幕尺寸）
 *
 * Cropper.open(pickedName, aspect) → Promise<string|null>：确认返回 JPEG dataUrl，取消返回 null。
 * 裁剪框坐标都按"图片在屏幕上的显示尺寸"计，确认时再换算回原图像素。
 */

'use strict';

window.Cropper = (() => {
    const $ = (id) => document.getElementById(id);

    const s = {
        imgNaturalW: 0,   // 图片原始宽
        imgNaturalH: 0,   // 图片原始高
        imgDisplayW: 0,   // 图片在屏幕上的显示宽
        imgDisplayH: 0,   // 图片在屏幕上的显示高
        imgOffsetX: 0,    // 图片在 crop-area 中的水平偏移
        imgOffsetY: 0,    // 图片在 crop-area 中的垂直偏移
        cropW: 0,         // 裁剪框宽
        cropH: 0,         // 裁剪框高
        cropX: 0,         // 裁剪框左上角 X（相对图片）
        cropY: 0,         // 裁剪框左上角 Y
        aspectRatio: 1,   // 锁定比例
        touchMode: null,  // 'move' | 'resize' | null
        touchStart: null, // 起始触摸信息
    };

    let resolveOpen = null;
    let bound = false;

    function isOpen() {
        return resolveOpen !== null;
    }

    /** 图片按 contain 摆进裁剪区，初始裁剪框取图片 80%、居中 */
    function layout(imgEl, aspect) {
        const area = $('crop-area');
        const areaW = area.clientWidth;
        const areaH = area.clientHeight;
        s.imgNaturalW = imgEl.naturalWidth;
        s.imgNaturalH = imgEl.naturalHeight;
        s.aspectRatio = aspect;

        const scale = Math.min(areaW / s.imgNaturalW, areaH / s.imgNaturalH);
        s.imgDisplayW = s.imgNaturalW * scale;
        s.imgDisplayH = s.imgNaturalH * scale;
        s.imgOffsetX = (areaW - s.imgDisplayW) / 2;
        s.imgOffsetY = (areaH - s.imgDisplayH) / 2;
        Object.assign(imgEl.style, {
            width: s.imgDisplayW + 'px',
            height: s.imgDisplayH + 'px',
            left: s.imgOffsetX + 'px',
            top: s.imgOffsetY + 'px',
        });

        if (s.imgDisplayW / s.imgDisplayH > aspect) {
            // 图片比屏幕宽 → 裁剪框按高度
            s.cropH = s.imgDisplayH * 0.8;
            s.cropW = s.cropH * aspect;
        } else {
            s.cropW = s.imgDisplayW * 0.8;
            s.cropH = s.cropW / aspect;
        }
        s.cropX = (s.imgDisplayW - s.cropW) / 2;
        s.cropY = (s.imgDisplayH - s.cropH) / 2;
        s.touchMode = null;
        render();
    }

    /** 更新遮罩和裁剪框的位置 */
    function render() {
        const left = s.imgOffsetX + s.cropX;
        const top = s.imgOffsetY + s.cropY;
        const w = s.cropW;
        const h = s.cropH;
        Object.assign($('crop-box').style, { left: left + 'px', top: top + 'px', width: w + 'px', height: h + 'px' });
        Object.assign($('crop-mask-top').style, { top: '0', left: '0', right: '0', height: top + 'px' });
        Object.assign($('crop-mask-bottom').style, { top: (top + h) + 'px', left: '0', right: '0', bottom: '0' });
        Object.assign($('crop-mask-left').style, { top: top + 'px', height: h + 'px', left: '0', width: left + 'px' });
        Object.assign($('crop-mask-right').style, { top: top + 'px', height: h + 'px', left: (left + w) + 'px', right: '0' });
    }

    /** 触摸坐标 → 相对图片显示区域的坐标 */
    function touchPos(touch) {
        const rect = $('crop-area').getBoundingClientRect();
        return { x: touch.clientX - rect.left - s.imgOffsetX, y: touch.clientY - rect.top - s.imgOffsetY };
    }

    /** 裁剪框钳在图片范围内，比图片大时缩到图片大小 */
    function clamp() {
        if (s.cropW > s.imgDisplayW) {
            s.cropW = s.imgDisplayW;
            s.cropH = s.cropW / s.aspectRatio;
        }
        if (s.cropH > s.imgDisplayH) {
            s.cropH = s.imgDisplayH;
            s.cropW = s.cropH * s.aspectRatio;
        }
        s.cropX = Math.min(Math.max(s.cropX, 0), s.imgDisplayW - s.cropW);
        s.cropY = Math.min(Math.max(s.cropY, 0), s.imgDisplayH - s.cropH);
    }

    function onTouchStart(e) {
        e.preventDefault();
        if (e.touches.length === 1) {
            // 单指 → 移动裁剪框
            const p = touchPos(e.touches[0]);
            s.touchMode = 'move';
            s.touchStart = { x: p.x, y: p.y, cropX: s.cropX, cropY: s.cropY };
        } else if (e.touches.length === 2) {
            // 双指 → 以两指中点为锚缩放
            const p1 = touchPos(e.touches[0]);
            const p2 = touchPos(e.touches[1]);
            s.touchMode = 'resize';
            s.touchStart = {
                cx: (p1.x + p2.x) / 2,
                cy: (p1.y + p2.y) / 2,
                dist: Math.hypot(p2.x - p1.x, p2.y - p1.y),
                cropW: s.cropW, cropH: s.cropH, cropX: s.cropX, cropY: s.cropY,
            };
        }
    }

    function onTouchMove(e) {
        e.preventDefault();
        const ts = s.touchStart;
        if (!s.touchMode || !ts) return;
        if (s.touchMode === 'move') {
            const p = touchPos(e.touches[0]);
            s.cropX = ts.cropX + p.x - ts.x;
            s.cropY = ts.cropY + p.y - ts.y;
        } else if (e.touches.length === 2) {
            const p1 = touchPos(e.touches[0]);
            const p2 = touchPos(e.touches[1]);
            const scale = Math.hypot(p2.x - p1.x, p2.y - p1.y) / Math.max(ts.dist, 1);
            const ax = (ts.cx - ts.cropX) / ts.cropW;   // 锚点在裁剪框里的相对位置
            const ay = (ts.cy - ts.cropY) / ts.cropH;
            s.cropW = ts.cropW * scale;
            s.cropH = ts.cropH * scale;
            s.cropX = ts.cx - s.cropW * ax;
            s.cropY = ts.cy - s.cropH * ay;
        }
        clamp();
        render();
    }

    function onTouchEnd() {
        s.touchMode = null;
        s.touchStart = null;
    }

    function finish(result) {
        $('crop-overlay').hidden = true;
        $('crop-image').removeAttribute('src');
        onTouchEnd();
        const resolve = resolveOpen;
        resolveOpen = null;
        if (resolve) resolve(result);
    }

    /** 把裁剪区域按原图像素画到 canvas，导出 JPEG */
    function confirm() {
        const img = $('crop-image');
        if (!img.complete || !s.imgDisplayW) return;
        const sx = s.imgNaturalW / s.imgDisplayW;
        const sy = s.imgNaturalH / s.imgDisplayH;
        const x = Math.max(0, Math.round(s.cropX * sx));
        const y = Math.max(0, Math.round(s.cropY * sy));
        const w = Math.min(s.imgNaturalW - x, Math.round(s.cropW * sx));
        const h = Math.min(s.imgNaturalH - y, Math.round(s.cropH * sy));
        const canvas = document.createElement('canvas');
        canvas.width = w;
        canvas.height = h;
        canvas.getContext('2d').drawImage(img, x, y, w, h, 0, 0, w, h);
        finish(canvas.toDataURL('image/jpeg', 0.92));
    }

    function bindOnce() {
        if (bound) return;
        bound = true;
        const area = $('crop-area');
        area.addEventListener('touchstart', onTouchStart, { passive: false });
        area.addEventListener('touchmove', onTouchMove, { passive: false });
        area.addEventListener('touchend', onTouchEnd);
        area.addEventListener('touchcancel', onTouchEnd);
        $('crop-confirm').addEventListener('click', confirm);
        $('crop-cancel').addEventListener('click', () => finish(null));
    }

    /**
     * @param {string} pickedName - 选图时复制出来的 pick_ 文件
     * @param {number} aspect - 屏幕宽高比
     * @returns {Promise<string|null>}
     */
    function open(pickedName, aspect) {
        bindOnce();
        if (isOpen()) finish(null);
        const img = $('crop-image');
        $('crop-overlay').hidden = false;
        return new Promise((resolve) => {
            resolveOpen = resolve;
            img.onload = () => layout(img, aspect);
            img.onerror = () => {
                Shell.toast('图片打不开', 'error');
                finish(null);
            };
            img.src = Bridge.localUrl('wallpapers', pickedName);
        });
    }

    return { open, isOpen };
})();
