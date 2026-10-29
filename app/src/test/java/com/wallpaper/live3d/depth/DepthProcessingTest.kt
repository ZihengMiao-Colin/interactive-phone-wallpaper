package com.wallpaper.live3d.depth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 移植自桌面项目 test/depth-processing.test.js，期望值（Pillow 生成）原样复制。
 * Kotlin 版和桌面 JS 版必须逐值一致，算法改动要两边同步。
 */
class DepthProcessingTest {

    private val D = DepthProcessing

    private fun randomPlane(width: Int, height: Int, seed: Long): FloatArray {
        val r = Random(seed)
        return FloatArray(width * height) { r.nextFloat() }
    }

    /** 左半边近（模型输出值大），右半边远；故意用任意量纲的原始值 */
    private fun step(): FloatArray {
        val raw = FloatArray(120 * 40) { 3f }
        for (y in 0 until 40) for (x in 0 until 60) raw[y * 120 + x] = 7f
        return raw
    }

    private fun u8(b: ByteArray) = IntArray(b.size) { b[it].toInt() and 0xFF }

    // ---------- 尺寸 ----------

    @Test
    fun depthOutputSize_keepsSmall_shrinksLarge() {
        assertEquals(Pair(1280, 720), D.depthOutputSize(1280, 720))
        assertEquals(Pair(1920, 1280), D.depthOutputSize(3840, 2560))
        assertEquals(Pair(960, 1920), D.depthOutputSize(2000, 4000))
    }

    @Test
    fun roundHalfEven_matchesPython() {
        val out = doubleArrayOf(0.5, 1.5, 2.5, 3.5, 2.4, 2.6).map { D.roundHalfEven(it) }
        assertEquals(listOf(0, 2, 2, 4, 2, 3), out)
    }

    // ---------- 外扩和模糊 ----------

