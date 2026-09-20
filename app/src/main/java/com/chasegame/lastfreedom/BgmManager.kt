package com.chasegame.lastfreedom

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * 背景音乐管理器：
 * - 追逐音乐：逃跑时循环播放 cmon 和 she_makes_me_go 两首，轮流交替
 * - 被捕音乐：被捕时播放 arrest_music
 * - 消星音乐：消星成功时播放 star_clear_music
 */
class BgmManager(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    // 追逐音乐（两首轮流）
    private val chasePlayers = mutableListOf<MediaPlayer>()
    private var currentChaseIndex = 0
    private var isChasePlaying = false

    // 被捕音乐
    private var arrestPlayer: MediaPlayer? = null

    // 消星音乐
    private var starClearPlayer: MediaPlayer? = null

    init {
        initChaseMusic()
        initArrestMusic()
        initStarClearMusic()
    }

    private fun initChaseMusic() {
        try {
            val cmonFd = context.assets.openFd("music/cmon.m4a")
            val cmonPlayer = MediaPlayer().apply {
                setDataSource(cmonFd.fileDescriptor, cmonFd.startOffset, cmonFd.length)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setVolume(0.7f, 0.7f)
                prepare()
                setOnCompletionListener {
                    // 播放下一首
                    currentChaseIndex = (currentChaseIndex + 1) % chasePlayers.size
                    if (isChasePlaying) {
                        chasePlayers[currentChaseIndex].start()
                    }
                }
            }
            chasePlayers.add(cmonPlayer)
            cmonFd.close()

            val sheFd = context.assets.openFd("music/she_makes_me_go.m4a")
            val shePlayer = MediaPlayer().apply {
                setDataSource(sheFd.fileDescriptor, sheFd.startOffset, sheFd.length)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setVolume(0.7f, 0.7f)
                prepare()
                setOnCompletionListener {
                    // 播放下一首
                    currentChaseIndex = (currentChaseIndex + 1) % chasePlayers.size
                    if (isChasePlaying) {
                        chasePlayers[currentChaseIndex].start()
                    }
                }
            }
            chasePlayers.add(shePlayer)
            sheFd.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initArrestMusic() {
        try {
            val fd = context.assets.openFd("music/arrest_music.m4a")
            arrestPlayer = MediaPlayer().apply {
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setVolume(0.8f, 0.8f)
                prepare()
            }
            fd.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initStarClearMusic() {
        try {
            val fd = context.assets.openFd("music/star_clear_music.m4a")
            starClearPlayer = MediaPlayer().apply {
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setVolume(0.8f, 0.8f)
                prepare()
            }
            fd.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 开始播放追逐音乐（两首轮流循环）
     */
    fun startChaseMusic() {
        if (isChasePlaying) return
        isChasePlaying = true
        stopArrestMusic()
        stopStarClearMusic()

        if (chasePlayers.isNotEmpty()) {
            chasePlayers[currentChaseIndex].seekTo(0)
            chasePlayers[currentChaseIndex].start()
        }
    }

    /**
     * 停止追逐音乐
     */
    fun stopChaseMusic() {
        isChasePlaying = false
        chasePlayers.forEach { player ->
            if (player.isPlaying) {
                player.pause()
            }
        }
    }

    /**
     * 播放被捕音乐
     */
    fun playArrestMusic() {
        stopChaseMusic()
        stopStarClearMusic()
        arrestPlayer?.let {
            it.seekTo(0)
            it.start()
        }
    }

    /**
     * 停止被捕音乐
     */
    fun stopArrestMusic() {
        arrestPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                it.seekTo(0)
            }
        }
    }

    /**
     * 播放消星成功音乐
     */
    fun playStarClearMusic() {
        stopChaseMusic()
        stopArrestMusic()
        starClearPlayer?.let {
            it.seekTo(0)
            it.start()
        }
    }

    /**
     * 停止消星音乐
     */
    fun stopStarClearMusic() {
        starClearPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                it.seekTo(0)
            }
        }
    }

    /**
     * 停止所有音乐
     */
    fun stopAll() {
        stopChaseMusic()
        stopArrestMusic()
        stopStarClearMusic()
    }

    /**
     * 释放资源
     */
    fun release() {
        stopAll()
        chasePlayers.forEach { it.release() }
        chasePlayers.clear()
        arrestPlayer?.release()
        arrestPlayer = null
        starClearPlayer?.release()
        starClearPlayer = null
    }
}
