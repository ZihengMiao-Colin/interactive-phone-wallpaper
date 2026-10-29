package com.wallpaper.live3d.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class TiltMathTest {

    /** 倾斜 deg 度时重力在该轴上的分量 */
    private fun gs(deg: Double) = (9.81 * sin(Math.toRadians(deg))).toFloat()
    private val base = gs(TiltMath.BASE_TILT_DEG)

    @Test
    fun normalize_neutralIsZero() {
        assertArrayEquals(floatArrayOf(0f, 0f), TiltMath.normalize(0f, base, 3.0, 30.0), 1e-4f)
    }

    @Test
    fun normalize_insideDeadZoneIsZero() {
        assertEquals(0f, TiltMath.normalize(gs(2.0), base, 3.0, 30.0)[0], 1e-4f)
    }

    @Test
    fun normalize_saturatesAtMaxTilt() {
        assertEquals(1f, TiltMath.normalize(gs(30.0), base, 3.0, 30.0)[0], 1e-4f)
        assertEquals(1f, TiltMath.normalize(gs(60.0), base, 3.0, 30.0)[0], 1e-4f)
        assertEquals(-1f, TiltMath.normalize(-gs(60.0), base, 3.0, 30.0)[0], 1e-4f)
    }

    @Test
    fun normalize_deadZoneEqualsMaxTilt_isFinite() {
        // 界面允许静止范围 = 最大倾斜 = 10°：超过就直接饱和，不能除以 0 得到 NaN
        val out = TiltMath.normalize(gs(20.0), base + gs(20.0), 10.0, 10.0)
        assertEquals(1f, out[0], 1e-4f)
        assertEquals(-1f, out[1], 1e-4f)
        assertEquals(0f, TiltMath.normalize(gs(5.0), base, 10.0, 10.0)[0], 1e-4f)
    }

    @Test
    fun normalize_forwardTiltIsNegativeY() {
        assertEquals(-1f, TiltMath.normalize(0f, base + gs(30.0), 3.0, 30.0)[1], 1e-4f)
    }

    @Test
    fun debouncer_threshold() {
        val d = TiltDebouncer(0.01f)
        assertTrue(d.accept(0f, 0f))
        assertFalse(d.accept(0.005f, 0f))
        assertTrue(d.accept(0.012f, 0f))
        assertFalse(d.accept(0.015f, 0f))
    }

    @Test
    fun debouncer_resetMovesBaseline() {
        val d = TiltDebouncer(0.01f)
        assertTrue(d.accept(0f, 0f))
        d.reset(0.5f, 0.5f)
        assertFalse(d.accept(0.505f, 0.5f))
        assertTrue(d.accept(0.52f, 0.5f))
    }
}
