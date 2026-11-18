package com.omestrem.media;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** A visible site session owns verification/renewal. ExoPlayer only consumes media. */
@SuppressLint({"SetJavaScriptEnabled", "UnsafeOptInUsageError"})
public final class RdsLiveActivity extends Activity {
    private static final String ANTENA_TEST_PAGE = "https://antena1.live/antena-1-live/";
    /** Hides common ad containers during page load; content and player stay untouched. */
    private static final String AD_HIDE_CSS = "iframe[src*='ads'],iframe[id*='ads'],"
            + "iframe[src*='doubleclick'],iframe[src*='googlesyndication'],"
            + "iframe[src*='adservice'],iframe[src*='adserver'],iframe[src*='taboola'],"
            + "iframe[src*='outbrain'],ins.adsbygoogle,div[id*='google_ads'],"
            + "div[class*='-ad-'],div[id*='-ad-'],div[class*='advert'],div[id*='advert'],"
            + "div[class*='banner-ad'],div[id*='banner-ad'],div[class*='ad-slot'],"
            + "div[class*='adsby'],a[href*='/ads/']{display:none!important;height:0!important}";
    private static final String AD_HIDE_JS = "try{var s=document.createElement('style');"
            + "s.textContent=" + JSONObject.quote(AD_HIDE_CSS) + ";"
            + "(document.head||document.documentElement).appendChild(s);}catch(e){}";
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView browser;
    private PlayerView video;
    private ExoPlayer player;
    private TextView status;
    private FrameLayout stage;
    private LinearLayout chrome;
    private Button sourceButton, qualityButton, fillButton;
    private boolean fillScreen;
    private LinearLayout preparing;
    private Button providerActionButton;
    private JSONArray sources = new JSONArray();
    private JSONArray iptvOrgStreams = new JSONArray();
    private boolean iptvOrgSelected;
    private boolean iptvOnly;
    private boolean antenaTest, antenaSelected;
    private volatile boolean antennaV2Captured;
    private boolean antennaV2Playback;
    private int antennaV2Retries;
    private JSONObject antennaV2Headers;
    private boolean antennaWebSuspended;
    private boolean antennaReloadStopped;
    private long lastAntennaPageStart;
    private int rapidAntennaReloads;
    private int antennaSessionAutoRetries;
    private boolean antennaSessionRetryPending;
    private int selectedIptvOrgSource = -1;
    private JSONObject iptvOrgSource;
    private int selectedSource;
    private int pendingRdsSource = -1;
    private boolean externalSiteSelected;
    private String selectedQuality = "", actualQuality = "";
    private String page, rdsPage, antenaPage, script, tvZonePage;
    private String channelTitle, channelCategory, channelQuery, channelId;
    private JSONArray channelOrder = new JSONArray();
    private boolean channelZap;
    private Intent pendingChannel;
    private float touchStartX, touchStartY;
    private volatile RdsLiveStream session;
    private int pageGeneration, retries, observedRevision;
    private int loggedTokenRequests = -1, loggedTokenGranted = -1, loggedTokenRejected = -1;
    private boolean nativeMode = true, nativeRequested = true, stopped, polling, loading, closed;
    private boolean closingPlayback;
    private boolean tvControls;
    private OnBackInvokedCallback backCallback;
    private long bufferingSince, readySince;
    private final Runnable hidePlaybackControls = () -> {
        if (!closed && !closingPlayback && player != null
                && player.getPlaybackState() == Player.STATE_READY
                && video != null && video.getVisibility() == View.VISIBLE) {
            video.hideController();
            chrome.setVisibility(View.GONE);
        }
    };
    private final Runnable finishAfterClose = () -> {
        if (isFinishing()) return;
        if (pendingChannel != null) {
            Intent next = pendingChannel;
            pendingChannel = null;
            if (browser != null) {
                stage.removeView(browser);
                browser.destroy();
                browser = null;
            }
            startActivity(next);
            overridePendingTransition(0, 0);
        }
        finish();
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Theme.applyPreferences(this);
        tvControls = getResources().getBoolean(R.bool.is_tv_build)
                || getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK);
        fillScreen = getPreferences(MODE_PRIVATE).getBoolean("rds_fill_screen", false);
        rdsPage = getIntent().getStringExtra("page");
        page = rdsPage;
        tvZonePage = getIntent().getStringExtra("tvZonePage");
        if (TvZoneCatalog.validPage(page)) tvZonePage = page;
        channelTitle = getIntent().getStringExtra("title");
        channelId = getIntent().getStringExtra("channelId");
        // Each channel keeps its own page; no stream token is bundled in the APK.
        antenaPage = getIntent().getStringExtra("antenaPage");
        if ("rds:antena1-hd-ro".equals(channelId)
                && !AntenaLiveCatalog.validPage(antenaPage)) antenaPage = ANTENA_TEST_PAGE;
        antenaTest = AntenaLiveCatalog.validPage(antenaPage);
        antenaSelected = antenaTest && getIntent().getBooleanExtra("initialAntena", true);
        if (antennaDirectTest()) antenaSelected = true;
        if (antenaSelected) page = antenaPage;
        // A fresh start requested for TVZone (program TV) overrides Antena/RDS.
        if (getIntent().getBooleanExtra("initialTvZone", false)
                && TvZoneCatalog.validPage(tvZonePage)) {
            antenaSelected = false;
            page = tvZonePage;
        }
        try {
            String savedIptvSources = getIntent().getStringExtra("iptvOrgStreams");
            if (savedIptvSources != null) iptvOrgStreams = new JSONArray(savedIptvSources);
        } catch (Exception ignored) {}
        channelZap = getIntent().getBooleanExtra("channelZap", false);
        channelCategory = getIntent().getStringExtra("channelCategory");
        channelQuery = getIntent().getStringExtra("channelQuery");
        pendingRdsSource = getIntent().getIntExtra("initialRdsSource", -1);
        iptvOnly = "iptvorg:voyosport1".equals(channelId) && iptvOrgStreams.length() > 0;
        if (!iptvOnly && !validSessionPage(page)) { finish(); return; }
        externalSiteSelected = TvZoneCatalog.validPage(page);
        channelOrder = "__favorites__".equals(channelCategory)
                ? IptvOrgCatalog.withSources(LiveTvFavoriteStore.all(this, channelQuery))
                : IptvOrgCatalog.channelOrder(channelCategory, channelQuery);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setBackgroundColor(0xB8000000);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                chrome.setPadding(bars.left, bars.top, bars.right, 0);
                return insets;
            });
        }
        LinearLayout controls = new LinearLayout(this);
        addButton(controls, "Înapoi", this::closePlayer);
        sourceButton = addButton(controls, antenaSelected ? "Surse · Antena Live"
                : "Surse · S1", this::showSources);
        qualityButton = addButton(controls, "Calitate", this::showQualities);
        fillButton = addButton(controls, fillScreen ? "Fit / Umple ✓" : "Fit ✓ / Umple", this::toggleFillScreen);
        addButton(controls, "Reîncarcă", () -> {
            if (iptvOrgSelected) {
                playIptvOrg(selectedIptvOrgSource);
                return;
            }
            if (externalSiteSelected) {
                if (browser != null) browser.reload();
                return;
            }
            nativeMode = true;
            nativeRequested = true;
            antennaSessionAutoRetries = 0;
            loadPage();
        });
        chrome.addView(controls);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setPadding(12, 8, 12, 8);
        chrome.addView(status);
        stage = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        root.addView(chrome, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP));
        browser = new WebView(this);
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (antennaDirectTest()) settings.setMediaPlaybackRequiresUserGesture(false);
        browser.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,
                    WebResourceRequest request) {
                if (!antennaDirectTest() || !antenaSelected || closingPlayback
                        || antennaV2Captured) return null;
                Uri uri = request.getUrl();
                String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(java.util.Locale.ROOT);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                        || !(path.endsWith(".m3u8") || path.endsWith(".mpd"))) return null;
                antennaV2Captured = true;
                java.util.Map<String, String> headers = new java.util.HashMap<>(request.getRequestHeaders());
                final String streamUrl = uri.toString();
                main.post(() -> playCapturedAntennaStream(streamUrl, headers));
                return null;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                // Keep the verification session on the original HTTPS site.
                if (!"https".equals(uri.getScheme())) return true;
                return !"rdslive.net".equals(uri.getHost())
                        && !(antenaTest && "antena1.live".equals(uri.getHost()));
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                pageGeneration++;
                android.util.Log.i("OmeRdsDiag", "page-start generation=" + pageGeneration
                        + " path=" + Uri.parse(url).getPath());
                if (closingPlayback) return;
                if (iptvOrgSelected) return;
                if (antennaReloadStopped) return;
                if (antenaSelected && !externalSiteSelected && page.equals(url)) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (session != null || now - lastAntennaPageStart > 5000)
                        rapidAntennaReloads = 0;
                    lastAntennaPageStart = now;
                    if (++rapidAntennaReloads >= 4) {
                        antennaReloadStopped = true;
                        android.util.Log.w("OmeRds", "Antena Live reloaded repeatedly; switching source");
                        if (RdsLiveCatalog.validPage(rdsPage)) {
                            showPreparing("Antena Live se reîncarcă repetat. Încerc RDSLive…");
                            main.post(() -> {
                                if (!closed && !closingPlayback) openFreshSource(0, false);
                            });
                        } else {
                            view.stopLoading();
                            showPreparing("Antena Live nu răspunde. Alege altă sursă sau Reîncarcă.");
                        }
                        return;
                    }
                }
                session = null; observedRevision = 0; retries = 0;
                if (player != null) player.stop();
                if (externalSiteSelected) showPreparing("Se pregătește TVZone…");
                else if (nativeMode && nativeRequested) showPreparing("Se pregătește transmisia…");
                else showSession("Se încarcă sesiunea RDSLive…");
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (closingPlayback && "about:blank".equals(url)) {
                    main.removeCallbacks(finishAfterClose);
                    // Let pagehide keepalive leave WebView before destroying it.
                    main.postDelayed(finishAfterClose, 500);
                    return;
                }
                if (closed) return;
                if (antennaReloadStopped) return;
                if (iptvOrgSelected) return;
                if (externalSiteSelected && "about:blank".equals(url)) {
                    loading = false;
                    resolveTvZone();
                    return;
                }
                if (!validSessionPage(url)) return;
                loading = false;
                view.evaluateJavascript(script, null);
                if (nativeMode && nativeRequested) {
                    // Ads stay hidden while the page prepares the stream.
                    view.evaluateJavascript(AD_HIDE_JS, null);
                }
                view.evaluateJavascript("window.dispatchEvent(new Event('mousemove'))", null);
                view.evaluateJavascript("window.__omeRds && window.__omeRds.useNative("
                        + nativeMode + ")", null);
                if (antenaSelected && page.equals(url) && session == null) {
                    if (nativeRequested) {
                        // Keep the provider page covered while it hands the stream to ExoPlayer.
                        final int generation = pageGeneration;
                        showPreparing("Se pornește canalul…");
                        if (antennaDirectTest()) {
                            // Allow the site's player to request its own manifest once.
                            // The page remains covered by OME's waiting overlay.
                            view.evaluateJavascript("window.__omeRds && window.__omeRds.useNative(false)", null);
                        }
                        main.postDelayed(() -> {
                            if (!closed && !stopped && generation == pageGeneration
                                    && !antennaV2Captured && antenaSelected)
                                showProviderAction(antennaDirectTest()
                                        ? "Test v2: pagina nu a cerut un manifest HLS/DASH. Încearcă din nou."
                                        : "Canalul încă se pregătește. Încearcă din nou sau alege altă sursă.");
                        }, 20000);
                    } else showSession("Antena Live · apasă Pornește pentru a iniția sesiunea site-ului.");
                } else status.setText(channelTitle
                        + " · Se pregătește redarea… Dacă apare un cod QR, confirmă-l cu telefonul.");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                   WebResourceError error) {
                if (iptvOrgSelected) return;
                if (request.isForMainFrame()) {
                    loading = false;
                    showSession("Pagina nu răspunde. Verifică internetul și apasă Reîncarcă.");
                }
            }
            @Override public boolean onRenderProcessGone(WebView view,
                    android.webkit.RenderProcessGoneDetail detail) {
                // The WebView cannot be reused after a renderer crash.
                if (player != null) player.stop();
                session = null; closed = true;
                stage.removeView(view); view.destroy(); browser = null;
                status.setText("Sesiunea web s-a închis. Revino la canale și redeschide postul.");
                return true;
            }
        });
        // Do not expose a JavascriptInterface to third-party pages or embedded frames.
        // v2 experiment: attached for JS/cookies, but has no drawable area.
        stage.addView(browser, antennaDirectTest()
                ? new FrameLayout.LayoutParams(0, 0)
                : new FrameLayout.LayoutParams(-1, -1));
        video = new PlayerView(this);
        video.setResizeMode(fillScreen ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        video.setBackgroundColor(Color.BLACK);
        video.setShutterBackgroundColor(Color.BLACK);
        video.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        video.setControllerShowTimeoutMs(tvControls ? 2000 : 4000);
        video.setShowNextButton(false);
        video.setShowPreviousButton(false);
        video.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            if (video.getVisibility() == View.VISIBLE) chrome.setVisibility(visibility);
        });
        stage.addView(video, new FrameLayout.LayoutParams(-1, -1));
        preparing = new LinearLayout(this);
        preparing.setOrientation(LinearLayout.VERTICAL);
        preparing.setGravity(android.view.Gravity.CENTER);
        preparing.setBackgroundColor(Color.BLACK);
        preparing.addView(new android.widget.ProgressBar(this));
        TextView preparingTitle = new TextView(this);
        preparingTitle.setText(channelTitle + "\nSe pregătește transmisia…");
        preparingTitle.setGravity(android.view.Gravity.CENTER);
        preparingTitle.setTextColor(Color.WHITE);
        preparingTitle.setTextSize(18);
        preparingTitle.setPadding(20, 24, 20, 0);
        preparing.addView(preparingTitle);
        providerActionButton = new Button(this);
        providerActionButton.setText("Încearcă din nou");
        providerActionButton.setVisibility(View.GONE);
        providerActionButton.setOnClickListener(v -> loadPage());
        preparing.addView(providerActionButton);
        stage.addView(preparing, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backCallback = this::closePlayer;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        enterFullscreen();
        try (InputStream input = getAssets().open("rdslive-session.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            script = output.toString("UTF-8");
        } catch (Exception error) { finish(); return; }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(browser, script,
                    antenaTest ? new java.util.HashSet<>(java.util.Arrays.asList(
                            "https://rdslive.net", "https://antena1.live"))
                            : java.util.Collections.singleton("https://rdslive.net"));
        } else {
            // Older WebViews can still use the provider's complete player.
            nativeMode = false; nativeRequested = false;
        }
        if (iptvOnly) playIptvOrg(0);
        else loadPage();
    }

    private Button addButton(LinearLayout bar, String title, Runnable click) {
        Button button = new Button(this);
        button.setText(title); button.setAllCaps(false); button.setTextSize(12);
        // Match the unobtrusive RDS Test player toolbar. Keep every action
        // unchanged, including the full-screen crop control in place of Sesiune.
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setStateListAnimator(null);
        button.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        button.setOnFocusChangeListener((view, focused) -> {
            button.setTextColor(focused ? Theme.ACCENT : Color.WHITE);
            if (tvControls && focused) {
                GradientDrawable focus = Theme.box(0xAA22212C, 10, this);
                focus.setStroke((int) (3 * getResources().getDisplayMetrics().density), Color.WHITE);
                button.setBackground(focus);
            } else button.setBackgroundColor(focused ? 0x66333333 : Color.TRANSPARENT);
        });
        button.setOnClickListener(view -> { if (!closed || "Înapoi".equals(title)) click.run(); });
        bar.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }

    private void loadPage() {
        if (closed || closingPlayback || browser == null) return;
        if (antennaDirectTest()) {
            // Experimental v2 only: a real, attached WebView obtains the page session.
            // Never expose its page; only its own media request is handed to Media3.
            antennaV2Captured = false;
            antennaV2Playback = false;
            antennaV2Headers = null;
            if (player != null) { player.release(); player = null; video.setPlayer(null); }
            session = null;
            loading = true;
            pageGeneration++;
            showPreparing("Test v2 · se obține fluxul prin sesiunea Antena Live…");
            browser.loadUrl(antenaPage);
            return;
        }
        antennaReloadStopped = false;
        rapidAntennaReloads = 0;
        pageGeneration++; retries = 0; observedRevision = 0; session = null;
        loading = true; bufferingSince = 0;
        if (player != null) player.stop();
        if (nativeMode && nativeRequested) showPreparing("Se pregătește transmisia…");
        else showSession("Se încarcă sesiunea RDSLive…");
        browser.loadUrl(externalSiteSelected ? "about:blank" : page);
    }

    private boolean antennaDirectTest() {
        return BuildConfig.APPLICATION_ID.endsWith(".rdslivetest")
                && antenaTest && AntenaLiveCatalog.validPage(antenaPage);
    }

    private void playCapturedAntennaStream(String url, java.util.Map<String, String> requestHeaders) {
        if (closed || stopped || closingPlayback || !antennaDirectTest() || browser == null) return;
        try {
            antennaV2Headers = new JSONObject();
            for (String key : new String[]{"User-Agent", "Referer", "Origin"}) {
                for (java.util.Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                    if (key.equalsIgnoreCase(entry.getKey()))
                        antennaV2Headers.put(key, entry.getValue());
                }
            }
            if (!antennaV2Headers.has("Referer")) antennaV2Headers.put("Referer", antenaPage);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) antennaV2Headers.put("Cookie", cookies);
            android.util.Log.i("OmeAntenaV2", "Captured media request; host=" + Uri.parse(url).getHost());
            antennaV2Playback = true;
            browser.evaluateJavascript("window.__omeRds && window.__omeRds.useNative(true)", null);
            if (player != null) { player.release(); player = null; video.setPlayer(null); }
            createPlayer();
            MediaItem.Builder item = new MediaItem.Builder().setUri(url);
            if (Uri.parse(url).getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8"))
                item.setMimeType(MimeTypes.APPLICATION_M3U8);
            else item.setMimeType(MimeTypes.APPLICATION_MPD);
            player.setMediaItem(item.build());
            player.prepare(); player.play();
            video.setVisibility(View.VISIBLE);
            showPreparing("Test v2 · se pornește fluxul în ExoPlayer…");
        } catch (Exception error) {
            android.util.Log.w("OmeAntenaV2", "Could not start captured stream", error);
            showProviderAction("Test v2: fluxul capturat nu a putut porni.");
        }
    }

    private boolean validSessionPage(String url) {
        return RdsLiveCatalog.validPage(url)
                || antenaTest && AntenaLiveCatalog.validPage(url);
    }

    private void showSession(String message) {
        if (antenaSelected && !externalSiteSelected && !iptvOrgSelected && nativeMode) {
            // Keep the provider WebView attached for its session timers, but never
            // raise its page (or advertisements) above OME's native waiting UI.
            showProviderAction(message + " Încearcă din nou sau alege altă sursă.");
            return;
        }
        if (preparing != null) preparing.setVisibility(View.GONE);
        if (video != null) video.setVisibility(View.INVISIBLE);
        chrome.setVisibility(View.VISIBLE);
        if (browser != null) {
            if (antennaWebSuspended) {
                antennaWebSuspended = false;
                browser.setVisibility(View.VISIBLE);
                browser.onResume();
            }
            // The WebView remains attached and visible behind the native video:
            // hiding/destroying it would stop the provider's session timers.
            browser.bringToFront(); browser.requestFocus();
        }
        status.setText(message);
    }

    private void showPreparing(String message) {
        video.setVisibility(View.VISIBLE);
        video.bringToFront();
        preparing.setVisibility(View.VISIBLE);
        preparing.bringToFront();
        preparing.getChildAt(0).setVisibility(View.VISIBLE);
        ((TextView) preparing.getChildAt(1)).setText(
                channelTitle + "\n" + message);
        if (providerActionButton != null) providerActionButton.setVisibility(View.GONE);
        chrome.setVisibility(channelZap ? View.GONE : View.VISIBLE);
        status.setText(message);
    }

    private void showProviderAction(String message) {
        showPreparing(message);
        preparing.getChildAt(0).setVisibility(View.GONE);
        providerActionButton.setVisibility(View.VISIBLE);
    }

    private void retryAntenaSessionOrPrompt() {
        if (!antenaSelected || !nativeMode || !nativeRequested) return;
        if (antennaSessionAutoRetries >= 1) {
            showProviderAction("Antena Live nu a pornit. Apasă Încearcă din nou sau alege altă sursă.");
            return;
        }
        if (antennaSessionRetryPending) return;
        antennaSessionRetryPending = true;
        final int generation = pageGeneration;
        showPreparing("Canalul întârzie. Reîncerc automat…");
        main.postDelayed(() -> {
            antennaSessionRetryPending = false;
            if (closed || stopped || closingPlayback || !antenaSelected
                    || generation != pageGeneration || session != null) return;
            antennaSessionAutoRetries++;
            loadPage();
        }, 12000L);
    }

    private void showProviderLimit() {
        // Keep the provider page attached for its own cleanup, but never expose
        // its error page as the app's playback UI or retry behind the user's back.
        video.setVisibility(View.VISIBLE);
        video.bringToFront();
        preparing.setVisibility(View.VISIBLE);
        preparing.bringToFront();
        preparing.getChildAt(0).setVisibility(View.GONE);
        ((TextView) preparing.getChildAt(1)).setText(
                "Prea multe canale deschise simultan.\n"
                        + "Închide alte redări, așteaptă eliberarea sesiunii, "
                        + "apoi apasă Reîncarcă.");
        chrome.setVisibility(View.VISIBLE);
        status.setText("Limita de canale a sursei a fost atinsă.");
    }

    private void updatePlaybackLabel() {
        status.setText(channelTitle + (iptvOrgSelected ? " · IPTV-org"
                : externalSiteSelected ? " · TVZone" : antenaSelected ? " · Antena Live"
                : " · RDSLive · S" + (selectedSource + 1))
                + (actualQuality.isEmpty() ? "" : " · " + actualQuality) + " · LIVE");
    }

    private void resolveTvZone() {
        final int generation = pageGeneration;
        new Thread(() -> {
            try {
                String url = TvZoneResolver.resolve(tvZonePage);
                JSONObject data = new JSONObject().put("url", url).put("token", "")
                        .put("mode", "direct").put("expiresAt", System.currentTimeMillis() + 3 * 60 * 60 * 1000L);
                RdsLiveStream resolved = RdsLiveStream.from(data);
                if (resolved == null) throw new Exception("Flux TVZone invalid");
                main.post(() -> {
                    if (closed || stopped || !externalSiteSelected || generation != pageGeneration) return;
                    session = resolved;
                    playSession(true);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (closed || stopped || !externalSiteSelected || generation != pageGeneration) return;
                    showPreparing("TVZone nu a furnizat momentan un flux redabil. Încearcă altă sursă.");
                    android.util.Log.w("OmeTvZone", "resolve failed: " + error.getClass().getSimpleName()
                            + " · " + error.getMessage());
                });
            }
        }, "ome-tvzone-resolve").start();
    }

    private boolean hasTvZoneSource() {
        return TvZoneCatalog.validPage(tvZonePage);
    }

    private void showSources() {
        boolean hasExternal = hasTvZoneSource();
        int rdsCount = antenaSelected ? RdsLiveCatalog.validPage(rdsPage) ? 1 : 0
                : sources.length();
        int rdsStart = antenaTest ? 1 : 0;
        int tvZoneIndex = rdsStart + rdsCount;
        int iptvStart = tvZoneIndex + (hasExternal ? 1 : 0);
        if (iptvStart == 0 && iptvOrgStreams.length() == 0) {
            themedMessage("Surse / servere", "Lista surselor se încarcă odată cu transmisia.");
            return;
        }
        String[] names = new String[iptvStart + iptvOrgStreams.length()];
        if (antenaTest) names[0] = "Antena Live · ExoPlayer";
        for (int i = 0; i < rdsCount; i++) {
            JSONObject source = antenaSelected ? null : sources.optJSONObject(i);
            String name = source == null ? "" : source.optString("name");
            String label = antenaSelected ? "RDSLive · S" + (i + 1) : "S" + (i + 1);
            if (!name.isEmpty() && !name.equalsIgnoreCase(label)) label += " · " + name;
            if (source != null && "embed".equals(source.optString("kind"))) label += " · player site";
            names[rdsStart + i] = label;
        }
        if (hasExternal) names[tvZoneIndex] = "TVZone · FHD · ExoPlayer";
        for (int i = 0; i < iptvOrgStreams.length(); i++) {
            JSONObject source = iptvOrgStreams.optJSONObject(i);
            String quality = source == null ? "" : source.optString("quality");
            names[iptvStart + i] = "IPTV-org" + (quality.isEmpty() ? "" : " · " + quality)
                    + " · ExoPlayer";
        }
        themedChoices("Surse / servere", names,
                iptvOrgSelected ? iptvStart + selectedIptvOrgSource
                        : externalSiteSelected ? tvZoneIndex
                        : antenaSelected ? 0
                        : rdsCount == 0 ? -1 : rdsStart + selectedSource,
                this::chooseSource);
    }

    private void chooseSource(int index) {
        int rdsCount = antenaSelected ? RdsLiveCatalog.validPage(rdsPage) ? 1 : 0
                : sources.length();
        int rdsStart = antenaTest ? 1 : 0;
        int tvZoneIndex = rdsStart + rdsCount;
        int iptvStart = tvZoneIndex + (hasTvZoneSource() ? 1 : 0);
        if (index >= iptvStart && index < iptvStart + iptvOrgStreams.length()) {
            playIptvOrg(index - iptvStart);
            return;
        }
        if (antenaTest && index == 0) {
            if (antenaSelected && !externalSiteSelected && !iptvOrgSelected
                    && !antennaReloadStopped) return;
            openFreshSource(-1, true);
            return;
        }
        if (hasTvZoneSource() && index == tvZoneIndex) {
            if (externalSiteSelected || browser == null) return;
            // A fresh Activity opens a new site session instead of reusing
            // the token already granted to this WebView.
            openFreshSource(-1, false, true);
            return;
        }
        if (externalSiteSelected) {
            if (antenaTest) {
                // Antena's paused WebView cannot reliably restart its token and
                // heartbeat timers after TVZone has replaced the page. Give S1
                // a fresh session, as we already do when returning from IPTV-org.
                openFreshSource(index - rdsStart, false);
                return;
            }
            externalSiteSelected = false;
            pendingRdsSource = index - rdsStart;
            nativeMode = true; nativeRequested = true;
            loadPage();
            return;
        }
        if (iptvOrgSelected) {
            // The old WebView has released its RDS session while IPTV-org played.
            // A fresh Activity avoids racing its delayed about:blank callbacks
            // against the new site's verification and token handshake.
            openFreshSource(index - rdsStart, false);
            return;
        }
        if (antenaSelected) {
            openFreshSource(index - rdsStart, false);
            return;
        }
        int rdsIndex = index - rdsStart;
        if (rdsIndex == selectedSource && session != null) { video.showController(); return; }
        // Every source switch starts a fresh site session in a new Activity,
        // so the provider grants a new token instead of reusing the old one.
        openFreshSource(rdsIndex, false, false);
    }

    private void openFreshSource(int index, boolean useAntena) {
        openFreshSource(index, useAntena, false);
    }

    private void openFreshSource(int index, boolean useAntena, boolean useTvZone) {
        pendingChannel = new Intent(this, RdsLiveActivity.class)
                .putExtra("page", rdsPage)
                .putExtra("tvZonePage", tvZonePage)
                .putExtra("antenaPage", antenaPage)
                .putExtra("initialAntena", useAntena)
                .putExtra("initialTvZone", useTvZone)
                .putExtra("iptvOrgStreams", iptvOrgStreams.toString())
                .putExtra("channelId", channelId)
                .putExtra("title", channelTitle)
                .putExtra("channelCategory", channelCategory)
                .putExtra("channelQuery", channelQuery)
                .putExtra("initialRdsSource", useAntena ? -1 : index)
                .putExtra("channelZap", channelZap);
        closePlayer();
    }

    private void playIptvOrg(int index) {
        JSONObject source = IptvOrgCatalog.playable(iptvOrgStreams, index);
        if (source == null) {
            themedMessage("IPTV-org", "Sursa nu este disponibilă momentan.");
            return;
        }
        iptvOrgSelected = true;
        selectedIptvOrgSource = index;
        iptvOrgSource = source;
        externalSiteSelected = false;
        nativeMode = true; nativeRequested = true;
        retries = 0; session = null; actualQuality = "";
        pageGeneration++;
        if (player != null) { player.release(); player = null; video.setPlayer(null); }
        browser.loadUrl("about:blank"); // Releases the previous provider session.
        sourceButton.setText("Surse · IPTV-org");
        qualityButton.setText("Calitate · automat");
        showPreparing("Se pregătește IPTV-org…");
        createPlayer();
        String streamUrl = source.optString("url");
        MediaItem.Builder itemBuilder = new MediaItem.Builder().setUri(streamUrl);
        if (Uri.parse(streamUrl).getPath() != null
                && Uri.parse(streamUrl).getPath().toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8"))
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8);
        MediaItem item = itemBuilder.build();
        player.setMediaItem(item);
        player.prepare();
        player.play();
        video.setVisibility(View.VISIBLE);
        video.bringToFront();
    }

    private void showQualities() {
        if (externalSiteSelected && player == null) {
            themedMessage("Calitate video", "Calitatea TVZone se afișează după pornirea fluxului.");
            return;
        }
        JSONObject source = iptvOrgSelected ? null : sources.optJSONObject(selectedSource);
        JSONArray qualities = source == null ? null : source.optJSONArray("qualities");
        if (qualities != null && qualities.length() > 1) {
            String[] names = new String[qualities.length()];
            int checked = -1;
            for (int i = 0; i < names.length; i++) {
                JSONObject quality = qualities.optJSONObject(i);
                names[i] = quality == null ? "" : quality.optString("name");
                if (quality != null && selectedQuality.equals(quality.optString("id"))) checked = i;
            }
            final JSONArray choices = qualities;
            themedChoices("Calitate video", names, checked,
                    index -> chooseQuality(choices.optJSONObject(index).optString("id")));
            return;
        }
        // Some external HLS sources advertise their resolutions in the manifest.
        java.util.TreeSet<Integer> heights = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        if (player != null) for (androidx.media3.common.Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != androidx.media3.common.C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < group.length; i++) {
                int height = group.getTrackFormat(i).height;
                if (height > 0 && group.isTrackSupported(i)) heights.add(height);
            }
        }
        if (heights.size() > 1) {
            java.util.List<Integer> sizes = new java.util.ArrayList<>(heights);
            String[] names = new String[sizes.size() + 1]; names[0] = "Automat";
            for (int i = 0; i < sizes.size(); i++) names[i + 1] = sizes.get(i) + "p";
            themedChoices("Calitate video", names, -1, index -> {
                        if (player != null) player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                                .buildUpon().setMaxVideoSize(Integer.MAX_VALUE,
                                        index == 0 ? Integer.MAX_VALUE : sizes.get(index - 1)).build());
                    });
        } else themedMessage("Calitate video", actualQuality.isEmpty()
                ? "Calitatea apare după pornirea transmisiei."
                : "Calitatea disponibilă: " + actualQuality);
    }

    private void chooseQuality(String id) {
        if (id.equals(selectedQuality)) { video.showController(); return; }
        nativeMode = true; nativeRequested = true;
        retries = 0; session = null; actualQuality = "";
        if (player != null) player.stop();
        browser.evaluateJavascript("window.__omeRds && (window.__omeRds.useNative(true),"
                + " window.__omeRds.selectQuality(" + JSONObject.quote(id) + "))", value -> {
            if (!"true".equals(value)) showSession("Calitatea selectată nu mai este disponibilă.");
        });
        showPreparing("Se schimbă calitatea…");
    }

    private void toggleFillScreen() {
        fillScreen = !fillScreen;
        video.setResizeMode(fillScreen ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        fillButton.setText(fillScreen ? "Fit / Umple ✓" : "Fit ✓ / Umple");
        getPreferences(MODE_PRIVATE).edit().putBoolean("rds_fill_screen", fillScreen).apply();
    }

    private interface ChoiceAction { void select(int index); }

    private void themedMessage(String title, String message) {
        themedChoices(title, new String[]{message}, -1, index -> {});
    }

    private void themedChoices(String title, String[] labels, int selected, ChoiceAction action) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(18, 16, 18, 16);
        panel.setBackground(Theme.box(Theme.PANEL, 18, this));
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(Theme.TEXT);
        heading.setTextSize(20);
        heading.setPadding(8, 4, 8, 14);
        panel.addView(heading);
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView row = new TextView(this);
            row.setText(labels[i] + (selected == i ? "  ✓" : ""));
            row.setTextColor(selected == i ? Theme.ACCENT : Theme.TEXT);
            row.setTextSize(17);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(18, 12, 18, 12);
            row.setMinHeight(52);
            row.setBackground(Theme.box(selected == i ? Theme.PANEL_2 : Theme.PANEL, 10, this));
            row.setFocusable(true);
            row.setOnFocusChangeListener((view, focused) -> {
                row.setBackground(Theme.box(focused ? Theme.ACCENT :
                        selected == index ? Theme.PANEL_2 : Theme.PANEL, 10, this));
                row.setTextColor(focused ?
                        (Color.luminance(Theme.ACCENT) > 0.5 ? Color.BLACK : Color.WHITE)
                        : selected == index ? Theme.ACCENT : Theme.TEXT);
            });
            row.setOnClickListener(view -> { dialog.dismiss(); action.select(index); });
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            if (rows.getChildCount() > 0) rows.getChildAt(Math.max(0, selected)).requestFocus();
        });
        dialog.show();
    }

    private void enterFullscreen() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void closePlayer() {
        if (closingPlayback) return;
        closingPlayback = true;
        nativeRequested = false;
        pageGeneration++;
        session = null;
        observedRevision = 0;
        polling = false;
        main.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; video.setPlayer(null); }
        if (browser == null) { finish(); return; }
        showPreparing(pendingChannel == null ? "Se închide playerul…" : "Se schimbă postul…");
        if (pendingChannel != null) chrome.setVisibility(View.GONE);
        if (antennaWebSuspended) {
            antennaWebSuspended = false;
            browser.onResume();
        }
        // Navigate away while WebView is still alive so the provider receives
        // its normal pagehide event before this Activity destroys the page.
        browser.loadUrl("about:blank");
        main.postDelayed(finishAfterClose, 1500);
    }

    @Override public void onBackPressed() { closePlayer(); }

    /** One WebView owns one TV channel; leave it before loading another channel. */
    private boolean switchChannel(int direction) {
        if (closed || closingPlayback || browser == null) return false;
        JSONObject next = nextChannel(channelOrder,
                RdsLiveCatalog.validPage(rdsPage) ? rdsPage : antenaPage, direction);
        if (next == null) return false;
        pendingChannel = new Intent(this, RdsLiveActivity.class)
                .putExtra("page", next.optString("rdsPage"))
                .putExtra("tvZonePage", next.optString("tvZonePage"))
                .putExtra("antenaPage", next.optString("antenaPage"))
                .putExtra("iptvOrgStreams", next.optJSONArray("iptvOrgStreams") == null
                        ? "" : next.optJSONArray("iptvOrgStreams").toString())
                .putExtra("channelId", next.optString("id"))
                .putExtra("title", RdsLiveCatalog.displayName(next.optString("name", "TV Live")))
                .putExtra("channelCategory", channelCategory)
                .putExtra("channelQuery", channelQuery)
                .putExtra("channelZap", true);
        closePlayer();
        return true;
    }

    static JSONObject nextChannel(JSONArray order, String currentPage, int direction) {
        if (order == null || order.length() < 2 || direction == 0) return null;
        int current = -1;
        for (int i = 0; i < order.length(); i++) {
            JSONObject candidate = order.optJSONObject(i);
            if (candidate != null && (currentPage.equals(candidate.optString("rdsPage"))
                    || AntenaLiveCatalog.validPage(currentPage)
                    && currentPage.equals(candidate.optString("antenaPage")))) {
                current = i;
                break;
            }
        }
        if (current < 0) return null;
        for (int step = 1; step < order.length(); step++) {
            int index = (current + direction * step + order.length() * step)
                    % order.length();
            JSONObject candidate = order.optJSONObject(index);
            if (candidate != null && (RdsLiveCatalog.validPage(candidate.optString("rdsPage"))
                    || AntenaLiveCatalog.validPage(candidate.optString("antenaPage")))) {
                return candidate;
            }
        }
        return null;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (!tvControls && !closingPlayback && !closed) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchStartX = event.getX();
                touchStartY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    && touchStartY > chrome.getBottom()) {
                float dx = event.getX() - touchStartX;
                float dy = event.getY() - touchStartY;
                float density = getResources().getDisplayMetrics().density;
                boolean vertical = Math.abs(dy) > density * 120
                        && Math.abs(dy) > Math.abs(dx) * 1.5f;
                if (vertical && switchChannel(dy < 0 ? 1 : -1)) return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (tvControls && event.getAction() == KeyEvent.ACTION_DOWN && !closingPlayback) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_UP)
                return switchChannel(-1);
            if (key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN)
                return switchChannel(1);
            // On simple D-pad remotes, Up/Down changes channels while controls
            // are hidden. Once controls are open, arrows keep their normal focus.
            if (video != null && video.getVisibility() == View.VISIBLE
                    && chrome.getVisibility() != View.VISIBLE) {
                if (key == KeyEvent.KEYCODE_DPAD_UP) return switchChannel(-1);
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) return switchChannel(1);
            }
        }
        if (tvControls && event.getAction() == KeyEvent.ACTION_DOWN && !closingPlayback
                && video != null && video.getVisibility() == View.VISIBLE) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                    || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                    || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                    || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                video.showController();
                chrome.setVisibility(View.VISIBLE);
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) enterFullscreen();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (closed || stopped || browser == null) return;
            if (!polling && !loading && validSessionPage(browser.getUrl())) {
                polling = true;
                final int generation = pageGeneration;
                browser.evaluateJavascript("window.__omeRds ? window.__omeRds.snapshot() : null", value -> {
                    polling = false;
                    if (closed || stopped || generation != pageGeneration) return;
                    try {
                        Object decoded = new JSONArray("[" + value + "]").get(0);
                        if (!(decoded instanceof String)) return;
                        acceptSnapshot(new JSONObject((String) decoded));
                    } catch (Exception ignored) { }
                });
            }
            if (player != null && video.getVisibility() == View.VISIBLE && nativeMode) {
                long now = android.os.SystemClock.elapsedRealtime();
                if (player.getPlaybackState() == Player.STATE_BUFFERING && player.getPlayWhenReady()) {
                    if (bufferingSince == 0) bufferingSince = now;
                    if (now - bufferingSince > 60000) { bufferingSince = now; recover(); }
                } else bufferingSince = 0;
                // A brief READY state must not reset the retry budget forever.
                if (player.isPlaying() && readySince > 0 && now - readySince > 60000) retries = 0;
            }
            main.postDelayed(this, 1000);
        }
    };

    private void acceptSnapshot(JSONObject json) {
        // v2 compares the actual request captured from the page with native playback.
        // Do not start the existing token resolver in parallel during this test.
        if (antennaDirectTest() && antenaSelected) return;
        int tokenRequests = json.optInt("tokenRequests", -1);
        int tokenGranted = json.optInt("tokenGranted", -1);
        int tokenRejected = json.optInt("tokenRejected", -1);
        if (tokenRequests != loggedTokenRequests || tokenGranted != loggedTokenGranted
                || tokenRejected != loggedTokenRejected) {
            loggedTokenRequests = tokenRequests;
            loggedTokenGranted = tokenGranted;
            loggedTokenRejected = tokenRejected;
            android.util.Log.i("OmeRdsDiag", "token requests=" + tokenRequests
                    + " granted=" + tokenGranted + " rejected=" + tokenRejected);
        }
        JSONArray offered = json.optJSONArray("sources");
        if (offered != null) sources = offered;
        if (pendingRdsSource >= 0 && pendingRdsSource < sources.length()) {
            int target = pendingRdsSource;
            pendingRdsSource = -1;
            if (json.optInt("selectedSource") != target) {
                browser.evaluateJavascript("window.__omeRds && window.__omeRds.selectSource("
                        + target + ")", null);
                return;
            }
        }
        selectedSource = json.optInt("selectedSource");
        selectedQuality = json.optString("quality");
        sourceButton.setText(antenaSelected ? "Surse · Antena Live"
                : "Surse · S" + (selectedSource + 1));
        String error = json.optString("error"), prompt = json.optString("prompt");
        if (!error.isEmpty() || !prompt.isEmpty()) {
            session = null;
            if (player != null) player.stop();
            if ("too_many_channels".equals(error)
                    || prompt.toLowerCase(java.util.Locale.ROOT).contains("prea multe canale")) {
                // This is a provider-side session limit. Reconnecting here only
                // creates another request and cannot free an existing session.
                nativeRequested = false;
                showProviderLimit();
                return;
            }
            if ("verification_required".equals(error) && prompt.isEmpty() && nativeMode && nativeRequested) {
                if (antenaSelected)
                    retryAntenaSessionOrPrompt();
                else showPreparing("Se pornește canalul…");
                return;
            }
            if (antenaSelected)
                retryAntenaSessionOrPrompt();
            else showSession("Sesiunea cere atenție. Confirmă mesajul afișat de site.");
            return;
        }
        int revision = json.optInt("revision");
        if (revision == 0 || revision == observedRevision) return;
        if (!json.optBoolean("ready")) {
            status.setText("Se inițializează transmisia în sesiunea sursei…");
            return;
        }
        RdsLiveStream fresh = RdsLiveStream.from(json);
        if (fresh == null) return;
        observedRevision = revision;
        RdsLiveStream previous = session;
        session = fresh;
        if (!nativeMode || !nativeRequested) return;
        // Standard token renewals update the data-source resolver without
        // flushing buffered video. A provider URL/path change needs a new item.
        if (previous == null || !previous.sameMedia(fresh)
                || player == null || player.getPlaybackState() == Player.STATE_IDLE) {
            playSession(true);
        }
    }

    private void createPlayer() {
        JSONObject iptvHeaders = antennaV2Playback ? antennaV2Headers
                : iptvOrgSelected && iptvOrgSource != null
                ? iptvOrgSource.optJSONObject("headers") : null;
        java.util.Map<String, String> requestHeaders = new java.util.HashMap<>();
        if (iptvOrgSelected || antennaV2Playback) {
            if (iptvHeaders != null) for (java.util.Iterator<String> it = iptvHeaders.keys(); it.hasNext();) {
                String key = it.next();
                if (!"User-Agent".equalsIgnoreCase(key))
                    requestHeaders.put(key, iptvHeaders.optString(key));
            }
        } else requestHeaders.put("Referer", externalSiteSelected ? tvZonePage : page);
        String userAgent = (iptvOrgSelected || antennaV2Playback) && iptvHeaders != null
                && !iptvHeaders.optString("User-Agent").isEmpty()
                ? iptvHeaders.optString("User-Agent")
                : externalSiteSelected ? TvZoneResolver.userAgent()
                : browser.getSettings().getUserAgentString();
        HttpDataSource.Factory http;
        http = new OkHttpDataSource.Factory(PreferredNetwork.client(3, 18))
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(requestHeaders);
        ResolvingDataSource.Factory resolving = new ResolvingDataSource.Factory(http, spec -> {
            RdsLiveStream current = session;
            if (current == null || current.expiresAt <= System.currentTimeMillis()) {
                throw new java.io.IOException("RDSLive session expired");
            }
            return spec.withUri(current.resolve(spec.uri));
        });
        player = new ExoPlayer.Builder(this)
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000, 60000, 1500, 5000).build())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(
                        iptvOrgSelected || antennaV2Playback ? http : resolving)
                        .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(5)))
                .build();
        video.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    preparing.setVisibility(View.GONE);
                    if (antenaSelected) {
                        video.setVisibility(View.VISIBLE);
                        video.bringToFront();
                        chrome.bringToFront();
                    }
                    readySince = android.os.SystemClock.elapsedRealtime();
                    updatePlaybackLabel();
                    if (channelZap) {
                        channelZap = false;
                        video.hideController();
                        chrome.setVisibility(View.GONE);
                    } else {
                        main.removeCallbacks(hidePlaybackControls);
                        main.postDelayed(hidePlaybackControls, tvControls ? 2000 : 3000);
                    }
                } else readySince = 0;
                if (state == Player.STATE_ENDED && nativeMode) recover();
            }
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                if (size.height <= 0) return;
                actualQuality = (size.height >= 2160 ? "UHD" : size.height >= 1080 ? "FHD"
                        : size.height >= 720 ? "HD" : "SD") + " · " + size.width + "×" + size.height;
                qualityButton.setText("Calitate · " + (size.height >= 2160 ? "UHD"
                        : size.height >= 1080 ? "FHD" : size.height >= 720 ? "HD" : "SD"));
                updatePlaybackLabel();
            }
            @Override public void onPlayerError(PlaybackException error) {
                Throwable problem = error;
                while (problem != null) {
                    if (problem instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException httpError =
                                (androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) problem;
                        Uri failed = httpError.dataSpec.uri;
                        RdsLiveStream current = session;
                        String reason = "";
                        try { reason = new JSONObject(new String(httpError.responseBody, "UTF-8")).optString("error"); }
                        catch (Exception ignored) { }
                        android.util.Log.w("OmeRds", "HTTP=" + httpError.responseCode
                                + " mode=" + (current == null ? "none" : current.mode)
                                + " host=" + failed.getHost() + " queryKeys=" + failed.getQueryParameterNames()
                                + " pathShape=" + pathShape(failed)
                                + " rootShape=" + (current == null ? "" : pathShape(Uri.parse(current.url)))
                                + " rootHost=" + (current == null ? "" : Uri.parse(current.url).getHost())
                                + " resolvedKeys=" + (current == null ? "" : current.resolve(failed).getQueryParameterNames())
                                + " reason=" + reason);
                        break;
                    }
                    problem = problem.getCause();
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare();
                    return;
                }
                recover();
            }
        });
    }

    private void playSession(boolean reveal) {
        if (closed || stopped || !nativeMode || !nativeRequested || session == null) return;
        if (antenaSelected && !antennaWebSuspended) {
            final int generation = pageGeneration;
            browser.evaluateJavascript("window.__omeRds && window.__omeRds.useNative(true)", value -> {
                if (closed || stopped || browser == null || generation != pageGeneration) return;
                // The page keeps its granted session; suspend its video/ads so
                // the phone's hardware decoder is available to ExoPlayer.
                browser.onPause();
                browser.setVisibility(View.INVISIBLE);
                antennaWebSuspended = true;
                playSession(reveal);
            });
            return;
        }
        android.util.Log.i("OmeRdsDiag", "native-play retry=" + retries
                + " revision=" + observedRevision);
        boolean firstPlayback = player == null;
        if (firstPlayback) createPlayer();
        MediaItem item = new MediaItem.Builder().setUri(session.url)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(45000).setMaxOffsetMs(100000).build()).build();
        player.setMediaItem(item); player.prepare(); player.play();
        browser.evaluateJavascript("window.__omeRds && window.__omeRds.useNative(true)", null);
        if (reveal) { video.setVisibility(View.VISIBLE); video.bringToFront(); video.requestFocus(); }
        if (player.getPlaybackState() != Player.STATE_READY) {
            preparing.setVisibility(View.VISIBLE); preparing.bringToFront();
        }
        status.setText("Se pregătește transmisia în ExoPlayer…");
        // A token renewal/recovery must not reopen the overlay over live TV.
        if (firstPlayback && !channelZap) {
            chrome.setVisibility(View.VISIBLE);
            video.showController();
        }
    }

    private void recover() {
        if (closed || stopped || !nativeMode || !nativeRequested) return;
        if (antennaV2Playback && antennaDirectTest()) {
            if (++antennaV2Retries > 2) {
                showProviderAction("Test v2: fluxul capturat nu este redabil. Încearcă din nou.");
                return;
            }
            showPreparing("Test v2 · sesiunea a expirat, reîncerc…");
            main.postDelayed(this::loadPage, 1500L);
            return;
        }
        if (iptvOrgSelected) {
            showPreparing("IPTV-org nu răspunde. Alege altă sursă sau apasă Reîncarcă.");
            return;
        }
        if (externalSiteSelected) {
            if (++retries > 3) {
                showPreparing("TVZone nu răspunde. Alege altă sursă sau apasă Reîncarcă.");
                return;
            }
            main.postDelayed(this::resolveTvZone, Math.min(10000, retries * 2500L));
            return;
        }
        if (++retries > 6 || session == null || session.expiresAt <= System.currentTimeMillis()) {
            if (player != null) player.stop();
            showSession("Transmisia nu răspunde. Alege altă sursă pe site sau Reîncarcă.");
            return;
        }
        final int generation = pageGeneration, attempt = retries;
        long delay = Math.min(30000, 3000L << (attempt - 1));
        status.setText("Transmisie întreruptă · reconectare " + attempt + "/6…");
        main.postDelayed(() -> {
            if (generation == pageGeneration && attempt == retries && nativeMode && nativeRequested
                    && !closed && !stopped) playSession(false);
        }, delay);
    }

    private static String pathShape(Uri uri) {
        StringBuilder shape = new StringBuilder();
        for (String part : uri.getPathSegments()) {
            shape.append('/');
            if (part.equals("media") || part.equals("index.m3u8")) shape.append(part);
            else shape.append(part.length()).append("chars").append(part.contains(".")
                    ? part.substring(part.lastIndexOf('.')) : "");
        }
        return shape.toString();
    }

    @Override protected void onStart() {
        super.onStart();
        if (closed) return;
        if (stopped) {
            stopped = false;
            browser.onResume();
            if (iptvOrgSelected) playIptvOrg(selectedIptvOrgSource);
            else loadPage();
        }
        main.removeCallbacks(poll); main.post(poll);
    }

    @Override protected void onStop() {
        super.onStop();
        stopped = true; pageGeneration++; session = null;
        observedRevision = 0;
        main.removeCallbacksAndMessages(null); polling = false;
        if (player != null) { player.release(); player = null; video.setPlayer(null); }
        if (browser != null) {
            if (!closingPlayback) {
                browser.loadUrl("about:blank");
                // Avoid suspending WebView before the site's pagehide runs.
                main.postDelayed(() -> {
                    if (stopped && browser != null) browser.onPause();
                }, 500);
            } else browser.onPause();
        }
    }

    @Override protected void onDestroy() {
        closed = true; main.removeCallbacksAndMessages(null);
        if (backCallback != null && android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
        if (browser != null) { stage.removeView(browser); browser.destroy(); browser = null; }
        super.onDestroy();
    }
}
