/**
 * bridge.js — 包装 Kotlin 的 AppBridge（JSON 字符串在这里解析）
 *
 * 不在 App 里时（浏览器开发预览）用假实现，数据来自仓库根目录的 dev/：
 *   ?fail=depth  生成深度图失败
 *   ?active=0    本应用不是当前桌面壁纸
 *   ?xiaomi=0    不是小米手机
 *   ?prev=live|static|unknown  设置本应用之前的壁纸（默认 static）
 *   ?current=static|live       本应用不是壁纸时，当前壁纸的类型（默认 static）
 *   ?backup=1                  已经备份过原壁纸
 */

'use strict';

window.Bridge = (() => {
    const native = window.AppBridge;
    const LOCAL_BASE = 'https://appassets.androidplatform.net/local/';

    function parse(json, fallback) {
        try {
            return JSON.parse(json);
        } catch (e) {
            return fallback;
        }
    }

    const NO_STATUS = { wallpaperActive: false, batteryOptimizationIgnored: false };

    if (native) {
        return {
            isNative: true,
            getConfig: () => parse(native.getConfig(), {}),
            saveConfig: (partial) => native.saveConfig(JSON.stringify(partial)),
            openImagePicker: () => native.openImagePicker(),
            saveCroppedImage: (picked, dataUrl) => native.saveCroppedImage(picked, dataUrl),
            discardPickedImage: (picked) => native.discardPickedImage(picked),
            generateDepth: (image, blurRadius) => native.generateAIDepthMap(image, blurRadius),
            applyWallpaper: (image, depth) => native.applyWallpaper(image, depth),
            openLiveWallpaperPicker: () => native.openLiveWallpaperPicker(),
            getStatus: () => parse(native.getStatus(), NO_STATUS),
            getPlatformInfo: () => parse(native.getPlatformInfo(), {}),
            requestBatteryOptimizationBypass: () => native.requestBatteryOptimizationBypass(),
            /** @returns {{kind: 'live'|'static'|'unknown', label?: string}} */
            getPreviousWallpaper: () => parse(native.getPreviousWallpaper(), { kind: 'unknown' }),
            /** @returns {'ours'|'live'|'static'} */
            getCurrentWallpaperKind: () => native.getCurrentWallpaperKind(),
            /** 原壁纸备份的文件名，没有时为空串 */
            getBackup: () => native.getBackup(),
            pickBackupImage: () => native.pickBackupImage(),
            clearBackup: () => native.clearBackup(),
            /** @returns {'live'|'backup'|'settings'|'failed'} */
            restorePrevious: () => native.restorePrevious(),
            /** @param {'wallpapers'|'depth_cache'|'original_wallpaper'} dir */
            localUrl: (dir, name) => LOCAL_BASE + dir + '/' + encodeURIComponent(name),
            log: (msg) => native.log(String(msg)),
        };
    }

    // ==================== 浏览器开发用的假实现 ====================

    const query = new URLSearchParams(location.search);
    const xiaomi = query.get('xiaomi') !== '0';
    let wallpaperActive = query.get('active') !== '0';
    const PARAM_KEYS = ['parallaxMultiplier', 'smoothness', 'focusDepth', 'deadZoneDeg', 'maxTiltDeg', 'blurRadius'];
    const config = {
        parallaxMultiplier: 10, smoothness: 0.05, focusDepth: 0.5,
        deadZoneDeg: 3, maxTiltDeg: 30, blurRadius: 8,
        currentImage: 'sample.jpg', depthFile: 'sample-depth.png',
    };
    /** 裁剪结果：文件名 → dataUrl */
    const files = {};
    /** 原壁纸备份 */
    let backup = query.get('backup') === '1' ? 'sample.jpg' : '';

    return {
        isNative: false,
        getConfig: () => ({ ...config }),
        saveConfig(partial) {
            for (const key of PARAM_KEYS) if (partial[key] !== undefined) config[key] = partial[key];
            console.log('[Bridge] saveConfig', JSON.stringify(partial));
        },
        openImagePicker() {
            setTimeout(() => window.onImageSelected && window.onImageSelected('sample-src.jpg'), 300);
        },
        saveCroppedImage(picked, dataUrl) {
            const name = 'wp_' + Date.now() + '.jpg';
            files[name] = dataUrl;
            return name;
        },
        discardPickedImage(picked) {
            console.log('[Bridge] discardPickedImage', picked);
        },
        generateDepth(image, blurRadius) {
            const steps = [[5, '正在读取图片'], [15, '正在准备模型'], [30, '正在分析深度'], [80, '正在平滑边缘'], [95, '正在保存']];
            steps.forEach(([p, text], i) => setTimeout(() => window.onDepthProgress && window.onDepthProgress(p, text), 350 * i));
            setTimeout(() => {
                if (!window.onAIDepthReady) return;
                if (query.get('fail') === 'depth') window.onAIDepthReady(null, '深度估计失败: 模拟的错误');
                else window.onAIDepthReady('sample-depth.png', null);
            }, 350 * steps.length);
        },
        applyWallpaper(image, depth) {
            config.currentImage = image;
            config.depthFile = depth;
            return wallpaperActive;
        },
        openLiveWallpaperPicker() {
            console.log('[Bridge] 打开动态壁纸预览页');
        },
        getStatus: () => ({ wallpaperActive, batteryOptimizationIgnored: false }),
        getPlatformInfo: () => ({
            apiLevel: 34, androidVersion: '14', versionName: '1.1.0',
            manufacturer: xiaomi ? 'Xiaomi' : 'Google', model: xiaomi ? '23127PN0CC' : 'Pixel 8',
            screenWidth: 1080, screenHeight: 2340, isXiaomi: xiaomi,
        }),
        requestBatteryOptimizationBypass() {
            console.log('[Bridge] 打开电池优化设置');
        },
        getPreviousWallpaper() {
            const prev = query.get('prev') || 'static';
            return prev === 'live' ? { kind: 'live', label: 'Mi Wallpaper' } : { kind: prev };
        },
        getCurrentWallpaperKind: () => (wallpaperActive ? 'ours' : (query.get('current') || 'static')),
        getBackup: () => backup,
        pickBackupImage() {
            // 模拟用户在相册里选了图、存好了备份
            setTimeout(() => {
                backup = 'sample.jpg';
                if (window.onBackupDone) window.onBackupDone(backup, null);
            }, 300);
        },
        clearBackup() {
            backup = '';
        },
        restorePrevious() {
            if (query.get('prev') === 'live') {
                console.log('[Bridge] 打开原来动态壁纸的预览页');
                return 'live';
            }
            if (!backup) {
                console.log('[Bridge] 打开系统壁纸设置');
                return 'settings';
            }
            setTimeout(() => {
                wallpaperActive = false;
                if (window.onRestoreDone) window.onRestoreDone(true, null);
            }, 300);
            return 'backup';
        },
        localUrl: (dir, name) => files[name] || '/dev/' + name,
        log: (msg) => console.log('[Bridge]', msg),
    };
})();
