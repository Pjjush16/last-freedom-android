package com.chasegame.lastfreedom

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.random.Random

/**
 * 背景音乐管理器 — 突围模式 BGM 播放表
 *
 * 阶段一：选点/出发前 → P1 + P3 + P5 随机循环
 *   P1: 开头の小曲1 (11s)
 *   P3: 开头の小曲2 / Crank That (246s)
 *   P5: R星の小曲 / Midnight City (241s)
 *
 * 阶段二：突围进行中
 *   P2: 追车の小曲 / 全速怕什么怕 / 细妹细狗 (276s) — 默认循环
 *   P7: 英菲尼迪の小曲 / Titanic (187s) — 收缩半径 < 500m 高压区
 *
 * 阶段三：结局
 *   P6: 消星の小曲 / Danza Kuduro (199s) — 胜利，单次播放
 *   P4: 行政休假の小曲 (59s) — 被捕，单次播放
 */
class BgmManager(private val context: Context) {

    companion object {
        private const val TAG = "BgmManager"
    }

    enum class Phase {
        IDLE,        // 启动后，未进入选点
        PICKING,     // 选点/出发前
        BREAKOUT,    // 正常突围
        HIGH_PRESS,  // 高压区 (< 500m)
        VICTORY,     // 胜利
        ARRESTED     // 被捕
    }

    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    // 所有播放器
    private var p1: MediaPlayer? = null   // 开头の小曲1
    private var p2: MediaPlayer? = null   // 追车の小曲
    private var p3: MediaPlayer? = null   // 开头の小曲2 / Crank That
    private var p4: MediaPlayer? = null   // 行政休假の小曲
    private var p5: MediaPlayer? = null   // R星の小曲 / Midnight City
    private var p6: MediaPlayer? = null   // 消星の小曲 / Danza Kuduro
    private var p7: MediaPlayer? = null   // 英菲尼迪の小曲 / Titanic

    private var currentPhase = Phase.IDLE
    private var currentBgm: MediaPlayer? = null

    // 选点阶段随机播放队列
    private val pickingPlaylist = mutableListOf<MediaPlayer>()
    private var pickingIndex = 0
    private val pickingCheckRunnable = object : Runnable {
        override fun run() {
            if (currentPhase == Phase.PICKING) {
                // 检查当前曲目是否播完
                val cur = currentBgm
                if (cur == null || !cur.isPlaying) {
                    playNextPicking()
                }
                handler.postDelayed(this, 1000)
            }
        }
    }

    init {
        initPlayers()
    }

    private fun initPlayers() {
        p1 = createPlayer("music/bgm/p1_opening1.m4a", false)
        p2 = createPlayer("music/bgm/p2_chase.m4a", true)
        p3 = createPlayer("music/bgm/p3_opening2.m4a", false)
        p4 = createPlayer("music/bgm/p4_arrest.m4a", false)
        p5 = createPlayer("music/bgm/p5_rstar.m4a", false)
        p6 = createPlayer("music/bgm/p6_starclear.m4a", false)
        p7 = createPlayer("music/bgm/p7_infiniti.m4a", true)
    }

    private fun createPlayer(assetPath: String, looping: Boolean): MediaPlayer? {
        return try {
            val afd = context.assets.openFd(assetPath)
            MediaPlayer().apply {
                setAudioAttributes(audioAttrs)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                isLooping = looping
                setVolume(0.7f, 0.7f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create player for $assetPath", e)
            null
        }
    }

    // === 阶段切换 ===

    /** 进入选点阶段：P1 + P3 + P5 随机循环 */
    fun enterPicking() {
        if (currentPhase == Phase.PICKING) return
        stopAll()
        currentPhase = Phase.PICKING

        // 打乱选点曲目
        pickingPlaylist.clear()
        listOfNotNull(p1, p3, p5).shuffled().forEach { pickingPlaylist.add(it) }
        pickingIndex = 0

        playNextPicking()
        handler.post(pickingCheckRunnable)
    }

    private fun playNextPicking() {
        if (pickingPlaylist.isEmpty()) return

        // 如果当前曲目还在播，不打断
        currentBgm?.let { if (it.isPlaying) return }

        val player = pickingPlaylist[pickingIndex % pickingPlaylist.size]
        pickingIndex++

        stopAll()
        player.seekTo(0)
        player.start()
        currentBgm = player
    }

    /** 进入突围阶段：P2 循环 */
    fun enterBreakout() {
        if (currentPhase == Phase.BREAKOUT) return
        stopAll()
        handler.removeCallbacks(pickingCheckRunnable)
        currentPhase = Phase.BREAKOUT

        p2?.let {
            it.seekTo(0)
            it.start()
            currentBgm = it
        }
    }

    /** 进入高压区：P7 循环 */
    fun enterHighPressure() {
        if (currentPhase == Phase.HIGH_PRESS) return
        stopAll()
        currentPhase = Phase.HIGH_PRESS

        p7?.let {
            it.seekTo(0)
            it.start()
            currentBgm = it
        }
    }

    /** 胜利：P6 单次播放 */
    fun playVictory() {
        if (currentPhase == Phase.VICTORY) return
        stopAll()
        handler.removeCallbacks(pickingCheckRunnable)
        currentPhase = Phase.VICTORY

        p6?.let {
            it.isLooping = false
            it.seekTo(0)
            it.start()
            currentBgm = it
        }
    }

    /** 被捕：P4 单次播放 */
    fun playArrested() {
        if (currentPhase == Phase.ARRESTED) return
        stopAll()
        handler.removeCallbacks(pickingCheckRunnable)
        currentPhase = Phase.ARRESTED

        p4?.let {
            it.isLooping = false
            it.seekTo(0)
            it.start()
            currentBgm = it
        }
    }

    /** 回到空闲 */
    fun enterIdle() {
        stopAll()
        handler.removeCallbacks(pickingCheckRunnable)
        currentPhase = Phase.IDLE
    }

    /** 获取当前阶段 */
    fun getCurrentPhase() = currentPhase

    // === 通用控制 ===

    fun stopAll() {
        listOf(p1, p2, p3, p4, p5, p6, p7).forEach {
            it?.let { player ->
                if (player.isPlaying) player.pause()
            }
        }
        currentBgm = null
    }

    fun release() {
        stopAll()
        handler.removeCallbacksAndMessages(null)
        listOf(p1, p2, p3, p4, p5, p6, p7).forEach { it?.release() }
        p1 = null; p2 = null; p3 = null; p4 = null
        p5 = null; p6 = null; p7 = null
    }
}
