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

/**
 * 高精度定位插值提供者：
 * 1. 位置插值 — GPS 每秒更新一次，内部以 60fps 线性插值输出平滑位置
 * 2. 方向插值 — 用指数移动平均(EMA)平滑 bearing，消除抖动
 * 3. 速度插值 — 相邻两点 haversine 距离 / 时间差，平滑输出
 *
 * 原理：GPS 两次更新之间（约 1s），内部定时器每 ~16ms 输出一次插值点，
 * 使地图上的位置箭头和方向指示丝滑移动，而不是跳跃。
 */
class InterpolatedLocationProvider(private val context: Context) :
    IMyLocationProvider, LocationListener {

    private var consumer: IMyLocationConsumer? = null
    private var locationManager: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())

    // === 插值状态 ===
    // 上一次真实 GPS 点
    private var prevLocation: Location? = null
    // 当前真实 GPS 点（插值目标）
    private var targetLocation: Location? = null
    // 上一次 GPS 更新时间戳
    private var lastGpsTimeMs: Long = 0L
    // 两次 GPS 更新之间的间隔（ms），初始假设 1000ms
    private var gpsIntervalMs: Long = 1000L

    // 方向 EMA 平滑后的值
    private var smoothedBearing: Float = 0f
    private var hasBearing: Boolean = false
    // EMA 系数：越小越平滑（0.0~1.0），0.15 兼顾灵敏度和平滑度
    private val bearingAlpha: Float = 0.15f

    // 速度 EMA
    private var smoothedSpeed: Float = 0f
    private val speedAlpha: Float = 0.2f

    // 插值定时器
    private val tickIntervalMs: Long = 16L  // ~60fps
    private val tickRunnable = object : Runnable {
        override fun run() {
            emitInterpolated()
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

        // GPS 优先，1s / 0.5m
        if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0.5f, this, Looper.getMainLooper()
            )
        }
        // 网络定位辅助
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
        return targetLocation ?: prevLocation
    }

    override fun destroy() {
        stopLocationProvider()
    }

    // === LocationListener 回调 ===

    override fun onLocationChanged(location: Location) {
        val now = System.currentTimeMillis()

        // 计算实际 GPS 更新间隔
        if (lastGpsTimeMs > 0) {
            val dt = now - lastGpsTimeMs
            if (dt in 200..5000) {
                gpsIntervalMs = dt
            }
        }
        lastGpsTimeMs = now

        // 更新方向（EMA 平滑）
        val prev = prevLocation
        if (prev != null) {
            val rawBearing = calculateBearing(
                prev.latitude, prev.longitude,
                location.latitude, location.longitude
            )
            if (!hasBearing) {
                smoothedBearing = rawBearing
                hasBearing = true
            } else {
                smoothedBearing = emaAngle(smoothedBearing, rawBearing, bearingAlpha)
            }
        }

        // 更新速度（EMA 平滑）
        if (location.hasSpeed()) {
            smoothedSpeed = emaFloat(smoothedSpeed, location.speed, speedAlpha)
        }

        // 滚动窗口：prev ← target, target ← 新点
        if (targetLocation != null) {
            prevLocation = targetLocation
        }
        targetLocation = location
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // === 插值引擎 ===

    private fun startTicking() {
        if (!ticking) {
            ticking = true
            handler.post(tickRunnable)
        }
    }

    private fun stopTicking() {
        ticking = false
        handler.removeCallbacks(tickRunnable)
    }

    /**
     * 每 16ms 调用一次，在 prev 和 target 之间线性插值输出位置。
     */
    private fun emitInterpolated() {
        val target = targetLocation ?: return
        val prev = prevLocation

        val interpolated = Location(target)

        if (prev != null && gpsIntervalMs > 0) {
            val elapsed = System.currentTimeMillis() - lastGpsTimeMs
            // t = 0.0（刚收到 GPS）→ 1.0（下一次 GPS 即将到达）
            // 限制到 1.2 防止 GPS 延迟时位置卡住
            val t = (elapsed.toFloat() / gpsIntervalMs).coerceIn(0f, 1.2f)

            // 线性插值经纬度
            interpolated.latitude = prev.latitude + (target.latitude - prev.latitude) * t
            interpolated.longitude = prev.longitude + (target.longitude - prev.longitude) * t
        }

        // 注入平滑后的方向
        if (hasBearing) {
            interpolated.bearing = smoothedBearing
        }

        // 注入平滑后的速度
        interpolated.speed = smoothedSpeed

        // 输出给 overlay
        consumer?.onLocationChanged(interpolated, this)
    }

    // === 数学工具 ===

    /**
     * 两点方位角（度，正北为 0，顺时针）
     */
    private fun calculateBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val dLng = Math.toRadians(lng2 - lng1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val y = sin(dLng) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLng)
        val bearing = Math.toDegrees(atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }

    /**
     * 角度 EMA 平滑，正确处理 359°→1° 的跨越问题
     */
    private fun emaAngle(current: Float, target: Float, alpha: Float): Float {
        var diff = target - current
        // 归一化到 [-180, 180]
        while (diff > 180) diff -= 360
        while (diff < -180) diff += 360
        var result = current + alpha * diff
        // 归一化到 [0, 360)
        result = ((result % 360) + 360) % 360
        return result
    }

    /**
     * 普通浮点数 EMA
     */
    private fun emaFloat(current: Float, target: Float, alpha: Float): Float {
        return current + alpha * (target - current)
    }
}
