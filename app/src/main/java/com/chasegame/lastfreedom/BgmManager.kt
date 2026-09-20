package com.chasegame.lastfreedom

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

/**
 * 背景音乐管理器
 * 基于B站视频 BV1fxgP6TE9k "最后的自由BGM合集" 的8首BGM：
 * P1: 开头の小曲1 (11s)
 * P2: 追车の小曲/全速怕什么怕/细妹细狗 (276s)
 * P3: 开头の小曲2/滑稽の小曲/小黄鸡の小曲 Crank That (246s)
 * P4: 行政休假の小曲 (59s)
 * P5: R星の小曲/开头の小曲3 Midnight City (241s)
 * P6: 消星の小曲/分钱の小曲/开头の小曲4 Danza Kuduro (199s)
 * P7: 英菲尼迪の小曲/登阶の小曲 Titanic (187s)
 * P8: 牢大の小曲/坠机の小曲/重开の小曲 See You Again (227s)
 *
 * 音乐触发逻辑：
 * - 追逐音乐(P2): 速度 > 20km/h 时播放
 * - 被捕音乐(P4): 被警察抓到时播放
 * - 消星音乐(P6): 警星消失时播放
 * - R星音乐(P5): 开场/特殊事件时播放
 */
class BgmManager(private val context: Context) {

    companion object {
        private const val TAG = "BgmManager"
    }

    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // 各音乐播放器
    private var chasePlayer: MediaPlayer? = null     // P2: 追车
    private var arrestPlayer: MediaPlayer? = null     // P4: 被捕/行政休假
    private var starClearPlayer: MediaPlayer? = null  // P6: 消星
    private var rstarPlayer: MediaPlayer? = null      // P5: R星
    private var opening1Player: MediaPlayer? = null   // P1: 开头1
    private var opening2Player: MediaPlayer? = null   // P3: 开头2
    private var infinitiPlayer: MediaPlayer? = null   // P7: 英菲尼迪
    private var seeyouPlayer: MediaPlayer? = null     // P8: See You Again

    private var isChasePlaying = false
    private var currentBgm: MediaPlayer? = null

    init {
        initPlayers()
    }

    private fun initPlayers() {
        chasePlayer = createPlayer("music/bgm/p2_chase.m4a")
        arrestPlayer = createPlayer("music/bgm/p4_arrest.m4a")
        starClearPlayer = createPlayer("music/bgm/p6_starclear.m4a")
        rstarPlayer = createPlayer("music/bgm/p5_rstar.m4a")
        opening1Player = createPlayer("music/bgm/p1_opening1.m4a")
        opening2Player = createPlayer("music/bgm/p3_opening2.m4a")
        infinitiPlayer = createPlayer("music/bgm/p7_infiniti.m4a")
        seeyouPlayer = createPlayer("music/bgm/p8_seeyou.m4a")
    }

    private fun createPlayer(assetPath: String): MediaPlayer? {
        return try {
            val afd = context.assets.openFd(assetPath)
            MediaPlayer().apply {
                setAudioAttributes(audioAttrs)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                isLooping = true
                setVolume(0.7f, 0.7f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create player for $assetPath", e)
            null
        }
    }

    /** 播放追逐音乐 (P2) */
    fun playChaseMusic() {
        if (isChasePlaying) return
        stopAll()
        chasePlayer?.let {
            it.seekTo(0)
            it.start()
            currentBgm = it
            isChasePlaying = true
        }
    }

    /** 停止追逐音乐 */
    fun stopChaseMusic() {
        chasePlayer?.let {
            if (it.isPlaying) it.pause()
        }
        isChasePlaying = false
        if (currentBgm == chasePlayer) currentBgm = null
    }

    /** 播放被捕音乐 (P4: 行政休假の小曲) */
    fun playArrestMusic() {
        stopAll()
        arrestPlayer?.let {
            it.isLooping = false
            it.seekTo(0)
            it.start()
            currentBgm = it
        }
    }

    /** 播放消星音乐 (P6: Danza Kuduro) */
    fun playStarClearMusic() {
        stopAll()
        starClearPlayer?.let {
            it.isLooping = false
            it.seekTo(0)
            it.start()
            currentBgm = it
        }
    }

    /** 播放R星音乐 (P5: Midnight City) */
    fun playRstarMusic() {
        stopAll()
        rstarPlayer?.let {
            it.seekTo(0)
            it.start()
            currentBgm = it
        }
    }

    /** 播放开场音乐1 (P1) */
    fun playOpening1() {
        stopAll()
        opening1Player?.let {
            it.isLooping = false
            it.seekTo(0)
            it.start()
            currentBgm = it
        }
    }

    /** 播放开场音乐2 (P3) */
    fun playOpening2() {
        stopAll()
        opening2Player?.let {
            it.seekTo(0)
            it.start()
            currentBgm = it
        }
    }

    /** 播放英菲尼迪音乐 (P7: Titanic) */
    fun playInfinitiMusic() {
        stopAll()
        infinitiPlayer?.let {
            it.seekTo(0)
            it.start()
            currentBgm = it
        }
    }

    /** 播放 See You Again (P8) */
    fun playSeeYouAgain() {
        stopAll()
        seeyouPlayer?.let {
            it.isLooping = false
            it.seekTo(0)
            it.start()
            currentBgm = it
        }
    }

    /** 停止所有音乐 */
    fun stopAll() {
        listOf(chasePlayer, arrestPlayer, starClearPlayer, rstarPlayer,
            opening1Player, opening2Player, infinitiPlayer, seeyouPlayer).forEach {
            it?.let { player ->
                if (player.isPlaying) player.pause()
            }
        }
        isChasePlaying = false
        currentBgm = null
    }

    fun release() {
        stopAll()
        listOf(chasePlayer, arrestPlayer, starClearPlayer, rstarPlayer,
            opening1Player, opening2Player, infinitiPlayer, seeyouPlayer).forEach {
            it?.release()
        }
        chasePlayer = null
        arrestPlayer = null
        starClearPlayer = null
        rstarPlayer = null
        opening1Player = null
        opening2Player = null
        infinitiPlayer = null
        seeyouPlayer = null
    }
}
