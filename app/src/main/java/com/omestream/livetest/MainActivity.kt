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
import android.widget.FrameLayout
import android.widget.ProgressBar
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
    private lateinit var loading: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var captureToolbar: LinearLayout
    private var interactiveMode = false
    private var pageGeneration = 0
    private var lastHttpError: Int? = null
    private var webError = "none"
    private var observedRequests = 0
    private var lastManifestHost = "none"
    private var attempted = mutableSetOf<String>()
    private var player: ExoPlayer? = null
    private var selected: Candidate? = null
    private var firstFrame = false
    private val site = "https://antena1.live/antena-1-live/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        status = TextView(this)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest
                ): android.webkit.WebResourceResponse? {
                    val uri = request.url
                    val path = uri.path.orEmpty().lowercase()
                    val isMedia = uri.scheme == "https" &&
                        (path.endsWith(".m3u8") || path.endsWith(".mpd"))
                    if (uri.scheme == "https" && uri.host != null) {
                        observedRequests++
                    }

                    if (isMedia && candidates.none {
                            it.url == uri.toString()
                        }) {
                        val candidate = Candidate(
                            uri.toString(),
                            request.requestHeaders.toMap()
                        )
                        candidates.add(candidate)

                        main.post {
                            lastManifestHost = uri.host.orEmpty()
                            report("Manifest detected: " + lastManifestHost)
                            report("Sursa capturata; nu intrerup pagina. Test Media3 disponibil.")
                        }
                    }
                    return null
                }

                override fun onReceivedHttpError(
                    view: WebView?, request: WebResourceRequest?,
                    errorResponse: android.webkit.WebResourceResponse?
                ) {
                    val code = errorResponse?.statusCode ?: return
                    if (code >= 400 && (request?.isForMainFrame == true || code == 401 || code == 403)) {
                        main.post {
                            lastHttpError = code
                            report("Web HTTP=$code host=${request?.url?.host ?: "unknown"}")
                        }
                    }
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        main.post {
                            webError = error?.errorCode?.toString() ?: "unknown"
                            report("Page load error code=$webError")
                        }
                    }
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    report("Page loaded")
                    view?.evaluateJavascript(
                        "(function(){return document.querySelector('video') ? 'video-found' : 'no-video-element';})()"
                    ) { result -> report("HTML video element: $result") }
                }
            }
        }

        root.addView(
            web,
            FrameLayout.LayoutParams(-1, -1)
        )

        video = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = true
            visibility = View.GONE
        }

        root.addView(
            video,
            FrameLayout.LayoutParams(-1, -1)
        )

        loadingText = TextView(this).apply {
            text = "Se cauta sursa LIVE..."
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
        }

        loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF101827.toInt())

            addView(TextView(this@MainActivity).apply {
                text = "OME STREAM"
                textSize = 28f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(30))
            })

            addView(ProgressBar(this@MainActivity))

            addView(loadingText, LinearLayout.LayoutParams(
                -1, dp(70)
            ))

            addView(Button(this@MainActivity).apply {
                text = "Reincarca transmisia"
                setOnClickListener {
                    stopPlayer()
                    firstFrame = false
                    selected = null
                    attempted.clear()
                    candidates.clear()
                    loading.visibility = View.VISIBLE
                    loadingText.text = "Se cauta sursa LIVE..."
                    pageGeneration++
                    lastHttpError = null
                    observedRequests = 0
                    webError = "none"
                    interactiveMode = false
                    web.loadUrl(site)
                }
            })

            addView(Button(this@MainActivity).apply {
                text = "Deschide pagina pentru Play"
                setOnClickListener {
                    interactiveMode = true
                    stopPlayer()
                    candidates.clear()
                    attempted.clear()
                    observedRequests = 0
                    lastHttpError = null
                    webError = "none"
                    firstFrame = false
                    loading.visibility = View.GONE
                    captureToolbar.visibility = View.VISIBLE
                    report("Captura pornita. Apasa Play pe pagina si asteapta 10 secunde.")
                }
            })

            addView(Button(this@MainActivity).apply {
                text = "Diagnostic (fara tokenuri)"
                setOnClickListener {
                    val details = "Requests observed: $observedRequests\n" +
                        "Manifests: ${candidates.size}\n" +
                        "Last manifest host: $lastManifestHost\n" +
                        "Web HTTP error: ${lastHttpError ?: "none"}\n" +
                        "Web error: $webError\n" +
                        "Player running: ${player != null}\n" +
                        "First video frame: $firstFrame\n" +
                        logLines.takeLast(10).joinToString("\n")
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("OME diagnostic")
                        .setMessage(details)
                        .setPositiveButton("Inchide", null)
                        .setNeutralButton("Copiaza") { _, _ ->
                            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("OME diagnostic", details))
                        }.show()
                }
            })
        }

        root.addView(
            loading,
            FrameLayout.LayoutParams(-1, -1)
        )

        // Meniu mic peste WebView: site-ul ramane utilizabil pentru Play.
        captureToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE8101827.toInt())
            visibility = View.GONE
            addView(Button(this@MainActivity).apply {
                text = "Copiaza raport"
                setAllCaps(false)
                setOnClickListener { copySafeReport() }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(Button(this@MainActivity).apply {
                text = "Test Media3"
                setAllCaps(false)
                setOnClickListener {
                    val media = candidates.lastOrNull()
                    if (media == null) {
                        report("Niciun manifest. Apasa Play pe site mai intai.")
                        android.widget.Toast.makeText(this@MainActivity,
                            "Niciun manifest detectat", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        selected = media
                        attempted.add(media.url)
                        loading.visibility = View.VISIBLE
                        loadingText.text = "Test Media3..."
                        playSelected()
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(Button(this@MainActivity).apply {
                text = "Inapoi"
                setAllCaps(false)
                setOnClickListener {
                    captureToolbar.visibility = View.GONE
                    loading.visibility = View.VISIBLE
                }
            }, LinearLayout.LayoutParams(0, dp(52), 1f))
        }
        root.addView(captureToolbar,
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                bottomMargin = dp(32)
            })

        setContentView(root)
        web.loadUrl(site)

        main.postDelayed({
            if (!firstFrame && player == null) {
                loadingText.text =
                    "Nu exista manifest detectat. Deschide pagina pentru Play sau verifica diagnosticul."
            }
        }, 20000)
    }

    private fun copySafeReport() {
        // Niciodata URL-uri complete, query params, tokenuri sau cookie-uri.
        val details = "OME Live Test 0.4 - dupa Play\n" +
            "Requests observed: $observedRequests\n" +
            "Manifests: ${candidates.size}\n" +
            "Last manifest host: $lastManifestHost\n" +
            "Web HTTP error: ${lastHttpError ?: "none"}\n" +
            "Web error: $webError\n" +
            "Player running: ${player != null}\n" +
            "First video frame: $firstFrame\n" +
            "Last events:\n" + logLines.takeLast(16).joinToString("\n")
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("OME safe diagnostic", details))
        AlertDialog.Builder(this).setTitle("Raport copiat")
            .setMessage(details).setPositiveButton("OK", null).show()
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
                loading.visibility = View.GONE
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
                main.post {
                    if (player === p && !firstFrame) retryNext()
                }
            }
        })
        val type = if (uri.path.orEmpty().lowercase().endsWith(".mpd")) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8
        p.setMediaItem(MediaItem.Builder().setUri(media.url).setMimeType(type).build())
        p.prepare()
        p.play()
        main.postDelayed({
            if (player === p && !firstFrame) retryNext()
        }, 15_000)
    }

    private fun retryNext() {
        if (firstFrame) return

        val next = candidates.firstOrNull {
            it.url !in attempted
        }

        if (next != null) {
            attempted.add(next.url)
            selected = next
            loadingText.text = "Se incearca alta sursa..."
            playSelected()
        } else {
            stopPlayer()
            loading.visibility = View.VISIBLE
            loadingText.text =
                "Media3 nu poate reda sursa. Verifica diagnosticul."
        }
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
