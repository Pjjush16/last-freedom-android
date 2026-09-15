package com.lastfreedom.app.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lastfreedom.app.data.Achievement

/**
 * 游戏数据持久化存储
 */
class GamePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("last_freedom_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    // 天地图 Key
    var tiandituKey: String
        get() = prefs.getString("tianditu_key", "") ?: ""
        set(value) = prefs.edit().putString("tianditu_key", value).apply()

    // 速度单位偏好
    var useMph: Boolean
        get() = prefs.getBoolean("use_mph", false)
        set(value) = prefs.edit().putBoolean("use_mph", value).apply()

    // 累计统计
    var totalGamesPlayed: Int
        get() = prefs.getInt("total_games", 0)
        set(value) = prefs.edit().putInt("total_games", value).apply()

    var totalWins: Int
        get() = prefs.getInt("total_wins", 0)
        set(value) = prefs.edit().putInt("total_wins", value).apply()

    var totalStars: Int
        get() = prefs.getInt("total_stars", 0)
        set(value) = prefs.edit().putInt("total_stars", value).apply()

    var bestMaxSpeed: Float
        get() = prefs.getFloat("best_speed", 0f)
        set(value) = prefs.edit().putFloat("best_speed", value).apply()

    var bestDistance: Double
        get() = prefs.getDouble("best_distance", 0.0)
        set(value) = prefs.edit().putLong("best_distance", java.lang.Double.doubleToLongBits(value)).apply()

    // 成就存储
    fun saveAchievements(achievements: List<Achievement>) {
        val json = gson.toJson(achievements)
        prefs.edit().putString("achievements", json).apply()
    }

    fun loadAchievements(): List<Achievement> {
        val json = prefs.getString("achievements", null) ?: return emptyList()
        val type = object : TypeToken<List<Achievement>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    private fun SharedPreferences.getDouble(key: String, default: Double): Double {
        return java.lang.Double.longBitsToDouble(getLong(key, java.lang.Double.doubleToRawLongBits(default)))
    }
}
