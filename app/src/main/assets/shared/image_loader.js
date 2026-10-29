/**
 * ============================================================================
 * shared/image_loader.js — 图片加载工具
 * ============================================================================
 *
 * 从 app.js 提取 loadImage / loadImageFromDataURL / createFallbackDepthImage。
 * Android 环境下通过 WebViewAssetLoader URL 加载图片。
 */

'use strict';
window.ImageLoader = {

    /**
     * 使用 Image 对象预加载图片。
     * @param {string} url - 图片 URL（WebViewAssetLoader 提供的 https:// 地址）
     * @returns {Promise<HTMLImageElement>}
     */
    loadImage: function(url) {
        return new Promise(function(resolve, reject) {
            var img = new Image();
            img.onload = function() {
                console.log('[加载完成] ' + url + ' (' + img.naturalWidth + 'x' + img.naturalHeight + ')');
                resolve(img);
            };
            img.onerror = function(err) {
                console.error('[加载失败] ' + url, err);
                reject(new Error('无法加载图片: ' + url));
            };
            img.src = url;
        });
    },

    /**
     * 从 Data URL 加载图片。
     * @param {string} dataUrl
     * @returns {Promise<HTMLImageElement>}
     */
    loadImageFromDataURL: function(dataUrl) {
        return new Promise(function(resolve, reject) {
            var img = new Image();
            img.onload = function() { resolve(img); };
            img.onerror = reject;
            img.src = dataUrl;
        });
    },

    /**
     * 创建默认的灰色深度图占位图。
     *
     * 当深度图缺失时使用。中性灰色 (RGB 128,128,128) 在 DisplacementFilter
     * 中表示"无位移"，因此整个画面不会产生视差效果。
     *
     * @param {number} width - 应与背景图宽度一致
     * @param {number} height - 应与背景图高度一致
     * @returns {HTMLImageElement}
     */
    createFallbackDepthImage: function(width, height) {
        var canvas = document.createElement('canvas');
        canvas.width = width || 1920;
        canvas.height = height || 1080;
        var ctx = canvas.getContext('2d');

        // 填充中性灰色 —— 位移滤镜中灰色 = 不偏移
        ctx.fillStyle = 'rgb(128, 128, 128)';
        ctx.fillRect(0, 0, canvas.width, canvas.height);

        // 添加渐变提示（中心略亮），让深度图不至于完全平坦
        var centerX = canvas.width / 2;
        var centerY = canvas.height / 2;
        var maxR = Math.max(canvas.width, canvas.height) * 0.6;
        var gradient = ctx.createRadialGradient(centerX, centerY, 0, centerX, centerY, maxR);
        gradient.addColorStop(0, 'rgb(140, 140, 140)');
        gradient.addColorStop(1, 'rgb(118, 118, 118)');
        ctx.fillStyle = gradient;
        ctx.fillRect(0, 0, canvas.width, canvas.height);

        var img = new Image();
        img.src = canvas.toDataURL('image/png');
        img.width = canvas.width;
        img.height = canvas.height;

        console.log('[ImageLoader] 已生成占位深度图 (' + canvas.width + 'x' + canvas.height + ')');
        return img;
    },
};
