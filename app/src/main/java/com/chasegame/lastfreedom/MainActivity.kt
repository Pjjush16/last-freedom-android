package com.chasegame.lastfreedom

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.atan2
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private var interpolatedProvider: InterpolatedLocationProvider? = null
    private val handler = Handler(Looper.getMainLooper())

    // HUD
    private lateinit var tvSpeed: TextView
    private lateinit var tvRoad: TextView

    // 逆地理编码
    private var lastGeoLat = 0.0
    private var lastGeoLng = 0.0
    private var geoRequestPending = false

    // === 三状态相机 ===
    private enum class CameraState { OPENING, FOLLOW, FLY_TO }
    private var cameraState = CameraState.OPENING
    private var lastFollowLat = 0.0
    private var lastFollowLng = 0.0
    private var hasLastFollow = false

    // 飞行模式阈值：屏幕像素位移
    private val flyThresholdPx = 300

    // 开场动画
    private var openingZoomedTo10 = false

    // === 速度驱动缩放 ===
    // 低速(0 km/h) → zoom 18（街道细节）
    // 中速(60 km/h) → zoom 16
    // 高速(120 km/h) → zoom 14（高速全局视图）
    // 超高速(160+ km/h) → zoom 13
    private var smoothedZoom = 17.0   // EMA 平滑后的目标 zoom
    private val zoomAlpha = 0.08       // EMA 平滑系数（越小越平滑，避免频繁跳 zoom）
    private var lastZoomSetTime = 0L   // 上次实际调用 setZoom 的时间

    companion object {
        private const val PERM_REQUEST = 100
        private const val GEO_MIN_DISTANCE = 50.0 // 每移动 50m 更新一次路名
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.mapView)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvRoad = findViewById(R.id.tvRoad)

        setupMap()
        requestPermissions()
    }

    private fun setupMap() {
        // ArcGIS World Imagery 卫星瓦片（全球覆盖，无需 Key）
        // 与 v4.7.0 完全一致的 zoom 限制方式：纯靠 osmdroid 内置机制
        val tileSource = object : XYTileSource(
            "arcgis_world_imagery", 1, 18, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
                val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
                var z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
                // 保险层：即使 osmdroid 内部 zoom 越界，瓦片 URL 永远不超过 19
                if (z > 18) z = 18
                return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
            }
        }

        map.setTileSource(tileSource)
        map.setMultiTouchControls(true)
        // 与 v4.7.0 完全一致的配置
        map.isTilesScaledToDpi = true
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 18.0
        // 不设置任何自定义 touch listener 或 zoom clamp
        // osmdroid 内置的 MultiTouchController + setZoomLevel() 会自动处理

        // 开场：显示整个地球
        map.controller.setZoom(2.0)
        map.controller.setCenter(GeoPoint(30.0, 110.0))
    }

    private fun setupLocationOverlay() {
        val provider = InterpolatedLocationProvider(this)
        interpolatedProvider = provider

        locationOverlay = MyLocationNewOverlay(provider, map).apply {
            enableMyLocation()
            // 禁用 osmdroid 内置跟随，由三状态相机系统接管
            disableFollowLocation()
            isDrawAccuracyEnabled = true
        }
        map.overlays.add(locationOverlay)
    }

    // === 权限 ===

    private fun requestPermissions() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (perms.any { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, PERM_REQUEST)
        } else {
            onPermissionGranted()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQUEST && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            onPermissionGranted()
        }
    }

    @SuppressLint("MissingPermission")
    private fun onPermissionGranted() {
        setupLocationOverlay()
        startHudUpdater()
        startCameraSystem()
    }

    // === 三状态相机系统 ===

    private val cameraRunnable = object : Runnable {
        override fun run() {
            when (cameraState) {
                CameraState.OPENING -> handleOpening()
                CameraState.FOLLOW -> handleFollow()
                CameraState.FLY_TO -> { /* 飞行动画自行运行，这里不干预 */ }
            }
            handler.postDelayed(this, 50) // 20Hz 相机更新
        }
    }

    private fun startCameraSystem() {
        handler.post(cameraRunnable)
    }

    /**
     * 开场状态：等待 GPS 定位，然后根据当前速度直接飞到对应的 zoom
     * 1. 显示整个地球（zoom 2）
     * 2. GPS 定位后 → 1.5s 飞到 zoom 10（全局概览）
     * 3. 再 1.2s 飞到速度驱动的目标 zoom，然后进入 FOLLOW
     */
    private fun handleOpening() {
        val provider = interpolatedProvider ?: return
        if (!provider.hasGpsFix()) return

        val loc = provider.lastKnownLocation ?: return
        val lat = loc.latitude
        val lng = loc.longitude

        if (!openingZoomedTo10) {
            openingZoomedTo10 = true

            // 阶段 1：1.5s 飞到 zoom 10，让用户看到全局
            map.controller.animateTo(GeoPoint(lat, lng), 10.0, 1500L)

            // 阶段 2：根据当前速度算出目标 zoom，直接飞过去（不再固定 17）
            handler.postDelayed({
                val speedKmh = provider.getSmoothedSpeedKmh().toDouble().coerceAtLeast(0.0)
                val targetZoom = speedToZoom(speedKmh)
                map.controller.animateTo(GeoPoint(lat, lng), targetZoom, 1200L)
                handler.postDelayed({
                    cameraState = CameraState.FOLLOW
                    // 初始化速度缩放基准为算出的目标 zoom，避免跳变
                    smoothedZoom = targetZoom
                    // 重置跟随基准，避免触发飞行
                    lastFollowLat = lat
                    lastFollowLng = lng
                    hasLastFollow = true
                }, 1300)
            }, 1600)
        }
    }

    /**
     * 跟随状态：
     * - 目标在视野内，相机平滑跟随
     * - 单次位移超过阈值（屏幕像素），不进跟随，直接进飞行态
     * - 速度驱动缩放：低速放大，高速缩小
     */
    private fun handleFollow() {
        val provider = interpolatedProvider ?: return
        if (!provider.hasGpsFix()) return

        val loc = provider.lastKnownLocation ?: return
        val lat = loc.latitude
        val lng = loc.longitude

        if (!hasLastFollow) {
            lastFollowLat = lat
            lastFollowLng = lng
            hasLastFollow = true
            map.controller.setCenter(GeoPoint(lat, lng))
            return
        }

        // === 速度驱动缩放 ===
        val speedKmh = provider.getSmoothedSpeedKmh().toDouble().coerceAtLeast(0.0)
        val targetZoom = speedToZoom(speedKmh)
        // EMA 平滑，避免 zoom 跳变
        smoothedZoom = smoothedZoom + zoomAlpha * (targetZoom - smoothedZoom)
        // 限制 zoom 范围
        val clampedZoom = smoothedZoom.coerceIn(3.0, 18.0)
        // 只有变化超过 0.1 且距离上次 setZoom 超过 500ms 才实际调整（避免频繁触发）
        val currentZoom = map.zoomLevelDouble
        val now = System.currentTimeMillis()
        if (kotlin.math.abs(clampedZoom - currentZoom) > 0.15 && now - lastZoomSetTime > 500) {
            // 用 animateTo 做平滑 zoom 过渡（800ms 缓动）
            map.controller.animateTo(GeoPoint(lat, lng), clampedZoom, 800L)
            lastZoomSetTime = now
        }

        val distM = haversine(lastFollowLat, lastFollowLng, lat, lng)

        if (distM > 5.0) {
            // 换算成屏幕像素位移
            val zoom = map.zoomLevel.toDouble()
            val metersPerPixel = 156543.03392 * cos(Math.toRadians(lat)) / Math.pow(2.0, zoom.toDouble())
            val screenPx = (distM / metersPerPixel).toInt()

            if (screenPx > flyThresholdPx) {
                // 超阈值 → 进入飞行态
                startFlyAnimation(lat, lng)
                return
            }

            // 正常跟随（只在 zoom 没在动画中时）
            if (now - lastZoomSetTime > 900) {
                map.controller.animateTo(GeoPoint(lat, lng))
            }
            lastFollowLat = lat
            lastFollowLng = lng
        }
    }

    /**
     * 速度 → zoom 映射
     * 0 km/h → zoom 18（停车看街道细节）
     * 30 km/h → zoom 17（城市低速）
     * 60 km/h → zoom 16（城市快速路）
     * 90 km/h → zoom 15（国道/省道）
     * 120 km/h → zoom 14（高速公路）
     * 160+ km/h → zoom 13（超高速全局视图）
     */
    private fun speedToZoom(speedKmh: Double): Double {
        return when {
            speedKmh <= 0 -> 18.0
            speedKmh <= 30 -> 18.0 - (speedKmh / 30.0) * 1.0   // 18→17
            speedKmh <= 60 -> 17.0 - ((speedKmh - 30) / 30.0) * 1.0  // 17→16
            speedKmh <= 90 -> 16.0 - ((speedKmh - 60) / 30.0) * 1.0  // 16→15
            speedKmh <= 120 -> 15.0 - ((speedKmh - 90) / 30.0) * 1.0 // 15→14
            speedKmh <= 160 -> 14.0 - ((speedKmh - 120) / 40.0) * 1.0 // 14→13
            else -> 13.0
        }
    }

    /**
     * 飞行态：拉高 → 平移 → 落下，走一条可控曲线
     * 用户看到的是"飞机飞过去了"，而不是"画面抽过去了"
     */
    private var flyRunnable: Runnable? = null

    private fun startFlyAnimation(destLat: Double, destLng: Double) {
        cameraState = CameraState.FLY_TO

        val startLat = lastFollowLat
        val startLng = lastFollowLng
        val startZoom = map.zoomLevelDouble

        // 飞行中间 zoom：距离越远 zoom 越低（看到更多全局视图）
        val distM = haversine(startLat, startLng, destLat, destLng)
        val midZoom = when {
            distM > 10000 -> 12.0
            distM > 5000 -> 13.0
            distM > 2000 -> 14.0
            else -> 15.0
        }

        val totalDurationMs = 2000L
        val startTime = System.currentTimeMillis()

        // 取消上一个飞行动画（如果有）
        flyRunnable?.let { handler.removeCallbacks(it) }

        flyRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / totalDurationMs).coerceIn(0f, 1f)

                // 位置：easeInOutCubic 插值
                val t = easeInOutCubic(progress)
                val curLat = startLat + (destLat - startLat) * t
                val curLng = startLng + (destLng - startLng) * t

                // Zoom：前半拉到 midZoom，后半恢复到速度驱动的目标 zoom
                val curZoom = if (progress < 0.5f) {
                    val zt = easeInOutCubic(progress * 2)
                    startZoom + (midZoom - startZoom) * zt
                } else {
                    // 实时读取当前速度，算出落地 zoom（不固定为起飞 zoom）
                    val speedKmh = interpolatedProvider?.getSmoothedSpeedKmh()?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
                    val landingZoom = speedToZoom(speedKmh).coerceIn(3.0, 18.0)
                    val zt = easeInOutCubic((progress - 0.5f) * 2)
                    midZoom + (landingZoom - midZoom) * zt
                }

                map.controller.setCenter(GeoPoint(curLat, curLng))
                map.controller.setZoom(curZoom)

                if (progress < 1.0f) {
                    handler.postDelayed(this, 16)
                } else {
                    // 飞行完成 → 回到跟随，同步 smoothedZoom 到当前速度 zoom
                    cameraState = CameraState.FOLLOW
                    lastFollowLat = destLat
                    lastFollowLng = destLng
                    val speedKmh = interpolatedProvider?.getSmoothedSpeedKmh()?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
                    smoothedZoom = speedToZoom(speedKmh)
                }
            }
        }

        flyRunnable?.let { handler.post(it) }
    }

    // === 缓动函数 ===

    private fun easeInOutCubic(t: Float): Double {
        val td = t.toDouble()
        return if (td < 0.5) 4.0 * td * td * td
        else 1.0 - Math.pow(-2.0 * td + 2.0, 3.0) / 2.0
    }

    private fun easeOutCubic(t: Float): Double {
        val td = t.toDouble()
        return 1.0 - Math.pow(1.0 - td, 3.0)
    }

    // === HUD 更新（速度 + 路名） ===

    private var currentLocation: Location? = null

    private val hudRunnable = object : Runnable {
        override fun run() {
            val provider = interpolatedProvider

            // 速度：直接从插值引擎读取平滑后的 km/h 值
            if (provider != null) {
                val speedKmh = provider.getSmoothedSpeedKmh().toInt()
                tvSpeed.text = speedKmh.toString()
            }

            // 逆地理编码（每 50m 请求一次）
            val loc = provider?.lastKnownLocation
            if (loc != null && !geoRequestPending) {
                val dist = haversine(lastGeoLat, lastGeoLng, loc.latitude, loc.longitude)
                if (dist > GEO_MIN_DISTANCE || lastGeoLat == 0.0) {
                    geoRequestPending = true
                    lastGeoLat = loc.latitude
                    lastGeoLng = loc.longitude
                    reverseGeocode(loc.latitude, loc.longitude)
                }
            }

            handler.postDelayed(this, 500) // HUD 2Hz
        }
    }

    private fun startHudUpdater() {
        handler.post(hudRunnable)
    }

    /** Nominatim 逆地理编码：坐标 → 路名 */
    private fun reverseGeocode(lat: Double, lng: Double) {
        Thread {
            try {
                val url = URL("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lng&format=json&zoom=18&addressdetails=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "LastFreedom/5.0")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(response)
                val address = json.optJSONObject("address")
                val roadName = address?.optString("road", "")
                    ?: json.optString("display_name", "").split(",").firstOrNull() ?: ""

                handler.post {
                    if (roadName.isNotBlank()) {
                        tvRoad.text = roadName.uppercase()
                    }
                }
            } catch (_: Exception) {
                // 网络错误静默处理
            } finally {
                handler.post { geoRequestPending = false }
            }
        }.start()
    }

    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // === 生命周期 ===

    override fun onResume() {
        super.onResume()
        map.onResume()
        locationOverlay.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        locationOverlay.disableMyLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        interpolatedProvider?.destroy()
        handler.removeCallbacksAndMessages(null)
    }
}
