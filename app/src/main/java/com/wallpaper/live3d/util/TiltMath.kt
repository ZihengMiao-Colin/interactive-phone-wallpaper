package com.wallpaper.live3d.util

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * 重力传感器读数 → 归一化倾斜 [-1, 1]（纯函数，方便单元测试）
 *
 * 归一化流程:
 *   重力原始值 (-9.8..9.8)
 *     → 根据静止范围 / 最大倾斜算出对应的重力阈值（倾斜角 θ 对应的分量 = g·sin θ）
 *     → 静止范围内归零 + 超过最大倾斜钳位
 *     → 输出 [-1, 1]
 */
object TiltMath {

    /**
     * 手持基准倾斜角 (度)。
     * 用户通常以约 45° 握持手机，该角度对应的重力分量作为 Y 轴中心。
     */
    const val BASE_TILT_DEG = 45.0

    private const val G = 9.81f
    private val BASE_GRAVITY_Y = (G * sin(Math.toRadians(BASE_TILT_DEG))).toFloat()

    /**
     * @param gx 左右倾斜分量 (TYPE_GRAVITY values[0])
     * @param gy 前后倾斜分量 (values[1])
     * @return [x, y]，手机自然握持（≈45°）时为 [0, 0]
     */
    fun normalize(gx: Float, gy: Float, deadZoneDeg: Double, maxTiltDeg: Double): FloatArray {
        val maxG = (G * sin(Math.toRadians(maxTiltDeg))).toFloat()
        val deadG = (G * sin(Math.toRadians(deadZoneDeg))).toFloat()

        fun normalizeWithDeadZone(value: Float): Float {
            val absVal = abs(value)
            if (absVal < deadG) return 0f
            val sign = if (value > 0) 1f else -1f
            val clamped = min(absVal, maxG)
            return sign * (clamped - deadG) / (maxG - deadG)
        }

        val nx = normalizeWithDeadZone(gx)
        // Y 轴先减去手持基准角对应的分量：前倾 > 45° 为正，取反后画面上移
        val ny = -normalizeWithDeadZone(gy - BASE_GRAVITY_Y)
        return floatArrayOf(nx, ny)
    }
}

/**
 * 倾斜去抖：和上次接受（或 reset）的值相比，任一轴变化达到阈值才接受。
 *
 * 手机拿在手里传感器总有细微抖动，不去抖的话渲染引擎会被噪声一直叫醒，静止时也停不了帧。
 * 慢慢倾斜时仍会按阈值一步步接受，渲染端插值让画面连续。
 */
class TiltDebouncer(private val threshold: Float = 0.01f) {
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    /** 变化足够大时返回 true 并记下新值；第一次总是 true */
    fun accept(x: Float, y: Float): Boolean {
        if (!lastX.isNaN() && abs(x - lastX) < threshold && abs(y - lastY) < threshold) return false
        lastX = x
        lastY = y
        return true
    }

    /** 直接把比较基准设成 (x, y)，比如在别处补推过这个值之后 */
    fun reset(x: Float, y: Float) {
        lastX = x
        lastY = y
    }
}
