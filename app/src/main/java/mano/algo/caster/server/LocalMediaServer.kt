package mano.algo.caster.server

import android.content.ContentUris
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Log
import android.util.Size
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Embedded HTTP server for the phone's gallery.
 *
 *  GET  /                    the web UI (bundled asset `index.html`)
 *  GET  /api/ping            identifies the device (no auth)
 *  POST /api/pair/request    asks to connect -> {"id","code","expiresInMs"}; the phone owner approves it
 *  GET  /api/pair/status?id= -> {"status":"pending|approved|denied|expired","token"?}
 *  GET  /api/videos?t=       gallery listing                  (token)
 *  GET  /api/thumb/{id}?t=   JPEG thumbnail (Android 10+)     (token)
 *  GET  /api/info/{id}?t=    codecs and size of a video, so the page can warn about unsupported formats (token)
 *  GET  /video/{id}?t=       the video, with 206 range support (token)
 *
 * NanoHTTPD invokes [serve] on one worker thread per connection; this class holds no mutable state.
 */
class LocalMediaServer(
    context: Context,
    hostname: String,
    port: Int,
) : NanoHTTPD(hostname, port) {

    private val appContext = context.applicationContext

    init {
        // Reused worker threads instead of one new thread per connection: a TV opens many short requests.
        setAsyncRunner(PoolRunner())
    }
    private val indexHtml: String by lazy {
        appContext.assets.open("index.html").bufferedReader().use { it.readText() }
    }

    override fun serve(session: IHTTPSession): Response = try {
        withCors(route(session))
    } catch (e: Exception) {
        Log.e(TAG, "Request failed: ${session.uri}", e)
        withCors(error(Response.Status.INTERNAL_ERROR, "server_error"))
    }

    private fun route(session: IHTTPSession): Response {
        val path = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            // 200 rather than 204: NanoHTTPD always adds Content-Length, which Chrome rejects on a 204.
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").apply {
                addHeader("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS")
                addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
                addHeader("Access-Control-Allow-Private-Network", "true")
                addHeader("Access-Control-Max-Age", "600")
            }
        }
        if (path == "/api/pair/request") {
            return if (method == Method.POST) pairRequest(session) else error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")
        }
        if (method != Method.GET && method != Method.HEAD) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")

        when (path) {
            "/", "/index.html" -> return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", indexHtml)
            "/favicon.ico" -> return error(Response.Status.NOT_FOUND, "not_found") // not behind the token, so no 401 noise
            "/api/ping" -> return json(
                JSONObject().put("app", "caster").put("name", Build.MODEL ?: "Android").put("id", ShareSettings.instanceId),
            )
            "/api/pair/status" -> return pairStatus(session.parameters["id"]?.firstOrNull().orEmpty())
        }

        if (!PairingManager.isValid(session.parameters["t"]?.firstOrNull())) {
            return error(Response.Status.UNAUTHORIZED, "unauthorized")
        }
        return when {
            path == "/api/videos" -> listVideos()
            path.startsWith("/api/thumb/") -> thumbnail(videoUri(path.removePrefix("/api/thumb/")))
            path.startsWith("/api/info/") -> videoInfo(videoUri(path.removePrefix("/api/info/")))
            path.startsWith("/video/") -> {
                val uri = videoUri(path.removePrefix("/video/")) ?: return error(Response.Status.NOT_FOUND, "not_found")
                if (method == Method.GET) recordWatching(session.parameters["t"]?.firstOrNull(), uri)
                serveVideo(uri, session.headers["range"], head = method == Method.HEAD)
            }
            else -> error(Response.Status.NOT_FOUND, "not_found")
        }
    }

    // ---- pairing -------------------------------------------------------------------------------------

    private fun pairRequest(session: IHTTPSession): Response {
        runCatching { session.parseBody(HashMap()) } // drain any body so a kept-alive connection stays in sync
        return when (val result = PairingManager.request(describeClient(session.headers["user-agent"]))) {
            is RequestResult.Created -> json(
                JSONObject()
                    .put("id", result.request.id)
                    .put("code", result.request.code)
                    .put("expiresInMs", result.ttlMs),
            )
            RequestResult.Busy -> error(TOO_MANY_REQUESTS, "busy")
        }
    }

    private fun pairStatus(id: String): Response {
        val body = JSONObject()
        when (val status = PairingManager.status(id)) {
            PairStatus.Pending -> body.put("status", "pending")
            is PairStatus.Approved -> body.put("status", "approved").put("token", status.token)
            PairStatus.Denied -> body.put("status", "denied")
            PairStatus.Expired -> body.put("status", "expired")
            PairStatus.Unknown -> return error(Response.Status.NOT_FOUND, "unknown_request")
        }
        return json(body)
    }

    /** A short human label for the approval prompt, e.g. "Fire TV" or "Windows PC (Chrome)". */
    private fun describeClient(userAgent: String?): String {
        val ua = userAgent.orEmpty()
        val device = when {
            "AFT" in ua || "Amazon" in ua -> "Fire TV"
            "SMART-TV" in ua || "Tizen" in ua || "Web0S" in ua -> "Smart TV"
            "Android" in ua -> "Android device"
            "iPhone" in ua || "iPad" in ua -> "iPhone/iPad"
            "Windows" in ua -> "Windows PC"
            "Macintosh" in ua -> "Mac"
            "Linux" in ua -> "Linux PC"
            else -> "A device"
        }
        val browser = when {
            "Edg/" in ua -> "Edge"
            "Firefox" in ua -> "Firefox"
            "Silk" in ua -> "Silk"
            "Chrome" in ua -> "Chrome"
            "Safari" in ua -> "Safari"
            else -> null
        }
        return if (browser != null) "$device ($browser)" else device
    }

    // ---- gallery -------------------------------------------------------------------------------------

    private fun listVideos(): Response {
        val videos = JSONArray()
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
            )
            appContext.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext() && videos.length() < MAX_LISTED) {
                    if (!ShareSettings.isAllowed(c.getLong(0))) continue
                    videos.put(
                        JSONObject()
                            .put("id", c.getLong(0).toString())
                            .put("name", c.getString(1) ?: "video")
                            .put("size", c.getLong(2))
                            .put("durationMs", c.getLong(3)),
                    )
                }
            }
        } catch (e: SecurityException) {
            return error(Response.Status.FORBIDDEN, "storage_permission")
        }
        val scope = if (ShareSettings.state.value.mode == ShareMode.ALL) "all" else "selected"
        return json(JSONObject().put("scope", scope).put("videos", videos))
    }

    /**
     * Only numeric MediaStore ids are accepted, so a client can never name an arbitrary file or Uri,
     * and only ones inside the owner's current selection, so guessing ids can't reveal other videos.
     */
    private fun videoUri(rawId: String): Uri? {
        val id = rawId.substringBefore('/').toLongOrNull() ?: return null
        if (!ShareSettings.isAllowed(id)) return null
        return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
    }

    /** Reads the track codecs without decoding anything; unknown fields are simply missing, not an error. */
    private fun videoInfo(uri: Uri?): Response {
        if (uri == null) return error(Response.Status.NOT_FOUND, "not_found")
        val key = ContentUris.parseId(uri)
        infoCache.get(key)?.let { return jsonText(it) }
        val info = JSONObject().put("container", appContext.contentResolver.getType(uri) ?: JSONObject.NULL)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(appContext, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && !info.has("video")) {
                    info.put("video", mime)
                    if (format.containsKey(MediaFormat.KEY_WIDTH)) info.put("width", format.getInteger(MediaFormat.KEY_WIDTH))
                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) info.put("height", format.getInteger(MediaFormat.KEY_HEIGHT))
                } else if (mime.startsWith("audio/") && !info.has("audio")) {
                    info.put("audio", mime)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read track info for $uri", e)
        } finally {
            extractor.release()
        }
        val text = info.toString()
        if (info.has("video")) infoCache.put(key, text)   // don't cache a failed read; it may succeed later
        return jsonText(text)
    }

    private fun recordWatching(token: String?, uri: Uri) {
        val (deviceId, label) = PairingManager.clientOf(token) ?: return
        ActivityTracker.touch(deviceId, label, ContentUris.parseId(uri)) { videoName(uri) }
    }

    private fun videoName(uri: Uri): String {
        val key = ContentUris.parseId(uri)
        nameCache.get(key)?.let { return it }
        val name = runCatching {
            appContext.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "a video"
        nameCache.put(key, name)
        return name
    }

    private fun thumbnail(uri: Uri?): Response {
        if (uri == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return error(Response.Status.NOT_FOUND, "not_found")
        val key = ContentUris.parseId(uri)
        // Decoding a frame is the slowest thing the server does, so each encoded thumbnail is made once.
        val bytes = thumbCache.get(key) ?: run {
            val bitmap = try {
                appContext.contentResolver.loadThumbnail(uri, Size(320, 180), null)
            } catch (e: Exception) {
                return error(Response.Status.NOT_FOUND, "not_found")
            }
            ByteArrayOutputStream(24 * 1024).also { bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it) }
                .toByteArray().also { thumbCache.put(key, it) }
        }
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
            .apply { addHeader("Cache-Control", "private, max-age=86400") }
    }

    // ---- streaming (RFC 7233 single range) -----------------------------------------------------------

    private fun serveVideo(uri: Uri, rangeHeader: String?, head: Boolean): Response {
        val afd = try {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (e: SecurityException) {
            return error(Response.Status.FORBIDDEN, "storage_permission")
        } catch (e: Exception) {
            null
        } ?: return error(Response.Status.NOT_FOUND, "not_found")

        val mime = appContext.contentResolver.getType(uri) ?: "video/mp4"
        val length = fileLength(afd)
        if (length < 0) {
            if (head) { afd.closeQuietly(); return mediaHeaders(newFixedLengthResponse(Response.Status.OK, mime, "")) }
            return mediaHeaders(newChunkedResponse(Response.Status.OK, mime, afd.createInputStream()))
        }

        return when (val range = parseRange(rangeHeader, length)) {
            RangeResult.Unsatisfiable -> {
                afd.closeQuietly()
                mediaHeaders(error(Response.Status.RANGE_NOT_SATISFIABLE, "range_not_satisfiable"))
                    .apply { addHeader("Content-Range", "bytes */$length") }
            }
            RangeResult.Full -> buildBody(afd, mime, Response.Status.OK, 0, length - 1, length, head)
            is RangeResult.Partial -> buildBody(afd, mime, Response.Status.PARTIAL_CONTENT, range.start, range.end, length, head)
        }
    }

    private fun buildBody(
        afd: AssetFileDescriptor,
        mime: String,
        status: Response.IStatus,
        start: Long,
        end: Long,
        totalLength: Long,
        head: Boolean,
    ): Response {
        val count = if (totalLength == 0L) 0 else end - start + 1
        val response = if (head) {
            afd.closeQuietly()
            newFixedLengthResponse(status, mime, "").apply { addHeader("Content-Length", count.toString()) }
        } else {
            newFixedLengthResponse(status, mime, RangeInputStream(afd, start, count), count)
        }
        if (status == Response.Status.PARTIAL_CONTENT) {
            response.addHeader("Content-Range", "bytes $start-$end/$totalLength")
        }
        return mediaHeaders(response)
    }

    private fun mediaHeaders(response: Response): Response = response.apply {
        addHeader("Accept-Ranges", "bytes")
        addHeader("Cache-Control", "no-cache")
        addHeader("transferMode.dlna.org", "Streaming")
        addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
    }

    private fun withCors(response: Response): Response = response.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun json(body: JSONObject): Response = jsonText(body.toString())

    private fun jsonText(text: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text)
            .apply { addHeader("Cache-Control", "no-store") }

    private fun error(status: Response.IStatus, code: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", JSONObject().put("error", code).toString())

    private fun fileLength(afd: AssetFileDescriptor): Long {
        if (afd.length >= 0) return afd.length
        return runCatching { afd.parcelFileDescriptor.statSize }.getOrDefault(-1L)
    }

    private sealed interface RangeResult {
        data object Full : RangeResult
        data object Unsatisfiable : RangeResult
        data class Partial(val start: Long, val end: Long) : RangeResult
    }

    /** Parses the first range of `bytes=a-b`, `bytes=a-` or `bytes=-n`; malformed headers are ignored per RFC 7233. */
    private fun parseRange(header: String?, length: Long): RangeResult {
        if (header == null || !header.startsWith("bytes=")) return RangeResult.Full
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeResult.Full
        val first = spec.substring(0, dash).trim()
        val last = spec.substring(dash + 1).trim()

        val start: Long
        val end: Long
        if (first.isEmpty()) {
            val suffix = last.toLongOrNull() ?: return RangeResult.Full
            if (suffix <= 0) return RangeResult.Unsatisfiable
            start = maxOf(0L, length - suffix)
            end = length - 1
        } else {
            start = first.toLongOrNull() ?: return RangeResult.Full
            end = if (last.isEmpty()) length - 1 else minOf(last.toLongOrNull() ?: return RangeResult.Full, length - 1)
        }
        return if (start >= length || start > end) RangeResult.Unsatisfiable else RangeResult.Partial(start, end)
    }

    /** Reads exactly [remaining] bytes starting at [start]; closing it releases the file descriptor. */
    private class RangeInputStream(
        private val afd: AssetFileDescriptor,
        start: Long,
        private var remaining: Long,
    ) : InputStream() {
        private val input: InputStream = afd.createInputStream()

        init {
            try {
                skipFully(start)
            } catch (e: IOException) {
                close()
                throw e
            }
        }

        private fun skipFully(bytes: Long) {
            var left = bytes
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else {
                    if (input.read() == -1) throw IOException("Unexpected EOF while seeking")
                    left--
                }
            }
        }

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = input.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = input.read(buffer, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(input.available().toLong(), remaining).toInt()

        override fun close() {
            runCatching { input.close() }
            afd.closeQuietly()
        }
    }

    /** Hands each connection to a cached thread pool and remembers them so [closeAll] can stop everything. */
    private class PoolRunner : NanoHTTPD.AsyncRunner {
        private val pool = Executors.newCachedThreadPool { task -> Thread(task, "caster-http").apply { isDaemon = true } }
        private val running = Collections.synchronizedSet(HashSet<NanoHTTPD.ClientHandler>())

        override fun closeAll() {
            synchronized(running) { running.toList() }.forEach { it.close() }
            pool.shutdownNow()
        }

        override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
            running.remove(clientHandler)
        }

        override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
            running.add(clientHandler)
            pool.execute(clientHandler)
        }
    }

    private companion object {
        const val TAG = "LocalMediaServer"
        const val MAX_LISTED = 1000

        /** Encoded JPEG thumbnails, capped by bytes (about 6 MB, a few hundred videos' worth). */
        val thumbCache = object : LruCache<Long, ByteArray>(6 * 1024 * 1024) {
            override fun sizeOf(key: Long, value: ByteArray) = value.size
        }
        val infoCache = LruCache<Long, String>(512)
        val nameCache = LruCache<Long, String>(256)

        val TOO_MANY_REQUESTS = object : Response.IStatus {
            override fun getRequestStatus() = 429
            override fun getDescription() = "429 Too Many Requests"
        }

        fun AssetFileDescriptor.closeQuietly() {
            try { close() } catch (_: IOException) { }
        }
    }
}
