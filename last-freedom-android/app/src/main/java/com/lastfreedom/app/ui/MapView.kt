package com.lastfreedom.app.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * 地图 WebView
 * 使用 Leaflet.js + 天地图瓦片渲染卫星地图
 * 通过 JavaScript Bridge 与 Kotlin 交互
 *
 * 选点模式（高德风格）：
 * - 准星固定在屏幕中央
 * - 用户拖动地图对准目标
 * - 防抖 400ms 后更新坐标
 * - 点击确认按钮提交目的地
 */
class MapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private var onMapReady: (() -> Unit)? = null
    private var onMapClick: ((Double, Double) -> Unit)? = null
    private var onMapCenterChanged: ((Double, Double) -> Unit)? = null
    private var onDestinationConfirmed: ((Double, Double) -> Unit)? = null

    init {
        setupWebView()
    }

    private fun setupWebView() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        setWebChromeClient(WebChromeClient())
        setBackgroundColor(Color.BLACK)

        // JavaScript Bridge
        addJavascriptInterface(MapBridge(), "AndroidBridge")
    }

    /**
     * 加载地图
     */
    fun loadMap(tiandituKey: String, onReady: (() -> Unit)? = null) {
        onMapReady = onReady
        loadUrl("file:///android_asset/map.html?key=$tiandituKey")
    }

    /**
     * 更新玩家位置
     */
    fun updatePlayerPosition(lat: Double, lng: Double, bearing: Float, speed: Float) {
        evaluateJavascript(
            "updatePlayer($lat, $lng, $bearing, $speed)", null
        )
    }

    /**
     * 设置终点标记
     */
    fun setDestination(lat: Double, lng: Double) {
        evaluateJavascript("setDestination($lat, $lng)", null)
    }

    /**
     * 更新威胁圈显示
     */
    fun updateThreatCircle(centerLat: Double, centerLng: Double, radius: Double, initialRadius: Double) {
        evaluateJavascript(
            "updateCircle($centerLat, $centerLng, $radius, $initialRadius)", null
        )
    }

    /**
     * 清除终点和圈
     */
    fun clearDestination() {
        evaluateJavascript("clearDestination()", null)
    }

    /**
     * 设置天气效果
     */
    fun setWeatherEffect(type: String) {
        evaluateJavascript("setWeather('$type')", null)
    }

    /**
     * 重置为选点模式（游戏结束后调用）
     */
    fun resetToPickMode() {
        evaluateJavascript("resetToPickMode()", null)
    }

    /**
     * 设置地图点击回调（后备）
     */
    fun setOnMapClickListener(listener: (Double, Double) -> Unit) {
        onMapClick = listener
    }

    /**
     * 设置地图中心变化回调（选点模式拖动时实时触发）
     */
    fun setOnMapCenterChangedListener(listener: (Double, Double) -> Unit) {
        onMapCenterChanged = listener
    }

    /**
     * 设置目的地确认回调（用户点击"确认目的地"按钮时触发）
     */
    fun setOnDestinationConfirmedListener(listener: (Double, Double) -> Unit) {
        onDestinationConfirmed = listener
    }

    /**
     * JavaScript Bridge — WebView 中的 JS 通过 AndroidBridge 调用这些方法
     */
    private inner class MapBridge {
        @JavascriptInterface
        fun onMapReady() {
            post { onMapReady?.invoke() }
        }

        @JavascriptInterface
        fun onMapClick(lat: Double, lng: Double) {
            post { onMapClick?.invoke(lat, lng) }
        }

        /**
         * 选点模式：地图拖动后中心坐标变化（防抖 400ms）
         */
        @JavascriptInterface
        fun onMapCenterChanged(lat: Double, lng: Double) {
            post { onMapCenterChanged?.invoke(lat, lng) }
        }

        /**
         * 选点模式：用户点击"确认目的地"按钮
         */
        @JavascriptInterface
        fun onDestinationConfirmed(lat: Double, lng: Double) {
            post { onDestinationConfirmed?.invoke(lat, lng) }
        }

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("MapView", message)
        }
    }
}
