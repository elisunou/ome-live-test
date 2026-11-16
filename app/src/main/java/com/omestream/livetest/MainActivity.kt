package com.omestream.livetest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

/** OME Live Test: independent, read-only use of existing public channel metadata.
 * The provider page creates its own authorized streaming session in this WebView.
 * No token harvesting, gate automation, stream proxying, or playback bypass.
 */
@SuppressLint("SetJavaScriptEnabled")
class MainActivity : Activity() {
    private data class Channel(val id:String, val name:String, val category:String, val page:String, val logo:String)
    private val channels = mutableListOf<Channel>()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var playerWeb: WebView
    private lateinit var epgWeb: WebView
    private lateinit var mainFrame: FrameLayout
    private lateinit var loading: LinearLayout
    private lateinit var loadingLabel: TextView
    private lateinit var root: LinearLayout
    private lateinit var titleLabel: TextView
    private lateinit var infoLabel: TextView
    private lateinit var sourceLabel: TextView
    private var active: Channel? = null
    private var provider = "Antena Live"
    private var generation = 0
    private var videoReady = false
    private var showOriginalPage = false
    private var requests = 0
    private var manifests = 0
    private var lastHttp = "none"
    private var lastMediaHost = "none"
    private var playerStartedAt = 0L
    private val dark = 0xFF0B1220.toInt()
    private val card = 0xFF172235.toInt()
    private val accent = 0xFFB11F30.toInt()
    private val antenna1 = "https://antena1.live/antena-1-live/"
    private val antennaEpg = "https://antena1.live/ghid-tv/"
    private val rdsEpg = "https://rdslive.net/program-tv-acum/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = dark
        window.navigationBarColor = dark
        parseChannels()
        setupWebViews()
        selectChannel(channels.first(), "Antena Live")
        renderLayout()
    }

    private fun parseChannels() {
        val items = JSONArray(assets.open("ome_live_channels.json").bufferedReader().use { it.readText() })
        for (i in 0 until items.length()) {
            val j = items.getJSONObject(i)
            val page = j.optString("rdsPage")
            if (page.startsWith("https://rdslive.net/")) channels.add(
                Channel(j.optString("id"), j.optString("name"), j.optString("category"), page, j.optString("logo"))
            )
        }
        channels.sortWith(compareBy<Channel> { if (it.name.equals("Antena 1", true)) -1 else 0 }.thenBy { it.name.lowercase() })
        if (channels.none { it.name.equals("Antena 1",true) })
            channels.add(0, Channel("antena1", "Antena 1", "generaliste", antenna1, ""))
    }

    private fun setupWebViews() {
        playerWeb = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            webChromeClient = WebChromeClient()
            webViewClient = object: WebViewClient() {
                override fun shouldInterceptRequest(v:WebView?, request:WebResourceRequest):android.webkit.WebResourceResponse? {
                    val u = request.url
                    if (u.scheme == "https") {
                        synchronized(this@MainActivity) {
                            requests++
                            if (u.path.orEmpty().matches(Regex("(?i).*\\.(m3u8|mpd)$"))) {
                                manifests++
                                lastMediaHost = u.host ?: "unknown"
                            }
                        }
                    }
                    return null
                }
                override fun onReceivedHttpError(v:WebView?,request:WebResourceRequest?,response:android.webkit.WebResourceResponse?) {
                    val code = response?.statusCode ?: return
                    if (code == 401 || code == 403) {
                        lastHttp = "$code @ ${request?.url?.host ?: "unknown"}"
                    }
                }
                override fun onPageFinished(v:WebView?,url:String?) {
                    val run = generation
                    ui.postDelayed({ if (run == generation) updateVideoStatus() }, 1200L)
                }
            }
        }
        epgWeb = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(card)
            webViewClient = WebViewClient()
        }
    }

    private fun dp(n:Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(s:String, size:Float=14f, color:Int=Color.WHITE) = TextView(this).apply {
        text=s; textSize=size; setTextColor(color); setPadding(dp(9),dp(8),dp(9),dp(8))
    }
    private fun button(label:String, action:()->Unit) = Button(this).apply {
        text=label; isAllCaps=false; setOnClickListener { action() }
    }
    private fun detach(view:View) { (view.parent as? ViewGroup)?.removeView(view) }

    private fun renderLayout() {
        val wide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (::root.isInitialized) {
            detach(playerWeb); detach(epgWeb); detach(loading)
        }
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(dark) }
        val header = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setBackgroundColor(card) }
        header.addView(text("OME STREAM  •  LIVE TV", 17f), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Raport") { copyReport() })
        root.addView(header)

        val body=LinearLayout(this).apply { orientation=if(wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        root.addView(body, LinearLayout.LayoutParams(-1,0,1f))

        val channelPanel=buildChannelPanel(wide)
        if(wide) body.addView(channelPanel, LinearLayout.LayoutParams(dp(205),-1))
        else body.addView(channelPanel, LinearLayout.LayoutParams(-1,dp(112)))

        val content=LinearLayout(this).apply { orientation=if(wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        body.addView(content, LinearLayout.LayoutParams(if(wide) 0 else -1,if(wide) -1 else 0,1f))

        mainFrame=FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        detach(playerWeb)
        mainFrame.addView(playerWeb,FrameLayout.LayoutParams(-1,-1))
        loading=createLoading()
        mainFrame.addView(loading,FrameLayout.LayoutParams(-1,-1))
        loading.visibility=if (videoReady || showOriginalPage) View.GONE else View.VISIBLE
        content.addView(mainFrame, LinearLayout.LayoutParams(if(wide) 0 else -1, if(wide) -1 else 0, if(wide) 1f else 1f))
        val details=buildInfoPanel(wide)
        content.addView(details, LinearLayout.LayoutParams(if(wide) dp(255) else -1,if(wide) -1 else dp(215)))
        setContentView(root)
    }

    private fun buildChannelPanel(wide:Boolean):View {
        if(wide) {
            val col=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(card) }
            col.addView(text("CANALE TV  (${channels.size})",14f))
            val sc=ScrollView(this)
            val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            channels.forEach { c -> list.addView(channelButton(c,true)) }
            sc.addView(list); col.addView(sc, LinearLayout.LayoutParams(-1,0,1f))
            return col
        }
        val col=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(card) }
        col.addView(text("CANALE TV  •  derulează pentru mai multe",12f))
        val sc=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=true }
        val list=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        channels.forEach { c -> list.addView(channelButton(c,false)) }
        sc.addView(list); col.addView(sc,LinearLayout.LayoutParams(-1,0,1f))
        return col
    }
    private fun channelButton(c:Channel, wide:Boolean):View = TextView(this).apply {
        text=c.name
        textSize=if(wide) 14f else 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(if(active?.id==c.id) accent else card)
        gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(12),dp(9),dp(12),dp(9))
        layoutParams=if(wide) LinearLayout.LayoutParams(-1,dp(46)) else LinearLayout.LayoutParams(dp(125),dp(55))
        setOnClickListener { selectChannel(c,if(c.name.equals("Antena 1",true)) "Antena Live" else "RDSLive"); renderLayout() }
    }
    private fun buildInfoPanel(wide:Boolean):View {
        val c=active ?: channels.first()
        val info=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(card) }
        titleLabel=text(c.name,19f); info.addView(titleLabel)
        infoLabel=text("Categorie: ${c.category}\nEPG: ${if (provider=="Antena Live") "Antena Live" else "RDSLive"}",12f,0xFFCCD7E5.toInt())
        info.addView(infoLabel)
        sourceLabel=text("Sursă: $provider",12f)
        info.addView(sourceLabel)
        val switchRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        if (c.name.equals("Antena 1",true)) switchRow.addView(button("Antena Live") { selectChannel(c,"Antena Live");renderLayout() },LinearLayout.LayoutParams(0,dp(44),1f))
        switchRow.addView(button("RDSLive") { selectChannel(c,"RDSLive");renderLayout() },LinearLayout.LayoutParams(0,dp(44),1f))
        info.addView(switchRow)
        val actions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        actions.addView(button("Play / pagină") { showOriginalPage=true;loading.visibility=View.GONE },LinearLayout.LayoutParams(0,dp(44),1f))
        actions.addView(button("Reîncarcă") { active?.let { selectChannel(it,provider) } },LinearLayout.LayoutParams(0,dp(44),1f))
        info.addView(actions)
        // EPG provider page is displayed as-is. No invented broadcast times.
        info.addView(text("GHID TV • date de la furnizor",12f))
        val epgActions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        epgActions.addView(button("Vezi EPG") {
            val u=if(provider=="Antena Live") antennaEpg else rdsEpg
            epgWeb.loadUrl(u)
            android.app.AlertDialog.Builder(this).setTitle("Ghid TV · $provider")
                .setView(LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL
                    detach(epgWeb)
                    addView(epgWeb,LinearLayout.LayoutParams(-1,dp(370)))
                }).setPositiveButton("Închide",null).show()
        },LinearLayout.LayoutParams(0,dp(44),1f))
        epgActions.addView(button("Browser") {
            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(if(provider=="Antena Live") antennaEpg else rdsEpg)))
        },LinearLayout.LayoutParams(0,dp(44),1f))
        info.addView(epgActions)
        info.addView(text("Programul curent și următorul se verifică în ghidul furnizorului.",12f,0xFFADBFD3.toInt()))
        return info
    }

    private fun createLoading():LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        gravity=Gravity.CENTER
        setBackgroundColor(0xF20B1220.toInt())
        addView(text("OME STREAM",23f))
        loadingLabel=text("Se încarcă transmisia…",15f)
        addView(loadingLabel)
        addView(button("Afișează playerul site-ului") {
            showOriginalPage=true;visibility=View.GONE
        })
    }
    private fun selectChannel(c:Channel,selectedProvider:String) {
        active=c
        provider=selectedProvider
        generation++
        videoReady=false;showOriginalPage=false;requests=0;manifests=0;lastHttp="none";lastMediaHost="none"
        playerStartedAt=System.currentTimeMillis()
        val url=if(provider=="Antena Live") antenna1 else c.page
        if(::playerWeb.isInitialized) {
            playerWeb.loadUrl(url)
            if(::loading.isInitialized) {loading.visibility=View.VISIBLE;loadingLabel.text="Se încarcă ${c.name}…"}
        }
        val run=generation
        ui.postDelayed({ if(run==generation) updateVideoStatus() },3000L)
        ui.postDelayed({
            if(run==generation && !videoReady && ::loading.isInitialized) {
                loadingLabel.text="Playerul necesită Play sau inițializarea sesiunii."
            }
        },14000L)
    }
    private fun updateVideoStatus() {
        if(!::playerWeb.isInitialized) return
        val run=generation
        val probe="""(function(){var v=document.querySelector('video');if(!v)return 'missing';return v.readyState>=2 && !v.paused && v.videoWidth>0?'playing':'waiting';})()"""
        playerWeb.evaluateJavascript(probe) { result ->
            if (run!=generation) return@evaluateJavascript
            if(result=="\"playing\"") {
                videoReady=true
                if(::loading.isInitialized) loading.visibility=View.GONE
            } else if(!videoReady && System.currentTimeMillis()-playerStartedAt < 30000L) {
                ui.postDelayed({if(run==generation) updateVideoStatus()},1500L)
            }
        }
    }
    private fun copyReport() {
        val report="OME Live Test v0.7\nCanal: ${active?.name}\nFurnizor: $provider\nCereri: $requests\nManifeste: $manifests\nUltimul host media: $lastMediaHost\nHTTP: $lastHttp\nVideo first-frame check: $videoReady\nPagina afișată: $showOriginalPage\nNu sunt exportate tokenuri, cookie-uri sau URL-uri semnate."
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OME report",report))
        Toast.makeText(this,"Raport copiat",Toast.LENGTH_SHORT).show()
    }
    override fun onConfigurationChanged(newConfig:Configuration) {
        super.onConfigurationChanged(newConfig)
        renderLayout() // Preserve same WebView and provider session on rotation.
    }
    override fun onBackPressed() {
        if(playerWeb.canGoBack()) playerWeb.goBack() else super.onBackPressed()
    }
    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        playerWeb.stopLoading();playerWeb.destroy();epgWeb.destroy()
        super.onDestroy()
    }
}
