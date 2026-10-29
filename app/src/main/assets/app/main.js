/**
 * main.js — 入口：共享状态、事件、页面注册
 *
 * 页面脚本加载时调用 App.registerPage({ name, init })；
 * DOMContentLoaded 时读配置、状态和平台信息，然后依次 init。
 *
 * 状态：
 *   applied  —— 桌面壁纸正在用的（配置里的 currentImage / depthFile，只有"应用壁纸"才改）
 *   selected —— 界面里选中的（选图、裁剪、生成深度图只改它）
 * 事件：'selected' | 'applied' | 'status' | 'config' | 'resume'（回到前台）| 'stop'（进了后台）
 */

'use strict';

window.App = (() => {
    const listeners = {};
    const pages = [];

    const state = {
        config: {},
        applied: { image: '', depth: '' },
        selected: { image: '', depth: '' },
        status: { wallpaperActive: false, batteryOptimizationIgnored: false },
        platform: {},
    };

    function on(event, cb) {
        (listeners[event] = listeners[event] || []).push(cb);
    }

    function emit(event) {
        for (const cb of listeners[event] || []) {
            try {
                cb(state);
            } catch (e) {
                console.error('[App] ' + event + ' 处理出错', e);
            }
        }
    }

    /** 选中的就是桌面壁纸正在用的（图片和深度图都一样） */
    function isSelectedApplied() {
        return !!state.selected.image
            && state.selected.image === state.applied.image
            && state.selected.depth === state.applied.depth;
    }

    function registerPage(page) {
        pages.push(page);
    }

    function refreshStatus() {
        state.status = Bridge.getStatus();
        emit('status');
    }

    /** 拖滑块时节流保存：第一次立即发，之后每 100ms 最多一次，最后一个值一定发出去 */
    const saver = Shared.configSync.createConfigSaver((partial) => Bridge.saveConfig(partial), 100);

    // MainActivity.onResume 调用：从系统壁纸页、图片选择器或后台回来
    window.onAppResume = () => {
        refreshStatus();
        emit('resume');
    };

    // MainActivity.onStop 调用（紧接着会销毁渲染引擎）：App 进了后台
    window.onAppStop = () => emit('stop');

    document.addEventListener('DOMContentLoaded', () => {
        state.config = Bridge.getConfig();
        state.applied = { image: state.config.currentImage || '', depth: state.config.depthFile || '' };
        state.selected = { ...state.applied };
        state.status = Bridge.getStatus();
        state.platform = Bridge.getPlatformInfo();
        // App 里的预览用同一个渲染引擎，参数也从配置来
        CoreEngine.setParams(state.config);

        Shell.init();
        for (const page of pages) {
            try {
                page.init();
            } catch (e) {
                console.error('[App] 页面初始化失败: ' + page.name, e);
            }
        }
    });

    return { state, on, emit, isSelectedApplied, registerPage, refreshStatus, saver };
})();
