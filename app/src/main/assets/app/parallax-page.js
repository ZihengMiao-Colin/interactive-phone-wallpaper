/**
 * parallax-page.js — "视差"页：强度、跟手程度、对焦深度、陀螺仪两个参数
 *
 * 拖动即时生效：预览马上变（CoreEngine.setParams），节流保存配置，
 * 桌面壁纸由壁纸服务监听配置变化跟着变；静止范围 / 最大倾斜由原生 SensorHelper 每次读配置。
 */

'use strict';

(() => {
    const $ = (id) => document.getElementById(id);

    /** 推荐值（"恢复推荐值"同时恢复边缘平滑，和桌面一致） */
    const RECOMMENDED = {
        parallaxMultiplier: 10, smoothness: 0.05, focusDepth: 0.5,
        deadZoneDeg: 3, maxTiltDeg: 30, blurRadius: 5,
    };

    /** 滑块值 ↔ 配置值；format 收到的是滑块值 */
    const SLIDERS = [
        { id: 'px-strength', key: 'parallaxMultiplier', toSlider: (v) => v, toConfig: (v) => v, format: (v) => String(v) },
        { id: 'px-smooth', key: 'smoothness', toSlider: (v) => Math.round(v * 100), toConfig: (v) => v / 100, format: (v) => (v / 100).toFixed(2) },
        { id: 'px-focus', key: 'focusDepth', toSlider: (v) => Math.round(v * 100), toConfig: (v) => v / 100, format: (v) => (v / 100).toFixed(2) },
        { id: 'px-deadzone', key: 'deadZoneDeg', toSlider: (v) => v, toConfig: (v) => v, format: (v) => v.toFixed(1) + '°' },
        { id: 'px-maxtilt', key: 'maxTiltDeg', toSlider: (v) => v, toConfig: (v) => v, format: (v) => v + '°' },
    ];

    /** 按 App.state.config 回填所有滑块 */
    function fill() {
        const config = App.state.config;
        for (const s of SLIDERS) {
            const v = s.toSlider(Number(config[s.key]));
            Controls.setRange($(s.id), v);
            $(s.id + '-val').textContent = s.format(v);
        }
    }

    function reset() {
        Object.assign(App.state.config, RECOMMENDED);
        CoreEngine.setParams(RECOMMENDED);
        App.saver.save({ ...RECOMMENDED });
        App.emit('config');
        Shell.toast('已恢复推荐值', 'ok');
    }

    App.registerPage({
        name: 'parallax',
        init() {
            const small = $('preview-small');
            Shell.onTabChange((name) => { if (name === 'parallax') Preview.mount(small); });

            fill();
            for (const s of SLIDERS) {
                Controls.bindRange($(s.id), $(s.id + '-val'), s.format, (v) => {
                    const value = s.toConfig(v);
                    App.state.config[s.key] = value;
                    CoreEngine.setParams({ [s.key]: value });   // 只认渲染用的三个键，其余忽略
                    App.saver.save({ [s.key]: value });
                });
            }
            App.on('config', fill);
            $('px-reset').addEventListener('click', reset);
        },
    });
})();
