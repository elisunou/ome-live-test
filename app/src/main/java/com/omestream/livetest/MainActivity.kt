package com.omestream.livetest

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.CopyOnWriteArrayList

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : Activity() {
    private data class Candidate(val url: String, val headers: Map<String, String>)
    private val candidates = CopyOnWriteArrayList<Candidate>()
    private val logLines = mutableListOf<String>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var video: PlayerView
    private lateinit var status: TextView
    private var player: ExoPlayer? = null
    private var selected: Candidate? = null
    private var firstFrame = false
    private val site = "https://antena1.live/antena-1-live/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF10131A.toInt())
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(name: String, task: () -> Unit) {
            val button = Button(this).apply { text = name; setAllCaps(false); setOnClickListener { task() } }
            buttons.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        }
        action("Open Antena") { stopPlayer(); selected = null; candidates.clear(); web.loadUrl(site); report("Page opened") }
        action("Choose stream") { chooseStream() }
        action("Play Media3") { playSelected() }
        action("Copy log") {
            val text = logLines.joinToString("\n")
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("OME Live Test diagnostic", text))
            report("Log copied (redacted)")
        }
        root.addView(buttons)

        video = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = true
            visibility = View.GONE
        }
        root.addView(video, LinearLayout.LayoutParams(-1, 0, 1f))
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    val uri = request.url
                    val path = uri.path.orEmpty().lowercase()
                    if (uri.scheme == "https" && (path.endsWith(".m3u8") || path.endsWith(".mpd"))) {
                        if (candidates.none { it.url == uri.toString() }) {
                            candidates.add(Candidate(uri.toString(), request.requestHeaders.toMap()))
                            main.post { report("Media manifest observed: ${uri.host} type=${if (path.endsWith(".mpd")) "DASH" else "HLS"}; candidates=${candidates.size}") }
                        }
                    }
                    return null // Never alter provider traffic.
                }
                override fun onPageFinished(view: WebView?, url: String?) { report("Page loaded: ${Uri.parse(url).host}") }
            }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF202631.toInt())
            textSize = 12f
            setPadding(16, 12, 16, 12)
            movementMethod = android.text.method.ScrollingMovementMethod()
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(160)))
        setContentView(root)
        report("Ready. Open Antena, interact with site, choose captured manifest.")
        web.loadUrl(site)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun report(message: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { report(message) }; return }
        val clean = message.replace(Regex("(?i)(token|cookie|auth|key|signature)=\\S+"), "$1=[hidden]")
        logLines.add(clean)
        while (logLines.size > 90) logLines.removeAt(0)
        status.text = logLines.takeLast(14).joinToString("\n")
    }

    private fun chooseStream() {
        if (candidates.isEmpty()) { report("No HLS/DASH manifest detected. Interact with site's player first."); return }
        val items = candidates.mapIndexed { i, c ->
            val u = Uri.parse(c.url)
            "${i + 1}. ${u.host} · ${if (u.path.orEmpty().endsWith(".mpd")) "DASH" else "HLS"}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Choose captured manifest")
            .setItems(items) { _, which ->
                selected = candidates[which]
                report("Selected manifest ${which + 1}/${candidates.size} (URL hidden)")
            }.show()
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun playSelected() {
        val media = selected ?: run { report("Choose a manifest first"); return }
        stopPlayer()
        firstFrame = false
        val uri = Uri.parse(media.url)
        // Cookies must be retrieved on the UI thread. They are not logged or exported.
        val cookies = CookieManager.getInstance().getCookie(media.url)
        val headers = mutableMapOf<String, String>()
        media.headers.forEach { (key, value) ->
            if (key.equals("Referer", true) || key.equals("Origin", true)) headers[key] = value
        }
        headers.putIfAbsent("Referer", site)
        if (!cookies.isNullOrBlank()) headers["Cookie"] = cookies
        val agent = media.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: web.settings.userAgentString
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(agent)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(false)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(factory)
            ).build()
        player = p
        video.player = p
        video.visibility = View.VISIBLE
        report("Media3 preparing; cookiesPresent=${!cookies.isNullOrEmpty()}")
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                report("Playback state=${when(state) { Player.STATE_IDLE -> "IDLE"; Player.STATE_BUFFERING -> "BUFFERING"; Player.STATE_READY -> "READY (not proof of picture)"; Player.STATE_ENDED -> "ENDED"; else -> state.toString() }}")
            }
            override fun onTracksChanged(tracks: Tracks) {
                val groups = tracks.groups
                val v = groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                val a = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                report("Tracks: videoGroups=${v.size} audioGroups=${a.size} videoSupported=${v.any { g -> (0 until g.length).any { g.isTrackSupported(it) } }} videoSelected=${v.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }}")
                for (g in v) for (i in 0 until g.length) {
                    val f = g.getTrackFormat(i)
                    report("Video format: codec=${f.codecs ?: "unknown"} mime=${f.sampleMimeType ?: "unknown"} ${f.width}x${f.height} supported=${g.isTrackSupported(i)} selected=${g.isTrackSelected(i)}")
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                report("Video size=${videoSize.width}x${videoSize.height}")
            }
            override fun onRenderedFirstFrame() {
                firstFrame = true
                report("FIRST VIDEO FRAME RENDERED ✅")
            }
            override fun onPlayerError(error: PlaybackException) {
                var cause: Throwable? = error
                var httpCode = "none"
                while (cause != null) {
                    if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) httpCode = cause.responseCode.toString()
                    cause = cause.cause
                }
                report("PLAYER ERROR code=${error.errorCodeName} HTTP=$httpCode firstFrame=$firstFrame")
            }
        })
        val type = if (uri.path.orEmpty().lowercase().endsWith(".mpd")) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8
        p.setMediaItem(MediaItem.Builder().setUri(media.url).setMimeType(type).build())
        p.prepare()
        p.play()
        main.postDelayed({ if (player === p && !firstFrame) report("No first video frame within 15 seconds; inspect tracks/codec/HTTP") }, 15_000)
    }

    private fun stopPlayer() {
        video.player = null
        player?.release()
        player = null
        video.visibility = View.GONE
    }

    override fun onDestroy() {
        stopPlayer()
        main.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
}
