package com.chasegame.lastfreedom

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.webkit.WebSettings
import android.webkit.WebView
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
import kotlin.math.abs
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
    private var smoothedZoom = 17.0
    private val zoomAlpha = 0.08
    private var lastZoomSetTime = 0L

    // === 用户交互检测：暂停自动缩放 ===
    private var lastUserTouchTime = 0L
    private val AUTO_ZOOM_RESUME_DELAY_MS = 10_000L  // 用户停止操作后 10 秒恢复自动缩放

    // === 缩放锁定按钮 ===
    private var zoomLocked = false
    private lateinit var btnZoomLock: TextView

    // === 速度→缩放迟滞（死区）===
    // zoom 变化需要速度持续 3 秒超过/低于阈值才执行，避免阈值边界"喘气"
    private var pendingZoomChange: Double = -1.0        // 待执行的目标 zoom（-1 = 无待执行）
    private var pendingZoomStartTime = 0L               // 速度首次越过阈值的时间
    private val HYSTERESIS_DURATION_MS = 3_000L         // 持续 3 秒才执行

    companion object {
        private const val PERM_REQUEST = 100
        private const val GEO_MIN_DISTANCE = 50.0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.mapView)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvRoad = findViewById(R.id.tvRoad)

        setupMap()
        setupZoomControls()
        setupStickers()
        requestPermissions()
    }

    private fun setupMap() {
        val tileSource = object : XYTileSource(
            "arcgis_world_imagery", 1, 18, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
                val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
                var z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
                if (z > 18) z = 18
                return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
            }
        }

        map.setTileSource(tileSource)
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 18.0
        // 禁用 osmdroid 内置缩放控件，使用自定义按钮
        map.setBuiltInZoomControls(false)

        // 检测用户触摸，暂停自动缩放
        map.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    lastUserTouchTime = System.currentTimeMillis()
                }
            }
            false // 不消费事件，让 osmdroid 继续处理
        }

        // 开场：显示整个地球
        map.controller.setZoom(2.0)
        map.controller.setCenter(GeoPoint(30.0, 110.0))
    }

    /**
     * 缩放控制按钮：+ / 锁定 / -
     */
    private fun setupZoomControls() {
        btnZoomLock = findViewById(R.id.btnZoomLock)

        findViewById<TextView>(R.id.btnZoomIn).setOnClickListener {
            lastUserTouchTime = System.currentTimeMillis()
            val newZoom = (map.zoomLevelDouble + 1.0).coerceAtMost(18.0)
            map.controller.animateTo(map.mapCenter as GeoPoint, newZoom, 300L)
        }

        findViewById<TextView>(R.id.btnZoomOut).setOnClickListener {
            lastUserTouchTime = System.currentTimeMillis()
            val newZoom = (map.zoomLevelDouble - 1.0).coerceAtLeast(3.0)
            map.controller.animateTo(map.mapCenter as GeoPoint, newZoom, 300L)
        }

        btnZoomLock.setOnClickListener {
            zoomLocked = !zoomLocked
            if (zoomLocked) {
                // 锁定：alpha 1.0，显示高亮
                btnZoomLock.text = "🔒"
                btnZoomLock.alpha = 1.0f
                btnZoomLock.setTextColor(0xFF00E5FF.toInt()) // 青蓝高亮
                // 锁定后重置迟滞状态
                pendingZoomChange = -1.0
                pendingZoomStartTime = 0L
            } else {
                // 解锁：alpha 0.6，恢复正常
                btnZoomLock.text = "🔓"
                btnZoomLock.alpha = 0.6f
                btnZoomLock.setTextColor(0x99FFFFFF.toInt()) // 恢复默认色
                // 解锁后重新初始化 smoothedZoom 为当前速度对应值
                val speedKmh = interpolatedProvider?.getSmoothedSpeedKmh()?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
                smoothedZoom = speedToZoom(speedKmh)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupStickers() {
        // 右下角贴纸（WebP 动画）
        val stickerRight = findViewById<WebView>(R.id.stickerRight)
        stickerRight.setBackgroundColor(0x00000000) // 透明背景
        stickerRight.isVerticalScrollBarEnabled = false
        stickerRight.isHorizontalScrollBarEnabled = false
        stickerRight.settings.apply {
            javaScriptEnabled = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        stickerRight.loadUrl("file:///android_asset/sticker_right.html")

        // 左下角贴纸（GIF 动画）
        val stickerLeft = findViewById<WebView>(R.id.stickerLeft)
        stickerLeft.setBackgroundColor(0x00000000) // 透明背景
        stickerLeft.isVerticalScrollBarEnabled = false
        stickerLeft.isHorizontalScrollBarEnabled = false
        stickerLeft.settings.apply {
            javaScriptEnabled = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        stickerLeft.loadUrl("file:///android_asset/sticker_left.html")
    }

    private fun setupLocationOverlay() {
        val provider = InterpolatedLocationProvider(this)
        interpolatedProvider = provider

        locationOverlay = MyLocationNewOverlay(provider, map).apply {
            enableMyLocation()
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
                CameraState.FLY_TO -> { /* 飞行动画自行运行 */ }
            }
            handler.postDelayed(this, 50)
        }
    }

    private fun startCameraSystem() {
        handler.post(cameraRunnable)
    }

    private fun handleOpening() {
        val provider = interpolatedProvider ?: return
        if (!provider.hasGpsFix()) return

        val loc = provider.lastKnownLocation ?: return
        val lat = loc.latitude
        val lng = loc.longitude

        if (!openingZoomedTo10) {
            openingZoomedTo10 = true
            map.controller.animateTo(GeoPoint(lat, lng), 10.0, 1500L)

            handler.postDelayed({
                val speedKmh = provider.getSmoothedSpeedKmh().toDouble().coerceAtLeast(0.0)
                val targetZoom = speedToZoom(speedKmh)
                map.controller.animateTo(GeoPoint(lat, lng), targetZoom, 1200L)
                handler.postDelayed({
                    cameraState = CameraState.FOLLOW
                    smoothedZoom = targetZoom
                    lastFollowLat = lat
                    lastFollowLng = lng
                    hasLastFollow = true
                }, 1300)
            }, 1600)
        }
    }

    /**
     * 跟随状态：
     * - 位置始终跟随（不受锁定影响）
     * - zoom 自动缩放受三个条件控制：
     *   1. 用户触摸 → 暂停，10 秒后恢复
     *   2. 锁定按钮 → 完全禁止自动缩放
     *   3. 迟滞死区 → 速度需持续 3 秒越过阈值才执行
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

        val now = System.currentTimeMillis()

        // === 速度驱动缩放（仅在未锁定 且 用户未操作时）===
        val userRecentlyTouched = (now - lastUserTouchTime) < AUTO_ZOOM_RESUME_DELAY_MS

        if (!zoomLocked && !userRecentlyTouched) {
            val speedKmh = provider.getSmoothedSpeedKmh().toDouble().coerceAtLeast(0.0)
            val targetZoom = speedToZoom(speedKmh).coerceIn(3.0, 18.0)

            // 迟滞逻辑：目标 zoom 变化时开始计时，持续 3 秒才执行
            if (abs(targetZoom - pendingZoomChange) > 0.3) {
                // 目标变了（或首次），开始/重设计时器
                pendingZoomChange = targetZoom
                pendingZoomStartTime = now
            }

            if (pendingZoomChange >= 0 && (now - pendingZoomStartTime) >= HYSTERESIS_DURATION_MS) {
                // 3 秒已过，执行缩放
                smoothedZoom = pendingZoomChange
                pendingZoomChange = -1.0
                pendingZoomStartTime = 0L
            }

            // EMA 平滑 + 实际设置
            val currentZoom = map.zoomLevelDouble
            if (abs(smoothedZoom - currentZoom) > 0.15 && now - lastZoomSetTime > 500) {
                map.controller.animateTo(GeoPoint(lat, lng), smoothedZoom.coerceIn(3.0, 18.0), 800L)
                lastZoomSetTime = now
            }
        } else {
            // 用户操作中或锁定中：重置迟滞计时器
            pendingZoomChange = -1.0
            pendingZoomStartTime = 0L
        }

        // === 位置跟随（始终执行，不受锁定影响）===
        val distM = haversine(lastFollowLat, lastFollowLng, lat, lng)

        if (distM > 5.0) {
            val zoom = map.zoomLevel.toDouble()
            val metersPerPixel = 156543.03392 * cos(Math.toRadians(lat)) / Math.pow(2.0, zoom.toDouble())
            val screenPx = (distM / metersPerPixel).toInt()

            if (screenPx > flyThresholdPx) {
                startFlyAnimation(lat, lng)
                return
            }

            if (now - lastZoomSetTime > 900) {
                map.controller.animateTo(GeoPoint(lat, lng))
            }
            lastFollowLat = lat
            lastFollowLng = lng
        }
    }

    private fun speedToZoom(speedKmh: Double): Double {
        return when {
            speedKmh <= 0 -> 18.0
            speedKmh <= 30 -> 18.0 - (speedKmh / 30.0) * 1.0
            speedKmh <= 60 -> 17.0 - ((speedKmh - 30) / 30.0) * 1.0
            speedKmh <= 90 -> 16.0 - ((speedKmh - 60) / 30.0) * 1.0
            speedKmh <= 120 -> 15.0 - ((speedKmh - 90) / 30.0) * 1.0
            speedKmh <= 160 -> 14.0 - ((speedKmh - 120) / 40.0) * 1.0
            else -> 13.0
        }
    }

    private var flyRunnable: Runnable? = null

    private fun startFlyAnimation(destLat: Double, destLng: Double) {
        cameraState = CameraState.FLY_TO

        val startLat = lastFollowLat
        val startLng = lastFollowLng
        val startZoom = map.zoomLevelDouble

        val distM = haversine(startLat, startLng, destLat, destLng)
        val midZoom = when {
            distM > 10000 -> 12.0
            distM > 5000 -> 13.0
            distM > 2000 -> 14.0
            else -> 15.0
        }

        val totalDurationMs = 2000L
        val startTime = System.currentTimeMillis()

        flyRunnable?.let { handler.removeCallbacks(it) }

        flyRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / totalDurationMs).coerceIn(0f, 1f)

                val t = easeInOutCubic(progress)
                val curLat = startLat + (destLat - startLat) * t
                val curLng = startLng + (destLng - startLng) * t

                val curZoom = if (progress < 0.5f) {
                    val zt = easeInOutCubic(progress * 2)
                    startZoom + (midZoom - startZoom) * zt
                } else {
                    val speedKmh = interpolatedProvider?.getSmoothedSpeedKmh()?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
                    val landingZoom = if (zoomLocked) startZoom else speedToZoom(speedKmh).coerceIn(3.0, 18.0)
                    val zt = easeInOutCubic((progress - 0.5f) * 2)
                    midZoom + (landingZoom - midZoom) * zt
                }

                map.controller.setCenter(GeoPoint(curLat, curLng))
                map.controller.setZoom(curZoom)

                if (progress < 1.0f) {
                    handler.postDelayed(this, 16)
                } else {
                    cameraState = CameraState.FOLLOW
                    lastFollowLat = destLat
                    lastFollowLng = destLng
                    if (!zoomLocked) {
                        val speedKmh = interpolatedProvider?.getSmoothedSpeedKmh()?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
                        smoothedZoom = speedToZoom(speedKmh)
                    }
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

    // === HUD 更新 ===

    private var currentLocation: Location? = null

    private val hudRunnable = object : Runnable {
        override fun run() {
            val provider = interpolatedProvider

            if (provider != null) {
                val speedKmh = provider.getSmoothedSpeedKmh().toInt()
                tvSpeed.text = speedKmh.toString()
            }

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

            handler.postDelayed(this, 500)
        }
    }

    private fun startHudUpdater() {
        handler.post(hudRunnable)
    }

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
