package com.wallpaper.live3d.depth

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ============================================================================
 * DepthProcessing — 深度图生成的数值处理（纯函数，不依赖 Android API）
 * ============================================================================
 *
 * 移植自桌面项目 src/depth/processing.js，算法和参数保持一致，改动要两边同步：
 *   - 缩放用 Pillow 的 bicubic 算法：8 位图按 Pillow 的定点数算法，浮点图用双精度系数，
 *     结果和桌面版逐值一致（DepthProcessingTest 里有 Pillow 生成的对照数据）
 *   - 后处理：归一化 → 用浮点放大到输出尺寸 → 近处轮廓外扩 → 三次均值滤波逼近高斯 → 量化成 8 位
 *
 * 平面数据都是行优先的一维数组，宽高单独传。
 * 数值类型和 JS 版一一对应：JS 里存进 Float32Array 或经 Math.fround 的值用 Float，
 * 累加和系数用 Double，8 位定点累加用 Long。
 */
object DepthProcessing {

    /** 模型固定输入 518×518（位置编码定死了，不能保持原图比例） */
    const val MODEL_INPUT_SIZE = 518

    /** 输出深度图的最长边。深度图本身很平滑，再大看不出差别，只会让后处理变慢、缓存变大 */
    const val DEPTH_MAX_SIDE = 1920

    const val DEFAULT_BLUR = 8

    private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** Python 的 round：恰好一半时取偶数 */
    fun roundHalfEven(x: Double): Int = Math.rint(x).toInt()

    // ==================== 尺寸 ====================

    /** 输出深度图尺寸：保持原图比例，最长边不超过 maxSide */
    fun depthOutputSize(width: Int, height: Int, maxSide: Int = DEPTH_MAX_SIDE): Pair<Int, Int> {
        val scale = min(1.0, maxSide.toDouble() / max(width, height))
        return Pair(max(1, roundHalfEven(width * scale)), max(1, roundHalfEven(height * scale)))
    }

    // ==================== Pillow 兼容的 bicubic 缩放 ====================

    /** Pillow 的 bicubic 核（a = -0.5，支撑半径 2） */
    private fun bicubicFilter(v: Double): Double {
        val a = -0.5
        val x = if (v < 0) -v else v
        if (x < 1) return ((a + 2) * x - (a + 3)) * x * x + 1
        if (x < 2) return (((x - 5) * x + 8) * x - 4) * a
        return 0.0
    }

    private class Coeffs(val ksize: Int, val bounds: IntArray, val kk: DoubleArray)

    /**
     * 每个输出像素从哪段输入取样、各自的权重（照搬 Pillow 的 precompute_coeffs）。
     * 缩小时核按比例放大，相当于先抗锯齿再取样。
     */
    private fun precomputeCoeffs(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterscale = max(scale, 1.0)
        val support = 2 * filterscale
        val ksize = ceil(support).toInt() * 2 + 1
        val bounds = IntArray(outSize * 2)
        val kk = DoubleArray(outSize * ksize)

        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            val ss = 1 / filterscale
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin

            val k = xx * ksize
            var ww = 0.0
            for (x in 0 until xmax) {
                val w = bicubicFilter((x + xmin - center + 0.5) * ss)
                kk[k + x] = w
                ww += w
            }
            if (ww != 0.0) {
                for (x in 0 until xmax) kk[k + x] /= ww
            }
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        return Coeffs(ksize, bounds, kk)
    }

    /** 8 位图的定点数精度（Pillow 的 PRECISION_BITS） */
    private const val PRECISION_BITS = 32 - 8 - 2
    private const val PRECISION_SCALE = (1 shl PRECISION_BITS).toDouble()

    /** 权重转成定点整数（Pillow 的 normalize_coeffs_8bpc） */
    private fun fixedCoeffs(kk: DoubleArray) = IntArray(kk.size) {
        val k = kk[it]
        (if (k < 0) -0.5 + k * PRECISION_SCALE else 0.5 + k * PRECISION_SCALE).toInt()
    }

    /** 右移即向下取整，和 JS 版的 Math.floor(sum / 2^22) 一致 */
    private fun clip8(sum: Long): Int {
        val v = sum shr PRECISION_BITS
        return if (v < 0) 0 else if (v > 255) 255 else v.toInt()
    }

