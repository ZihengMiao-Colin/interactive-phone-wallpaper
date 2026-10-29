package com.wallpaper.live3d.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * ============================================================================
 * SensorHelper — Android 原生重力传感器封装
 * ============================================================================
 *
 * 替代 JS deviceorientation API (后台 WebView 无法使用)。
 *
 * 使用 TYPE_GRAVITY 传感器获取设备倾斜:
 *   - gx (values[0]): 左右倾斜分量
 *   - gy (values[1]): 前后倾斜分量
 *   - gz (values[2]): 垂直分量
 *
 * 归一化流程:
 *   重力原始值 (-9.8..9.8)
 *     → 根据 deadZoneDeg / maxTiltDeg 计算对应重力阈值
 *     → 死区削除 + 饱和钳位
 *     → 输出归一化 [-1, 1]
 *
 * 采样频率: ~30Hz (SENSOR_DELAY_GAME ≈ 50Hz 原始采样 + 内部节流)
 * 通过 evaluateJavascript 推送给 WebView JS 的 CoreEngine.setSensorTarget(x, y)
 */
class SensorHelper(private val context: Context) {

    private var sensorManager: SensorManager? = null
    private var gravitySensor: Sensor? = null
    private var callback: ((Float, Float) -> Unit)? = null

    companion object {
        private const val SEND_INTERVAL_MS = 33L       // ~30fps 推送给 JS
        private const val DEFAULT_MAX_TILT_DEG = 30.0   // 默认最大倾斜角
        private const val DEFAULT_DEAD_ZONE_DEG = 3.0   // 默认死区

        /**
         * 手持基准倾斜角 (度)。
         * 用户通常以约 45° 握持手机，该角度对应的重力分量
         * g * sin(45°) ≈ 9.81 * 0.707 ≈ 6.93 将作为 Y 轴中心。
         */
        private const val BASE_TILT_DEG = 45.0
        private val BASE_GRAVITY_Y = (9.81f * sin(Math.toRadians(BASE_TILT_DEG))).toFloat()
    }

    /** 开始传感器采样 */
    fun start(onSensorData: (normalizedX: Float, normalizedY: Float) -> Unit) {
        callback = onSensorData
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)

        gravitySensor?.let {
            sensorManager?.registerListener(
                sensorListener, it,
                SensorManager.SENSOR_DELAY_GAME  // ~20ms ≈ 50Hz
            )
        }
    }

    /** 停止传感器采样 */
    fun stop() {
        sensorManager?.unregisterListener(sensorListener)
        callback = null
    }

    // ==================== 内部实现 ====================

    private var lastSendTime = 0L

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            // 节流: 限制推送到 JS 的频率
            val now = SystemClock.elapsedRealtime()
            if (now - lastSendTime < SEND_INTERVAL_MS) return
            lastSendTime = now

            if (event.values.size < 3) return

            val gx = event.values[0]  // 左右倾斜
            val gy = event.values[1]  // 前后倾斜
            // val gz = event.values[2]  // 垂直 (未使用)

            // 计算重力阈值:
            //   倾斜角 θ 对应的重力分量 = g * sin(θ)
            //   例如 θ=30° → g*sin(30°) = 9.8*0.5 = 4.9
            val maxTiltDeg = DEFAULT_MAX_TILT_DEG  // 可从配置读取
            val deadZoneDeg = DEFAULT_DEAD_ZONE_DEG
            val maxG = (9.81f * sin(Math.toRadians(maxTiltDeg))).toFloat()
            val deadG = (9.81f * sin(Math.toRadians(deadZoneDeg))).toFloat()

            fun normalizeWithDeadZone(value: Float): Float {
                val absVal = abs(value)
                if (absVal < deadG) return 0f
                val sign = if (value > 0) 1f else -1f
                val clamped = min(absVal, maxG)
                return sign * (clamped - deadG) / (maxG - deadG)
            }

            val nx = normalizeWithDeadZone(gx)

            // Y 轴: 减去手持基准倾斜角 (45°) 对应的重力分量
            // 手机自然握持 (≈45°) 时 gy ≈ 6.93 → 校准后 ≈ 0 → 画面居中
            // 前倾 > 45° → gy > 6.93 → 正值 → 画面上移 (取反后为负)
            // 后仰 < 45° → gy < 6.93 → 负值 → 画面下移 (取反后为正)
            val gyCalibrated = gy - BASE_GRAVITY_Y
            val ny = -normalizeWithDeadZone(gyCalibrated)

            callback?.invoke(nx, ny)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // 无需处理
        }
    }
}
