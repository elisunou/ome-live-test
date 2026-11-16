# OME Live Test — Antena Live

Independent Android diagnostic APK built with Kotlin and Media3 1.11.1. It does **not** access or bypass protected streams: the user opens the provider site, performs any required interaction, and then explicitly selects a media request for playback. The site must permit playback.

## On a phone: build with GitHub Actions

1. Create an empty repository `ome-live-test` on GitHub (choose **no README** initially).
2. Upload **the contents** of this project to the repository, preserving `.github/workflows/android.yml`. The GitHub web uploader may not expose dotfolders on some mobile browsers; desktop mode helps.
3. Open **Actions → Build Android APK → Run workflow**, or push to `main` to trigger the workflow.
4. After success, open the workflow run → **Artifacts → OME-Live-Test-debug-APK**. Unzip and install `app-debug.apk` on your Android phone.

## Diagnostic steps

1. Tap **Open Antena Live**; the WebView is visible and has a real size. Interact with the website directly and complete any required prompts.
2. When the page requests HLS/DASH, tap **Choose stream**; multiple requests might include audio-only variants. A manifest URL is not necessarily the main video manifest.
3. Tap **Play in Media3**. The app passes User-Agent, Referer and cookies from the legitimate WebView session where available.
4. Observe `audio/video tracks`, `video supported/selected`, `video size`, `first frame`, `HTTP/error`. A READY player without a rendered frame is not considered proof of video.
5. Tap **Copy diagnostic**. Sensitive URLs, cookies, tokens and request headers are not included in the diagnostic text.

Note: Android WebView may not expose all fetch requests or required headers through `shouldInterceptRequest`; some sites use DRM, nonstandard playlists or session-bound manifests. This app is only a diagnostic. It does not guarantee playback, does not renew provider tokens or bypass session restrictions.
