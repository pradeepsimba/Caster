package mano.algo.caster.browser

import java.util.Locale

/** Pure, allocation-light URL classifier; safe to call from any thread (no shared mutable state). */
object MediaSniffer {

    data class Match(val url: String, val mimeType: String)

    private const val HLS_MIME = "application/vnd.apple.mpegurl"
    private const val DASH_MIME = "application/dash+xml"

    private val DELIMITER = """(?=$|[?#&/;])"""
    private val VIDEO_FILE = Regex("""\.(mp4|mkv|mov|avi|webm)$DELIMITER""")
    private val HLS_PLAYLIST = Regex("""\.m3u8$DELIMITER""")
    private val DASH_MANIFEST = Regex("""\.mpd$DELIMITER""")
    private val FORMAT_PARAM = Regex("""[?&](?:format|type|fmt)=(m3u8|hls|mpd|dash)(?:&|#|$)""")

    /** Streaming-server path hints; only trusted for extension-less resources (see [analyze]). */
    private val HLS_HINTS = listOf("/hls/", "playlist.m3u8", "master.m3u8", "index.m3u8")
    private val DASH_HINTS = listOf("/dash/", "manifest.mpd")

    /** Things that look like streaming URLs but are segments, subtitles, keys or page assets. */
    private val NON_PLAYABLE = Regex("""\.(ts|m4s|m4a|aac|mp3|vtt|srt|key|jpe?g|png|gif|webp|svg|ico|css|js|json|woff2?|ttf)$""")

    fun analyze(rawUrl: String): Match? {
        if (!rawUrl.startsWith("http://", true) && !rawUrl.startsWith("https://", true)) return null

        val url = rawUrl.substringBefore('#')
        val lower = url.lowercase(Locale.ROOT)
        val path = lower.substringBefore('?')

        if (NON_PLAYABLE.containsMatchIn(path)) return null

        HLS_PLAYLIST.find(lower)?.let { return Match(url, HLS_MIME) }
        DASH_MANIFEST.find(lower)?.let { return Match(url, DASH_MIME) }
        VIDEO_FILE.find(lower)?.let { return Match(url, mimeForExtension(it.groupValues[1])) }

        FORMAT_PARAM.find(lower)?.let { m ->
            return Match(url, if (m.groupValues[1] == "mpd" || m.groupValues[1] == "dash") DASH_MIME else HLS_MIME)
        }

        // Keyword hints are noisy (/hls/ also hosts keys and segments), so require an extension-less leaf.
        val leaf = path.substringAfterLast('/')
        if (!leaf.contains('.')) {
            if (HLS_HINTS.any { path.contains(it) }) return Match(url, HLS_MIME)
            if (DASH_HINTS.any { path.contains(it) }) return Match(url, DASH_MIME)
        }
        return null
    }

    private fun mimeForExtension(ext: String): String = when (ext) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "webm" -> "video/webm"
        else -> "video/*"
    }
}