    @Test
    fun dilateMax_matchesBruteForce() {
        val w = 31
        val h = 23
        val a = randomPlane(w, h, 1)
        for (r in intArrayOf(0, 1, 2, 5, 9, 40)) {
            val out = D.dilateMax(a, w, h, r)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var m = Float.NEGATIVE_INFINITY
                    for (yy in max(0, y - r)..min(h - 1, y + r)) {
                        for (xx in max(0, x - r)..min(w - 1, x + r)) m = max(m, a[yy * w + xx])
                    }
                    assertEquals("r=$r ($x,$y)", m, out[y * w + x], 0f)
                }
            }
        }
    }

    @Test
    fun gaussianBlur_constantUnchanged_zeroSigmaIdentity() {
        val flat = FloatArray(40 * 30) { 0.7f }
        for (v in D.gaussianBlur(flat, 40, 30, 5.0)) assertEquals(0.7f, v, 1e-6f)
        val a = randomPlane(12, 10, 2)
        assertArrayEquals(a, D.gaussianBlur(a, 12, 10, 0.0), 0f)
    }

    @Test
    fun gaussianBlur_impulse_sumSymmetryStd() {
        val n = 121
        val a = FloatArray(n * n)
        a[60 * n + 60] = 1f
        val out = D.gaussianBlur(a, n, n, 6.0)
        var sum = 0.0
        var variance = 0.0
        for (y in 0 until n) {
            for (x in 0 until n) {
                val v = out[y * n + x].toDouble()
                sum += v
                variance += v * (x - 60) * (x - 60)
                assertTrue(abs(out[y * n + x] - out[y * n + (n - 1 - x)]) < 1e-7f)
            }
        }
        assertEquals(1.0, sum, 1e-4)
        assertEquals(6.0, sqrt(variance), 0.5)
    }

    @Test
    fun gaussianBlur_supportIsBlurSupport() {
        val n = 121
        val a = FloatArray(n * n)
        a[60 * n + 60] = 1f
        val k = D.blurSupport(6.0)
        val out = D.gaussianBlur(a, n, n, 6.0)
        assertTrue(out[60 * n + 60 + k] > 0f)
        assertEquals(0f, out[60 * n + 60 + k + 1], 0f)
    }

    // ---------- 后处理 ----------

    @Test
    fun postprocess_noSmooth_normalizes() {
        val out = u8(D.postprocessDepth(step(), 120, 40, 120, 40, 0.0))
        assertEquals(120 * 40, out.size)
        for (y in 0 until 40) for (x in 0 until 120) {
            assertEquals(if (x < 60) 255 else 0, out[y * 120 + x])
        }
    }

    @Test
    fun postprocess_subjectKeepsDepth_transitionOnBackground() {
        val out = u8(D.postprocessDepth(step(), 120, 40, 120, 40, 4.0))
        val k = D.blurSupport(4.0)
        for (y in 0 until 40) {
            for (x in 0 until 60) assertEquals(255, out[y * 120 + x])
            for (x in 60 + 2 * k until 120) assertEquals(0, out[y * 120 + x])
        }
        assertTrue(out[60] < 255)
        for (x in 1 until 120) assertTrue(out[x] <= out[x - 1])
    }

    @Test
    fun postprocess_invertAfterSmooth() {
        val out = u8(D.postprocessDepth(step(), 120, 40, 120, 40, 4.0))
        val inverted = u8(D.postprocessDepth(step(), 120, 40, 120, 40, 4.0, invert = true))
        assertArrayEquals(IntArray(out.size) { 255 - out[it] }, inverted)
    }

    @Test
    fun postprocess_flatIsBlack_sizeScaled() {
        assertTrue(u8(D.postprocessDepth(FloatArray(400) { 2.5f }, 20, 20, 20, 20, 4.0)).all { it == 0 })
        assertEquals(300 * 200, D.postprocessDepth(randomPlane(518, 518, 3), 518, 518, 300, 200, 2.0).size)
    }

    // ---------- 和 Pillow 逐像素一致的 bicubic 缩放 ----------
    // 期望值由桌面项目 venv 里的 Pillow 12.3 生成：Image.resize(size, Image.BICUBIC)

    /** 7×5 的测试图（桌面版是 RGBA，这里直接给去掉透明通道后的 RGB） */
    private fun sampleRgb(): ByteArray {
        val w = 7
        val h = 5
        val rgb = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            rgb[i] = ((x * 37 + y * 11) % 256).toByte()
            rgb[i + 1] = ((x * 5 + y * 71 + 13) % 256).toByte()
            rgb[i + 2] = ((x * y * 29 + 200) % 256).toByte()
        }
        return rgb
    }

    @Test
    fun resizeRgb8_down_matchesPillow() {
        val expected = intArrayOf(29, 27, 200, 113, 38, 165, 197, 49, 177, 42, 114, 137, 126, 124, 62, 210, 141, 115, 56, 213, 96, 140, 229, 127, 227, 171, 148, 69, 84, 133, 160, 97, 104, 158, 82, 91)
        assertArrayEquals(expected, u8(D.resizeRgb8(sampleRgb(), 7, 5, 3, 4)))
    }

    @Test
    fun resizeRgb8_up_matchesPillow() {
        val expected = intArrayOf(0, 7, 200, 12, 9, 198, 39, 12, 199, 63, 16, 212, 86, 19, 216, 110, 22, 214, 134, 25, 212, 157, 28, 211, 181, 32, 209, 208, 35, 208, 224, 37, 207, 2, 30, 199, 16, 32, 210, 43, 35, 212, 67, 39, 160, 90, 42, 142, 114, 45, 150, 138, 48, 155, 161, 51, 159, 185, 55, 163, 212, 58, 174, 228, 60, 181, 7, 75, 197, 23, 77, 227, 50, 80, 225, 74, 84, 64, 97, 87, 12, 121, 90, 40, 145, 93, 56, 168, 96, 73, 192, 100, 88, 219, 103, 114, 235, 105, 130, 13, 116, 205, 29, 118, 178, 56, 121, 123, 80, 125, 33, 103, 128, 17, 127, 131, 52, 151, 134, 88, 174, 136, 124, 198, 140, 150, 225, 152, 104, 241, 160, 62, 19, 155, 214, 35, 157, 115, 62, 160, 0, 86, 164, 37, 109, 167, 81, 133, 170, 118, 157, 173, 155, 180, 176, 199, 204, 180, 238, 231, 183, 121, 247, 185, 22, 25, 212, 214, 41, 214, 114, 68, 217, 2, 92, 221, 60, 115, 224, 132, 139, 227, 184, 163, 230, 121, 186, 237, 110, 209, 240, 182, 247, 144, 161, 255, 79, 133, 31, 215, 212, 47, 217, 126, 74, 220, 33, 98, 224, 95, 121, 227, 161, 145, 230, 196, 169, 233, 88, 193, 242, 41, 217, 244, 101, 232, 98, 172, 236, 0, 218, 38, 94, 210, 54, 96, 133, 81, 99, 54, 105, 103, 139, 128, 106, 143, 152, 109, 86, 176, 112, 108, 204, 118, 96, 230, 121, 39, 142, 76, 103, 71, 45, 165, 42, 26, 210, 58, 28, 137, 85, 31, 65, 109, 35, 162, 132, 38, 131, 156, 41, 22, 180, 44, 120, 210, 46, 132, 237, 51, 8, 92, 66, 63, 0, 77, 129)
        assertArrayEquals(expected, u8(D.resizeRgb8(sampleRgb(), 7, 5, 11, 9)))
    }

    @Test
    fun resizeRgb8_sameSize_isCopy() {
        val rgb = sampleRgb()
        assertArrayEquals(rgb, D.resizeRgb8(rgb, 7, 5, 7, 5))
    }

    @Test
    fun argbToRgb_dropsAlpha() {
        val argb = intArrayOf(0xFF102030.toInt(), 0x80A0B0C0.toInt())
        assertArrayEquals(intArrayOf(0x10, 0x20, 0x30, 0xA0, 0xB0, 0xC0), u8(D.argbToRgb(argb)))
    }

    /** 6×4 的浮点测试图（和桌面测试一致） */
    private fun samplePlane() = FloatArray(24) { i ->
        val x = i % 6
        val y = i / 6
        (((x * 0.37 + y * 1.3) % 1.7) - 0.2).toFloat()
    }

    private fun assertClose(expected: DoubleArray, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { i, v -> assertTrue("#$i: ${actual[i]} != $v", abs(actual[i] - v) < 1e-6) }
    }

    @Test
    fun resizeFloat_up_matchesPillow() {
        assertClose(doubleArrayOf(-0.33104953169822693, -0.195954367518425, 0.06687500327825546, 0.3730250298976898, 0.6543615460395813, 0.8698884844779968, 1.177803874015808, 1.3117307424545288, 0.3772348463535309, -0.2636801600456238, 0.18001709878444672, 0.3470489978790283, 0.5779416561126709, 0.43434789776802063, 0.4163919985294342, 0.711330771446228, 1.014076828956604, 1.1744509935379028, 0.6608964204788208, 0.29631826281547546, 1.0255203247070312, 1.2416661977767944, 1.4234448671340942, 0.5963115096092224, 0.09288834780454636, 0.4316515624523163, 0.6958484053611755, 0.9103944897651672, 1.1172256469726562, 1.2255618572235107, 0.9783254861831665, 1.1622761487960815, 1.3762500286102295, 1.0704001188278198, 0.655750036239624, 0.2529999911785126, 0.3177499771118164, 0.6800000071525574, 1.0105316638946533, 1.1921933889389038, 0.6416809558868408, 0.7804759740829468, 1.0396056175231934, 1.4148151874542236, 1.2857640981674194, 0.3429577350616455, 0.15500213205814362, 0.4496055245399475, 0.7029510736465454, 0.8475300669670105, 0.37762442231178284, 0.515851616859436, 0.7755489945411682, 1.0796959400177002, 1.2196781635284424, 1.0929499864578247, 0.6622861623764038, 0.18554894626140594, 0.3892127275466919, 0.5834735035896301, 0.24034470319747925, 0.3814781904220581, 0.6382692456245422, 0.8505595922470093, 1.132124900817871, 1.536057710647583, 0.9809288382530212, 0.048269230872392654, 0.2229025512933731, 0.44619375467300415), D.resizeFloat(samplePlane(), 6, 4, 10, 7))
    }

    @Test
    fun resizeFloat_down_matchesPillow() {
        assertClose(doubleArrayOf(0.27506357431411743, 0.43631237745285034, 0.9648228883743286, 0.6294997334480286, 1.117939829826355, 0.9418140649795532, 0.3796818256378174, 0.9781910181045532, 0.48200708627700806, 1.1022512912750244, 0.7740090489387512, 0.3530876636505127), D.resizeFloat(samplePlane(), 6, 4, 4, 3))
    }

    // ---------- 模型输入 ----------

    @Test
    fun toModelInput_imagenetNchw() {
        val rgb = byteArrayOf(255.toByte(), 0, 128.toByte(), 10, 20, 30)
        val x = D.toModelInput(rgb, 2)
        assertEquals(6, x.size)
        assertEquals((1 - 0.485) / 0.229, x[0].toDouble(), 1e-5)          // R 通道第 1 个像素
        assertEquals((10 / 255.0 - 0.485) / 0.229, x[1].toDouble(), 1e-5) // R 通道第 2 个像素
        assertEquals((0 - 0.456) / 0.224, x[2].toDouble(), 1e-5)          // G 通道
        assertEquals((30 / 255.0 - 0.406) / 0.225, x[5].toDouble(), 1e-5) // B 通道
    }
}
