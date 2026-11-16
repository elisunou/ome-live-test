package com.omestream.livetest

import android.net.Uri
import android.webkit.CookieManager

/** Builds a playback request from the currently authorized WebView session.
 * It cannot issue new provider tokens or bypass HTTP authorization.
 */
internal class LiveSessionResolver {
    data class Resolved(
        val url: String,
        val headers: Map<String, String>,
        val userAgent: String,
        val cookiesPresent: Boolean,
        val signedQueryPresent: Boolean,
        val manifestHost: String
    )

    fun resolve(
        url: String,
        observedHeaders: Map<String, String>,
        pageUrl: String,
        fallbackUserAgent: String
    ): Resolved {
        val uri = Uri.parse(url)
        require(uri.scheme == "https") { "Only HTTPS manifests are accepted" }
        val cookies = CookieManager.getInstance().getCookie(url)
        val headers = mutableMapOf<String, String>()
        for ((name, value) in observedHeaders) {
            if (name.equals("Referer", true) || name.equals("Origin", true)) {
                headers[name] = value
            }
        }
        if (headers.keys.none { it.equals("Referer", true) }) headers["Referer"] = pageUrl
        if (!cookies.isNullOrBlank()) headers["Cookie"] = cookies
        val ua = observedHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: fallbackUserAgent
        val signed = uri.queryParameterNames.any { n ->
            n.contains("token", true) || n.contains("signature", true) ||
                n.equals("sig", true) || n.equals("expires", true) || n.equals("exp", true)
        }
        return Resolved(url, headers, ua, !cookies.isNullOrBlank(), signed, uri.host ?: "unknown")
    }

    fun requestType(url: String): String {
        val path = Uri.parse(url).path.orEmpty().lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".mpd") -> "manifest"
            path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".mp4") -> "segment"
            path.endsWith(".key") -> "key"
            else -> "other"
        }
    }
}
