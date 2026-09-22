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
 * 2. 在卫星图上绘制道路 Polyline 叠加层（基于真实路宽换算）
 * 3. 提供 GPS 坐标吸附到最近道路的功能
 */
class RoadOverlayManager(
    private val map: MapView,
    private val handler: Handler
) {
    data class RoadSegment(
        val points: List<GeoPoint>,
        val highwayType: String,
        val lanes: Int = 0,
        val oneway: Boolean = false,
        val width: Double = 0.0
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
    private var isShowing = false
    private val MIN_ZOOM_FOR_ROADS = 13.0
    private var isVisible = false

    // === 真实路宽默认值（米，基于卫星实测）===
    private fun roadRealWidthM(type: String): Double = when (type) {
        "motorway" -> 70.0
        "motorway_link" -> 25.0
        "trunk" -> 65.0
        "trunk_link" -> 20.0
        "primary" -> 40.0
        "primary_link" -> 15.0
        "secondary" -> 30.0
        "secondary_link" -> 12.0
        "tertiary" -> 14.0
        "tertiary_link" -> 8.0
        "residential" -> 6.0
        "living_street" -> 5.0
        "service" -> 4.0
        else -> 5.0
    }

    private fun roadFillColor(type: String): Int = when (type) {
        "motorway", "motorway_link" -> 0x88FF4444.toInt()
        "trunk", "trunk_link" -> 0x88FF7722.toInt()
        "primary", "primary_link" -> 0x88FFAA00.toInt()
        "secondary", "secondary_link" -> 0x88FFDD00.toInt()
        "tertiary", "tertiary_link" -> 0x8888DD44.toInt()
        "residential", "living_street" -> 0x8844AAFF.toInt()
        "service" -> 0x88AAAAAA.toInt()
        else -> 0x88CCAA00.toInt()
    }

    private fun roadBorderColor(type: String): Int = when (type) {
        "motorway", "motorway_link" -> 0xCCBB0000.toInt()
        "trunk", "trunk_link" -> 0xCCBB4400.toInt()
        "primary", "primary_link" -> 0xCCBB7700.toInt()
        "secondary", "secondary_link" -> 0xCCBBAA00.toInt()
        "tertiary", "tertiary_link" -> 0xCC559922.toInt()
        "residential", "living_street" -> 0xCC2277BB.toInt()
        "service" -> 0xCC777777.toInt()
        else -> 0xCC997700.toInt()
    }

    // 米→像素换算
    private fun metersToPixels(meters: Double, lat: Double, zoom: Double): Float {
        val mpp = 156543.03392 * cos(Math.toRadians(lat)) / (1 shl zoom.toInt()).toDouble()
        return (meters / mpp).toFloat().coerceAtLeast(1.5f)
    }

    // 判断是否画双线（双向分隔车道）
    private fun isDualCarriageway(seg: RoadSegment): Boolean {
        if (seg.highwayType == "motorway" || seg.highwayType == "trunk") return true
        if (!seg.oneway && seg.lanes >= 4) return true
        return false
    }

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
        if (isVisible && cachedRoads.isNotEmpty()) {
            rebuildAllPolylines()
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
            rebuildAllPolylines()
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
                if (isVisible) rebuildAllPolylines()
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
                    if (isVisible) rebuildAllPolylines()
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
                val width = tags.optString("width", "0").toDoubleOrNull() ?: 0.0
                val geom = way.getJSONArray("geometry")
                val points = mutableListOf<GeoPoint>()
                for (j in 0 until geom.length()) {
                    val node = geom.getJSONObject(j)
                    points.add(GeoPoint(node.getDouble("lat"), node.getDouble("lon")))
                }
                if (points.size >= 2) {
                    roads.add(RoadSegment(points, highwayType, lanes, oneway, width))
                }
            }
        } catch (_: Exception) {}
        return roads
    }

    // === 基于真实路宽重建所有 Polyline ===
    private fun rebuildAllPolylines() {
        roadPolylines.forEach { map.overlays.remove(it) }
        roadPolylines.clear()
        val zoom = map.zoomLevelDouble
        if (zoom < MIN_ZOOM_FOR_ROADS) return
        val sortedRoads = cachedRoads.sortedBy { roadRealWidthM(it.highwayType) }
        for (segment in sortedRoads) {
            val realWidthM = if (segment.width > 0) segment.width else roadRealWidthM(segment.highwayType)
            val lat = segment.points.first().latitude
            val totalWidthPx = metersToPixels(realWidthM, lat, zoom)
            if (isDualCarriageway(segment) && totalWidthPx > 8f) {
                val halfWidth = totalWidthPx * 0.4f
                val offsetM = realWidthM * 0.25
                for (side in listOf(-1.0, 1.0)) {
                    val offsetPoints = offsetPolyline(segment.points, offsetM * side, lat)
                    addRoadPolyline(offsetPoints, halfWidth, segment.highwayType)
                }
            } else {
                addRoadPolyline(segment.points, totalWidthPx, segment.highwayType)
            }
        }
        map.invalidate()
    }

    private fun addRoadPolyline(points: List<GeoPoint>, widthPx: Float, type: String) {
        // 边线（深色描边）
        val border = Polyline().apply {
            setPoints(points)
            outlinePaint.color = roadBorderColor(type)
            outlinePaint.strokeWidth = widthPx + 2f
            outlinePaint.isAntiAlias = true
            outlinePaint.style = Paint.Style.STROKE
        }
        roadPolylines.add(border)
        if (isVisible) map.overlays.add(border)
        // 填充线（半透明彩色）
        val fill = Polyline().apply {
            setPoints(points)
            outlinePaint.color = roadFillColor(type)
            outlinePaint.strokeWidth = widthPx
            outlinePaint.isAntiAlias = true
            outlinePaint.style = Paint.Style.STROKE
        }
        roadPolylines.add(fill)
        if (isVisible) map.overlays.add(fill)
    }

    // 偏移 Polyline（用于双线车道）
    private fun offsetPolyline(points: List<GeoPoint>, offsetM: Double, refLat: Double): List<GeoPoint> {
        if (points.size < 2) return points
        val result = mutableListOf<GeoPoint>()
        for (i in points.indices) {
            val prev = if (i > 0) points[i - 1] else points[i]
            val next = if (i < points.size - 1) points[i + 1] else points[i]
            val dx = next.longitude - prev.longitude
            val dy = next.latitude - prev.latitude
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-10) { result.add(points[i]); continue }
            val nx = -dy / len
            val ny = dx / len
            val mPerDegLat = 111320.0
            val mPerDegLng = 111320.0 * cos(Math.toRadians(refLat))
            val offsetLat = points[i].latitude + ny * offsetM / mPerDegLat
            val offsetLng = points[i].longitude + nx * offsetM / mPerDegLng
            result.add(GeoPoint(offsetLat, offsetLng))
        }
        return result
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
        cachedRoads = emptyList()
    }
}