    /**
     * 8 位 RGB 一维缩放：沿 step 方向，每条线 length 个像素，共 lines 条线。
     * 每个像素 3 个通道连续存放。
     */
    private fun resampleLines8(
        src: ByteArray, dst: ByteArray, lines: Int, inLen: Int, outLen: Int,
        srcLineStride: Int, srcStep: Int, dstLineStride: Int, dstStep: Int,
    ) {
        val c = precomputeCoeffs(inLen, outLen)
        val kk = fixedCoeffs(c.kk)
        val half = 1L shl (PRECISION_BITS - 1)
        for (line in 0 until lines) {
            val s0 = line * srcLineStride
            val d0 = line * dstLineStride
            for (xx in 0 until outLen) {
                val xmin = c.bounds[xx * 2]
                val xmax = c.bounds[xx * 2 + 1]
                val k = xx * c.ksize
                var r = half
                var g = half
                var b = half
                for (x in 0 until xmax) {
                    val p = s0 + (x + xmin) * srcStep
                    val w = kk[k + x].toLong()
                    r += (src[p].toInt() and 0xFF) * w
                    g += (src[p + 1].toInt() and 0xFF) * w
                    b += (src[p + 2].toInt() and 0xFF) * w
                }
                val q = d0 + xx * dstStep
                dst[q] = clip8(r).toByte()
                dst[q + 1] = clip8(g).toByte()
                dst[q + 2] = clip8(b).toByte()
            }
        }
    }

    /** Bitmap.getPixels 得到的 ARGB 像素 → 每像素 3 字节的 RGB（丢掉透明通道，和 Pillow 的 convert('RGB') 一样） */
    fun argbToRgb(argb: IntArray): ByteArray {
        val rgb = ByteArray(argb.size * 3)
        for (i in argb.indices) {
            val p = argb[i]
            rgb[i * 3] = (p shr 16).toByte()
            rgb[i * 3 + 1] = (p shr 8).toByte()
            rgb[i * 3 + 2] = p.toByte()
        }
        return rgb
    }

    /**
     * RGB 图缩放，等同于 Pillow 的 Image.resize(size, Image.BICUBIC)：
     * 先水平后垂直，两遍之间按 8 位取整。尺寸不变时返回一份拷贝。
     */
    fun resizeRgb8(rgb: ByteArray, inW: Int, inH: Int, outW: Int, outH: Int): ByteArray {
        var cur = rgb.copyOf()
        if (outW != inW) {
            val next = ByteArray(outW * inH * 3)
            resampleLines8(cur, next, inH, inW, outW, inW * 3, 3, outW * 3, 3)
            cur = next
        }
        if (outH != inH) {
            val next = ByteArray(outW * outH * 3)
            resampleLines8(cur, next, outW, inH, outH, 3, outW * 3, 3, outW * 3)
            cur = next
        }
        return cur
    }

    /** 浮点图一维缩放（Pillow 的 32 位浮点版本：双精度累加，存回单精度） */
    private fun resampleLinesF(
        src: FloatArray, dst: FloatArray, lines: Int, inLen: Int, outLen: Int,
        srcLineStride: Int, srcStep: Int, dstLineStride: Int, dstStep: Int,
    ) {
        val c = precomputeCoeffs(inLen, outLen)
        for (line in 0 until lines) {
            val s0 = line * srcLineStride
            val d0 = line * dstLineStride
            for (xx in 0 until outLen) {
                val xmin = c.bounds[xx * 2]
                val xmax = c.bounds[xx * 2 + 1]
                val k = xx * c.ksize
                var sum = 0.0
                for (x in 0 until xmax) sum += src[s0 + (x + xmin) * srcStep] * c.kk[k + x]
                dst[d0 + xx * dstStep] = sum.toFloat()
            }
        }
    }

    /** 浮点图缩放，等同于 Pillow 'F' 模式的 Image.resize(size, Image.BICUBIC) */
    fun resizeFloat(data: FloatArray, inW: Int, inH: Int, outW: Int, outH: Int): FloatArray {
        var cur = data
        if (outW != inW) {
            val next = FloatArray(outW * inH)
            resampleLinesF(cur, next, inH, inW, outW, inW, 1, outW, 1)
            cur = next
        }
        if (outH != inH) {
            val next = FloatArray(outW * outH)
            resampleLinesF(cur, next, outW, inH, outH, 1, outW, 1, outW)
            cur = next
        }
        return if (cur === data) data.copyOf() else cur
    }

    // ==================== 模型输入 ====================

    /** RGB 像素 → 模型输入：按 ImageNet 均值方差归一化，排成 NCHW（批大小 1）；每一步都按单精度取整 */
    fun toModelInput(rgb: ByteArray, pixelCount: Int): FloatArray {
        val out = FloatArray(pixelCount * 3)
        for (i in 0 until pixelCount) {
            for (c in 0 until 3) {
                val v = (rgb[i * 3 + c].toInt() and 0xFF) / 255f
                out[c * pixelCount + i] = (v - IMAGENET_MEAN[c]) / IMAGENET_STD[c]
            }
        }
        return out
    }

    // ==================== 外扩和模糊 ====================

