package com.omestream.livetest

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Standalone Live TV UI test. No token forging, challenge automation, proxy or gate bypass.
 * Media3 playback is attempted only for a request from an already initiated provider session.
 */
@SuppressLint("SetJavaScriptEnabled")
class MainActivity : Activity() {
    private data class Channel(val id: String, val name: String, val category: String, val page: String)
    private data class Source(val url: String, val headers: Map<String, String>, val name: String)
    private val channels = mutableListOf<Channel>()
    private val sources = CopyOnWriteArrayList<Source>()
    private val requestCount = AtomicInteger()
    private val handler = Handler(Looper.getMainLooper())
    private var channel: Channel? = null
    private var category = "Toate"
    private var provider = "Antena Live"
    private var generation = 0
    private var attempted = mutableSetOf<String>()
    private var lastHttp = "none"
    private var lastStage = "unknown"
    private var firstFrame = false
    private var webFirstFrame = false
    private var player: ExoPlayer? = null
    private lateinit var web: WebView
    private lateinit var video: PlayerView
    private lateinit var root: LinearLayout
    private lateinit var videoFrame: FrameLayout
    private lateinit var overlay: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var channelLabel: TextView
    private lateinit var epgLabel: TextView
    private var webVisible = false
    private val navy = 0xFF111A2B.toInt()
    private val panel = 0xFF1B2940.toInt()
    private val red = 0xFFB82035.toInt()
    private val grey = 0xFFCDDAE8.toInt()
    private val antenna = "https://antena1.live/antena-1-live/"
    private val antennaGuide = "https://antena1.live/ghid-tv/"
    private val rdsGuide = "https://rdslive.net/program-tv-acum/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        readChannels()
        setupPlayers()
        channel = channels.firstOrNull { it.name.equals("Antena 1", true) } ?: channels.firstOrNull()
        buildUi()
        channel?.let { openChannel(it, if (it.name.equals("Antena 1", true)) "Antena Live" else "RDSLive") }
    }

    private fun readChannels() {
        val entries = JSONArray(assets.open("ome_live_channels.json").bufferedReader().use { it.readText() })
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val page = e.optString("rdsPage")
            if (page.startsWith("https://rdslive.net/")) {
                channels.add(Channel(e.optString("id"), e.optString("name"), e.optString("category", "diverse"), page))
            }
        }
        if (channels.none { it.name.equals("Antena 1", true) }) {
            channels.add(0, Channel("antena1", "Antena 1", "generaliste", antenna))
        }
        channels.sortWith(compareBy<Channel> { if (it.name.equals("Antena 1", true)) -1 else 0 }.thenBy { it.name.lowercase() })
    }

    private fun setupPlayers() {
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.scheme != "https") return null
                    requestCount.incrementAndGet()
                    val uri = request.url
                    val path = uri.path.orEmpty().lowercase()
                    val format = when {
                        path.endsWith(".m3u8") -> "HLS"
                        path.endsWith(".mpd") -> "DASH"
                        else -> null
                    }
                    if (format != null) {
                        val item = Source(uri.toString(), request.requestHeaders.toMap(), "$format • ${uri.host}")
                        if (sources.none { it.url == item.url }) {
                            sources.add(item)
                            handler.post { if (::sourceLabel.isInitialized) sourceLabel.text = "Surse detectate: ${sources.size} • ${item.name}" }
                        }
                    }
                    return null // Do not alter provider traffic.
                }
                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    val status = errorResponse?.statusCode ?: return
                    if (status == 401 || status == 403) {
                        handler.post { lastHttp = "HTTP $status • ${request?.url?.host ?: "unknown"}" }
                    }
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    inspectVideo(generation)
                }
            }
        }
        video = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = true
            visibility = View.GONE
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float = 14f, color: Int = Color.WHITE): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        setPadding(dp(10), dp(9), dp(10), dp(9))
    }
    private fun button(name: String, click: () -> Unit) = Button(this).apply {
        text = name; isAllCaps = false; setOnClickListener { click() }
    }
    private fun removeFromParent(view: View) { (view.parent as? ViewGroup)?.removeView(view) }

    private fun buildUi() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (::root.isInitialized) {
            removeFromParent(web); removeFromParent(video)
        }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(navy) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(panel) }
        top.addView(label("OME STREAM  |  LIVE TV", 19f), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("Raport") { copyReport() })
        root.addView(top)
        val content = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val left = buildChannelList(landscape)
        content.addView(left, if (landscape) LinearLayout.LayoutParams(dp(210), -1) else LinearLayout.LayoutParams(-1, dp(158)))
        val right = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        content.addView(right, LinearLayout.LayoutParams(if (landscape) 0 else -1, if (landscape) -1 else 0, 1f))
        videoFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        removeFromParent(web); removeFromParent(video)
        videoFrame.addView(web, FrameLayout.LayoutParams(-1, -1))
        videoFrame.addView(video, FrameLayout.LayoutParams(-1, -1))
        video.visibility = if (player != null) View.VISIBLE else View.GONE
        web.visibility = if (webVisible) View.VISIBLE else View.INVISIBLE
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF7111A2B.toInt())
            addView(label("OME STREAM", 24f))
            loadingText = label("Se încarcă transmisia…", 15f, grey)
            addView(loadingText)
            addView(button("Deschide pagina pentru Play") { showWebForSession() })
            addView(button("Surse detectate") { selectSource() })
        }
        videoFrame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.visibility = if (firstFrame || webVisible) View.GONE else View.VISIBLE
        right.addView(videoFrame, if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        val info = buildInfo()
        right.addView(info, if (landscape) LinearLayout.LayoutParams(dp(260), -1) else LinearLayout.LayoutParams(-1, dp(235)))
        setContentView(root)
    }

    private fun buildChannelList(landscape: Boolean): View {
        val frame = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(panel) }
        frame.addView(label("CANALE TV  •  ${channels.size}", 14f))
        val filterBar = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val categories = listOf("Toate") + channels.map { it.category }.distinct().sorted()
        for (cat in categories) chips.addView(button("$cat (${if(cat == "Toate") channels.size else channels.count { it.category == cat }})") {
            category = cat; buildUi()
        })
        filterBar.addView(chips)
        frame.addView(filterBar, LinearLayout.LayoutParams(-1, dp(55)))
        val matched = channels.filter { category == "Toate" || it.category == category }
        val list: View
        if (landscape) {
            val scroll = ScrollView(this)
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (ch in matched) column.addView(channelButton(ch, true))
            scroll.addView(column); list = scroll
        } else {
            val scroll = HorizontalScrollView(this)
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (ch in matched) line.addView(channelButton(ch, false))
            scroll.addView(line); list = scroll
        }
        frame.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        return frame
    }

    private fun channelButton(item: Channel, landscape: Boolean): View = TextView(this).apply {
        text = item.name; textSize = 13f; gravity = Gravity.CENTER_VERTICAL
        setTextColor(Color.WHITE)
        setBackgroundColor(if (channel?.id == item.id) red else panel)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        layoutParams = if(landscape) LinearLayout.LayoutParams(-1, dp(48)) else LinearLayout.LayoutParams(dp(140), dp(48))
        setOnClickListener {
            openChannel(item, if (item.name.equals("Antena 1", true)) "Antena Live" else "RDSLive")
            buildUi()
        }
    }

    private fun buildInfo(): View {
        val c = channel
        val scroller = ScrollView(this).apply { setBackgroundColor(panel) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(label(c?.name ?: "Alege canalul", 20f))
        channelLabel = label("${c?.category ?: ""}  •  $provider", 13f, grey)
        column.addView(channelLabel)
        sourceLabel = label("Surse detectate: ${sources.size}", 13f)
        column.addView(sourceLabel)
        val providers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (c?.name.equals("Antena 1", true)) {
            providers.addView(button("Antena Live") { c?.let { openChannel(it, "Antena Live") } }, LinearLayout.LayoutParams(0, dp(45), 1f))
        }
        providers.addView(button("RDSLive") { c?.let { openChannel(it, "RDSLive") } }, LinearLayout.LayoutParams(0, dp(45), 1f))
        column.addView(providers)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Surse / Calitate") { selectSource() }, LinearLayout.LayoutParams(0, dp(47), 1f))
        actions.addView(button("Play / sesiune") { showWebForSession() }, LinearLayout.LayoutParams(0, dp(47), 1f))
        column.addView(actions)
        column.addView(label("GHID TV / EPG", 14f))
        epgLabel = label("Programul curent și următorul: consultă ghidul furnizorului.", 12f, grey)
        column.addView(epgLabel)
        column.addView(button("Deschide EPG ${if (provider == "Antena Live") "Antena" else "RDSLive"}") {
            val guide = if (provider == "Antena Live") antennaGuide else rdsGuide
            val epg = WebView(this).apply { settings.javaScriptEnabled = true; loadUrl(guide) }
            AlertDialog.Builder(this).setTitle("Ghid TV • $provider")
                .setView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(epg, LinearLayout.LayoutParams(-1, dp(420))) })
                .setPositiveButton("Închide") { _, _ -> epg.destroy() }.show()
        })
        scroller.addView(column)
        return scroller
    }

    private fun openChannel(item: Channel, service: String) {
        channel = item; provider = service
        generation++
        stopNative()
        sources.clear(); attempted.clear(); firstFrame = false; webFirstFrame = false
        requestCount.set(0); lastHttp = "none"; lastStage = "unknown"
        webVisible = false
        if (::loadingText.isInitialized) loadingText.text = "Se încarcă ${item.name}…"
        if (::overlay.isInitialized) overlay.visibility = View.VISIBLE
        if (::web.isInitialized) {
            web.visibility = View.INVISIBLE
            web.loadUrl(if (service == "Antena Live") antenna else item.page)
        }
        val run = generation
        handler.postDelayed({
            if (run == generation && !firstFrame && ::loadingText.isInitialized) {
                loadingText.text = "Apasă «Play / sesiune» pentru inițializarea normală a transmisiei."
            }
        }, 12000)
    }

    private fun showWebForSession() {
        stopNative()
        webVisible = true
        web.visibility = View.VISIBLE
        overlay.visibility = View.GONE
        Toast.makeText(this, "Apasă Play pe site. Apoi alege Surse / Calitate.", Toast.LENGTH_LONG).show()
        inspectVideo(generation)
    }

    private fun inspectVideo(run: Int) {
        if (!::web.isInitialized) return
        web.evaluateJavascript("(function(){var v=document.querySelector('video');return v&&v.readyState>=2&&!v.paused&&v.videoWidth>0?'playing':'waiting';})()") { answer ->
            if (run != generation) return@evaluateJavascript
            webFirstFrame = answer == "\"playing\""
        }
    }

    private fun selectSource() {
        val list = sources.toList()
        if (list.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Surse")
                .setMessage("Nu există încă un manifest detectat. Deschide pagina pentru Play și pornește canalul înainte de redarea în Media3.")
                .setPositiveButton("Deschide Play") { _, _ -> showWebForSession() }
                .setNegativeButton("Închide", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Surse detectate • ${list.size}")
            .setItems(list.mapIndexed { idx, source -> "${idx + 1}. ${source.name}" }.toTypedArray()) { _, which ->
                val item = list[which]
                attempted.add(item.url)
                playNative(item)
            }.setNegativeButton("Anulează", null).show()
    }

    private fun pauseWebVideo() {
        // No automatic consent/gate interaction; just prevent two concurrent playbacks.
        web.evaluateJavascript("(function(){document.querySelectorAll('video').forEach(function(v){v.pause()})})()", null)
    }

    private fun playNative(source: Source) {
        stopNative()
        firstFrame = false
        webVisible = false
        pauseWebVideo()
        web.visibility = View.INVISIBLE
        overlay.visibility = View.VISIBLE
        loadingText.text = "Media3 se conectează…"
        val cookies = CookieManager.getInstance().getCookie(source.url)
        val allowed = mutableMapOf<String, String>()
        source.headers.forEach { (key, value) ->
            if (key.equals("Referer", true) || key.equals("Origin", true)) allowed[key] = value
        }
        allowed.putIfAbsent("Referer", web.url ?: antenna)
        if (!cookies.isNullOrEmpty()) allowed["Cookie"] = cookies
        val agent = source.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: web.settings.userAgentString
        val factory = DefaultHttpDataSource.Factory().setUserAgent(agent).setDefaultRequestProperties(allowed)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()
        player = p
        video.player = p
        video.visibility = View.VISIBLE
        p.addListener(object: Player.Listener {
            override fun onRenderedFirstFrame() {
                if (player !== p) return
                firstFrame = true
                overlay.visibility = View.GONE
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player !== p) return
                var cause: Throwable? = error
                while (cause != null) {
                    if (cause is HttpDataSource.InvalidResponseCodeException) {
                        val u = cause.dataSpec.uri
                        lastHttp = "HTTP ${cause.responseCode} • ${u.host}"
                        lastStage = when {
                            u.path.orEmpty().lowercase().endsWith(".m3u8") || u.path.orEmpty().lowercase().endsWith(".mpd") -> "manifest"
                            u.path.orEmpty().lowercase().endsWith(".ts") || u.path.orEmpty().lowercase().endsWith(".m4s") -> "segment"
                            else -> "media/other"
                        }
                        break
                    }
                    cause = cause.cause
                }
                stopNative()
                overlay.visibility = View.VISIBLE
                loadingText.text = "Media3: $lastHttp ($lastStage). Redeschide pagina pentru o sesiune autorizată."
            }
        })
        val mime = if (Uri.parse(source.url).path.orEmpty().lowercase().endsWith(".mpd")) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8
        p.setMediaItem(MediaItem.Builder().setUri(source.url).setMimeType(mime).build())
        p.prepare(); p.play()
    }

    private fun stopNative() {
        if (!::video.isInitialized) return
        video.player = null
        player?.release(); player = null
        video.visibility = View.GONE
    }

    private fun copyReport() {
        val report = "OME Live Test 0.8\nCanal: ${channel?.name}\nCategorie: $category\nFurnizor: $provider\nCereri: ${requestCount.get()}\nSurse: ${sources.size}\nWeb video: $webFirstFrame\nMedia3 primul cadru: $firstFrame\nEroare: $lastHttp\nEtapa: $lastStage\nNu include tokenuri, cookie-uri sau URL-uri media."
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OME diagnostic", report))
        Toast.makeText(this, "Raport copiat", Toast.LENGTH_SHORT).show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi() // Retain the same WebView, native player and session during rotation.
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopNative()
        web.destroy()
        super.onDestroy()
    }
}
