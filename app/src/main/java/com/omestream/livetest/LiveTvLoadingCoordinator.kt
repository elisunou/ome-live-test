package com.omestream.livetest

import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/**
 * Non-invasive Live TV loading/switching coordinator.
 * Does not change provider URLs, skip access controls, or consume ads in video streams.
 * WebView stays alive to manage a provider-authorized session, but can be hidden
 * while Media3 is preparing and while playback succeeds.
 *
 * Integrate in the EXISTING Live TV screen from source code, not by modifying an APK.
 */
class LiveTvLoadingCoordinator(
    private val webView: WebView,
    private val playerView: PlayerView,
    private val loadingOverlay: View,
    private val loadingMessage: TextView,
    private val onSessionRefreshRequested: () -> Unit,
    private val onPlaybackFailure: (PlaybackException) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var currentPlayer: Player? = null
    private var generation = 0
    private var refreshTried = false

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            main.post {
                loadingOverlay.visibility = View.GONE
                playerView.visibility = View.VISIBLE
                webView.visibility = View.INVISIBLE
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            main.post {
                showLoading("Transmisia nu este disponibilă. Verificăm sesiunea…")
                if (!refreshTried) {
                    refreshTried = true
                    // Request a fresh normal provider-authorized session.
                    // The caller should re-resolve and set a NEW MediaItem only
                    // if the provider actually grants a new playable source.
                    onSessionRefreshRequested()
                } else {
                    showLoading("Nu s-a putut porni transmisia. Alege altă sursă.")
                    onPlaybackFailure(error)
                }
            }
        }
    }

    /** Call on every channel/source change, before resolving the source. */
    fun beginSourceChange(name: String) {
        generation++
        refreshTried = false
        showLoading("Se pregătește transmisia… $name")
    }

    /** Attach an existing player after its source has been resolved. */
    fun attachPlayer(player: Player) {
        currentPlayer?.removeListener(listener)
        currentPlayer = player
        player.addListener(listener)
        playerView.player = player
        showLoading("Se pregătește transmisia…")
    }

    /** Do not expose the provider's webpage while waiting for the native first frame. */
    private fun showLoading(message: String) {
        loadingMessage.text = message
        loadingOverlay.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
        playerView.visibility = View.VISIBLE
        loadingOverlay.bringToFront()
    }

    /** Prevent displaying a stale ready state when switching source. */
    fun showSessionRequired() {
        showLoading("Sesiunea trebuie confirmată în pagina furnizorului.")
    }

    /** When user must perform required interaction or consent, show the actual page. */
    fun showProviderInteraction() {
        loadingOverlay.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.bringToFront()
    }

    fun release() {
        currentPlayer?.removeListener(listener)
        currentPlayer = null
        main.removeCallbacksAndMessages(null)
    }
}
