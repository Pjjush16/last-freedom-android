package com.chasegame.lastfreedom

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        map.isTilesScaledToDpi = true
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 19.0
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(39.9042, 116.4074)) // 默认北京
    }

    private fun setupLocationOverlay() {
        // 使用插值定位提供者：60fps 平滑位置 + EMA 平滑方向
        val provider = InterpolatedLocationProvider(this)
        interpolatedProvider = provider

        locationOverlay = MyLocationNewOverlay(provider, map).apply {
            enableMyLocation()
            enableFollowLocation()
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

        // 启动 HUD 更新循环
        startHudUpdater()
    }

    // === HUD 更新（速度 + 路名） ===

    private var currentLocation: Location? = null

    private val hudRunnable = object : Runnable {
        override fun run() {
            currentLocation?.let { loc ->
                // 速度 (km/h)
                if (loc.hasSpeed()) {
                    val speedKmh = (loc.speed * 3.6).toInt()
                    tvSpeed.text = speedKmh.toString()
                }

                // 逆地理编码（每 50m 请求一次）
                if (!geoRequestPending) {
                    val dist = haversine(lastGeoLat, lastGeoLng, loc.latitude, loc.longitude)
                    if (dist > GEO_MIN_DISTANCE || lastGeoLat == 0.0) {
                        geoRequestPending = true
                        lastGeoLat = loc.latitude
                        lastGeoLng = loc.longitude
                        reverseGeocode(loc.latitude, loc.longitude)
                    }
                }
            }
            handler.postDelayed(this, 500) // HUD 2Hz 足够
        }
    }

    private fun startHudUpdater() {
        // 监听插值提供者的位置更新
        interpolatedProvider?.let { provider ->
            // MyLocationNewOverlay 已经在接收插值位置了
            // 我们通过 overlay 获取最新位置
            val locationCheckRunnable = object : Runnable {
                override fun run() {
                    currentLocation = locationOverlay.myLocation?.let { geo ->
                        Location("interpolated").apply {
                            latitude = geo.latitude
                            longitude = geo.longitude
                            // 从 provider 获取速度和方向
                            provider.lastKnownLocation?.let { loc ->
                                if (loc.hasSpeed()) speed = loc.speed
                                if (loc.hasBearing()) bearing = loc.bearing
                            }
                        }
                    }
                    handler.postDelayed(this, 100)
                }
            }
            handler.post(locationCheckRunnable)
        }
        handler.post(hudRunnable)
    }

    /** Nominatim 逆地理编码：坐标 → 路名 */
    private fun reverseGeocode(lat: Double, lng: Double) {
        Thread {
            try {
                val url = URL("https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lng&format=json&zoom=18&addressdetails=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "LastFreedom/4.7")
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
            } catch (e: Exception) {
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
