package com.lastfreedom.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.lastfreedom.app.data.*
import com.lastfreedom.app.game.GameEngine
import com.lastfreedom.app.ui.MapView
import com.lastfreedom.app.ui.PandaOverlayView
import com.lastfreedom.app.ui.SpeedGaugeView
import com.lastfreedom.app.ui.SubtitleView
import com.lastfreedom.app.util.GamePreferences
import com.lastfreedom.app.util.LocationTracker

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var speedGauge: SpeedGaugeView
    private lateinit var subtitleView: SubtitleView
    private lateinit var pandaView: PandaOverlayView
    private lateinit var hudOverlay: View
    private lateinit var startButton: View
    private lateinit var statusBar: View

    private lateinit var gameEngine: GameEngine
    private lateinit var locationTracker: LocationTracker
    private lateinit var prefs: GamePreferences

    private var mapReady = false
    private var selectedEndLat = 0.0
    private var selectedEndLng = 0.0
    private var hasDestination = false

    // 游戏循环
    private val gameRunnable = object : Runnable {
        override fun run() {
            gameLoop()
            hudOverlay.postDelayed(this, 33L) // ~30fps
        }
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏 + 屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        setContentView(R.layout.activity_main)

        initViews()
        initGame()
        checkPermissions()
    }

    private fun initViews() {
        mapView = findViewById(R.id.mapView)
        speedGauge = findViewById(R.id.speedGauge)
        subtitleView = findViewById(R.id.subtitleView)
        pandaView = findViewById(R.id.pandaView)
        hudOverlay = findViewById(R.id.hudOverlay)
        startButton = findViewById(R.id.startButton)
        statusBar = findViewById(R.id.statusBar)

        prefs = GamePreferences(this)
        speedGauge.setUseMph(prefs.useMph)

        startButton.setOnClickListener {
            if (hasDestination) {
                beginCountdown()
            } else {
                Toast.makeText(this, "请先在地图上选择终点", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initGame() {
        gameEngine = GameEngine()
        locationTracker = LocationTracker(this)

        // 游戏事件回调
        gameEngine.onGameEnd = { success, stats ->
            runOnUiThread { handleGameEnd(success, stats) }
        }

        gameEngine.onAchievementUnlocked = { achievement ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    "${achievement.icon} 成就解锁：${achievement.name}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        gameEngine.onEventTriggered = { event ->
            runOnUiThread { handleRandomEvent(event) }
        }

        gameEngine.onSubtitleChange = { text ->
            runOnUiThread { subtitleView.setSubtitle(text) }
        }

        // 加载地图（ArcGIS 卫星影像，无需 Key）
        loadMapWithKey("")
    }

    private fun loadMapWithKey(key: String) {
        mapView.loadMap(key) {
            mapReady = true

            // 高德风格选点：拖动地图 + 准星居中 + 确认按钮
            mapView.setOnDestinationConfirmedListener { lat, lng ->
                if (gameEngine.mode == GameMode.IDLE) {
                    selectedEndLat = lat
                    selectedEndLng = lng
                    hasDestination = true
                    mapView.setDestination(lat, lng)
                    subtitleView.setSubtitle("终点已选定，点击出发！")
                }
            }

            // 选点时实时坐标变化（可用于更新状态栏）
            mapView.setOnMapCenterChangedListener { lat, lng ->
                // 可选：实时更新状态栏显示当前准星坐标
            }

            // 后备：直接点击地图（非选点模式时生效）
            mapView.setOnMapClickListener { lat, lng ->
                if (gameEngine.mode == GameMode.IDLE) {
                    selectedEndLat = lat
                    selectedEndLng = lng
                    hasDestination = true
                    mapView.setDestination(lat, lng)
                    subtitleView.setSubtitle("终点已选定，点击出发！")
                }
            }

            // 如果有上次位置，移动地图
            locationTracker.getLastKnownLocation()?.let { state ->
                mapView.updatePlayerPosition(state.latitude, state.longitude, state.bearing, state.speed)
            }
        }
    }

    private fun beginCountdown() {
        val lastLoc = locationTracker.getLastKnownLocation() ?: return
        gameEngine.startGame(
            lastLoc.latitude, lastLoc.longitude,
            selectedEndLat, selectedEndLng,
            MissionType.SINGLE_END
        )

        startButton.visibility = View.GONE
        subtitleView.setSubtitle("3... 2... 1... 出发！")

        // 倒计时 3 秒
        hudOverlay.postDelayed({ subtitleView.setSubtitle("2...") }, 1000)
        hudOverlay.postDelayed({ subtitleView.setSubtitle("1...") }, 2000)
        hudOverlay.postDelayed({
            gameEngine.countdownFinished()
            startGameLoop()
        }, 3000)
    }

    private fun startGameLoop() {
        locationTracker.startTracking { playerState ->
            gameEngine.updateFrame(playerState)
        }
        hudOverlay.post(gameRunnable)
    }

    private fun gameLoop() {
        val engine = gameEngine
        val player = engine.player
        val circle = engine.circle

        // 更新地图
        if (mapReady) {
            mapView.updatePlayerPosition(player.latitude, player.longitude, player.bearing, player.speedKmh)
            circle?.let {
                mapView.updateThreatCircle(it.centerLat, it.centerLng, it.currentRadius, it.initialRadius)
            }
        }

        // 更新 HUD
        speedGauge.setSpeed(player.speedKmh)
        pandaView.updateSpeed(player.speedKmh)

        // 更新状态栏
        updateStatusBar()
    }

    private fun updateStatusBar() {
        // 可以在这里更新距离终点的距离、时间等信息
    }

    private fun handleGameEnd(success: Boolean, stats: RoundStats) {
        locationTracker.stopTracking()
        hudOverlay.removeCallbacks(gameRunnable)

        // 更新统计
        prefs.totalGamesPlayed++
        if (success) {
            prefs.totalWins++
            prefs.totalStars += stats.starsEarned
        }
        if (stats.maxSpeedKmh > prefs.bestMaxSpeed) {
            prefs.bestMaxSpeed = stats.maxSpeedKmh
        }

        val message = if (success) {
            val stars = "⭐".repeat(stats.starsEarned)
            "消星成功！\n\n" +
                    "最高速度：${String.format("%.1f", stats.maxSpeedKmh)} km/h\n" +
                    "总距离：${String.format("%.1f", stats.totalDistanceM / 1000)} km\n" +
                    "用时：${String.format("%.1f", stats.durationMinutes)} 分钟\n" +
                    "评级：$stars"
        } else {
            "被逮了！\n\n" +
                    "最高速度：${String.format("%.1f", stats.maxSpeedKmh)} km/h\n" +
                    "跑了：${String.format("%.1f", stats.totalDistanceM / 1000)} km\n" +
                    "坚持了：${String.format("%.1f", stats.durationMinutes)} 分钟"
        }

        AlertDialog.Builder(this)
            .setTitle(if (success) "🎉 消星成功" else "🚨 被抓了")
            .setMessage(message)
            .setPositiveButton("再来一局") { _, _ ->
                resetGame()
            }
            .setNeutralButton("自由模式") { _, _ ->
                gameEngine.enterFreeRoam()
                startGameLoop()
                startButton.visibility = View.VISIBLE
                mapView.clearDestination()
            }
            .setNegativeButton("退出") { _, _ ->
                resetGame()
            }
            .show()
    }

    private fun handleRandomEvent(event: RandomEvent) {
        val message = when (event.type) {
            EventType.ROADBLOCK -> "⚠️ 前方路障！"
            EventType.GAS_STATION -> "⛽ 加油站！圈减速10秒"
            EventType.HELICOPTER_REINFORCE -> "🚁 第二架直升机！"
            EventType.POLICE_BLOCKADE -> "🚔 警车拦截！"
            EventType.SHORTCUT -> "🔀 发现捷径！"
            EventType.WEATHER_FOG -> "🌫️ 大雾来袭！"
            EventType.WEATHER_RAIN -> "🌧️ 暴雨！"
            EventType.BGM_SWITCH -> "🎵 BGM 切换！"
            EventType.STAR_SURGE -> "⭐ 星级跳变！"
            EventType.FREEZE_CIRCLE -> "❄️ 圈冻结5秒！"
            EventType.SPEED_BOOST -> "🚀 加速带！圈暂停10秒"
        }

        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        // 天气效果
        when (event.type) {
            EventType.WEATHER_FOG -> mapView.setWeatherEffect("fog")
            EventType.WEATHER_RAIN -> mapView.setWeatherEffect("rain")
            else -> {}
        }
    }

    private fun resetGame() {
        hasDestination = false
        mapView.clearDestination()
        mapView.resetToPickMode()  // 恢复高德风格选点模式
        startButton.visibility = View.VISIBLE
        subtitleView.setSubtitle("拖动地图选择逃跑目的地")
        speedGauge.setSpeed(0f)

        // 重新开始定位（但不启动游戏循环）
        locationTracker.startTracking { playerState ->
            if (mapReady) {
                mapView.updatePlayerPosition(
                    playerState.latitude, playerState.longitude,
                    playerState.bearing, playerState.speedKmh
                )
            }
        }
    }

    // ==================== 权限管理 ====================

    private fun checkPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), LOCATION_PERMISSION_REQUEST)
        } else {
            onPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            val granted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                onPermissionsGranted()
            } else {
                Toast.makeText(this, "需要位置权限才能使用", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onPermissionsGranted() {
        locationTracker.startTracking { playerState ->
            if (mapReady && gameEngine.mode == GameMode.IDLE) {
                mapView.updatePlayerPosition(
                    playerState.latitude, playerState.longitude,
                    playerState.bearing, playerState.speedKmh
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        if (gameEngine.mode == GameMode.PLAYING) {
            locationTracker.stopTracking()
            hudOverlay.removeCallbacks(gameRunnable)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationTracker.stopTracking()
        hudOverlay.removeCallbacks(gameRunnable)
    }
}
