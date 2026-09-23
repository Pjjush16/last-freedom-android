package com.chasegame.lastfreedom

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
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

    // === 地图样式 ===
    private enum class MapMode { SATELLITE, SATELLITE_ROAD, STANDARD }
    private var currentMapMode = MapMode.SATELLITE
    private lateinit var mapStyleMenu: View
    private var roadOverlay: TilesOverlay? = null
    private var roadManager: RoadOverlayManager? = null
    private var pickedDestMarker: Marker? = null
    private lateinit var prefs: SharedPreferences

    // === 路线预览 ===
    private var routePreviewLine: Polyline? = null
    private var pendingDestLat = 0.0
    private var pendingDestLng = 0.0
    private var routePreviewActive = false

    // === 速度→缩放迟滞（死区）===
    // zoom 变化需要速度持续 3 秒超过/低于阈值才执行，避免阈值边界"喘气"
    private var pendingZoomChange: Double = -1.0        // 待执行的目标 zoom（-1 = 无待执行）
    private var pendingZoomStartTime = 0L               // 速度首次越过阈值的时间
    private val HYSTERESIS_DURATION_MS = 3_000L         // 持续 3 秒才执行

    // === 选点模式（高德风格：准星居中 + 拖地图选目的地）===
    private var pickerModeActive = false
    private var pickerCameraWasFollowing = true  // 进入选点前的相机状态

    // === 路线信息显示 ===
    private var tvRouteInfo: TextView? = null

    // === 惯导融合 ===
    private var inertialManager: InertialNavigationManager? = null

    // === 背景音乐 ===
    private var bgmManager: BgmManager? = null

    // === 规则打分系统 ===
    private var ruleScoringModel: RuleScoringModel? = null

    // === 突围引擎 ===
    private var breakoutEngine: BreakoutEngine? = null

    // === 突围 HUD 控件 ===
    private lateinit var tvBreakoutInfo: TextView
    private lateinit var tvConfidence: TextView
    private lateinit var tvShrinkRadius: TextView
    private lateinit var tvVisibility: TextView
    private lateinit var tvTimer: TextView
    private lateinit var breakoutHud: View

    // === 路障地图标记 ===
    private val barricadeMarkers = mutableListOf<Marker>()

    // === 持续道路吸附 ===
    private var lastRoadSnapTime = 0L
    private val ROAD_SNAP_INTERVAL = 500L  // 每500ms吸附一次
    private val SPEED_THRESHOLD_FOR_SNAP = 20f  // km/h，速度>20才吸附

    companion object {
        private const val PERM_REQUEST = 100
        private const val GEO_MIN_DISTANCE = 50.0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        prefs = getSharedPreferences("lastfreedom", MODE_PRIVATE)
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.mapView)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvRoad = findViewById(R.id.tvRoad)

        setupMap()
        setupZoomControls()
        setupMapStyleMenu()
        // roadManager 在 setupRoadManager() 中创建（权限授予后，interpolatedProvider 已就绪）
        setupStickers()
        setupBreakoutHud()
        requestPermissions()
    }

    private fun setupMap() {
        // 恢复上次选择的地图模式
        val savedMode = prefs.getString("map_mode", "SATELLITE") ?: "SATELLITE"
        currentMapMode = try { MapMode.valueOf(savedMode) } catch (_: Exception) { MapMode.SATELLITE }

        // 根据恢复的模式设置底图
        when (currentMapMode) {
            MapMode.SATELLITE -> map.setTileSource(createSatelliteSource())
            MapMode.SATELLITE_ROAD -> {
                map.setTileSource(createSatelliteSource())
                // 注意：路网叠加层需要在 roadManager 初始化后恢复，见 setupMapStyleMenu()
            }
            MapMode.STANDARD -> map.setTileSource(createOsmStandardSource())
        }

        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 18.0
        map.setBuiltInZoomControls(false)

        // 检测用户触摸，暂停自动缩放（选点模式下不干预地图拖动）
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
                btnZoomLock.text = "🔒"
                // 锁定后重置迟滞状态
                pendingZoomChange = -1.0
                pendingZoomStartTime = 0L
            } else {
                btnZoomLock.text = "🔓"
                // 解锁后重新初始化 smoothedZoom 为当前速度对应值
                val speedKmh = interpolatedProvider?.getSmoothedSpeedKmh()?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
                smoothedZoom = speedToZoom(speedKmh)
            }
        }

        // 导航选点按钮：进入高德风格移图选点模式
        findViewById<TextView>(R.id.btnNavigate).setOnClickListener {
            enterPickerMode()
        }
    }

    // === OSM Carto 路网叠加层（透明底，只有道路） ===
    private fun createRoadOverlaySource(): XYTileSource {
        return object : XYTileSource("osm_carto_only", 1, 18, 256, ".png",
            arrayOf("https://tile.openstreetmap.de")) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                val clampedZ = if (z > 18) 18 else z
                return "https://tile.openstreetmap.de/$clampedZ/$x/$y.png"
            }
        }
    }

    // === OSM 标准地图 ===
    private fun createOsmStandardSource(): XYTileSource {
        return object : XYTileSource("osm_standard", 1, 18, 256, ".png",
            arrayOf("https://tile.openstreetmap.de")) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                val clampedZ = if (z > 18) 18 else z
                return "https://tile.openstreetmap.de/$clampedZ/$x/$y.png"
            }
        }
    }

    // === ArcGIS 卫星底图 ===
    private fun createSatelliteSource(): XYTileSource {
        return object : XYTileSource("arcgis_world_imagery", 1, 18, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com")) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                var z = MapTileIndex.getZoom(pMapTileIndex)
                if (z > 18) z = 18
                return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
            }
        }
    }

    private fun setupMapStyleMenu() {
        mapStyleMenu = findViewById(R.id.mapStyleMenu)

        val optSatellite = findViewById<TextView>(R.id.optSatellite)
        val optSatRoad = findViewById<TextView>(R.id.optSatRoad)
        val optStandard = findViewById<TextView>(R.id.optStandard)

        findViewById<TextView>(R.id.btnMapStyle).setOnClickListener {
            mapStyleMenu.visibility = if (mapStyleMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        optSatellite.setOnClickListener {
            switchMapMode(MapMode.SATELLITE)
            mapStyleMenu.visibility = View.GONE
        }

        optSatRoad.setOnClickListener {
            switchMapMode(MapMode.SATELLITE_ROAD)
            mapStyleMenu.visibility = View.GONE
        }

        optStandard.setOnClickListener {
            switchMapMode(MapMode.STANDARD)
            mapStyleMenu.visibility = View.GONE
        }

        // 高亮当前选中项（根据恢复的地图模式）
        val initialOpt = when (currentMapMode) {
            MapMode.SATELLITE -> optSatellite
            MapMode.SATELLITE_ROAD -> optSatRoad
            MapMode.STANDARD -> optStandard
        }
        highlightMapOption(initialOpt)
    }

    private fun highlightMapOption(selected: TextView) {
        val opts = listOf(
            findViewById<TextView>(R.id.optSatellite),
            findViewById<TextView>(R.id.optSatRoad),
            findViewById<TextView>(R.id.optStandard)
        )
        opts.forEach {
            if (it == selected) {
                it.setTextColor(0xFF00E5FF.toInt())
                it.alpha = 1.0f
            } else {
                it.setTextColor(0x99FFFFFF.toInt())
                it.alpha = 0.7f
            }
        }
    }

    private fun switchMapMode(mode: MapMode) {
        if (mode == currentMapMode) return
        currentMapMode = mode

        // 记住选择
        prefs.edit().putString("map_mode", mode.name).apply()

        // 先移除旧的叠加层
        roadOverlay?.let { map.overlays.remove(it) }
        roadOverlay = null
        roadManager?.hide()

        when (mode) {
            MapMode.SATELLITE -> {
                map.setTileSource(createSatelliteSource())
            }
            MapMode.SATELLITE_ROAD -> {
                map.setTileSource(createSatelliteSource())
                // 渲染路网 Polyline（仅在此模式下显示）
                roadManager?.show()
                // 用当前位置立即查询路网
                interpolatedProvider?.lastKnownLocation?.let { loc ->
                    roadManager?.updateForPosition(loc.latitude, loc.longitude)
                }
                // 确保车标在路网上方
                bringCarToTop()
            }
            MapMode.STANDARD -> {
                map.setTileSource(createOsmStandardSource())
            }
        }

        // 所有模式下都加载路网数据（用于吸附），但不渲染
        interpolatedProvider?.lastKnownLocation?.let { loc ->
            roadManager?.updateForPosition(loc.latitude, loc.longitude)
        }

        map.invalidate()

        // 高亮选中项
        val optId = when (mode) {
            MapMode.SATELLITE -> R.id.optSatellite
            MapMode.SATELLITE_ROAD -> R.id.optSatRoad
            MapMode.STANDARD -> R.id.optStandard
        }
        highlightMapOption(findViewById(optId))
    }

    // === 选点模式（高德风格：准星居中 + 拖地图选目的地）===

    private fun enterPickerMode() {
        // 不强制切换地图模式，保持用户当前选择的模式
        pickerModeActive = true
        pickerCameraWasFollowing = (cameraState == CameraState.FOLLOW)

        // 暂停相机跟随
        cameraState = CameraState.FLY_TO  // 借用 FLY_TO 状态暂停跟随

        // 显示准星 + 提示 + 按钮
        findViewById<View>(R.id.crosshairOverlay).visibility = View.VISIBLE
        findViewById<View>(R.id.pickerHint).visibility = View.VISIBLE
        findViewById<View>(R.id.pickerButtons).visibility = View.VISIBLE

        // 隐藏 Dock
        findViewById<View>(R.id.zoomDock).visibility = View.GONE

        // 设置确认/取消按钮
        findViewById<TextView>(R.id.btnPickerConfirm).text = "确定"
        findViewById<TextView>(R.id.btnPickerConfirm).setTextColor(0xFF4FC3F7.toInt())
        findViewById<TextView>(R.id.btnPickerConfirm).setOnClickListener {
            confirmPickedDestination()
        }
        findViewById<TextView>(R.id.btnPickerCancel).setOnClickListener {
            exitPickerMode()
        }
    }

    private fun confirmPickedDestination() {
        // 获取地图中心点坐标（用户选的目的地）
        val center = map.mapCenter as GeoPoint
        val destLat = center.latitude
        val destLng = center.longitude

        // 获取当前位置
        val loc = interpolatedProvider?.lastKnownLocation
        if (loc == null) {
            exitPickerMode()
            return
        }

        pendingDestLat = destLat
        pendingDestLng = destLng

        // 放置目的地标记
        pickedDestMarker?.let { map.overlays.remove(it) }
        pickedDestMarker = Marker(map).apply {
            position = GeoPoint(destLat, destLng)
            title = "目的地 $destLat, $destLng"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        map.overlays.add(pickedDestMarker)

        // 画红色路线连接当前位置和目的地
        routePreviewLine?.let { map.overlays.remove(it) }
        routePreviewLine = Polyline().apply {
            setPoints(listOf(GeoPoint(loc.latitude, loc.longitude), GeoPoint(destLat, destLng)))
            outlinePaint.color = 0xCCFF3333.toInt()
            outlinePaint.strokeWidth = 6f
            outlinePaint.isAntiAlias = true
        }
        map.overlays.add(routePreviewLine)
        map.invalidate()

        // 自动缩放地图让两点都在屏幕内
        val distM = haversine(loc.latitude, loc.longitude, destLat, destLng)
        val targetZoom = when {
            distM > 20000 -> 11.0
            distM > 10000 -> 12.0
            distM > 5000 -> 13.0
            distM > 2000 -> 14.0
            distM > 1000 -> 15.0
            else -> 16.0
        }
        val midLat = (loc.latitude + destLat) / 2.0
        val midLng = (loc.longitude + destLng) / 2.0
        map.controller.animateTo(GeoPoint(midLat, midLng), targetZoom, 800L)

        // 切换到出发预览模式
        routePreviewActive = true
        findViewById<View>(R.id.crosshairOverlay).visibility = View.GONE
        findViewById<View>(R.id.pickerHint).let {
            (it as TextView).text = "确认路线后点击出发"
        }
        findViewById<TextView>(R.id.btnPickerConfirm).text = "出发"
        findViewById<TextView>(R.id.btnPickerConfirm).setTextColor(0xFF44FF44.toInt())
        findViewById<TextView>(R.id.btnPickerConfirm).setOnClickListener {
            departFromPreview()
        }
        findViewById<TextView>(R.id.btnPickerCancel).setOnClickListener {
            clearRoutePreview()
            // 回到选点模式（准星重新显示）
            findViewById<View>(R.id.crosshairOverlay).visibility = View.VISIBLE
            (findViewById<View>(R.id.pickerHint) as TextView).text = "拖动地图选择目的地"
            findViewById<TextView>(R.id.btnPickerConfirm).text = "确定"
            findViewById<TextView>(R.id.btnPickerConfirm).setTextColor(0xFF4FC3F7.toInt())
            findViewById<TextView>(R.id.btnPickerConfirm).setOnClickListener {
                confirmPickedDestination()
            }
            findViewById<TextView>(R.id.btnPickerCancel).setOnClickListener {
                exitPickerMode()
            }
            routePreviewActive = false
        }
    }

    private fun clearRoutePreview() {
        routePreviewLine?.let { map.overlays.remove(it) }
        routePreviewLine = null
        pickedDestMarker?.let { map.overlays.remove(it) }
        pickedDestMarker = null
        map.invalidate()
    }

    private fun departFromPreview() {
        // 清除预览线条（保留目的地标记）
        routePreviewLine?.let { map.overlays.remove(it) }
        routePreviewLine = null
        map.invalidate()

        exitPickerMode()
        routePreviewActive = false

        // 启动突围引擎
        val loc = interpolatedProvider?.lastKnownLocation
        if (loc != null) {
            breakoutEngine?.startBreakout(loc.latitude, loc.longitude, pendingDestLat, pendingDestLng)
        }
    }

    private fun exitPickerMode() {
        pickerModeActive = false
        routePreviewActive = false

        // 清除路线预览
        routePreviewLine?.let { map.overlays.remove(it) }
        routePreviewLine = null

        // 隐藏准星 + 提示 + 按钮
        findViewById<View>(R.id.crosshairOverlay).visibility = View.GONE
        findViewById<View>(R.id.pickerHint).visibility = View.GONE
        findViewById<View>(R.id.pickerButtons).visibility = View.GONE

        // 恢复 Dock
        findViewById<View>(R.id.zoomDock).visibility = View.VISIBLE

        // 恢复相机跟随
        if (pickerCameraWasFollowing) {
            cameraState = CameraState.FOLLOW
        }
    }

    // === 突围 HUD 设置 ===
    private fun setupBreakoutHud() {
        breakoutHud = findViewById(R.id.breakoutHud)
        tvBreakoutInfo = findViewById(R.id.tvBreakoutInfo)
        tvConfidence = findViewById(R.id.tvConfidence)
        tvShrinkRadius = findViewById(R.id.tvShrinkRadius)
        tvVisibility = findViewById(R.id.tvVisibility)
        tvTimer = findViewById(R.id.tvTimer)

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
            useWideViewPort = false
            loadWithOverviewMode = false
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
            useWideViewPort = false
            loadWithOverviewMode = false
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

    /** 将车标移到 overlay 列表末尾（绘制在最上层，在路网之上） */
    private fun bringCarToTop() {
        if (::locationOverlay.isInitialized) {
            map.overlays.remove(locationOverlay)
            map.overlays.add(locationOverlay)
        }
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
        setupRoadManager()
        setupInertialNavigation()
        bgmManager = BgmManager(this)
        bgmManager?.enterPicking()  // 从启动就开始播放漫游音乐
        ruleScoringModel = RuleScoringModel(this)
        breakoutEngine = BreakoutEngine(handler).apply {
            onStateChanged = { state ->
                handler.post {
                    when (state) {
                        BreakoutEngine.State.IDLE -> {
                            bgmManager?.enterIdle()
                            breakoutHud.visibility = View.GONE
                            clearBarricadeMarkers()
                        }
                        BreakoutEngine.State.PICKING -> {
                            bgmManager?.enterPicking()
                            breakoutHud.visibility = View.GONE
                        }
                        BreakoutEngine.State.BREAKOUT -> {
                            bgmManager?.enterBreakout()
                            breakoutHud.visibility = View.VISIBLE
                        }
                        BreakoutEngine.State.VICTORY -> {
                            bgmManager?.playVictory()
                            val score = ruleScoringModel?.scoreRound(
                                true,
                                breakoutEngine?.getElapsedSeconds() ?: 0,
                                breakoutEngine?.getTotalDistanceM() ?: 0.0,
                                interpolatedProvider?.getMaxSpeedKmh() ?: 0f,
                                interpolatedProvider?.getMaxSpeedKmh()?.times(0.6f) ?: 0f,
                                breakoutEngine?.tortuosityIndex ?: 1.5
                            )
                            val gainStr = if ((score?.starsGained ?: 0) >= 0) "+${score?.starsGained}" else "${score?.starsGained}"
                            tvBreakoutInfo.text = "消星成功\n${gainStr}★ (驾驶+${score?.drivingBonus})\n累计${score?.totalStars}★\n${score?.comment ?: ""}"
                            tvBreakoutInfo.setTextColor(0xFF44FF44.toInt())
                        }
                        BreakoutEngine.State.ARRESTED -> {
                            bgmManager?.playArrested()
                            val score = ruleScoringModel?.scoreRound(
                                false,
                                breakoutEngine?.getElapsedSeconds() ?: 0,
                                breakoutEngine?.getTotalDistanceM() ?: 0.0,
                                interpolatedProvider?.getMaxSpeedKmh() ?: 0f,
                                interpolatedProvider?.getMaxSpeedKmh()?.times(0.6f) ?: 0f,
                                breakoutEngine?.tortuosityIndex ?: 1.5
                            )
                            val gainStr = if ((score?.starsGained ?: 0) >= 0) "+${score?.starsGained}" else "${score?.starsGained}"
                            tvBreakoutInfo.text = "被捕\n${gainStr}★ (驾驶+${score?.drivingBonus} 失败-1)\n累计${score?.totalStars}★\n${score?.comment ?: ""}"
                            tvBreakoutInfo.setTextColor(0xFFFF4444.toInt())
                        }
                        BreakoutEngine.State.TIMEOUT -> {
                            bgmManager?.playArrested()
                            val score = ruleScoringModel?.scoreRound(
                                false,
                                breakoutEngine?.getElapsedSeconds() ?: 0,
                                breakoutEngine?.getTotalDistanceM() ?: 0.0,
                                interpolatedProvider?.getMaxSpeedKmh() ?: 0f,
                                interpolatedProvider?.getMaxSpeedKmh()?.times(0.6f) ?: 0f,
                                breakoutEngine?.tortuosityIndex ?: 1.5
                            )
                            val gainStr = if ((score?.starsGained ?: 0) >= 0) "+${score?.starsGained}" else "${score?.starsGained}"
                            tvBreakoutInfo.text = "超时\n${gainStr}★ (驾驶+${score?.drivingBonus} 失败-1)\n累计${score?.totalStars}★\n${score?.comment ?: ""}"
                            tvBreakoutInfo.setTextColor(0xFFFF8800.toInt())
                        }
                    }
                }
            }
            onBarricadesChanged = { barricades ->
                handler.post { updateBarricadeMarkers(barricades) }
            }
            onHudUpdate = { hud ->
                handler.post {
                    val confPct = (hud.confidence * 100).toInt()
                    tvConfidence.text = "置信度: $confPct%"
                    tvConfidence.setTextColor(when {
                        confPct > 70 -> 0xFFFF2222.toInt()
                        confPct > 40 -> 0xFFFFAA00.toInt()
                        else -> 0xFF44FF44.toInt()
                    })

                    val radiusKm = hud.shrinkRadius / 1000.0
                    tvShrinkRadius.text = String.format("包围圈: %.1fkm", radiusKm)

                    val visLabel = when (hud.visibility) {
                        BreakoutEngine.VisibilityLevel.BLIND -> "盲堵"
                        BreakoutEngine.VisibilityLevel.PRECISE -> "精准封锁"
                        BreakoutEngine.VisibilityLevel.INTERCEPT -> "前方拦截"
                    }
                    tvVisibility.text = "可见性: $visLabel"
                    tvVisibility.setTextColor(when (hud.visibility) {
                        BreakoutEngine.VisibilityLevel.INTERCEPT -> 0xFFFF2222.toInt()
                        BreakoutEngine.VisibilityLevel.PRECISE -> 0xFFFF8800.toInt()
                        else -> 0xFFAAAAAA.toInt()
                    })

                    val eMin = hud.elapsedSec / 60
                    val eSec = hud.elapsedSec % 60
                    val tMin = hud.timeLimitSec / 60
                    val tSec = hud.timeLimitSec % 60
                    tvTimer.text = String.format("%02d:%02d / %02d:%02d", eMin, eSec, tMin, tSec)

                    // 规则打分实时预测：突围中时定期评估累计是否接近八星
                    if (hud.state == BreakoutEngine.State.BREAKOUT) {
                        val predicted = ruleScoringModel?.predictCurrentRound(
                            interpolatedProvider?.getMaxSpeedKmh() ?: 0f,
                            (interpolatedProvider?.getMaxSpeedKmh() ?: 0f) * 0.6f,
                            breakoutEngine?.getTotalDistanceM() ?: 0.0,
                            breakoutEngine?.getElapsedSeconds() ?: 0,
                            hud.distToDest,
                            breakoutEngine?.tortuosityIndex ?: 1.5
                        ) ?: 0.0
                        if (predicted >= 7.5) {
                            bgmManager?.enterEightStarMode()
                        }
                    }
                }
            }
        }
        startHudUpdater()
        startCameraSystem()
    }

    private fun setupRoadManager() {
        roadManager = RoadOverlayManager(map, handler)
        // 恢复路网模式：如果上次选择了 SATELLITE_ROAD，立即显示并触发查询
        if (currentMapMode == MapMode.SATELLITE_ROAD) {
            roadManager?.show()
            interpolatedProvider?.lastKnownLocation?.let { loc ->
                roadManager?.updateForPosition(loc.latitude, loc.longitude)
            }
        }
    }

    private fun setupInertialNavigation() {
        val sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        inertialManager = InertialNavigationManager(sensorManager, handler)
    }

    // === 三状态相机系统 ===

    private val cameraRunnable = object : Runnable {
        override fun run() {
            // 更新路网可见性（根据缩放级别）
            if (currentMapMode == MapMode.SATELLITE_ROAD) {
                roadManager?.updateZoomLevel(map.zoomLevelDouble)
            }

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
        val gpsLat = loc.latitude
        val gpsLng = loc.longitude
        val gpsAccuracy = loc.accuracy
        val gpsBearing = loc.bearing
        val gpsSpeed = loc.speed

        // === 惯导融合 ===
        val fusedPoint = inertialManager?.updateGps(gpsLat, gpsLng, gpsAccuracy, gpsBearing, gpsSpeed)
            ?: GeoPoint(gpsLat, gpsLng)
        val lat = fusedPoint.latitude
        val lng = fusedPoint.longitude

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

        // === 路网数据更新（所有模式都加载，用于吸附）===
        roadManager?.updateForPosition(lat, lng)

        // === 路网渲染（仅 SATELLITE_ROAD 模式显示 Polyline）===
        if (currentMapMode == MapMode.SATELLITE_ROAD) {
            // 确保车标在路网上方（移到 overlay 列表末尾）
            bringCarToTop()
        }

        // === 持续道路吸附（所有模式都生效，速度 > 20km/h 且间隔 > 500ms）===
        var displayLat = lat
        var displayLng = lng
        val speedKmh = gpsSpeed * 3.6f  // m/s → km/h
        if (speedKmh > SPEED_THRESHOLD_FOR_SNAP && (now - lastRoadSnapTime) >= ROAD_SNAP_INTERVAL) {
            val snapped = roadManager?.snapToRoad(lat, lng)
            if (snapped != null) {
                displayLat = snapped.latitude
                displayLng = snapped.longitude
            }
            lastRoadSnapTime = now
        }

        // === 突围引擎更新（如果有活跃游戏）===
        breakoutEngine?.updatePlayer(displayLat, displayLng, gpsBearing.toDouble())

        // === 位置跟随（始终执行，不受锁定影响）===
        val distM = haversine(lastFollowLat, lastFollowLng, displayLat, displayLng)

        if (distM > 5.0) {
            val zoom = map.zoomLevel.toDouble()
            val metersPerPixel = 156543.03392 * cos(Math.toRadians(displayLat)) / Math.pow(2.0, zoom.toDouble())
            val screenPx = (distM / metersPerPixel).toInt()

            if (screenPx > flyThresholdPx) {
                startFlyAnimation(displayLat, displayLng)
                return
            }

            if (now - lastZoomSetTime > 900) {
                map.controller.animateTo(GeoPoint(displayLat, displayLng))
            }
            lastFollowLat = displayLat
            lastFollowLng = displayLng
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

    // === 路障地图标记渲染 ===

    private fun updateBarricadeMarkers(barricades: List<BreakoutEngine.Barricade>) {
        // 清除旧标记
        clearBarricadeMarkers()

        for (b in barricades) {
            if (!b.active) continue
            val marker = Marker(map)
            marker.position = GeoPoint(b.lat, b.lng)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

            when (b.type) {
                BreakoutEngine.BarricadeType.PATROL -> {
                    marker.title = "巡逻车"
                    marker.snippet = "入径 #${b.routeIndex + 1}"
                    // 红色圆点表示巡逻车
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xCCFF4444.toInt())
                        setSize(24, 24)
                        setStroke(2, 0xFFFFFFFF.toInt())
                    }
                    marker.icon = shape
                }
                BreakoutEngine.BarricadeType.BARRIER -> {
                    marker.title = "路障"
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setColor(0xCCFF8800.toInt())
                        setSize(20, 8)
                        setStroke(1, 0xFFFFFFFF.toInt())
                    }
                    marker.icon = shape
                }
                BreakoutEngine.BarricadeType.INTERCEPT -> {
                    marker.title = "拦截车"
                    val shape = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(0xCCFF0000.toInt())
                        setSize(28, 28)
                        setStroke(3, 0xFFFF0000.toInt())
                    }
                    marker.icon = shape
                }
            }

            map.overlays.add(marker)
            barricadeMarkers.add(marker)
        }
        map.invalidate()
    }

    private fun clearBarricadeMarkers() {
        for (m in barricadeMarkers) {
            map.overlays.remove(m)
        }
        barricadeMarkers.clear()
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
        roadManager?.destroy()
        inertialManager?.destroy()
        bgmManager?.release()
        breakoutEngine?.reset()
        handler.removeCallbacksAndMessages(null)
    }
}
