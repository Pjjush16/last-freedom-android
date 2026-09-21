package com.chasegame.lastfreedom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 速度制插值定位提供者 (v5.0.0)
 *
 * 核心改进：把"次数"换成"速度"
 * - 每帧取真实 delta time（秒），位移 = 速度 × dt
 * - 速度按"每秒走多少米"定义，跟帧率无关
 * - 单帧最大位移钳制：dt 异常大（切后台回来、卡顿时）时，把位移上限压死
 *
 * 逻辑步长固定（60Hz），渲染帧只做插值外推。
 * 逻辑行为在所有机器上完全一致，只有顺滑度不同。
 */
class InterpolatedLocationProvider(private val context: Context) :
    IMyLocationProvider, LocationListener {

    private var consumer: IMyLocationConsumer? = null
    private var locationManager: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())

    // === GPS 原始数据 ===
    private var prevGps: Location? = null
    private var targetGps: Location? = null

    // === 速度制插值状态 ===
    // 当前插值位置（每帧更新）
    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private var currentBearing: Float = 0f
    private var currentSpeedKmh: Float = 0f
    private var maxSpeedKmh: Float = 0f
    private var hasFix: Boolean = false

    // 上一帧的 wall-clock 时间，用于计算 dt
    private var lastTickMs: Long = 0L

    // === 可配置参数 ===
    /** 位置追赶速度（米/秒），15 m/s ≈ 54 km/h */
    var followSpeedMps: Float = 15f
    /** 单帧最大位移（米），防止切后台回来时一帧跨半个地图 */
    var maxDisplacementPerFrameM: Float = 200f
    /** 方向 EMA 系数，越小越平滑 */
    var bearingAlpha: Float = 0.15f
    /** 速度 EMA 系数 */
    var speedAlpha: Float = 0.25f

    // 插值定时器（60Hz 逻辑步长）
    private val tickIntervalMs: Long = 16L
    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, tickIntervalMs)
        }
    }
    private var ticking = false

    // === IMyLocationProvider 接口 ===

    override fun startLocationProvider(consumer: IMyLocationConsumer?): Boolean {
        this.consumer = consumer
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0.5f, this, Looper.getMainLooper()
            )
        }
        if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 2000L, 5f, this, Looper.getMainLooper()
            )
        }

        startTicking()
        return true
    }

    override fun stopLocationProvider() {
        stopTicking()
        locationManager?.removeUpdates(this)
    }

    override fun getLastKnownLocation(): Location? {
        if (!hasFix) return null
        return Location("interpolated").apply {
            latitude = currentLat
            longitude = currentLng
            bearing = currentBearing
            speed = currentSpeedKmh / 3.6f  // km/h → m/s
            time = System.currentTimeMillis()
        }
    }

    override fun destroy() {
        stopLocationProvider()
    }

    /** 当前平滑速度（km/h），供 HUD 直接读取 */
    fun getSmoothedSpeedKmh(): Float = currentSpeedKmh
    fun getMaxSpeedKmh(): Float = maxSpeedKmh

    /** 当前平滑方向（度），供外部读取 */
    fun getSmoothedBearing(): Float = currentBearing

    /** 是否已有 GPS 定位 */
    fun hasGpsFix(): Boolean = hasFix

    // === LocationListener 回调 ===

    override fun onLocationChanged(location: Location) {
        // 首次定位：直接跳到目标位置
        if (!hasFix) {
            currentLat = location.latitude
            currentLng = location.longitude
            if (location.hasBearing()) {
                currentBearing = location.bearing
            }
            if (location.hasSpeed()) {
                currentSpeedKmh = location.speed * 3.6f
            }
            hasFix = true
            prevGps = location
            targetGps = location
            return
        }

        // 更新速度（EMA 平滑）
        if (location.hasSpeed()) {
            currentSpeedKmh = emaFloat(currentSpeedKmh, location.speed * 3.6f, speedAlpha)
        }
        if (currentSpeedKmh > maxSpeedKmh) maxSpeedKmh = currentSpeedKmh

        // 更新方向（EMA 平滑，从 prevGps 到当前点的方位角）
        val prev = prevGps
        if (prev != null) {
            val rawBearing = calculateBearing(
                prev.latitude, prev.longitude,
                location.latitude, location.longitude
            )
            currentBearing = emaAngle(currentBearing, rawBearing, bearingAlpha)
        }

        // 滚动 GPS 窗口
        prevGps = targetGps
        targetGps = location
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // === 速度制插值引擎 ===

    private fun startTicking() {
        if (!ticking) {
            ticking = true
            lastTickMs = System.currentTimeMillis()
            handler.post(tickRunnable)
        }
    }

    private fun stopTicking() {
        ticking = false
        handler.removeCallbacks(tickRunnable)
    }

    /**
     * 每 16ms 调用一次（60Hz 逻辑步长）。
     *
     * 核心：位移 = 速度 × dt
     * - dt 是真实帧间隔（秒），不是固定值
     * - 速度是 followSpeedMps（米/秒），跟帧率无关
     * - 单帧位移上限 = maxDisplacementPerFrameM，防止掉帧/切后台回来后一帧跳很远
     */
    private fun tick() {
        val now = System.currentTimeMillis()
        if (lastTickMs == 0L) {
            lastTickMs = now
            return
        }

        // 真实 delta time（秒）
        val dtSec = (now - lastTickMs) / 1000.0
        lastTickMs = now

        // 无定位时不输出
        val target = targetGps ?: return

        // === 位置：速度制追赶 ===
        // 当前插值位置到 GPS 目标的距离（米）
        val distToTarget = haversine(currentLat, currentLng, target.latitude, target.longitude)

        if (distToTarget > 0.5) {
            // 本帧允许的最大位移 = 速度 × dt
            var maxDisp = (followSpeedMps * dtSec).toFloat()
            // 钳制：单帧最大位移
            maxDisp = maxDisp.coerceAtMost(maxDisplacementPerFrameM)

            // 实际移动距离 = min(到目标的距离, 本帧最大位移)
            val moveDistance = distToTarget.toFloat().coerceAtMost(maxDisp)

            // 朝向目标移动
            val bearing = calculateBearing(
                currentLat, currentLng, target.latitude, target.longitude
            )
            val result = movePoint(currentLat, currentLng, bearing.toDouble(), moveDistance.toDouble())
            currentLat = result[0]
            currentLng = result[1]
        } else {
            // 足够近了，吸附到目标
            currentLat = target.latitude
            currentLng = target.longitude
        }

        // 输出插值后的位置
        val interpolated = Location("interpolated").apply {
            latitude = currentLat
            longitude = currentLng
            bearing = currentBearing
            speed = currentSpeedKmh / 3.6f  // km/h → m/s
            time = now
        }
        consumer?.onLocationChanged(interpolated, this)
    }

    // === 数学工具 ===

    /** 两点方位角（度，正北为 0，顺时针） */
    private fun calculateBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val dLng = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val y = sin(dLng) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLng)
        val bearing = Math.toDegrees(atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }

    /** 两点距离（米，Haversine 公式） */
    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** 从一点沿给定方向移动指定距离（米），返回 [lat, lng] */
    private fun movePoint(lat: Double, lng: Double, bearingDeg: Double, distM: Double): DoubleArray {
        val R = 6371000.0
        val d = distM / R
        val brng = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lng1 = Math.toRadians(lng)
        val lat2 = Math.asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brng))
        val lng2 = lng1 + atan2(
            sin(brng) * sin(d) * cos(lat1),
            cos(d) - sin(lat1) * sin(lat2)
        )
        return doubleArrayOf(Math.toDegrees(lat2), Math.toDegrees(lng2))
    }

    /** 角度 EMA 平滑，正确处理 359°→1° 跨越 */
    private fun emaAngle(current: Float, target: Float, alpha: Float): Float {
        var diff = target - current
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        var result = current + alpha * diff
        result = ((result % 360) + 360) % 360
        return result
    }

    /** 普通浮点数 EMA */
    private fun emaFloat(current: Float, target: Float, alpha: Float): Float {
        return current + alpha * (target - current)
    }
}
