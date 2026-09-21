package com.chasegame.lastfreedom

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * AI 评分模型 — 轻量神经网络推理引擎
 *
 * 模型结构: 输入(7) → 隐藏层(12, ReLU) → 输出(1, sigmoid) → 1~10 星
 *
 * 输入特征:
 *   0. max_stars / 10         (历史最高星数)
 *   1. max_speed_kmh / 120    (本局最高速度)
 *   2. avg_speed_kmh / 120    (本局平均速度)
 *   3. distance_m / 10000     (本局移动距离)
 *   4. duration_sec / 600     (本局持续时间)
 *   5. (tortuosity - 1) / 2   (路线曲折度)
 *   6. route_efficiency       (路线效率 0~1)
 *
 * 权重从 assets/ai/scoring_model.json 加载，仅 3.8KB
 */
class AIScoringModel(context: Context) {

    companion object {
        private const val TAG = "AIScoringModel"
        private const val PREFS_NAME = "ai_scoring_prefs"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 历史数据
    var maxStars: Int = 0; private set
    var totalRounds: Int = 0; private set
    var totalWins: Int = 0; private set
    var cumulativePower: Int = 50; private set

    // 模型权重
    private val W1: Array<FloatArray>
    private val b1: FloatArray
    private val W2: Array<FloatArray>
    private val b2: FloatArray

    init {
        // 加载历史数据
        maxStars = prefs.getInt("max_stars", 0)
        totalRounds = prefs.getInt("total_rounds", 0)
        totalWins = prefs.getInt("total_wins", 0)
        cumulativePower = prefs.getInt("cumulative_power", 50)

        // 加载模型权重
        val json = context.assets.open("ai/scoring_model.json").bufferedReader().readText()
        val model = JSONObject(json)

        val w1Json = model.getJSONArray("W1")
        val b1Json = model.getJSONArray("b1")
        val w2Json = model.getJSONArray("W2")
        val b2Json = model.getJSONArray("b2")

        val inputDim = model.getInt("input_dim")
        val hiddenDim = model.getInt("hidden_dim")

        W1 = Array(inputDim) { i ->
            FloatArray(hiddenDim) { j ->
                w1Json.getJSONArray(i).getDouble(j).toFloat()
            }
        }
        b1 = FloatArray(hiddenDim) { j -> b1Json.getDouble(j).toFloat() }

        W2 = Array(hiddenDim) { i ->
            FloatArray(1) { j ->
                w2Json.getJSONArray(i).getDouble(j).toFloat()
            }
        }
        b2 = FloatArray(1) { b2Json.getDouble(0).toFloat() }

        Log.i(TAG, "AI评分模型加载成功: ${inputDim}→${hiddenDim}→1")
    }

    data class ScoreResult(
        val predictedStars: Double,
        val roundedStars: Int,
        val confidence: Double,
        val comment: String,
        val totalPower: Int,
        val canReachEight: Boolean  // AI判断本局是否可能晋升八星
    )

    /**
     * 对当局表现进行AI评分
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

        // 路线效率: 成功时用 1/tortuosity, 失败时打折
        val routeEff = if (success) {
            (1.0 / tortuosity).coerceIn(0.0, 1.0)
        } else {
            (1.0 / tortuosity * 0.6).coerceIn(0.0, 1.0)
        }

        // 归一化输入特征
        val input = floatArrayOf(
            (maxStars / 10.0f).coerceIn(0f, 1f),
            (maxSpeedKmh / 120.0f).coerceIn(0f, 1f),
            (avgSpeedKmh / 120.0f).coerceIn(0f, 1f),
            (distanceM / 10000.0f).coerceIn(0f, 1f),
            (durationSec / 600.0f).coerceIn(0f, 1f),
            ((tortuosity - 1.0).toFloat() / 2.0f).coerceIn(0f, 1f),
            routeEff.toFloat().coerceIn(0f, 1f)
        )

        // 前向传播
        val predicted = forward(input)
        val stars = predicted * 9.0 + 1.0  // 映射到 1~10

        // 更新历史
        val roundedStars = stars.roundToInt().coerceIn(1, 10)
        if (roundedStars > maxStars) maxStars = roundedStars
        if (success) totalWins++

        // 累计战力 (EMA平滑)
        cumulativePower = (cumulativePower * 0.7 + stars * 10 * 0.3).roundToInt()
        cumulativePower = max(0, min(100, cumulativePower))

        // 保存
        prefs.edit()
            .putInt("max_stars", maxStars)
            .putInt("total_rounds", totalRounds)
            .putInt("total_wins", totalWins)
            .putInt("cumulative_power", cumulativePower)
            .apply()

        // AI判断是否可能晋升八星
        val canReachEight = stars >= 7.5

        val comment = when {
            stars >= 8.5 -> "传奇车手！神级路线，速度惊人"
            stars >= 7.0 -> "高手过招！策略与速度兼备"
            stars >= 5.0 -> "不错表现，继续磨练突围技巧"
            stars >= 3.0 -> "初露锋芒，多试几次会更好"
            else -> "加油！每次失败都是经验"
        }

        return ScoreResult(
            predictedStars = stars,
            roundedStars = roundedStars,
            confidence = if (success) 0.85 else 0.65,
            comment = comment,
            totalPower = cumulativePower,
            canReachEight = canReachEight
        )
    }

    /**
     * 实时预测：当局进行中预估最终星数
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
        val routeEff = (1.0 / tortuosity).coerceIn(0.0, 1.0)

        val input = floatArrayOf(
            (maxStars / 10.0f).coerceIn(0f, 1f),
            (currentSpeedKmh / 120.0f).coerceIn(0f, 1f),
            (avgSpeedKmh / 120.0f).coerceIn(0f, 1f),
            (distanceM / 10000.0f).coerceIn(0f, 1f),
            (durationSec / 600.0f).coerceIn(0f, 1f),
            ((tortuosity - 1.0).toFloat() / 2.0f).coerceIn(0f, 1f),
            routeEff.toFloat().coerceIn(0f, 1f)
        )

        val predicted = forward(input)
        return predicted * 9.0 + 1.0  // 1~10
    }

    /** 前向传播: input(7) → hidden(12, ReLU) → output(1, sigmoid) */
    private fun forward(input: FloatArray): Double {
        // 隐藏层
        val hidden = FloatArray(b1.size)
        for (j in hidden.indices) {
            var sum = b1[j]
            for (i in input.indices) {
                sum += input[i] * W1[i][j]
            }
            hidden[j] = max(0f, sum) // ReLU
        }

        // 输出层
        var output = b2[0]
        for (j in hidden.indices) {
            output += hidden[j] * W2[j][0]
        }

        // sigmoid
        return 1.0 / (1.0 + Math.exp(-output.toDouble()))
    }

    fun getTotalPower(): Int = cumulativePower
    fun getWinRate(): Double = if (totalRounds > 0) totalWins.toDouble() / totalRounds else 0.0
    fun getTotalRounds(): Int = totalRounds
    fun getMaxStars(): Int = maxStars
}