    /** 每条线内宽 2r+1 的均值滤波，边缘按最近像素延伸 */
    private fun boxLine(src: FloatArray, dst: FloatArray, start: Int, step: Int, n: Int, r: Int) {
        val size = 2 * r + 1
        var sum = 0.0
        for (k in -r..r) sum += src[start + min(max(k, 0), n - 1) * step]
        for (i in 0 until n) {
            dst[start + i * step] = (sum / size).toFloat()
            // 窗口右移一格：加上 i+r+1，去掉 i-r（先转 Double 再相减，和 JS 一致）
            sum += src[start + min(i + r + 1, n - 1) * step].toDouble() - src[start + max(i - r, 0) * step].toDouble()
        }
    }

    /** 用 passes 次均值滤波逼近标准差为 sigma 的高斯，返回每次的窗口宽度（奇数） */
    fun boxSizes(sigma: Double, passes: Int = 3): IntArray {
        val ideal = sqrt(12 * sigma * sigma / passes + 1)
        var lower = floor(ideal).toInt()
        if (lower % 2 == 0) lower -= 1
        val upper = lower + 2
        val m = roundHalfEven(
            (12 * sigma * sigma - passes * lower * lower - 4 * passes * lower - 3 * passes) / (-4 * lower - 4).toDouble()
        )
        return IntArray(passes) { if (it < m) lower else upper }
    }

    /** gaussianBlur 的影响半径（像素）：离得更远的像素互不影响 */
    fun blurSupport(sigma: Double): Int {
        if (sigma <= 0) return 0
        return boxSizes(sigma).sumOf { (it - 1) / 2 }
    }

    /** 高斯模糊（三次均值滤波逼近，任意半径都很快）。sigma <= 0 时原样返回 */
    fun gaussianBlur(data: FloatArray, width: Int, height: Int, sigma: Double): FloatArray {
        if (sigma <= 0) return data
        val out = data.copyOf()
        val tmp = FloatArray(out.size)
        for (w in boxSizes(sigma)) {
            val r = (w - 1) / 2
            if (r <= 0) continue
            for (y in 0 until height) boxLine(out, tmp, y * width, 1, width, r)
            for (x in 0 until width) boxLine(tmp, out, x, width, height, r)
        }
        return out
    }

    /** 每条线内宽 2r+1 的最大值滤波（单调队列，O(n)） */
    private fun maxLine(src: FloatArray, dst: FloatArray, start: Int, step: Int, n: Int, r: Int, queue: IntArray) {
        var head = 0
        var tail = 0
        var next = 0
        for (i in 0 until n) {
            val hi = min(i + r, n - 1)
            while (next <= hi) {
                val v = src[start + next * step]
                while (tail > head && src[start + queue[tail - 1] * step] <= v) tail--
                queue[tail++] = next++
            }
            while (queue[head] < i - r) head++
            dst[start + i * step] = src[start + queue[head] * step]
        }
    }

    /** 方形窗口最大值滤波：近处（亮）区域向外扩 radius 像素 */
    fun dilateMax(data: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return data.copyOf()
        val tmp = FloatArray(data.size)
        val out = FloatArray(data.size)
        val queue = IntArray(max(width, height))
        for (y in 0 until height) maxLine(data, tmp, y * width, 1, width, radius, queue)
        for (x in 0 until width) maxLine(tmp, out, x, width, height, radius, queue)
        return out
    }

    // ==================== 后处理 ====================

    /**
     * 模型原始输出 → 最终深度图。
     *
     * @param raw 模型输出，值越大越近，量纲任意
     * @param smoothRadius 边缘平滑半径（像素，按输出尺寸计），0 表示不处理边缘
     * @param invert 反转成黑=近（在平滑之后做，外扩的始终是近处）
     * @return 8 位灰度，白=近
     */
    fun postprocessDepth(
        raw: FloatArray, rawW: Int, rawH: Int, outW: Int, outH: Int,
        smoothRadius: Double, invert: Boolean = false,
    ): ByteArray {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (v in raw) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        if (hi.toDouble() - lo.toDouble() < 1e-8) {
            // 深度几乎没有变化，图片可能是纯色的
            return ByteArray(outW * outH)
        }

        val range = (hi.toDouble() - lo.toDouble()).toFloat()
        var depth = FloatArray(raw.size) { (raw[it] - lo) / range }
        if (rawW != outW || rawH != outH) depth = resizeFloat(depth, rawW, rawH, outW, outH)
        for (i in depth.indices) depth[i] = min(max(depth[i], 0f), 1f)

        if (smoothRadius > 0) {
            // 外扩距离等于模糊的影响半径：原来的主体像素一个都不会被模糊到
            depth = gaussianBlur(dilateMax(depth, outW, outH, blurSupport(smoothRadius)), outW, outH, smoothRadius)
        }

        val out = ByteArray(depth.size)
        for (i in depth.indices) {
            var d = if (invert) 1f - depth[i] else depth[i]
            d = min(max(d, 0f), 1f)
            out[i] = roundHalfEven((d * 255f).toDouble()).toByte()
        }
        return out
    }
}
