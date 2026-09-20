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
    // 缓存的道路段（GeoPoint 列表）
    private var cachedRoads: List<List<GeoPoint>> = emptyList()
    private var lastQueryLat = 0.0
    private var lastQueryLng = 0.0
    private var queryInProgress = false

    // 查询半径（米）
    private val queryRadiusM = 800.0
    // 最小移动距离才重新查询（米）
    private val reQueryDistanceM = 400.0

    // Polyline 叠加层
    private val roadPolylines = mutableListOf<Polyline>()
    private var isShowing = false

    // === 显示/隐藏路网叠加 ===

    fun show() {
        if (isShowing) return
        isShowing = true
        roadPolylines.forEach { map.overlays.add(it) }
        map.invalidate()
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        roadPolylines.forEach { map.overlays.remove(it) }
        map.invalidate()
    }

    fun isVisible() = isShowing

    // === 查询路网数据 ===

    fun updateForPosition(lat: Double, lng: Double) {
        if (queryInProgress) return

        val dist = haversine(lastQueryLat, lastQueryLng, lat, lng)
        if (cachedRoads.isNotEmpty() && dist < reQueryDistanceM) return

        queryInProgress = true
        lastQueryLat = lat
        lastQueryLng = lng

        Thread {
            try {
                val roads = queryOverpassRoads(lat, lng, queryRadiusM)
                handler.post {
                    updateRoadPolylines(roads)
                    queryInProgress = false
                }
            } catch (_: Exception) {
                handler.post { queryInProgress = false }
            }
        }.start()
    }

    /**
     * Overpass API 查询道路数据
     * 返回道路段列表，每段是一系列 GeoPoint
     */
    private fun queryOverpassRoads(lat: Double, lng: Double, radiusM: Double): List<List<GeoPoint>> {
        val query = """
            [out:json][timeout:10];
            way["highway"~"^(motorway|trunk|primary|secondary|tertiary|residential|unclassified|living_street|service|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"]
            (around:$radiusM,$lat,$lng);
            out geom;
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

    private fun parseOverpassResponse(json: String): List<List<GeoPoint>> {
        val roads = mutableListOf<List<GeoPoint>>()
        try {
            val root = JSONObject(json)
            val elements = root.getJSONArray("elements")

            for (i in 0 until elements.length()) {
                val way = elements.getJSONObject(i)
                if (!way.has("geometry")) continue

                val geom = way.getJSONArray("geometry")
                val points = mutableListOf<GeoPoint>()
                for (j in 0 until geom.length()) {
                    val node = geom.getJSONObject(j)
                    val lat = node.getDouble("lat")
                    val lon = node.getDouble("lon")
                    points.add(GeoPoint(lat, lon))
                }
                if (points.size >= 2) {
                    roads.add(points)
                }
            }
        } catch (_: Exception) {}
        return roads
    }

    /**
     * 更新 Polyline 叠加层
     */
    private fun updateRoadPolylines(roads: List<List<GeoPoint>>) {
        // 移除旧的
        if (isShowing) {
            roadPolylines.forEach { map.overlays.remove(it) }
        }
        roadPolylines.clear()

        cachedRoads = roads

        // 创建新的 Polyline
        for (roadPoints in roads) {
            val polyline = Polyline().apply {
                setPoints(roadPoints)
                outlinePaint.color = 0xAAFFAA00.toInt() // 半透明橙色
                outlinePaint.strokeWidth = 4f
                outlinePaint.isAntiAlias = true
                // 半透明填充让路网在卫星图上可见
            }
            roadPolylines.add(polyline)
            if (isShowing) {
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

        for (roadPoints in cachedRoads) {
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
