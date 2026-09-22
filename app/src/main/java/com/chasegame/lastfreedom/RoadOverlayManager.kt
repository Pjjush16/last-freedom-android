package com.chasegame.lastfreedom

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
 * 3. 提供 GPS 坐标吸附到最近道路的功能
 */
class RoadOverlayManager(
    private val map: MapView,
    private val handler: Handler
) {
    // 道路类型数据
    data class RoadSegment(val points: List<GeoPoint>, val highwayType: String)

    // 缓存的道路段
    private var cachedRoads: List<RoadSegment> = emptyList()
    private var lastQueryLat = 0.0
    private var lastQueryLng = 0.0
    private var queryInProgress = false

    // 区域缓存
    private val regionCache = mutableMapOf<String, List<RoadSegment>>()
    private val CACHE_GRID_SIZE_M = 1000.0  // 1km 网格

    // 查询半径（米）
    private val queryRadiusM = 800.0
    // 最小移动距离才重新查询（米）
    private val reQueryDistanceM = 400.0

    // Polyline 叠加层
    private val roadPolylines = mutableListOf<Polyline>()
    private var isShowing = false
    private val MIN_ZOOM_FOR_ROADS = 13.0
    private var isVisible = false

    // 道路类型 → 颜色/宽度映射
    private fun roadColor(type: String): Int = when (type) {
        "motorway", "motorway_link" -> 0xCCFF4444.toInt()  // 高速：红色
        "trunk", "trunk_link" -> 0xCCFF6600.toInt()         // 快速路：橙红
        "primary", "primary_link" -> 0xCCFFAA00.toInt()     // 主干道：橙色
        "secondary", "secondary_link" -> 0xCCFFDD00.toInt() // 次干道：黄色
        "tertiary", "tertiary_link" -> 0xCC88DD44.toInt()   // 支路：浅绿
        "residential", "living_street" -> 0xCC44AAFF.toInt()// 居民区道路：蓝色
        "service" -> 0xCCAAAAAA.toInt()                      // 服务道路：灰色
        else -> 0xAAFFAA00.toInt()                           // 其他：半透明橙
    }

    private fun roadBaseWidth(type: String): Float = when (type) {
        "motorway", "motorway_link" -> 14f
        "trunk", "trunk_link" -> 12f
        "primary", "primary_link" -> 10f
        "secondary", "secondary_link" -> 8f
        "tertiary", "tertiary_link" -> 6f
        "residential", "living_street" -> 4f
        "service" -> 3f
        else -> 4f
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
        // 动态调整路网线宽：按道路类型基础宽度 + 缩放级别缩放
        if (isVisible) {
            val zoomFactor = ((zoom - MIN_ZOOM_FOR_ROADS) / (18.0 - MIN_ZOOM_FOR_ROADS)).toFloat().coerceIn(0.3f, 1.5f)
            roadPolylines.forEachIndexed { index, polyline ->
                val type = if (index < cachedRoads.size) cachedRoads[index].highwayType else "unclassified"
                polyline.outlinePaint.strokeWidth = roadBaseWidth(type) * zoomFactor
            }
            map.invalidate()
        }
    }

    // === 显示/隐藏路网叠加 ===

    fun show() {
        if (isShowing) return
        isShowing = true
        isVisible = map.zoomLevelDouble >= MIN_ZOOM_FOR_ROADS
        // 如果有已构建的 polylines，直接加回地图
        if (roadPolylines.isNotEmpty() && isVisible) {
            roadPolylines.forEach { map.overlays.add(it) }
            map.invalidate()
        } else if (cachedRoads.isNotEmpty()) {
            // polylines 被清空了但道路数据还在，重建
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

    // === 查询路网数据（带区域缓存）===

    fun updateForPosition(lat: Double, lng: Double) {
        if (queryInProgress) return

        // 计算当前网格坐标
        val gridKey = getGridKey(lat, lng)

        // 检查区域缓存
        val cachedForRegion = regionCache[gridKey]
        if (cachedForRegion != null) {
            if (cachedRoads !== cachedForRegion) {
                cachedRoads = cachedForRegion
                updateRoadPolylines(cachedRoads)
            }
            return
        }

        // 未命中缓存，检查距离阈值
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

    /**
     * 计算网格坐标键（1km x 1km 网格）
     */
    private fun getGridKey(lat: Double, lng: Double): String {
        val gridLat = (lat * 1000 / (CACHE_GRID_SIZE_M / 111320.0)).toInt()
        val gridLng = (lng * 1000 / (CACHE_GRID_SIZE_M / (111320.0 * cos(Math.toRadians(lat))))).toInt()
        return "$gridLat,$gridLng"
    }

    /**
     * Overpass API 查询道路数据
     * 返回道路段列表，每段是一系列 GeoPoint
     */
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

                val highwayType = way.optJSONObject("tags")?.optString("highway", "unclassified") ?: "unclassified"

                val geom = way.getJSONArray("geometry")
                val points = mutableListOf<GeoPoint>()
                for (j in 0 until geom.length()) {
                    val node = geom.getJSONObject(j)
                    val lat = node.getDouble("lat")
                    val lon = node.getDouble("lon")
                    points.add(GeoPoint(lat, lon))
                }
                if (points.size >= 2) {
                    roads.add(RoadSegment(points, highwayType))
                }
            }
        } catch (_: Exception) {}
        return roads
    }

    /**
     * 更新 Polyline 叠加层
     */
    private fun updateRoadPolylines(roads: List<RoadSegment>) {
        // 移除旧的
        if (isVisible) {
            roadPolylines.forEach { map.overlays.remove(it) }
        }
        roadPolylines.clear()

        cachedRoads = roads

        // 按类型分层渲染：先画小路（底层），再画大路（上层）
        val sortedRoads = roads.sortedBy { roadBaseWidth(it.highwayType) }

        for (segment in sortedRoads) {
            val polyline = Polyline().apply {
                setPoints(segment.points)
                outlinePaint.color = roadColor(segment.highwayType)
                outlinePaint.strokeWidth = roadBaseWidth(segment.highwayType)
                outlinePaint.isAntiAlias = true
            }
            roadPolylines.add(polyline)
            if (isVisible) {
                map.overlays.add(polyline)
            }
        }

        map.invalidate()
    }

    // === 道路吸附 ===

    /**
     * 将 GPS 坐标吸附到最近的道路点
     * 返回吸附后的坐标，如果没有道路数据则返回原始坐标
     */
    fun snapToRoad(lat: Double, lng: Double): GeoPoint {
        if (cachedRoads.isEmpty()) return GeoPoint(lat, lng)

        var minDist = Double.MAX_VALUE
        var nearestPoint = GeoPoint(lat, lng)

        for (segment in cachedRoads) {
            val roadPoints = segment.points
            for (i in 0 until roadPoints.size - 1) {
                val p = nearestPointOnSegment(
                    lat, lng,
                    roadPoints[i].latitude, roadPoints[i].longitude,
                    roadPoints[i + 1].latitude, roadPoints[i + 1].longitude
                )
                val dist = haversine(lat, lng, p.latitude, p.longitude)
                if (dist < minDist) {
                    minDist = dist
                    nearestPoint = p
                }
            }
        }

        // 只有距离 < 50 米时才吸附（太远说明不在路上）
        return if (minDist < 50.0) nearestPoint else GeoPoint(lat, lng)
    }

    /**
     * 计算点到线段的最短距离点
     */
    private fun nearestPointOnSegment(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double
    ): GeoPoint {
        val dx = bx - ax
        val dy = by - ay
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
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun destroy() {
        roadPolylines.forEach { map.overlays.remove(it) }
        roadPolylines.clear()
        cachedRoads = emptyList()
    }
}
