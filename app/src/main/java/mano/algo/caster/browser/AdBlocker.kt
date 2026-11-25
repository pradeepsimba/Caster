package mano.algo.caster.browser

import java.net.URI

/**
 * Lightweight host and path blocker for ad, popup, and tracker domains.
 */
object AdBlocker {
    private val AD_DOMAINS = setOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "adservice.google.com",
        "popads.net",
        "popcash.net",
        "exoclick.com",
        "adsterra.com",
        "propellerads.com",
        "adnxs.com",
        "criteo.com",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "trafficjunky.com",
        "juicyads.com",
        "yllix.com",
        "clickadu.com",
        "adform.net",
        "admob.com",
    )

    private val AD_PATH_PATTERNS = listOf(
        "/ads.js",
        "/ad.js",
        "/pagead/",
        "/ads/ad-",
        "/popunder",
        "/popup.js",
    )

    fun isAd(url: String): Boolean {
        return try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: ""
            if (AD_DOMAINS.any { host == it || host.endsWith(".$it") }) return true
            val path = uri.path?.lowercase() ?: ""
            AD_PATH_PATTERNS.any { path.contains(it) }
        } catch (_: Exception) {
            false
        }
    }
}
