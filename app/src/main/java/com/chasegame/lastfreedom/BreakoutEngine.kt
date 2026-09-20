package com.chasegame.lastfreedom

import android.os.Handler
import android.os.Looper
import kotlin.math.*

/**
 * 突围引擎 — AI 警力重心 + 置信度 + 收缩半径
 *
 * 核心变量：
 * 1. 警力重心 (gravityLat, gravityLng) — 有惯性地向玩家位置漂移
 * 2. 置信度 (confidence, 0.0~1.0) — 控制封锁精度
 * 3. 收缩半径 (shrinkRadius, 米) — 包围圈，只缩不扩
 *
 * 游戏状态：
 * - IDLE: 未开始
 * - PICKING: 选点中
 * - BREAKOUT: 突围进行中
 * - HIGH_PRESS: 高压区 (shrinkRadius < 500m)
 * - VICTORY: 胜利 (距离终点 < 100m)
 * - ARRESTED: 被捕 (撞路障 / shrinkRadius < 200m 且被拦截)
 */
class BreakoutEngine(
    private val handler: Handler
) {
    enum class State {
        IDLE, PICKING, BREAKOUT, HIGH_PRESS, VICTORY, ARRESTED
    }

    // 游戏状态
    var state = State.IDLE
        private set

    // 终点坐标
    var destLat = 0.0
        private set
    var destLng = 0.0
        private set

    // 起点坐标
    private var startLat = 0.0
    private var startLng = 0.0

    // 警力重心
    var gravityLat = 0.0
        private set
    var gravityLng = 0.0
        private set

    // 置信度 (0.0 ~ 1.0)
    var confidence = 0.3
        private set

    // 收缩半径 (米)
    var shrinkRadius = 0.0
        private set

    // 玩家上一次方向（用于检测变向）
    private var lastBearing = 0.0
    private var hasLastBearing = false

    // 可见性
    var isVisible = false
        private set
    private var visibleDuration = 0.0  // 连续可见时间(秒)

    // 上次更新时间
    private var lastUpdateTime = 0L

    // 状态监听器
    var onStateChanged: ((State) -> Unit)? = null

    // 游戏循环
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (state == State.BREAKOUT || state == State.HIGH_PRESS) {
                update()
            }
            handler.postDelayed(this, 50) // 20fps
        }
    }

    // === 游戏控制 ===

    /** 进入选点模式 */
    fun startPicking() {
        state = State.PICKING
        onStateChanged?.invoke(state)
    }

    /** 确认目的地，开始突围 */
    fun startBreakout(playerLat: Double, playerLng: Double, destLat: Double, destLng: Double) {
        this.startLat = playerLat
        this.startLng = playerLng
        this.destLat = destLat
        this.destLng = destLng

        // 初始化警力重心在终点
        gravityLat = destLat
        gravityLng = destLng

        // 初始化置信度
        confidence = 0.3

        // 初始化收缩半径 = 起点到终点的距离
        shrinkRadius = haversine(startLat, startLng, destLat, destLng)

        // 重置方向记录
        hasLastBearing = false
        visibleDuration = 0.0
        isVisible = false

        // 开始游戏循环
        state = State.BREAKOUT
        lastUpdateTime = System.currentTimeMillis()
        handler.post(updateRunnable)

        onStateChanged?.invoke(state)
    }

    /** 更新玩家位置和方向 */
    fun updatePlayer(playerLat: Double, playerLng: Double, bearing: Double) {
        if (state != State.BREAKOUT && state != State.HIGH_PRESS) return

        val now = System.currentTimeMillis()
        val dt = (now - lastUpdateTime) / 1000.0 // 秒
        lastUpdateTime = now

        if (dt <= 0.0 || dt > 1.0) return // 跳过异常帧

        // 1. 检测方向变化 → 降低置信度
        if (hasLastBearing) {
            val dirChange = abs(normalizeAngle(bearing - lastBearing))
            if (dirChange > 90.0) {
                confidence *= 0.7
            }
        }
        lastBearing = bearing
        hasLastBearing = true

        // 2. 更新置信度（可见性）
        if (isVisible) {
            visibleDuration += dt
            confidence += 0.1 * dt  // 每秒 +10%
        } else {
            visibleDuration = 0.0
            confidence -= 0.03 * dt // 每秒 -3%
        }
        confidence = confidence.coerceIn(0.0, 1.0)

        // 3. 更新警力重心（向玩家漂移）
        val driftRate = 0.05 + confidence * 0.15 // 5%~20%/秒
        val deltaLat = (playerLat - gravityLat) * driftRate * dt
        val deltaLng = (playerLng - gravityLng) * driftRate * dt
        gravityLat += deltaLat
        gravityLng += deltaLng

        // 4. 更新收缩半径
        val distToDest = haversine(playerLat, playerLng, destLat, destLng)
        val targetRadius = distToDest * 1.5
        shrinkRadius = min(shrinkRadius, max(targetRadius, 200.0)) // 最小200米

        // 5. 检查胜利条件
        if (distToDest < 100.0) {
            state = State.VICTORY
            handler.removeCallbacks(updateRunnable)
            onStateChanged?.invoke(state)
            return
        }

        // 6. 检查阶段切换
        val newState = when {
            shrinkRadius < 500.0 -> State.HIGH_PRESS
            else -> State.BREAKOUT
        }
        if (newState != state) {
            state = newState
            onStateChanged?.invoke(state)
        }
    }

    /** 设置可见性（被警车看到） */
    fun setVisibility(visible: Boolean) {
        isVisible = visible
    }

    /** 检查是否被捕（简化版：高压区且被持续锁定） */
    fun checkArrest(playerLat: Double, playerLng: Double): Boolean {
        if (state != State.HIGH_PRESS) return false

        // 被捕条件：高压区 + 收缩半径接近最小值 + 高置信度
        val distToGravity = haversine(playerLat, playerLng, gravityLat, gravityLng)
        if (shrinkRadius < 250.0 && confidence > 0.8 && distToGravity < 300.0) {
            state = State.ARRESTED
            handler.removeCallbacks(updateRunnable)
            onStateChanged?.invoke(state)
            return true
        }
        return false
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
        onStateChanged?.invoke(state)
    }

    // === 工具函数 ===

    private fun update() {
        // 由 updatePlayer 驱动，这里只做时间补偿
        val now = System.currentTimeMillis()
        val dt = (now - lastUpdateTime) / 1000.0
        if (dt > 0.5) {
            // 超过 500ms 没更新，可能卡顿了，跳过
            lastUpdateTime = now
        }
    }

    private fun normalizeAngle(angle: Double): Double {
        var a = angle % 360.0
        if (a > 180.0) a -= 360.0
        if (a < -180.0) a += 360.0
        return a
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
