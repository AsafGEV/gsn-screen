package com.gsn.screenplayer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        const val API_BASE  = "http://gsn-spark.com/api_v1"
        const val API_KEY   = "Asaf_2026_MainApps_SuperSecret_9981"
        const val HB_TABLE  = "screens_heartbeat"
        const val CFG_TABLE = "screens_registry"
        const val APP_VER   = "2.2"
        const val INTERVAL  = 600000L
        const val RELOAD_DELAY = 3000L
        const val FALLBACK  = "file:///android_asset/fallback.html"
    }

    private lateinit var wv: WebView
    private lateinit var prefs: android.content.SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var deviceId    = ""
    private var hbRecordKey = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        WindowManager.LayoutParams.FLAG_FULLSCREEN)
        hideSystem()
        prefs       = getSharedPreferences("gsn", Context.MODE_PRIVATE)
        deviceId    = prefs.getString("device_id", "") ?: ""
        hbRecordKey = prefs.getString("hb_key", "") ?: ""
        wv = WebView(this)
        setContentView(wv)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            allowFileAccess = true
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                if (url.contains("fallback"))
                    v.evaluateJavascript("try{setDeviceId('" + deviceId + "')}catch(e){}", null)
            }
        }
        if (deviceId.isEmpty()) askName() else start()
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.action == KeyEvent.ACTION_DOWN) {
            when (ev.keyCode) {
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> { manualRefresh(); return true }
                KeyEvent.KEYCODE_BACK -> return true
            }
        }
        return super.dispatchKeyEvent(ev)
    }

    override fun onBackPressed() {}

    override fun onWindowFocusChanged(h: Boolean) {
        super.onWindowFocusChanged(h)
        if (h) hideSystem()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private fun hideSystem() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun askName() {
        val et = android.widget.EditText(this)
        et.setText("screen-1")
        AlertDialog.Builder(this)
            .setTitle("Screen name")
            .setView(et)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                deviceId = et.text.toString().trim().ifEmpty { "screen-1" }
                prefs.edit().putString("device_id", deviceId).apply()
                start()
            }.show()
    }

    private fun start() {
        val lastUrl = prefs.getString("last_url", FALLBACK) ?: FALLBACK
        runOnUiThread { wv.loadUrl(lastUrl) }
        handler.post(object : Runnable {
            override fun run() {
                beat()
                handler.postDelayed(this, INTERVAL)
            }
        })
    }

    private fun manualRefresh() {
        handler.removeCallbacksAndMessages(null)
        beat()
        handler.postDelayed(object : Runnable {
            override fun run() { beat(); handler.postDelayed(this, INTERVAL) }
        }, INTERVAL)
    }

    // JSON built with single-quote template then replace — zero escaping issues
    private fun jq(s: String) = s.replace("'", """)

    private fun beat() {
        if (!isOnline()) { reloadCurrent(); return }
        thread {
            try {
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val data = jq("{'device_id':'" + deviceId + "','last_seen':'" + now + "','app_version':'" + APP_VER + "'}")
                if (hbRecordKey.isEmpty()) {
                    val body = jq("{'app':'" + HB_TABLE + "','data':") + data + jq("}")
                    val resp = httpPost(API_BASE + "/put_record.php", body)
                    val key = parseVal(resp, "record_key")
                    if (key.isNotEmpty()) {
                        hbRecordKey = key
                        prefs.edit().putString("hb_key", hbRecordKey).apply()
                    }
                } else {
                    val body = jq("{'app':'" + HB_TABLE + "','record_key':'" + hbRecordKey + "','data':") + data + jq("}")
                    httpPost(API_BASE + "/update_record.php", body)
                }
                fetchConfig()
                handler.postDelayed({ reloadCurrent() }, RELOAD_DELAY)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun fetchConfig() {
        try {
            val resp = httpGet(API_BASE + "/search_records.php?app=" + CFG_TABLE + "&q=" + deviceId)
            val orient = parseVal(resp, "orientation")
            if (orient.isNotEmpty()) {
                runOnUiThread {
                    requestedOrientation = if (orient == "portrait")
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }
            if (!resp.contains("current_url")) return
            val marker = "current_url" + """ + ":"" // avoid literal " in source
            val idx = resp.indexOf(marker)
            if (idx < 0) return
            val start = idx + marker.length
            val end = resp.indexOf(""", start)
            if (end <= start) return
            val newUrl = resp.substring(start, end)
            if (!newUrl.startsWith("http")) return
            val saved = prefs.getString("last_url", "") ?: ""
            if (newUrl != saved) {
                prefs.edit().putString("last_url", newUrl).apply()
                runOnUiThread { wv.loadUrl(newUrl) }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun reloadCurrent() {
        val url = prefs.getString("last_url", FALLBACK) ?: FALLBACK
        runOnUiThread { wv.loadUrl(url) }
    }

    private fun parseVal(json: String, key: String): String {
        val marker = """ + key + """ + ":"" // built at runtime to avoid escaping
        val i = json.indexOf(marker)
        if (i < 0) return ""
        val s = i + marker.length
        val e = json.indexOf(""", s)
        return if (e > s) json.substring(s, e) else ""
    }

    private fun httpPost(urlStr: String, body: String): String {
        return try {
            val c = URL(urlStr).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-API-Key", API_KEY)
            c.doOutput = true
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.outputStream.write(body.toByteArray())
            c.inputStream.bufferedReader().readText()
        } catch (ex: Exception) { "" }
    }

    private fun httpGet(urlStr: String): String {
        return try {
            val c = URL(urlStr).openConnection() as HttpURLConnection
            c.setRequestProperty("X-API-Key", API_KEY)
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.inputStream.bufferedReader().readText()
        } catch (ex: Exception) { "" }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.activeNetworkInfo?.isConnected == true
    }
}
