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
        val tileSource = object : XYTileSource(
            "arcgis_world_imagery", 1, 19, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
                val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
                val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
                return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
            }
        }

        map.setTileSource(tileSource)
        map.setMultiTouchControls(true)
        // 关键：禁用 DPI 缩放，否则在高 DPI 屏幕上用户手势可以放大超过瓦片源的最大 zoom
        map.isTilesScaledToDpi = false
        map.minZoomLevel = 2.0
        // ArcGIS World Imagery 全球覆盖最大 zoom = 19
        map.maxZoomLevel = 19.0

        // 缩放边界监听器：任何操作（手势、动画、相机系统）导致 zoom 超限时自动钳回
        map.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                return false
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                val currentZoom = map.zoomLevel
                if (currentZoom > 19.0) {
                    map.controller.setZoom(19.0)
                } else if (currentZoom < 2.0) {
                    map.controller.setZoom(2.0)
                }
                return false
            }
        })

        // 开场：显示整个地球
        map.controller.setZoom(2.0)
        map.controller.setCenter(GeoPoint(30.0, 110.0)) // 亚太区域居中
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
     * 开场状态：等待 GPS 定位，然后逐渐放大到用户当前位置
     * 1. 显示整个地球（zoom 2）
     * 2. GPS 定位后 → 1.5s 飞到 zoom 10
     * 3. 再 1.2s 飞到 zoom 17
     * 4. 切换到 FOLLOW 状态
     */
    private fun handleOpening() {
        val provider = interpolatedProvider ?: return
        if (!provider.hasGpsFix()) return

        val loc = provider.lastKnownLocation ?: return
        val lat = loc.latitude
        val lng = loc.longitude

        if (!openingZoomedTo10) {
            // 阶段 1：1.5s 飞到 zoom 10
            openingZoomedTo10 = true
            map.controller.animateTo(GeoPoint(lat, lng), 10.0, 1500L)

            // 阶段 2：1.2s 飞到 zoom 17，然后进入 FOLLOW
            handler.postDelayed({
                map.controller.animateTo(GeoPoint(lat, lng), 17.0, 1200L)
                handler.postDelayed({
                    cameraState = CameraState.FOLLOW
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

            // 正常跟随
            map.controller.animateTo(GeoPoint(lat, lng))
            lastFollowLat = lat
            lastFollowLng = lng
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
        val startZoom = map.zoomLevel.toDouble()

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

                // Zoom：前半拉到 midZoom，后半恢复到 startZoom
                val curZoom = if (progress < 0.5f) {
                    val zt = easeInOutCubic(progress * 2)
                    startZoom + (midZoom - startZoom) * zt
                } else {
                    val zt = easeInOutCubic((progress - 0.5f) * 2)
                    midZoom + (startZoom - midZoom) * zt
                }

                map.controller.setCenter(GeoPoint(curLat, curLng))
                map.controller.setZoom(curZoom)

                if (progress < 1.0f) {
                    handler.postDelayed(this, 16)
                } else {
                    // 飞行完成 → 回到跟随
                    cameraState = CameraState.FOLLOW
                    lastFollowLat = destLat
                    lastFollowLng = destLng
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
