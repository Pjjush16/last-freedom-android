package com.chasegame.lastfreedom

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 精细化传统规则打分系统（替代 AI 模型）
 *
 * 核心规则：
 * 1. 驾驶越激进，星级越高
 * 2. 一次最多加 2 星
 * 3. 累加制（跨局累积）
 * 4. 逃离失败固定减 1 星，但驾驶加分仍生效
 * 5. 速度阈值上限 120 km/h
 *
 * 打分维度：
 * - 速度阶梯：按本局最高速度逐级加分
 * - 逃脱成功奖励：+1 星
 * - 失败惩罚：-1 星
 */
class RuleScoringModel(context: Context) {

    companion object {
        private const val TAG = "RuleScoringModel"
        private const val PREFS_NAME = "rule_scoring_prefs"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 累积数据
    var totalStars: Int = 0; private set
    var totalRounds: Int = 0; private set
    var totalWins: Int = 0; private set

    init {
        totalStars = prefs.getInt("total_stars", 0)
        totalRounds = prefs.getInt("total_rounds", 0)
        totalWins = prefs.getInt("total_wins", 0)
        Log.i(TAG, "规则打分系统加载: 累计${totalStars}星, ${totalRounds}局, ${totalWins}胜")
    }

    data class ScoreResult(
        val starsGained: Int,       // 本局净增星数（可负）
        val drivingBonus: Int,      // 驾驶加分（≥0）
        val outcomeBonus: Int,      // 结果加减（+1/−1）
        val totalStars: Int,        // 累计总星数
        val comment: String,        // 评语
        val canReachEight: Boolean  // 累计星数是否接近八星（≥7）
    )

    /**
     * 对当局表现进行规则打分
     *
     * @param success       是否成功逃脱
     * @param durationSec   持续时间(秒)
     * @param distanceM     移动距离(米)
     * @param maxSpeedKmh   最高速度(km/h)
     * @param avgSpeedKmh   平均速度(km/h)
     * @param tortuosity    路线曲折度(路径距离/直线距离, 1.0~3.0)
     */
    fun scoreRound(
        success: Boolean,
        durationSec: Long,
        distanceM: Double,
        maxSpeedKmh: Float,
        avgSpeedKmh: Float = maxSpeedKmh * 0.6f,
        tortuosity: Double = 1.5
    ): ScoreResult {
        totalRounds++

        // === 1. 驾驶评分：速度阶梯 ===
        // 按最高速度逐级加分，上限 120 km/h
        val speedScore = when {
            maxSpeedKmh >= 120f -> 2   // 极限驾驶
            maxSpeedKmh >= 100f -> 2   // 高速狂飙
            maxSpeedKmh >= 80f  -> 1   // 快速驾驶
            maxSpeedKmh >= 60f  -> 1   // 中速驾驶
            else -> 0                 // 低速/静止
        }

        // 驾驶加分上限 2 星
        val drivingBonus = min(speedScore, 2)

        // === 2. 结果加减 ===
        val outcomeBonus = if (success) 1 else -1

        // === 3. 净增星数 ===
        // 失败时：驾驶加分仍生效，但结果扣 1 星
        // 例：驾驶+1 但失败-1 → 净增 0；驾驶+2 但失败-1 → 净增 +1
        val starsGained = drivingBonus + outcomeBonus

        // === 4. 累计星数（下限为 0）===
        totalStars = max(0, totalStars + starsGained)
        if (success) totalWins++

        // 保存
        prefs.edit()
            .putInt("total_stars", totalStars)
            .putInt("total_rounds", totalRounds)
            .putInt("total_wins", totalWins)
            .apply()

        // 八星潜力判断（累计 ≥7 星）
        val canReachEight = totalStars >= 7

        // 评语生成
        val comment = generateComment(success, drivingBonus, maxSpeedKmh, totalStars)

        Log.i(TAG, "打分: success=$success maxSpeed=${maxSpeedKmh}km/h driving=+$drivingBonus outcome=$outcomeBonus net=$starsGained total=$totalStars")

        return ScoreResult(
            starsGained = starsGained,
            drivingBonus = drivingBonus,
            outcomeBonus = outcomeBonus,
            totalStars = totalStars,
            comment = comment,
            canReachEight = canReachEight
        )
    }

    /**
     * 实时预测：突围中评估累计是否接近八星
     * 用于触发"八星潜力"音乐
     */
    fun predictCurrentRound(
        currentSpeedKmh: Float,
        avgSpeedKmh: Float,
        distanceM: Double,
        durationSec: Long,
        distToDest: Double,
        tortuosity: Double
    ): Double {
        // 基于当前速度预估本局驾驶加分
        val speedScore = when {
            currentSpeedKmh >= 120f -> 2
            currentSpeedKmh >= 100f -> 2
            currentSpeedKmh >= 80f  -> 1
            currentSpeedKmh >= 60f  -> 1
            else -> 0
        }
        val predictedBonus = min(speedScore, 2)
        // 预估总星数 = 当前累计 + 驾驶加分 + 成功奖励(+1)
        val predictedTotal = totalStars + predictedBonus + 1
        // 映射到 1~10 区间（累计 10 星 = 10.0）
        return min(predictedTotal.toDouble(), 10.0)
    }

    /**
     * 根据本局表现和累计成绩生成评语
     */
    private fun generateComment(
        success: Boolean,
        drivingBonus: Int,
        maxSpeedKmh: Float,
        totalStars: Int
    ): String {
        val speedDesc = when {
            maxSpeedKmh >= 120f -> "极速"
            maxSpeedKmh >= 100f -> "狂飙"
            maxSpeedKmh >= 80f  -> "快速"
            maxSpeedKmh >= 60f  -> "稳健"
            else -> "龟速"
        }

        return if (success) {
            when {
                drivingBonus >= 2 -> "消星成功！${speedDesc}驾驶 +2★，累计${totalStars}★"
                drivingBonus == 1 -> "消星成功！${speedDesc}驾驶 +1★，再加速可以拿更多"
                else -> "消星成功！但开太慢了，没有驾驶加分"
            }
        } else {
            when {
                drivingBonus >= 2 -> "虽然被抓，但${speedDesc}驾驶挽回 +1★（驾驶+2 失败-1）"
                drivingBonus == 1 -> "被抓了，驾驶+1 失败-1，星数不变"
                else -> "被抓了 -1★，下次开快点能抵消惩罚"
            }
        }
    }

    // totalStars 和 totalRounds 属性已自动生成 getter
    fun getWinRate(): Double = if (totalRounds > 0) totalWins.toDouble() / totalRounds else 0.0

    /** 重置累计数据（调试用） */
    fun reset() {
        totalStars = 0
        totalRounds = 0
        totalWins = 0
        prefs.edit().clear().apply()
        Log.i(TAG, "打分数据已重置")
    }
}
