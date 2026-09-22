package com.chasegame.lastfreedom

import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

/**
 * 路网数据管理器：
 * 1. 通过 Overpass API 查询当前位置周围的道路矢量数据
 * 2. 在卫星图上绘制道路 Polyline 叠加层
 * 3. 大路（motorway/trunk/双向4车道以上）加宽 + 中间画双黄线
 * 4. 提供 GPS 坐标吸附到最近道路的功能
 */
class RoadOverlayManager(
    private val map: MapView,
    private val handler: Handler
) {
    data class RoadSegment(
        val points: List<GeoPoint>,
        val isMajor: Boolean = false  // motorway/trunk 或双向4车道以上
    )

    private var cachedRoads: List<RoadSegment> = emptyList()
    private var lastQueryLat = 0.0
    private var lastQueryLng = 0.0
    private var queryInProgress = false

    private val regionCache = mutableMapOf<String, List<RoadSegment>>()
    private val CACHE_GRID_SIZE_M = 1000.0
    private val queryRadiusM = 800.0
    private val reQueryDistanceM = 400.0

    private val roadPolylines = mutableListOf<Polyline>()
    private val polylineTypes = HashMap<Polyline, String>()  // polyline -> "normal"/"major"/"center"
    private var isShowing = false
    private val MIN_ZOOM_FOR_ROADS = 13.0
    private var isVisible = false

    // === 缩放控制 ===
    fun updateZoomLevel(zoom: Double) {
        if (!isShowing) return
        val shouldShow = zoom >= MIN_ZOOM_FOR_ROADS
        if (shouldShow != isVisible) {
            isVisible = shouldShow
            if (isVisible) {
                roadPolylines.forEach { map.overlays.add(it) }
            } else {
                roadPolylines.forEach { map.overlays.remove(it) }
            }
            map.invalidate()
        }
        // 动态调整线宽
        if (isVisible) {
            val baseW = ((zoom - MIN_ZOOM_FOR_ROADS) / (18.0 - MIN_ZOOM_FOR_ROADS) * 15.0).toFloat().coerceIn(1f, 15f)
            roadPolylines.forEach { pl ->
                when (polylineTypes[pl]) {
                    "center" -> pl.outlinePaint.strokeWidth = (baseW * 0.12f).coerceAtLeast(0.5f)
                    "major" -> pl.outlinePaint.strokeWidth = baseW * 1.5f
                    else -> pl.outlinePaint.strokeWidth = baseW
                }
            }
            map.invalidate()
        }
    }

    fun show() {
        if (isShowing) return
        isShowing = true
        isVisible = map.zoomLevelDouble >= MIN_ZOOM_FOR_ROADS
        if (roadPolylines.isNotEmpty() && isVisible) {
            roadPolylines.forEach { map.overlays.add(it) }
            map.invalidate()
        } else if (cachedRoads.isNotEmpty()) {
            updateRoadPolylines(cachedRoads)
        }
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        if (isVisible) {
            roadPolylines.forEach { map.overlays.remove(it) }
            map.invalidate()
        }
        isVisible = false
    }

    fun isVisible() = isVisible

    fun updateForPosition(lat: Double, lng: Double) {
        if (queryInProgress) return
        val gridKey = getGridKey(lat, lng)
        val cachedForRegion = regionCache[gridKey]
        if (cachedForRegion != null) {
            if (cachedRoads !== cachedForRegion) {
                cachedRoads = cachedForRegion
                updateRoadPolylines(cachedForRegion)
            }
            return
        }
        val dist = haversine(lastQueryLat, lastQueryLng, lat, lng)
        if (dist < reQueryDistanceM && cachedRoads.isNotEmpty()) return
        queryInProgress = true
        lastQueryLat = lat
        lastQueryLng = lng
        Thread {
            try {
                val roads = queryOverpassRoads(lat, lng, queryRadiusM)
                handler.post {
                    regionCache[gridKey] = roads
                    cachedRoads = roads
                    updateRoadPolylines(roads)
                    queryInProgress = false
                }
            } catch (_: Exception) {
                handler.post { queryInProgress = false }
            }
        }.start()
    }

    private fun getGridKey(lat: Double, lng: Double): String {
        val gridLat = (lat * 1000 / (CACHE_GRID_SIZE_M / 111320.0)).toInt()
        val gridLng = (lng * 1000 / (CACHE_GRID_SIZE_M / (111320.0 * cos(Math.toRadians(lat))))).toInt()
        return "$gridLat,$gridLng"
    }

    private fun queryOverpassRoads(lat: Double, lng: Double, radiusM: Double): List<RoadSegment> {
        val query = """
            [out:json][timeout:10];
            way["highway"~"^(motorway|trunk|primary|secondary|tertiary|residential|unclassified|living_street|service|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"]
            (around:$radiusM,$lat,$lng);
            out tags geom;
        """.trimIndent()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://overpass-api.de/api/interpreter?data=$encoded")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "LastFreedom/5.7")
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        val response = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return parseOverpassResponse(response)
    }

    private fun parseOverpassResponse(json: String): List<RoadSegment> {
        val roads = mutableListOf<RoadSegment>()
        try {
            val root = JSONObject(json)
            val elements = root.getJSONArray("elements")
            for (i in 0 until elements.length()) {
                val way = elements.getJSONObject(i)
                if (!way.has("geometry")) continue
                val tags = way.optJSONObject("tags") ?: continue
                val highwayType = tags.optString("highway", "unclassified")
                val lanes = tags.optString("lanes", "0").toIntOrNull() ?: 0
                val oneway = tags.optString("oneway", "no") == "yes"

                // 判断是否大路：motorway/trunk，或双向4车道以上
                val isMajor = highwayType == "motorway" || highwayType == "motorway_link"
                        || highwayType == "trunk" || highwayType == "trunk_link"
                        || (!oneway && lanes >= 4)

                val geom = way.getJSONArray("geometry")
                val points = mutableListOf<GeoPoint>()
                for (j in 0 until geom.length()) {
                    val node = geom.getJSONObject(j)
                    points.add(GeoPoint(node.getDouble("lat"), node.getDouble("lon")))
                }
                if (points.size >= 2) {
                    roads.add(RoadSegment(points, isMajor))
                }
            }
        } catch (_: Exception) {}
        return roads
    }

    private fun updateRoadPolylines(roads: List<RoadSegment>) {
        if (isVisible) {
            roadPolylines.forEach { map.overlays.remove(it) }
        }
        roadPolylines.clear()
        polylineTypes.clear()
        cachedRoads = roads

        val zoom = map.zoomLevelDouble
        val baseW = ((zoom - MIN_ZOOM_FOR_ROADS) / (18.0 - MIN_ZOOM_FOR_ROADS) * 15.0).toFloat().coerceIn(1f, 15f)

        // 先画大路（上层），再画普通路（底层）
        val ordinary = roads.filter { !it.isMajor }
        val major = roads.filter { it.isMajor }

        // 普通路
        for (seg in ordinary) {
            val polyline = Polyline().apply {
                setPoints(seg.points)
                outlinePaint.color = 0xAAFFAA00.toInt()
                outlinePaint.strokeWidth = baseW
                outlinePaint.isAntiAlias = true
            }
            roadPolylines.add(polyline)
            polylineTypes[polyline] = "normal"
            if (isVisible) map.overlays.add(polyline)
        }

        // 大路：加宽填充
        for (seg in major) {
            val polyline = Polyline().apply {
                setPoints(seg.points)
                outlinePaint.color = 0xAAFFAA00.toInt()
                outlinePaint.strokeWidth = baseW * 1.5f
                outlinePaint.isAntiAlias = true
            }
            roadPolylines.add(polyline)
            polylineTypes[polyline] = "major"
            if (isVisible) map.overlays.add(polyline)
        }

        // 大路：中间双黄线（浅黑色细线）
        for (seg in major) {
            val centerLine = Polyline().apply {
                setPoints(seg.points)
                outlinePaint.color = 0x99000000.toInt()
                outlinePaint.strokeWidth = (baseW * 0.12f).coerceAtLeast(0.5f)
                outlinePaint.isAntiAlias = true
            }
            roadPolylines.add(centerLine)
            polylineTypes[centerLine] = "center"
            if (isVisible) map.overlays.add(centerLine)
        }

        map.invalidate()
    }

    // === 道路吸附 ===
    fun snapToRoad(lat: Double, lng: Double): GeoPoint {
        if (cachedRoads.isEmpty()) return GeoPoint(lat, lng)
        var minDist = Double.MAX_VALUE
        var nearestPoint = GeoPoint(lat, lng)
        for (segment in cachedRoads) {
            for (i in 0 until segment.points.size - 1) {
                val p = nearestPointOnSegment(
                    lat, lng,
                    segment.points[i].latitude, segment.points[i].longitude,
                    segment.points[i + 1].latitude, segment.points[i + 1].longitude
                )
                val dist = haversine(lat, lng, p.latitude, p.longitude)
                if (dist < minDist) {
                    minDist = dist
                    nearestPoint = p
                }
            }
        }
        return if (minDist < 50.0) nearestPoint else GeoPoint(lat, lng)
    }

    private fun nearestPointOnSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): GeoPoint {
        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-12) return GeoPoint(ax, ay)
        var t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        t = t.coerceIn(0.0, 1.0)
        return GeoPoint(ax + t * dx, ay + t * dy)
    }

    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun destroy() {
        roadPolylines.forEach { map.overlays.remove(it) }
        roadPolylines.clear()
        polylineTypes.clear()
        cachedRoads = emptyList()
    }
}
