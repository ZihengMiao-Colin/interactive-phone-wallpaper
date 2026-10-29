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

/**
 * ============================================================================
 * DepthEstimator — 使用 Depth Anything V2 ONNX 模型进行 AI 深度估计
 * ============================================================================
 *
 * 和桌面版 src/depth/ 的流程一致，数值处理都在 DepthProcessing 里：
 *   1. 解码图片（超大图先降采样）→ RGB
 *   2. Pillow 兼容的 bicubic 缩到 518×518 + ImageNet 归一化 → NCHW
 *   3. ONNX 推理 → 原始深度
 *   4. 后处理：放大到输出尺寸（最长边 1920）→ 近处轮廓外扩 → 高斯平滑 → 8 位灰度
 *   5. 存成 PNG → 返回深度图文件名
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

            // 创建 ONNX Runtime 会话（CPU，Android 上最稳定）
            ortEnv = OrtEnvironment.getEnvironment()
            ortSession = ortEnv?.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
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
     * @param cacheDir   深度图目录 (context.filesDir/depth_cache/)
     * @param blurRadius 边缘平滑半径（像素，按输出深度图计），0 表示不平滑
     * @param onProgress 阶段进度 (百分比, 说明文字)，在后台线程回调
     * @return 深度图文件名 (depth_<时间戳>.png)，失败返回 null，原因见 lastError
     */
    suspend fun estimateDepth(
        imageFile: File,
        cacheDir: File,
        blurRadius: Int,
        onProgress: (percent: Int, text: String) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        lastError = null
        try {
            // ---- 1. 解码：先读尺寸，超大图降采样 ----
            onProgress(5, "正在读取图片")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
            val origW = bounds.outWidth
            val origH = bounds.outHeight
            if (origW <= 0 || origH <= 0) {
                lastError = "无法读取图片"
                return@withContext null
            }
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = DepthProcessing.sampleSizeFor(origW, origH)
            }
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options) ?: run {
                lastError = "无法解码图片"
                return@withContext null
            }
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.recycle()
            Log.d(TAG, "原图 ${origW}x${origH}，解码 ${w}x${h}")

            // ---- 2. 模型输入 ----
            val size = DepthProcessing.MODEL_INPUT_SIZE
            val input = DepthProcessing.toModelInput(
                DepthProcessing.resizeRgb8(DepthProcessing.argbToRgb(pixels), w, h, size, size),
                size * size,
            )

            onProgress(15, "正在准备模型")
            if (!isInitialized && !initialize()) return@withContext null
            val env = ortEnv ?: return@withContext null
            val session = ortSession ?: return@withContext null

            // ---- 3. 推理 ----
            onProgress(30, "正在分析深度")
            val raw: FloatArray
            val rawW: Int
            val rawH: Int
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { outputs ->
                    val out = outputs.get(0) as? OnnxTensor ?: run {
                        lastError = "ONNX 输出不是张量"
                        return@withContext null
                    }
                    val shape = out.info.shape
                    rawH = shape[shape.size - 2].toInt()
                    rawW = shape[shape.size - 1].toInt()
                    val buffer = out.floatBuffer
                    raw = FloatArray(buffer.remaining())
                    buffer.get(raw)
                }
            }
            Log.d(TAG, "推理完成，输出 ${rawW}x${rawH}")

            // ---- 4. 后处理 ----
            onProgress(80, "正在平滑边缘")
            val (outW, outH) = DepthProcessing.depthOutputSize(origW, origH)
            val gray = DepthProcessing.postprocessDepth(raw, rawW, rawH, outW, outH, blurRadius.toDouble())

            // ---- 5. 保存 ----
            onProgress(95, "正在保存")
            val fileName = "depth_${System.currentTimeMillis()}.png"
            saveGrayPng(gray, outW, outH, File(cacheDir, fileName))
            Log.d(TAG, "深度图已保存: $fileName (${outW}x${outH})")
            fileName
        } catch (e: Throwable) {
            // OutOfMemoryError 也要接住，给界面一个原因
            lastError = "深度估计失败: ${e.javaClass.simpleName} — ${e.message}"
            Log.e(TAG, lastError!!, e)
            null
        }
    }

    /** 8 位灰度 → ARGB（r = g = b，着色器读 r 通道）→ PNG */
    private fun saveGrayPng(gray: ByteArray, width: Int, height: Int, file: File) {
        file.parentFile?.mkdirs()
        val argb = IntArray(gray.size) {
            val v = gray[it].toInt() and 0xFF
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
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
