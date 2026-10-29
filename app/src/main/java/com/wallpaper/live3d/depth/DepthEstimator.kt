package com.wallpaper.live3d.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * ============================================================================
 * DepthEstimator — 使用 Depth Anything V2 ONNX 模型进行 AI 深度估计
 * ============================================================================
 *
 * 与桌面版 generate_depth_map.py 管线一致。
 *
 * 流程:
 *   1. 加载图片 → Bitmap
 *   2. 预处理: 缩放至尺寸可被 14 整除 + ImageNet 归一化 → FloatArray (NCHW)
 *   3. ONNX 推理 → 原始深度张量 (1, H/14, W/14)
 *   4. 后处理: 双三次放大 → 全范围归一化 0-255 → 灰度 PNG
 *   5. 保存文件 → 返回深度图缓存文件名
 *
 * 模型文件: assets/models/depth_anything_v2_small.onnx (FP32, ~96MB)
 * 首次运行自动解压到: filesDir/models/depth_anything_v2_small.onnx
 *
 * 推理在后台协程中执行，不阻塞 UI。
 */
class DepthEstimator(private val context: Context) {

    companion object {
        private const val TAG = "[DepthEstimator]"
        private const val MODEL_ASSET_PATH = "models/depth_anything_v2_small.onnx"
        private const val SIZE_DIVISOR = 14  // DPT 架构要求尺寸可被 14 整除

        // ImageNet 归一化参数 (与 transformers DPTImageProcessor 一致)
        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD  = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isInitialized = false

    /** 最后一次错误的详细信息 (供 AppBridge 读取并返回给 JS) */
    var lastError: String? = null
        private set

    /** 初始化 ONNX Runtime，加载模型 */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true

        try {
            // 复制模型文件从 assets → filesDir (ONNX Runtime 需要文件路径)
            // 始终覆盖，确保使用最新版本的模型（防止旧 IR 版本残留）
            val modelDir = File(context.filesDir, "models")
            if (!modelDir.exists()) modelDir.mkdirs()

            val modelFile = File(modelDir, MODEL_ASSET_PATH.substringAfterLast("/"))
            Log.d(TAG, "正在解压模型文件到: ${modelFile.absolutePath}")
            try {
                context.assets.open(MODEL_ASSET_PATH).use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "模型文件解压完成 (${modelFile.length() / (1024 * 1024)} MB)")
            } catch (e: Exception) {
                // assets 复制失败但文件可能已存在，尝试使用旧文件
                if (!modelFile.exists()) throw e
                Log.w(TAG, "assets 复制失败，使用已有模型文件: ${e.message}")
            }

            // 创建 ONNX Runtime 会话
            ortEnv = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // 使用 CPU (Android 上最稳定)，后续可尝试 NNAPI delegate
                // addConfigEntry("session.intra_op_num_threads", "4")
            }
            ortSession = ortEnv?.createSession(modelFile.absolutePath, options)
            isInitialized = true
            Log.d(TAG, "ONNX Runtime 初始化成功")
            true
        } catch (e: Exception) {
            lastError = "ONNX 初始化失败: ${e.javaClass.simpleName} — ${e.message}"
            Log.e(TAG, lastError!!, e)
            isInitialized = false
            false
        }
    }

    /**
     * 从图片文件生成 AI 深度图。
     *
     * @param imageFile  原始壁纸文件 (内部存储路径)
     * @param cacheDir   深度图缓存目录 (context.filesDir/depth_cache/)
     * @return 深度图文件名 (如 "depth_xxx.png")，失败返回 null
     */
    suspend fun estimateDepth(imageFile: File, cacheDir: File): String? =
        withContext(Dispatchers.IO) {
            if (!isInitialized) {
                val ok = initialize()
                if (!ok) {
                    Log.e(TAG, "模型未初始化，无法推理: $lastError")
                    return@withContext null
                }
            }

            try {
                // ---- 1. 加载图片 ----
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val originalBitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options)
                    ?: run {
                        Log.e(TAG, "无法解码图片: ${imageFile.absolutePath}")
                        return@withContext null
                    }

                val origW = originalBitmap.width
                val origH = originalBitmap.height
                Log.d(TAG, "原始图片: ${origW}x${origH}")

                // ---- 2. 预处理: 缩放 → 归一化 → NCHW ----
                val (inputW, inputH) = computeInputSize(origW, origH)
                Log.d(TAG, "模型输入尺寸: ${inputW}x${inputH} (原始 ${origW}x${origH})")

                val inputData = preprocess(originalBitmap, inputW, inputH)
                // 原始尺寸的 Bitmap 仍需要保留用于后续上采样

                // ---- 3. ONNX 推理 ----
                val ortEnv = this@DepthEstimator.ortEnv ?: return@withContext null
                val session = this@DepthEstimator.ortSession ?: return@withContext null

                val inputShape = longArrayOf(1, 3, inputH.toLong(), inputW.toLong())
                val inputBuffer = FloatBuffer.wrap(inputData)
                val inputTensor = OnnxTensor.createTensor(ortEnv, inputBuffer, inputShape)

                val outputs = session.run(mapOf("pixel_values" to inputTensor))
                // OrtSession.Result 实现了 Iterable<Map.Entry<String, OrtValue>>
                val firstEntry = outputs.iterator().next()
                val outputKey = firstEntry.key
                val outputValue = firstEntry.value
                Log.d(TAG, "ONNX 输出键: $outputKey, 类型: ${outputValue::class.java.simpleName}")

                val outputTensor = outputValue as? OnnxTensor
                    ?: run {
                        Log.e(TAG, "ONNX 输出不是 OnnxTensor")
                        inputTensor.close()
                        return@withContext null
                    }

                val rawDepth = outputTensor.floatBuffer
                val depthShape = outputTensor.info.shape
                Log.d(TAG, "推理完成, 输出 shape: ${depthShape.contentToString()}")

                // 输出可能是 3 维 [1, H, W] 或 4 维 [1, 1, H, W]
                val depthH: Int
                val depthW: Int
                when (depthShape.size) {
                    3 -> {
                        depthH = depthShape[1].toInt()
                        depthW = depthShape[2].toInt()
                    }
                    4 -> {
                        depthH = depthShape[2].toInt()
                        depthW = depthShape[3].toInt()
                    }
                    else -> {
                        lastError = "未知的输出维度: ${depthShape.contentToString()}"
                        Log.e(TAG, lastError!!)
                        inputTensor.close()
                        return@withContext null
                    }
                }
                Log.d(TAG, "深度图尺寸: ${depthW}x${depthH}")

                // ---- 4. 后处理: 上采样 + 归一化 ----
                val depthBitmap = postprocess(rawDepth, depthW, depthH, origW, origH)

                // ---- 4.5. 边缘平滑: 腐蚀 (缩边) + 高斯模糊 (渐变斜坡) ----
                // 解决视差位移时前景边缘被拉伸导致的"鬼影/轮廓残留"
                val smoothedBitmap = applyEdgeSmoothing(depthBitmap)
                depthBitmap.recycle()

                // ---- 5. 保存 ----
                val depthFileName = saveDepthMap(smoothedBitmap, imageFile.name, cacheDir)

                // 释放资源
                originalBitmap.recycle()
                smoothedBitmap.recycle()
                inputTensor.close()
                outputTensor.close()

                Log.d(TAG, "深度图已保存: $depthFileName")
                depthFileName
            } catch (e: Exception) {
                lastError = "深度估计失败: ${e.javaClass.simpleName} — ${e.message}"
                Log.e(TAG, lastError!!, e)
                null
            }
        }

    /** 计算模型输入尺寸。
     * 模型内部有硬编码的方形 reshape (37x37=1369 patches)，必须使用方形输入。*/
    private fun computeInputSize(origW: Int, origH: Int): Pair<Int, Int> {
        // 518 = 37 * 14，是 Depth Anything V2 的标准训练尺寸
        return Pair(518, 518)
    }

    /**
     * 图片预处理: 缩放到模型输入尺寸 + ImageNet 归一化 → FloatArray (NCHW)。
     * 与 transformers DPTImageProcessor 行为一致。
     */
    private fun preprocess(bitmap: Bitmap, targetW: Int, targetH: Int): FloatArray {
        // 缩放
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)

        val pixels = IntArray(targetW * targetH)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

        val floatArray = FloatArray(3 * targetH * targetW)  // NCHW
        val planeSize = targetH * targetW

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8)  and 0xFF) / 255.0f
            val b = ( pixel        and 0xFF) / 255.0f

            // 归一化: (value - mean) / std
            floatArray[i]                    = (r - IMAGENET_MEAN[0]) / IMAGENET_STD[0]
            floatArray[planeSize + i]        = (g - IMAGENET_MEAN[1]) / IMAGENET_STD[1]
            floatArray[2 * planeSize + i]    = (b - IMAGENET_MEAN[2]) / IMAGENET_STD[2]
        }

        scaled.recycle()
        return floatArray
    }

    /**
     * 后处理: 将模型输出放大到原始尺寸 + 全范围归一化 0-255。
     * 与桌面版 generate_depth_map.py 的第 4 步一致。
     */
    private fun postprocess(
        rawDepth: FloatBuffer,
        depthW: Int,
        depthH: Int,
        targetW: Int,
        targetH: Int,
    ): Bitmap {
        // 将 FloatBuffer → 低分辨率灰度 Bitmap
        val depthPixels = IntArray(depthW * depthH)

        // 第一遍: 找 min/max
        var depthMin = Float.MAX_VALUE
        var depthMax = -Float.MAX_VALUE
        rawDepth.rewind()
        while (rawDepth.hasRemaining()) {
            val v = rawDepth.get()
            if (v < depthMin) depthMin = v
            if (v > depthMax) depthMax = v
        }

        // 第二遍: 归一化到 0-255
        val range = depthMax - depthMin
        val effectiveRange = if (range < 1e-8f) 1.0f else range
        rawDepth.rewind()
        var idx = 0
        while (rawDepth.hasRemaining()) {
            val v = rawDepth.get()
            val normalized = (v - depthMin) / effectiveRange
            val gray = (normalized * 255.0f).toInt().coerceIn(0, 255)
            depthPixels[idx] = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
            idx++
        }

        val lowResBmp = Bitmap.createBitmap(depthPixels, depthW, depthH, Bitmap.Config.ARGB_8888)

        // 放大到目标尺寸
        val upscaled = Bitmap.createScaledBitmap(lowResBmp, targetW, targetH, true)
        lowResBmp.recycle()

        return upscaled
    }

    /** 保存深度图为 PNG，返回文件名 (如 depth_abc123.png) */
    private fun saveDepthMap(depthBitmap: Bitmap, imageName: String, cacheDir: File): String {
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val hash = hashFileName(imageName)
        val fileName = "${hash}.png"
        val file = File(cacheDir, fileName)

        file.outputStream().use { out ->
            depthBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        return fileName
    }

    /**
     * =========================================================================
     * 深度图边缘平滑后处理
     * =========================================================================
     *
     * 解决视差位移时前景边缘像素被拉伸导致的"鬼影/轮廓残留"问题。
     *
     * 算法:
     *   1. 形态学腐蚀 (Min Filter) — 将高亮(前景)区域向内收缩 N 像素。
     *      前景=白(255)、背景=黑(0)，min 操作使亮区收缩，边缘像素变为背景色，
     *      引擎拉伸的就是背景像素而非前景(人脸)像素。
     *   2. 高斯模糊 (可分离 1D 核) — 将硬边缘变为渐变斜坡，
     *      视差拉伸时产生平滑过渡而非生硬断层。
     *
     * @param depthBitmap  原始深度图 (灰度 ARGB_8888)
     * @param erosionRadius 腐蚀半径 (px)，默认 2 → 前景收缩 ~2px
     * @param blurRadius    高斯模糊半径 (px)，默认 3 → 边缘渐变 ~3px
     * @return 处理后的深度图 (新 Bitmap)
     */
    private fun applyEdgeSmoothing(
        depthBitmap: Bitmap,
        erosionRadius: Int = 2,
        blurRadius: Int = 3,
    ): Bitmap {
        val w = depthBitmap.width
        val h = depthBitmap.height

        // 提取灰度值 (R 通道即可，灰度图三通道相同)
        val pixels = IntArray(w * h)
        depthBitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var gray = FloatArray(w * h) { i -> (pixels[i] and 0xFF) / 255f }

        // 第 1 步：形态学腐蚀 — 收缩前景
        if (erosionRadius > 0) {
            gray = erodeGrayscale(gray, w, h, erosionRadius)
        }

        // 第 2 步：高斯模糊 — 渐变斜坡
        if (blurRadius > 0) {
            gray = gaussianBlurSeparable(gray, w, h, blurRadius)
        }

        // 写回 Bitmap
        val out = IntArray(w * h)
        for (i in out.indices) {
            val v = (gray[i] * 255f).toInt().coerceIn(0, 255)
            out[i] = (255 shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** 灰度形态学腐蚀: 输出 = 邻域内最小值 (收缩亮区/前景) */
    private fun erodeGrayscale(
        src: FloatArray, w: Int, h: Int, radius: Int,
    ): FloatArray {
        val dst = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var minVal = 1f
                val y0 = (y - radius).coerceAtLeast(0)
                val y1 = (y + radius).coerceAtMost(h - 1)
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius).coerceAtMost(w - 1)
                for (ny in y0..y1) {
                    val rowBase = ny * w
                    for (nx in x0..x1) {
                        val v = src[rowBase + nx]
                        if (v < minVal) minVal = v
                    }
                }
                dst[y * w + x] = minVal
            }
        }
        return dst
    }

    /** 可分离高斯模糊: 水平 pass → 垂直 pass (O(2·n·r) 替代 O(n·r²)) */
    private fun gaussianBlurSeparable(
        src: FloatArray, w: Int, h: Int, radius: Int,
    ): FloatArray {
        val kernel = makeGaussianKernel1D(radius)
        val kr = kernel.size / 2
        val tmp = FloatArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            val rowBase = y * w
            for (x in 0 until w) {
                var sum = 0f
                var weight = 0f
                val x0 = (x - kr).coerceAtLeast(0)
                val x1 = (x + kr).coerceAtMost(w - 1)
                for (nx in x0..x1) {
                    val k = kernel[nx - x + kr]
                    sum += src[rowBase + nx] * k
                    weight += k
                }
                tmp[rowBase + x] = if (weight > 0f) sum / weight else src[rowBase + x]
            }
        }

        // Vertical pass
        val dst = FloatArray(w * h)
        for (y in 0 until h) {
            val rowBase = y * w
            val y0 = (y - kr).coerceAtLeast(0)
            val y1 = (y + kr).coerceAtMost(h - 1)
            for (x in 0 until w) {
                var sum = 0f
                var weight = 0f
                for (ny in y0..y1) {
                    val k = kernel[ny - y + kr]
                    sum += tmp[ny * w + x] * k
                    weight += k
                }
                dst[rowBase + x] = if (weight > 0f) sum / weight else tmp[rowBase + x]
            }
        }
        return dst
    }

    /** 生成 1D 高斯核 (σ = radius/2) */
    private fun makeGaussianKernel1D(radius: Int): FloatArray {
        val size = 2 * radius + 1
        val sigma = radius / 2.0f
        val kernel = FloatArray(size)
        var sum = 0f
        for (i in 0 until size) {
            val x = (i - radius).toFloat()
            kernel[i] = kotlin.math.exp((-x * x) / (2.0 * sigma * sigma)).toFloat()
            sum += kernel[i]
        }
        for (i in 0 until size) kernel[i] /= sum
        return kernel
    }

    /** 与 DepthCacheManager.hashPath 相同的哈希 */
    private fun hashFileName(imageName: String): String {
        var hash = 0
        for (char in imageName.lowercase()) {
            hash = (hash shl 5) - hash + char.code
            hash = hash and 0xFFFFFFFF.toInt()
        }
        return "depth_${kotlin.math.abs(hash).toString(16)}"
    }

    /** 释放 ONNX Runtime 资源 */
    fun release() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (_: Exception) {}
        isInitialized = false
        Log.d(TAG, "ONNX Runtime 已释放")
    }
}
