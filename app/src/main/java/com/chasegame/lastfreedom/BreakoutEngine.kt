package com.chasegame.lastfreedom

import android.os.Handler
import android.os.Looper
import kotlin.math.*

/**
 * 突围引擎 v3 — AI 警力部署系统（精简版）
 *
 * 核心机制：
 * 1. 开局预部署：按终点反推 3~5 条入径，每条入径放巡逻车/路障
 * 2. 警力重心 + 惯性漂移：重心向玩家漂移，漂移率受置信度影响
 * 3. 置信度 (0~1)：反复变向降低置信度→兵力分散回主要入口
 * 4. 收缩半径：离终点越近包围圈越小，自动递增难度
 * 5. 三层可见性：盲堵→精准封锁→前方拦截车生成
 * 6. 失败：被拦截/超时
 * 7. 曲折度系数：路网距离÷直线距离
 *
 * v3 变更：删除高压圈(HIGH_PRESS)、弃车逃跑(ABANDONED)、封锁(BLOCKED)状态
 */
class BreakoutEngine(
    private val handler: Handler
) {
    enum class State {
        IDLE, PICKING, BREAKOUT, VICTORY, ARRESTED, TIMEOUT
    }

    /** 封锁/路障节点 */
    data class Barricade(
        val lat: Double,
        val lng: Double,
        var type: BarricadeType,   // PATROL / BARRIER / INTERCEPT
        var active: Boolean = true,
        val routeIndex: Int = 0,    // 所属入径编号
        var spawnTime: Long = 0     // 生成时间
    )

    enum class BarricadeType { PATROL, BARRIER, INTERCEPT }

    /** 可见性级别 */
    enum class VisibilityLevel {
        BLIND,       // 盲堵：只知道大致扇区
        PRECISE,     // 精准封锁：知道方向，路障往实际路上挪
        INTERCEPT    // 前方拦截：直接在下一个路口生成拦截车
    }

    /** 游戏状态变更回调 */
    var onStateChanged: ((State) -> Unit)? = null

    /** 路障/巡逻车变更回调（用于地图渲染） */
    var onBarricadesChanged: ((List<Barricade>) -> Unit)? = null

    /** HUD 数据回调 */
    var onHudUpdate: ((HudData) -> Unit)? = null

    data class HudData(
        val confidence: Double,
        val shrinkRadius: Double,
        val distToDest: Double,
        val visibility: VisibilityLevel,
        val elapsedSec: Int,
        val timeLimitSec: Int,
        val tortuosityIndex: Double,
        val barricadeCount: Int,
        val state: State
    )

    // === 游戏状态 ===
    var state = State.IDLE
        private set

    // 终点
    var destLat = 0.0; private set
    var destLng = 0.0; private set

    // 起点
    private var startLat = 0.0
    private var startLng = 0.0

    // === 警力重心 ===
    var gravityLat = 0.0; private set
    var gravityLng = 0.0; private set

    // === 置信度 (0.0 ~ 1.0) ===
    var confidence = 0.3; private set

    // === 收缩半径 (米) ===
    var shrinkRadius = 0.0; private set

    // === 路障列表 ===
    private val barricades = mutableListOf<Barricade>()

    // === 可见性 ===
    var visibilityLevel = VisibilityLevel.BLIND
        private set
    var isVisible = false
        private set
    private var visibleDuration = 0.0
    private var invisibleDuration = 0.0

    // === 计时 ===
    private var gameStartTime = 0L
    private var lastUpdateTime = 0L
    var elapsedSec = 0; private set
    var timeLimitSec = 300  // 默认5分钟

    fun getElapsedSeconds(): Long = elapsedSec.toLong()
    fun getTotalDistanceM(): Double = pathDistance

    // === 方向追踪 ===
    private var lastBearing = 0.0
    private var hasLastBearing = false
    private var directionChangeCount = 0

    // === 曲折度 ===
    var tortuosityIndex = 1.0; private set
    private var pathDistance = 0.0  // 累计行走距离

    // === 拦截车冷却 ===
    private var lastInterceptSpawnTime = 0L
    private val INTERCEPT_COOLDOWN_MS = 8000L  // 拦截车最少8秒生成一辆

    // === 入径方向角（从终点向外辐射） ===
    private val approachBearings = mutableListOf<Double>()

    // === 游戏循环 ===
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (state == State.BREAKOUT) {
                tickUpdate()
            }
            handler.postDelayed(this, 50) // 20fps
        }
    }

    // ================================================================
    //  游戏控制
    // ================================================================

    fun startPicking() {
        state = State.PICKING
        onStateChanged?.invoke(state)
    }

    fun startBreakout(playerLat: Double, playerLng: Double, destLat: Double, destLng: Double) {
        this.startLat = playerLat
        this.startLng = playerLng
        this.destLat = destLat
        this.destLng = destLng

        // 重置所有状态
        confidence = 0.3
        visibleDuration = 0.0
        invisibleDuration = 0.0
        hasLastBearing = false
        directionChangeCount = 0
        pathDistance = 0.0
        visibilityLevel = VisibilityLevel.BLIND
        lastInterceptSpawnTime = 0L
        barricades.clear()

        // 初始化收缩半径 = 起点到终点距离
        val straightDist = haversine(startLat, startLng, destLat, destLng)
        shrinkRadius = straightDist

        // 计算曲折度（基于路网距离，简化版：起点到终点的直线距离 × 1.5 模拟路网）
        tortuosityIndex = 1.5  // 初始估计，后续用实际路径更新

        // 根据曲折度调整时限
        timeLimitSec = (180 + straightDist / 50).toInt().coerceIn(180, 600) // 3~10分钟

        // 初始化警力重心在终点
        gravityLat = destLat
        gravityLng = destLng

        // 计算入径并预部署
        calculateApproachRoutes()
        preDeploy()

        // 开始
        state = State.BREAKOUT
        gameStartTime = System.currentTimeMillis()
        lastUpdateTime = gameStartTime
        handler.post(updateRunnable)

        onStateChanged?.invoke(state)
        emitBarricades()
        emitHud()
    }

    /** 更新玩家位置 */
    fun updatePlayer(playerLat: Double, playerLng: Double, bearing: Double) {
        if (state != State.BREAKOUT) return

        val now = System.currentTimeMillis()
        val dt = (now - lastUpdateTime) / 1000.0
        lastUpdateTime = now

        if (dt <= 0.0 || dt > 2.0) return

        elapsedSec = ((now - gameStartTime) / 1000).toInt()

        // === 累计路径距离 ===
        if (hasLastBearing) {
            val stepDist = haversine(playerLat, playerLng, playerLat, playerLng)
            pathDistance += stepDist
        }

        // === 1. 方向变化检测 → 影响置信度 ===
        if (hasLastBearing) {
            val dirChange = abs(normalizeAngle(bearing - lastBearing))
            if (dirChange > 60.0) {
                directionChangeCount++
                if (dirChange > 120.0) {
                    confidence *= 0.6
                } else {
                    confidence *= 0.85
                }
            }
        }
        lastBearing = bearing
        hasLastBearing = true

        // === 2. 可见性 → 置信度微调 + 可见性级别 ===
        updateVisibility(dt)

        // === 3. 警力重心漂移 ===
        updateGravityCenter(playerLat, playerLng, dt)

        // === 4. 收缩半径 ===
        val distToDest = haversine(playerLat, playerLng, destLat, destLng)
        updateShrinkRadius(distToDest)

        // === 5. 动态路障生成（根据可见性级别）===
        updateDynamicBarricades(playerLat, playerLng, bearing, now)

        // === 6. 胜利条件 ===
        if (distToDest < 100.0) {
            state = State.VICTORY
            handler.removeCallbacks(updateRunnable)
            onStateChanged?.invoke(state)
            emitHud()
            return
        }

        // === 7. 被捕检测 ===
        if (checkArrest(playerLat, playerLng)) {
            return
        }

        // === 8. 超时检测 ===
        if (elapsedSec >= timeLimitSec) {
            state = State.TIMEOUT
            handler.removeCallbacks(updateRunnable)
            onStateChanged?.invoke(state)
            emitHud()
            return
        }

        // === 9. 更新曲折度 ===
        val straightDist = haversine(startLat, startLng, destLat, destLng)
        if (straightDist > 0) {
            tortuosityIndex = pathDistance / straightDist
        }

        emitHud()
    }

    // ================================================================
    //  入径计算 & 预部署
    // ================================================================

    /** 从终点向外辐射，计算 3~5 条主要入径方向 */
    private fun calculateApproachRoutes() {
        approachBearings.clear()

        // 起点到终点的方向作为主入径
        val mainBearing = bearing(startLat, startLng, destLat, destLng)
        approachBearings.add(mainBearing)

        // 在主入径两侧各加 60°、120° 作为辅助入径
        approachBearings.add(normalizeBearing(mainBearing + 60.0))
        approachBearings.add(normalizeBearing(mainBearing - 60.0))

        // 如果距离较远，再加两条
        val dist = haversine(startLat, startLng, destLat, destLng)
        if (dist > 2000) {
            approachBearings.add(normalizeBearing(mainBearing + 120.0))
            approachBearings.add(normalizeBearing(mainBearing - 120.0))
        }
    }

    /** 在每条入径上预部署巡逻车和路障 */
    private fun preDeploy() {
        val dist = haversine(startLat, startLng, destLat, destLng)
        val deployDist = (dist * 0.6).coerceIn(500.0, 3000.0)

        for ((idx, approachBearing) in approachBearings.withIndex()) {
            val reverseBearing = normalizeBearing(approachBearing + 180.0)

            // 在终点前方 1~2 公里处放巡逻车
            val patrolDist = deployDist * (0.7 + Math.random() * 0.3)
            val (patrolLat, patrolLng) = offsetPoint(destLat, destLng, reverseBearing, patrolDist)
            barricades.add(Barricade(
                lat = patrolLat, lng = patrolLng,
                type = BarricadeType.PATROL,
                active = true,
                routeIndex = idx,
                spawnTime = System.currentTimeMillis()
            ))

            // 在终点前方 500~800 米处放路障
            val barrierDist = (deployDist * 0.4).coerceIn(300.0, 1000.0)
            val (barrierLat, barrierLng) = offsetPoint(destLat, destLng, reverseBearing, barrierDist)
            barricades.add(Barricade(
                lat = barrierLat, lng = barrierLng,
                type = BarricadeType.BARRIER,
                active = true,
                routeIndex = idx,
                spawnTime = System.currentTimeMillis()
            ))
        }

        emitBarricades()
    }

    // ================================================================
    //  核心更新逻辑
    // ================================================================

    /** 可见性更新：三级递进 */
    private fun updateVisibility(dt: Double) {
        if (isVisible) {
            visibleDuration += dt
            invisibleDuration = 0.0

            visibilityLevel = when {
                visibleDuration > 8.0 -> VisibilityLevel.INTERCEPT
                visibleDuration > 3.0 -> VisibilityLevel.PRECISE
                else -> VisibilityLevel.BLIND
            }

            confidence += 0.08 * dt
        } else {
            invisibleDuration += dt
            visibleDuration = max(0.0, visibleDuration - dt * 0.5)

            confidence -= 0.02 * dt

            if (invisibleDuration > 10.0) {
                visibilityLevel = VisibilityLevel.BLIND
            }
        }

        confidence = confidence.coerceIn(0.0, 1.0)
    }

    /** 警力重心惯性漂移 */
    private fun updateGravityCenter(playerLat: Double, playerLng: Double, dt: Double) {
        val driftRate = 0.05 + confidence * 0.15

        val deltaLat = (playerLat - gravityLat) * driftRate * dt
        val deltaLng = (playerLng - gravityLng) * driftRate * dt
        gravityLat += deltaLat
        gravityLng += deltaLng

        // 置信度低时，路障向入径分散
        if (confidence < 0.4) {
            redistributeBarricades()
        }
    }

    /** 收缩半径：只缩不扩 */
    private fun updateShrinkRadius(distToDest: Double) {
        val targetRadius = distToDest * 1.5
        shrinkRadius = min(shrinkRadius, max(targetRadius, 200.0))
    }

    /** 动态路障生成（精准封锁 & 拦截车） */
    private fun updateDynamicBarricades(playerLat: Double, playerLng: Double, bearing: Double, now: Long) {
        // 精准封锁：把盲堵路障往玩家实际方向挪
        if (visibilityLevel >= VisibilityLevel.PRECISE && confidence > 0.5) {
            val playerBearingToDest = bearing(playerLat, playerLng, destLat, destLng)

            val blockDist = 500.0 + confidence * 500.0
            val (blockLat, blockLng) = offsetPoint(playerLat, playerLng, playerBearingToDest, blockDist)

            val tooClose = barricades.any {
                haversine(it.lat, it.lng, blockLat, blockLng) < 200.0 && it.active
            }
            if (!tooClose) {
                barricades.add(Barricade(
                    lat = blockLat, lng = blockLng,
                    type = BarricadeType.BARRIER,
                    active = true,
                    routeIndex = -1,
                    spawnTime = now
                ))
                emitBarricades()
            }
        }

        // 前方拦截车：直接在下个路口生成
        if (visibilityLevel >= VisibilityLevel.INTERCEPT && confidence > 0.7) {
            if (now - lastInterceptSpawnTime > INTERCEPT_COOLDOWN_MS) {
                val playerBearingToDest = bearing(playerLat, playerLng, destLat, destLng)
                val interceptDist = 300.0 + confidence * 400.0
                val (intLat, intLng) = offsetPoint(playerLat, playerLng, playerBearingToDest, interceptDist)

                barricades.add(Barricade(
                    lat = intLat, lng = intLng,
                    type = BarricadeType.INTERCEPT,
                    active = true,
                    routeIndex = -2,
                    spawnTime = now
                ))
                lastInterceptSpawnTime = now
                emitBarricades()
            }
        }

        // 清理过期拦截车（超过 15 秒自动消失）
        val removed = barricades.removeAll {
            it.type == BarricadeType.INTERCEPT && (now - it.spawnTime) > 15000
        }
        if (removed) emitBarricades()
    }

    /** 置信度低时，路障回收入径 */
    private fun redistributeBarricades() {
        barricades.filter { it.routeIndex < 0 && it.type != BarricadeType.INTERCEPT }
            .forEach { it.active = false }
    }

    // ================================================================
    //  被捕检测
    // ================================================================

    /** 被捕检测 */
    private fun checkArrest(playerLat: Double, playerLng: Double): Boolean {
        for (b in barricades) {
            if (!b.active) continue
            val dist = haversine(playerLat, playerLng, b.lat, b.lng)
            val hitRange = when (b.type) {
                BarricadeType.PATROL -> 50.0
                BarricadeType.BARRIER -> 30.0
                BarricadeType.INTERCEPT -> 40.0
            }
            if (dist < hitRange) {
                state = State.ARRESTED
                handler.removeCallbacks(updateRunnable)
                onStateChanged?.invoke(state)
                emitHud()
                return true
            }
        }
        return false
    }

    /** 设置可见性（外部调用：被警车看到） */
    fun setVisibility(visible: Boolean) {
        isVisible = visible
    }

    /** 重新开始 */
    fun reset() {
        handler.removeCallbacks(updateRunnable)
        state = State.IDLE
        confidence = 0.3
        shrinkRadius = 0.0
        gravityLat = 0.0
        gravityLng = 0.0
        hasLastBearing = false
        isVisible = false
        visibleDuration = 0.0
        invisibleDuration = 0.0
        barricades.clear()
        approachBearings.clear()
        pathDistance = 0.0
        directionChangeCount = 0
        elapsedSec = 0
        onStateChanged?.invoke(state)
        emitBarricades()
    }

    /** 获取当前路障列表（只读） */
    fun getBarricades(): List<Barricade> = barricades.toList()

    /** 获取入径方向 */
    fun getApproachBearings(): List<Double> = approachBearings.toList()

    // ================================================================
    //  工具函数
    // ================================================================

    private fun tickUpdate() {
        val now = System.currentTimeMillis()
        val dt = (now - lastUpdateTime) / 1000.0
        if (dt > 0.5) {
            lastUpdateTime = now
        }
    }

    private fun emitBarricades() {
        onBarricadesChanged?.invoke(barricades.toList())
    }

    private fun emitHud() {
        onHudUpdate?.invoke(HudData(
            confidence = confidence,
            shrinkRadius = shrinkRadius,
            distToDest = shrinkRadius,
            visibility = visibilityLevel,
            elapsedSec = elapsedSec,
            timeLimitSec = timeLimitSec,
            tortuosityIndex = tortuosityIndex,
            barricadeCount = barricades.count { it.active },
            state = state
        ))
    }

    private fun normalizeAngle(angle: Double): Double {
        var a = angle % 360.0
        if (a > 180.0) a -= 360.0
        if (a < -180.0) a += 360.0
        return a
    }

    private fun normalizeBearing(b: Double): Double {
        var r = b % 360.0
        if (r < 0) r += 360.0
        return r
    }

    /** 两点间的方位角 */
    private fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLng = Math.toRadians(lng2 - lng1)
        val y = sin(dLng) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLng)
        return normalizeBearing(Math.toDegrees(atan2(y, x)))
    }

    /** 从一点沿方位角走指定距离 */
    private fun offsetPoint(lat: Double, lng: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val R = 6371000.0
        val br = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lng1 = Math.toRadians(lng)
        val d = distM / R

        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lng2 = lng1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))

        return Pair(Math.toDegrees(lat2), Math.toDegrees(lng2))
    }

    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
