package mano.algo.caster.browser

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Observes every sub-resource request the page makes and reports media URLs to [listener].
 *
 * [shouldInterceptRequest] runs on WebView's background IO threads, so it only does cheap string
 * matching, de-duplicates through a concurrent set, and hops to the main looper before notifying.
 * It always returns null so the request proceeds untouched.
 */
class MediaInterceptingWebViewClient(
    private val listener: MediaDetectionListener,
    private val onNavigationStarted: (url: String) -> Unit = {},
) : WebViewClient() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (AdBlocker.isAd(url)) {
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(EMPTY_BYTES))
        }

        if (request.method.equals("GET", ignoreCase = true)) {
            val match = MediaSniffer.analyze(url)
            if (match != null) {
                if (seen.size >= MAX_REMEMBERED) seen.clear() // bound memory on endless players
                if (seen.add(match.url)) {
                    mainHandler.post { listener.onMediaDetected(match.url, match.mimeType) }
                }
            }
        }
        return null
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        seen.clear() // a fresh page may legitimately re-request the same stream
        onNavigationStarted(url)
        super.onPageStarted(view, url, favicon)
    }

    private companion object {
        const val MAX_REMEMBERED = 500
        val EMPTY_BYTES = ByteArray(0)
    }
}
