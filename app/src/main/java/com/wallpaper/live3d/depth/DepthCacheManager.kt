package com.wallpaper.live3d.depth

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * ============================================================================
 * DepthCacheManager — 深度图文件缓存
 * ============================================================================
 *
 * 与 Electron src/store.js _hashPath() + saveDepthCache() 逻辑完全一致。
 *
 * 存储布局:
 *   context.filesDir/depth_cache/
 *   ├── depth_<hash1>.png
 *   ├── depth_<hash2>.png
 *   └── index.json   (映射: imageName → 缓存文件名)
 *
 * 缓存键: 对 imageName (小写) 使用与 store.js 相同的 32-bit 简单哈希算法。
 *
 * 图片通过 WebViewAssetLoader 加载:
 *   https://appassets.androidplatform.net/local/depth_cache/depth_abc123.png
 */
class DepthCacheManager(private val context: Context) {

    private val cacheDir: File = File(context.filesDir, "depth_cache")
    private val indexFile: File = File(cacheDir, "index.json")

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    /**
     * 返回深度图缓存文件名 (不含路径)。
     * JS 端通过 WebViewAssetLoader 构建完整 URL。
     *
     * @param imageName 原始壁纸文件名
     * @return 缓存文件名 (如 "depth_1a2b3c.png")，或 null
     */
    fun getDepthFileName(imageName: String): String? {
        val index = loadIndex()
        val hash = hashPath(imageName)
        val entry = index.optJSONObject(hash) ?: return null
        val fileName = entry.optString("fileName", "")
        val depthFile = File(cacheDir, fileName)
        return if (depthFile.exists()) fileName else null
    }

    /** 检查是否有缓存的深度图 */
    fun hasDepthMap(imageName: String): Boolean {
        return getDepthFileName(imageName) != null
    }

    /**
     * 保存深度图到缓存。
     * @param imageName 原始壁纸文件名
     * @param depthDataUrl Base64 Data URL
     * @return 保存的深度图文件名 (如 "depth_1a2b3c4d.png")，供 JS 构建 URL
     */
    fun saveDepthMap(imageName: String, depthDataUrl: String): String {
        val hash = hashPath(imageName)
        val depthFileName = "${hash}.png"
        val depthFile = File(cacheDir, depthFileName)

        // 解码 Base64 → 写入 PNG 文件
        val base64Data = if (depthDataUrl.contains("base64,")) {
            depthDataUrl.substringAfter("base64,")
        } else {
            depthDataUrl
        }
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        depthFile.writeBytes(bytes)

        // 更新索引
        val index = loadIndex()
        val entry = JSONObject().apply {
            put("imageName", imageName)
            put("fileName", depthFileName)
            put("cachedAt", System.currentTimeMillis())
        }
        index.put(hash, entry)
        saveIndex(index)

        android.util.Log.d("[DepthCache]", "Saved: $imageName → $depthFileName")
        return depthFileName
    }

    /** 删除指定壁纸的缓存 */
    fun removeCache(imageName: String) {
        val index = loadIndex()
        val hash = hashPath(imageName)
        val entry = index.optJSONObject(hash)
        if (entry != null) {
            val fileName = entry.optString("fileName", "")
            val depthFile = File(cacheDir, fileName)
            if (depthFile.exists()) depthFile.delete()
            index.remove(hash)
            saveIndex(index)
        }
    }

    /** 清空所有缓存 */
    fun clearAll() {
        val index = loadIndex()
        for (key in index.keys()) {
            val entry = index.optJSONObject(key) ?: continue
            val fileName = entry.optString("fileName", "")
            File(cacheDir, fileName).delete()
        }
        cacheDir.listFiles()?.forEach { it.delete() }
        saveIndex(JSONObject())
    }

    // ==================== 内部方法 ====================

    /**
     * 与 store.js _hashPath() 相同的 32-bit 字符串哈希算法。
     * 保持与 Electron 版本的缓存文件命名兼容。
     */
    private fun hashPath(imageName: String): String {
        var hash = 0
        val str = imageName.lowercase()
        for (char in str) {
            hash = (hash shl 5) - hash + char.code
            hash = hash and 0xFFFFFFFF.toInt() // 强制 32-bit
        }
        return "depth_${kotlin.math.abs(hash).toString(16)}"
    }

    private fun loadIndex(): JSONObject {
        return if (indexFile.exists()) {
            try {
                JSONObject(indexFile.readText())
            } catch (_: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }
    }

    private fun saveIndex(index: JSONObject) {
        indexFile.writeText(index.toString(2))
    }
}
