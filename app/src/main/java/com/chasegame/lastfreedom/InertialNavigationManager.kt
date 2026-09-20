package com.chasegame.lastfreedom

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.osmdroid.util.GeoPoint
import kotlin.math.*

/**
 * 惯导融合管理器：
 * - GPS 正常时使用 GPS
 * - GPS 信号差（精度 < 30m 或丢失）时切换到惯导推算
 * - 基于加速度计 + 陀螺仪积分推算位置
 * - GPS 恢复后平滑切回
 */
class InertialNavigationManager(
    private val sensorManager: SensorManager,
    private val handler: Handler
) {
    // 状态
    enum class Mode { GPS, INERTIAL, BLENDING }
    var currentMode = Mode.GPS
        private set

    // 惯导状态
    private var lastGpsLat = 0.0
    private var lastGpsLng = 0.0
    private var lastGpsTime = 0L
    private var lastGpsAccuracy = 0f

    // 惯导推算状态
    private var inertialLat = 0.0
    private var inertialLng = 0.0
    private var inertialBearing = 0.0  // 度
    private var inertialSpeed = 0.0    // m/s
    private var lastInertialTime = 0L

    // 传感器数据
    private val accelerometer = floatArrayOf(0f, 0f, 0f)
    private val gyroscope = floatArrayOf(0f, 0f, 0f)
    private var hasAccelerometer = false
    private var hasGyroscope = false

    // GPS 质量阈值
    private val GPS_ACCURACY_THRESHOLD = 30f  // 米
    private val GPS_LOST_TIMEOUT = 3000L      // 3秒无 GPS 切换到惯导
    private val BLENDING_DURATION = 2000L     // 2秒平滑切回

    // 监听器
    private var sensorListener: SensorEventListener? = null

    init {
        registerSensors()
    }

    private fun registerSensors() {
        sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        accelerometer[0] = event.values[0]
                        accelerometer[1] = event.values[1]
                        accelerometer[2] = event.values[2]
                        hasAccelerometer = true
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        gyroscope[0] = event.values[0]
                        gyroscope[1] = event.values[1]
                        gyroscope[2] = event.values[2]
                        hasGyroscope = true
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        val accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        accSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    /**
     * 更新 GPS 数据，返回融合后的位置
     */
    fun updateGps(lat: Double, lng: Double, accuracy: Float, bearing: Float, speed: Float): GeoPoint {
        val now = SystemClock.elapsedRealtime()

        lastGpsLat = lat
        lastGpsLng = lng
        lastGpsTime = now
        lastGpsAccuracy = accuracy

        when (currentMode) {
            Mode.GPS -> {
                // GPS 正常，检查是否需要切换到惯导
                if (accuracy > GPS_ACCURACY_THRESHOLD) {
                    switchToInertial(lat, lng, bearing, speed, now)
                }
                return GeoPoint(lat, lng)
            }

            Mode.INERTIAL -> {
                // 惯导模式，检查是否可以切回 GPS
                if (accuracy <= GPS_ACCURACY_THRESHOLD) {
                    startBlending(lat, lng, now)
                } else {
                    // 继续惯导，更新惯导起点
                    inertialLat = lat
                    inertialLng = lng
                }
                return getInertialPosition(now)
            }

            Mode.BLENDING -> {
                // 平滑切回中
                val blendStart = lastInertialTime
                val elapsed = now - blendStart

                if (elapsed >= BLENDING_DURATION) {
                    // 切换完成，回到 GPS 模式
                    currentMode = Mode.GPS
                    return GeoPoint(lat, lng)
                } else {
                    // 平滑混合
                    val ratio = elapsed.toFloat() / BLENDING_DURATION
                    val gpsPoint = GeoPoint(lat, lng)
                    val inertialPoint = getInertialPosition(now)

                    val blendLat = inertialPoint.latitude * (1 - ratio) + gpsPoint.latitude * ratio
                    val blendLng = inertialPoint.longitude * (1 - ratio) + gpsPoint.longitude * ratio

                    return GeoPoint(blendLat, blendLng)
                }
            }
        }
    }

    private fun switchToInertial(lat: Double, lng: Double, bearing: Float, speed: Float, now: Long) {
        currentMode = Mode.INERTIAL
        inertialLat = lat
        inertialLng = lng
        inertialBearing = bearing.toDouble()
        inertialSpeed = speed.toDouble()
        lastInertialTime = now
    }

    private fun startBlending(lat: Double, lng: Double, now: Long) {
        currentMode = Mode.BLENDING
        inertialLat = lat
        inertialLng = lng
        lastInertialTime = now
    }

    /**
     * 惯导推算位置（基于加速度和陀螺仪积分）
     */
    private fun getInertialPosition(now: Long): GeoPoint {
        if (lastInertialTime == 0L) return GeoPoint(inertialLat, inertialLng)

        val dt = (now - lastInertialTime) / 1000.0  // 秒

        // 陀螺仪更新航向
        if (hasGyroscope) {
            // gyroscope[2] 是绕 Z 轴的角速度（弧度/秒）
            inertialBearing += Math.toDegrees(gyroscope[2].toDouble()) * dt
            inertialBearing = (inertialBearing + 360) % 360
        }

        // 加速度计更新速度（简化模型：沿航向的加速度）
        if (hasAccelerometer) {
            // 简化：使用加速度计的 X 轴作为前进方向加速度
            val forwardAcc = accelerometer[0].toDouble()
            inertialSpeed += forwardAcc * dt
            inertialSpeed = inertialSpeed.coerceAtLeast(0.0)  // 速度不能为负
        }

        // 更新位置
        if (inertialSpeed > 0.1) {  // 速度 > 0.1 m/s 才更新
            val bearingRad = Math.toRadians(inertialBearing)
            val distance = inertialSpeed * dt  // 米

            // 经纬度增量（简化球面模型）
            val dLat = distance * cos(bearingRad) / 111320.0  // 1度纬度 ≈ 111320米
            val dLng = distance * sin(bearingRad) / (111320.0 * cos(Math.toRadians(inertialLat)))

            inertialLat += dLat
            inertialLng += dLng
        }

        lastInertialTime = now
        return GeoPoint(inertialLat, inertialLng)
    }

    fun destroy() {
        sensorListener?.let {
            sensorManager.unregisterListener(it)
        }
    }
}
