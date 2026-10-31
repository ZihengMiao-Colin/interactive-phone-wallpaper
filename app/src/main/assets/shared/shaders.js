// 节选自桌面项目 renderer/shaders.js（只取背景视差的三段，不含元素着色器），改动要两边同步
/**
 * shaders.js — 背景 2.5D 深度视差着色器源码
 */

'use strict';

/**
 * 自定义顶点着色器
 * 核心关键：直接将 aVertexPosition (归一化 0.0 ~ 1.0 的全屏 Quad 坐标) 传给片元着色器。
 * Pixi.js 默认 Filter 顶点着色器会乘以 outputFrame.zw * inputSize.zw，导致由于
 * 内部分配的 Framebuffer 尺寸比例与屏幕不一致而严重横向拉长图片！
 */
const PARALLAX_VERTEX_SHADER = `
attribute vec2 aVertexPosition;
uniform mat3 projectionMatrix;
uniform vec4 outputFrame;

varying vec2 vTextureCoord;

vec4 filterVertexPosition(void) {
    vec2 position = aVertexPosition * max(outputFrame.zw, vec2(0.0)) + outputFrame.xy;
    return vec4((projectionMatrix * vec3(position, 1.0)).xy, 0.0, 1.0);
}

void main(void) {
    gl_Position = filterVertexPosition();
    // 强制传递 0.0 ~ 1.0 的绝对归一化屏幕坐标
    vTextureCoord = aVertexPosition;
}
`;

/**
 * 视差位移公式和光线步进（GLSL 版），背景、自定义元素和设置页预览的着色器共用。
 * 与 shared/parallax-math.js 的 parallaxUV / rayMarchUV 是同一算法，改一边必须同步另一边。
 *
 * - 对焦平面：depth == focus 时位移为 0，主体不撕裂
 * - 软截断：位移超过 maxDist 后只按 20% 继续增长，避免大幅移动拉出缝隙
 */
const PARALLAX_GLSL = `
vec2 parallaxUV(vec2 mouse, float depth, float focus, float strength) {
    vec2 p = mouse * (depth - focus) * strength;
    float maxDist = max(0.035, strength * 1.6);
    float len = length(p);
    if (len > maxDist) {
        p = (p / len) * (maxDist + (len - maxDist) * 0.2);
    }
    return p;
}

// 光线步进参数，和 shared/parallax-math.js 的 RAY_* 一致
#define RAY_STEP_PX 2.5
#define RAY_MAX_STEPS 48
#define RAY_REFINE_STEPS 5

// 屏幕上这一点实际显示的是图片的哪个位置（图片 UV）。算法见 shared/parallax-math.js 的 rayMarchUV：
// 从最近一层往最远一层试，第一个碰到的表面就是挡在最前面的，前景挪动时能盖住背景、不会被拉扯变形。
// depthSize 是深度图的像素尺寸，用来决定走几步。
vec2 rayMarchUV(sampler2D depthMap, vec2 depthSize, vec2 baseUV, vec2 mouse, float focus, float strength) {
    vec2 spanPx = (parallaxUV(mouse, 1.0, focus, strength) - parallaxUV(mouse, 0.0, focus, strength)) * depthSize;
    float n = clamp(ceil(length(spanPx) / RAY_STEP_PX), 1.0, float(RAY_MAX_STEPS));
    float lo = 1.0;
    float hi = 0.0;
    for (int i = 0; i <= RAY_MAX_STEPS; i++) {
        // i 走到 n 时 t = 0，深度一定 >= 0，循环在这里结束
        float t = max(1.0 - float(i) / n, 0.0);
        if (texture2D(depthMap, baseUV + parallaxUV(mouse, t, focus, strength)).r >= t) {
            hi = t;
            break;
        }
        lo = t;
    }
    for (int k = 0; k < RAY_REFINE_STEPS; k++) {
        float mid = 0.5 * (lo + hi);
        if (texture2D(depthMap, baseUV + parallaxUV(mouse, mid, focus, strength)).r >= mid) hi = mid;
        else lo = mid;
    }
    return baseUV + parallaxUV(mouse, hi, focus, strength);
}
`;

/**
 * 背景片元着色器：屏幕 UV 先按 Cover 映射到图片 UV，再用光线步进找出这里显示的图片位置。
 */
const PARALLAX_FRAGMENT_SHADER = `
precision highp float;

varying vec2 vTextureCoord;

uniform sampler2D uImage;
uniform sampler2D uDepth;
uniform vec2 uDepthSize;
uniform vec2 uMouse;
uniform float uStrength;
uniform float uFocus;
uniform vec2 uCoverScale;
uniform vec2 uCoverOffset;
${PARALLAX_GLSL}
void main(void) {
    vec2 baseUV = vTextureCoord * uCoverScale + uCoverOffset;
    vec2 shownUV = clamp(rayMarchUV(uDepth, uDepthSize, baseUV, uMouse, uFocus, uStrength), vec2(0.001), vec2(0.999));
    gl_FragColor = texture2D(uImage, shownUV);
}
`;
