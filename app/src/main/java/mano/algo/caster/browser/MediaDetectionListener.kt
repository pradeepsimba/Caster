package mano.algo.caster.browser

/** Receives media URLs sniffed from the WebView's network traffic. Always invoked on the main thread. */
fun interface MediaDetectionListener {
    fun onMediaDetected(url: String, mimeType: String)
}
