package com.chasegame.lastfreedom

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * AI 评分模型 — 战力评估系统
 *
 * 参考"争取最后的自由"的战力表机制，根据当局表现和历史数据进行综合评分。
 *
 * 评分维度：
 * 1. 突围结果（成功/失败类型）
 * 2. 存活时间
 * 3. 移动距离
 * 4. 速度表现
 * 5. 历史战绩加权
 *
 * 输出：1-5 星评分 + 综合战力分 (0-100)
 */
class ScoringModel(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("scoring_prefs", Context.MODE_PRIVATE)

    private var totalRounds = 0
    private var totalWins = 0
    private var bestScore = 0
    private var cumulativePower = 50

    init {
        totalRounds = prefs.getInt("total_rounds", 0)
        totalWins = prefs.getInt("total_wins", 0)
        bestScore = prefs.getInt("best_score", 0)
        cumulativePower = prefs.getInt("cumulative_power", 50)
    }

    data class ScoreResult(
        val compositeScore: Int,
        val stars: Int,
        val comment: String,
        val totalPower: Int
    )

    fun scoreRound(
        success: Boolean,
        durationSec: Long,
        distanceM: Double,
        maxSpeedKmh: Float
    ): ScoreResult {
        totalRounds++

        val resultScore = if (success) 40 else 10

        val durationMin = durationSec / 60.0
        val timeScore = when {
            durationMin >= 4.0 -> 20
            durationMin >= 3.0 -> 16
            durationMin >= 2.0 -> 12
            durationMin >= 1.0 -> 8
            else -> 4
        }

        val distKm = distanceM / 1000.0
        val distScore = when {
            distKm >= 3.0 -> 20
            distKm >= 2.0 -> 16
            distKm >= 1.0 -> 12
            distKm >= 0.5 -> 8
            else -> 4
        }

        val speedScore = when {
            maxSpeedKmh >= 80 -> 10
            maxSpeedKmh >= 60 -> 8
            maxSpeedKmh >= 40 -> 6
            maxSpeedKmh >= 20 -> 4
            else -> 2
        }

        val winRate = if (totalRounds > 0) totalWins.toDouble() / totalRounds else 0.0
        val historyScore = (winRate * 10).roundToInt()

        val compositeScore = min(100, resultScore + timeScore + distScore + speedScore + historyScore)

        val stars = when {
            compositeScore >= 85 -> 5
            compositeScore >= 70 -> 4
            compositeScore >= 50 -> 3
            compositeScore >= 30 -> 2
            else -> 1
        }

        val comment = when {
            compositeScore >= 85 -> "传奇车手！路线诡谲，速度惊人"
            compositeScore >= 70 -> "高手过招！策略与速度兼备"
            compositeScore >= 50 -> "不错表现，继续磨练突围技巧"
            compositeScore >= 30 -> "初露锋芒，多试几次会更好"
            else -> "加油！每次失败都是经验"
        }

        cumulativePower = (cumulativePower * 0.7 + compositeScore * 0.3).roundToInt()
        cumulativePower = max(0, min(100, cumulativePower))

        if (success) totalWins++
        if (compositeScore > bestScore) bestScore = compositeScore

        prefs.edit()
            .putInt("total_rounds", totalRounds)
            .putInt("total_wins", totalWins)
            .putInt("best_score", bestScore)
            .putInt("cumulative_power", cumulativePower)
            .apply()

        return ScoreResult(compositeScore, stars, comment, cumulativePower)
    }

    fun getTotalPower(): Int = cumulativePower
    fun getWinRate(): Double = if (totalRounds > 0) totalWins.toDouble() / totalRounds else 0.0
    fun getTotalRounds(): Int = totalRounds
}
