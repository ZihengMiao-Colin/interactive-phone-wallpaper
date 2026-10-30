/**
 * wallpaper-page.js — "壁纸"页：选图 → 裁剪 → 生成深度图 → 应用壁纸
 *
 * 选择和应用分开：选图、裁剪、生成深度图只改 App.state.selected，
 * 桌面壁纸要点"应用壁纸"才换（Bridge.applyWallpaper 写配置，壁纸服务马上同步）。
 */

'use strict';

(() => {
    const $ = (id) => document.getElementById(id);
    const blurText = (v) => (v === 0 ? '关闭' : v + 'px');

    /** 正在为哪张图生成深度图（null = 没在生成） */
    let generating = null;
    let progress = { percent: 0, text: '' };
    let depthError = null;
    let resolution = '';
    let viewSeg = null;

    function screenAspect() {
        const p = App.state.platform;
        return p.screenWidth && p.screenHeight ? p.screenWidth / p.screenHeight : window.innerWidth / window.innerHeight;
    }

    // ==================== 渲染 ====================

    function setButton(btn, text, disabled) {
        btn.querySelector('span').textContent = text;
        btn.disabled = disabled;
    }

    function renderStatusChip() {
        const active = App.state.status.wallpaperActive;
        $('wp-status-chip').className = 'chip status-chip' + (active ? ' ok' : '');
        $('wp-status-text').textContent = active ? '已设为桌面壁纸' : '未设为桌面壁纸';
    }

    function renderDepthStatus() {
        const sel = App.state.selected;
        const box = $('wp-depth-status');
        let kind, icon, label, sub = '';
        if (generating) {
            kind = 'busy'; icon = 'loader-circle'; label = '正在生成深度图'; sub = progress.percent + '%';
        } else if (depthError) {
            kind = 'error'; icon = 'circle-alert'; label = '生成失败，可以重试';
        } else if (sel.depth) {
            kind = 'ready'; icon = 'circle-check'; label = '深度图已就绪';
        } else if (sel.image) {
            kind = 'none'; icon = 'info'; label = '还没有深度图'; sub = '生成后可以预览视差';
        } else {
            kind = 'none'; icon = 'info'; label = '还没有选择图片';
        }
        box.className = 'status ' + kind;
        box.replaceChildren(Icons.svg(icon, 'i' + (kind === 'busy' ? ' spin' : '')),
            Controls.el('span', 'label', label), Controls.el('span', 'sub', sub));

        $('wp-progress').hidden = !generating;
        $('wp-progress').firstElementChild.style.width = progress.percent + '%';
        const text = $('wp-progress-text');
        text.hidden = !generating && !depthError;
        text.classList.toggle('error', !!depthError && !generating);
        text.textContent = generating ? progress.text : (depthError || '');
    }

    function render() {
        const sel = App.state.selected;
        const applied = App.isSelectedApplied();
        const active = App.state.status.wallpaperActive;
        const busy = !!generating;

        renderStatusChip();
        renderDepthStatus();
        $('wp-res').textContent = resolution;
        // 配置里是这张、并且本应用确实是桌面壁纸，才算"使用中"
        $('wp-in-use').hidden = !(applied && active);
        $('wp-view').hidden = !sel.depth;

        $('wp-pick').disabled = busy;
        $('wp-blur').disabled = busy;
        setButton($('wp-generate'), busy ? '生成中…' : (sel.depth ? '重新生成' : '生成深度图'), busy || !sel.image);
        if (applied && !active) {
            // 应用过但在系统预览页退出了：让用户直接在这里重新设
            setButton($('wp-apply'), '设为桌面壁纸', busy);
        } else {
            setButton($('wp-apply'), applied ? '正在使用这张' : '应用壁纸', busy || !sel.depth || applied);
        }
    }

    // ==================== 预览 ====================

    async function showSelected() {
        const sel = App.state.selected;
        try {
            const size = await Preview.show(sel.image, sel.depth);
            resolution = size ? size.width + '×' + size.height : '';
        } catch (e) {
            console.error('[WallpaperPage] 预览加载失败', e);
            resolution = '';
            Shell.toast('预览加载失败', 'error');
        }
        render();
    }

    function setView(view) {
        viewSeg.set(view);
        Preview.setView(view);
    }

    // ==================== 选图 ====================

    /**
     * 打开图片选择器时 onStop 已经销毁了引擎，回来时又因为裁剪界面开着跳过了重建；
     * 没走"确认"这条路（取消、图片打不开、保存失败）就在这里把原来的预览建回来。
     */
    function ensurePreview() {
        if (!CoreEngine.getApp()) showSelected();
    }

    window.onImageSelected = async (pickedName, error) => {
        if (!pickedName) {
            Shell.toast(error || '图片保存失败', 'error');
            ensurePreview();
            return;
        }
        const dataUrl = await Cropper.open(pickedName, screenAspect());
        if (!dataUrl) {
            Bridge.discardPickedImage(pickedName);
            ensurePreview();
            return;
        }
        const name = Bridge.saveCroppedImage(pickedName, dataUrl);
        if (!name) {
            Shell.toast('保存裁剪结果失败', 'error');
            ensurePreview();
            return;
        }
        App.state.selected = { image: name, depth: '' };
        depthError = null;
        App.emit('selected');
        setView('image');
        await showSelected();
    };

    // ==================== 生成深度图 ====================

    function generate() {
        const sel = App.state.selected;
        if (generating || !sel.image) return;
        generating = { image: sel.image };
        progress = { percent: 0, text: '准备中' };
        depthError = null;
        render();
        Bridge.generateDepth(sel.image, App.state.config.blurRadius);
    }

    window.onDepthProgress = (percent, text) => {
        if (!generating) return;
        progress = { percent, text };
        renderDepthStatus();
    };

    window.onAIDepthReady = async (fileName, error) => {
        if (!generating) return;
        const forImage = generating.image;
        generating = null;
        // 生成期间换了图（理论上按钮已锁住）：结果不属于当前选中的图，丢掉
        if (forImage !== App.state.selected.image) {
            render();
            return;
        }
        if (!fileName) {
            depthError = error || '生成失败';
            render();
            Shell.toast('深度图生成失败', 'error');
            return;
        }
        App.state.selected = { image: forImage, depth: fileName };
        App.emit('selected');
        setView('depth');   // 刚生成完自动切到深度图（和桌面一致）
        await showSelected();
        Shell.toast('深度图已生成', 'ok');
    };

    // ==================== 应用壁纸 ====================

    async function apply() {
        const sel = App.state.selected;
        if (!sel.image || !sel.depth || generating) return;
        // 本应用还不是壁纸：先问要不要备份原来的静态壁纸（"恢复原来的壁纸"要用）
        if (!App.state.status.wallpaperActive && !(await BackupPrompt.beforeSetWallpaper())) return;
        if (App.isSelectedApplied() && !App.state.status.wallpaperActive) {
            // 配置已经是这张，只差在系统里设成动态壁纸
            Bridge.openLiveWallpaperPicker();
            return;
        }
        const active = Bridge.applyWallpaper(sel.image, sel.depth);
        App.state.applied = { ...sel };
        App.emit('applied');
        render();
        // 本应用还不是壁纸时已经跳到系统的动态壁纸预览页，回来时 onAppResume 会刷新状态
        if (active) Shell.toast('已应用到桌面壁纸', 'ok');
    }

    // ==================== 初始化 ====================

    App.registerPage({
        name: 'wallpaper',
        init() {
            document.documentElement.style.setProperty('--screen-aspect', String(screenAspect()));
            const big = $('preview-big');
            Preview.mount(big);
            Shell.onTabChange((name) => { if (name === 'wallpaper') Preview.mount(big); });

            viewSeg = Controls.segmented($('wp-view'), (v) => Preview.setView(v));
            viewSeg.set('image');

            const blur = $('wp-blur');
            Controls.setRange(blur, App.state.config.blurRadius);
            $('wp-blur-val').textContent = blurText(App.state.config.blurRadius);
            Controls.bindRange(blur, $('wp-blur-val'), blurText, (v) => {
                App.state.config.blurRadius = v;
                App.saver.save({ blurRadius: v });
            });
            App.on('config', () => {
                Controls.setRange(blur, App.state.config.blurRadius);
                $('wp-blur-val').textContent = blurText(App.state.config.blurRadius);
            });

            $('wp-pick').addEventListener('click', () => Bridge.openImagePicker());
            $('wp-generate').addEventListener('click', generate);
            $('wp-apply').addEventListener('click', apply);

            App.on('status', render);
            // 回到前台：onStop 时引擎已经销毁，裁剪中就先不动（确认后会重新显示）
            App.on('resume', () => {
                if (!Cropper.isOpen()) showSelected();
            });

            render();
            showSelected();
        },
    });
})();
