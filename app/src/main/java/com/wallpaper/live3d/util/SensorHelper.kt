package com.wallpaper.live3d.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.wallpaper.live3d.config.ConfigManager

/**
 * ============================================================================
 * SensorHelper — Android 原生重力传感器封装
 * ============================================================================
 *
 * 替代 JS deviceorientation API (后台 WebView 无法使用)。
 *
 * 使用 TYPE_GRAVITY 传感器获取设备倾斜，归一化见 TiltMath。
 * 静止范围 / 最大倾斜每次事件从配置读，App 里调了马上生效。
 *
 * 采样: SENSOR_DELAY_GAME (≈50Hz)，最多每 33ms 回调一次，
 * 并且和上次推送的值相比变化够大才回调（TiltDebouncer），静止时几乎不推送。
 */
class SensorHelper(context: Context, private val config: ConfigManager) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var callback: ((Float, Float) -> Unit)? = null
    private val debouncer = TiltDebouncer(TILT_PUSH_THRESHOLD)

    /** 最近一次算出的倾斜（不管有没有推送） */
    private var latest: FloatArray? = null
    private var lastSendTime = 0L

    companion object {
        private const val SEND_INTERVAL_MS = 33L          // ~30fps 推送给 JS
        private const val TILT_PUSH_THRESHOLD = 0.01f     // 归一化单位
    }

    /** 开始传感器采样（回调在主线程） */
    fun start(onSensorData: (normalizedX: Float, normalizedY: Float) -> Unit) {
        callback = onSensorData
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    /** 停止传感器采样 */
    fun stop() {
        sensorManager.unregisterListener(sensorListener)
        callback = null
    }

    /**
     * 不管去抖，直接把最近一次的倾斜（没有就是 0,0）推给回调。
     * 用在桌面上手指松开时：不补推的话，画面会停在手指最后的位置，直到手机明显动一下。
     */
    fun pushLatest() {
        val (x, y) = latest ?: floatArrayOf(0f, 0f)
        debouncer.reset(x, y)
        callback?.invoke(x, y)
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSendTime < SEND_INTERVAL_MS) return
            if (event.values.size < 3) return

            val tilt = TiltMath.normalize(
                event.values[0], event.values[1],
                config.getFloat(ConfigManager.KEY_DEAD_ZONE, 3.0f).toDouble(),
                config.getFloat(ConfigManager.KEY_MAX_TILT, 30.0f).toDouble(),
            )
            latest = tilt
            if (!debouncer.accept(tilt[0], tilt[1])) return
            lastSendTime = now
            callback?.invoke(tilt[0], tilt[1])
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
}
