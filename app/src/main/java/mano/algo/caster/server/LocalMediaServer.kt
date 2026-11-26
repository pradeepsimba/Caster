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
import java.io.File
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
 *  GET  /api/list?k=v|a|i    videos, music or photos (music and photos only when the owner enabled them) (token)
 *  GET  /api/sub/{id}?t=     WebVTT subtitles found next to the video file (token)
 *  GET  /api/remote?t=&...   page state in, queued phone-remote commands out (token)
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
        // Small writes (the answer's header, the last piece of a range) go out at once instead of waiting to be joined (Nagle),
        // so a player asking for many ranges is not held up. (The send buffer is left to the system: setting it by hand would
        // turn off its automatic sizing.)
        setServerSocketFactory {
            object : java.net.ServerSocket() {
                override fun accept(): java.net.Socket = super.accept().also { socket ->
                    runCatching { socket.tcpNoDelay = true }
                }
            }
        }
    }
    private val indexHtml: String by lazy {
        appContext.assets.open("index.html").bufferedReader().use { it.readText() }
    }

    override fun serve(session: IHTTPSession): Response = try {
        withCors(route(session))
    } catch (e: OutOfMemoryError) {
        // A request that needs more memory than is free must not take the whole app (and every stream) down with it.
        thumbCache.evictAll()
        infoCache.evictAll()
        Log.e(TAG, "Out of memory serving ${session.uri}", e)
        withCors(error(Response.Status.INTERNAL_ERROR, "out_of_memory"))
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
        if (path == "/api/pair/disconnect") {
            return if (method == Method.POST) pairDisconnect(session) else error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")
        }
        if (path == "/upnp/control/cds") {
            if (method != Method.POST && method != Method.GET) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")
            if (method == Method.POST) runCatching { session.parseBody(HashMap()) }
            return upnpControlContentDirectory(session)
        }
        if (path == "/upnp/control/cm") {
            if (method != Method.POST && method != Method.GET) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")
            if (method == Method.POST) runCatching { session.parseBody(HashMap()) }
            return upnpControlConnectionManager()
        }
        if (method != Method.GET && method != Method.HEAD) return error(Response.Status.METHOD_NOT_ALLOWED, "method_not_allowed")

        when (path) {
            "/", "/index.html" -> return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", indexHtml)
            "/favicon.ico" -> return error(Response.Status.NOT_FOUND, "not_found") // not behind the token, so no 401 noise
            "/api/ping" -> return json(
                JSONObject().put("app", "caster").put("name", Build.MODEL ?: "Android").put("id", ShareSettings.instanceId)
                    .put("pw", ShareSettings.state.value.password.isNotEmpty()),
            )
            "/api/pair/status" -> return pairStatus(session.parameters["id"]?.firstOrNull().orEmpty())
            "/api/speed" -> return speedChunk()
            "/upnp/desc.xml" -> return upnpDeviceDescription()
            "/upnp/cds.xml" -> return upnpContentDirectoryScpd()
            "/upnp/cm.xml" -> return upnpConnectionManagerScpd()
        }

        val isUpnpVideo = path.startsWith("/video/") && session.parameters["upnp"]?.firstOrNull() == "1"
        if (!isUpnpVideo && !PairingManager.isValid(session.parameters["t"]?.firstOrNull())) {
            return error(Response.Status.UNAUTHORIZED, "unauthorized")
        }
        val kind = session.parameters["k"]?.firstOrNull() ?: "v"
        val token = session.parameters["t"]?.firstOrNull()
        val query = session.parameters["q"]?.firstOrNull()
        val sort = session.parameters["sort"]?.firstOrNull()
        return when {
            path == "/api/videos" -> listMedia("v", token, query, sort)
            path == "/api/list" -> listMedia(kind, token, query, sort)
            path == "/api/remote" -> remote(session)
            path == "/api/resume" -> resumeList(token)
            path.startsWith("/api/thumb/") -> thumbnail(mediaUri(path.removePrefix("/api/thumb/"), kind, token), kind)
            path.startsWith("/api/info/") -> videoInfo(mediaUri(path.removePrefix("/api/info/"), "v", token))
            path.startsWith("/api/sub/") -> subtitles(mediaUri(path.removePrefix("/api/sub/"), "v", token))
            path.startsWith("/video/") -> {
                val id = path.removePrefix("/video/").substringBefore('/').toLongOrNull() ?: -1L
                val uri = if (isUpnpVideo) {
                    if (id < 0) return error(Response.Status.NOT_FOUND, "not_found")
                    if (!ShareSettings.isAllowed(id)) return error(Response.Status.FORBIDDEN, "not_allowed")
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    mediaUri(path.removePrefix("/video/"), kind, token) ?: return error(Response.Status.NOT_FOUND, "not_found")
                }
                val isDownload = session.parameters["download"]?.firstOrNull() == "1"
                if (isDownload && !ShareSettings.state.value.allowDownload) {
                    return error(Response.Status.FORBIDDEN, "downloads_disabled")
                }
                val isTranscode = !isDownload && kind == "v" && session.parameters["transcode"]?.firstOrNull() == "1"
                if (method == Method.GET && kind == "v") recordWatching(token, uri)
                val stillAllowed = {
                    if (isUpnpVideo) ShareSettings.isAllowed(id)
                    else PairingManager.isValid(token) &&
                        (if (kind == "v") isWebVideoAllowed(token, id) else extrasAllowed(token))
                }
                if (isTranscode) {
                    val startSec = session.parameters["start"]?.firstOrNull()?.toLongOrNull() ?: 0L
                    val sessionKey = (token ?: session.remoteIpAddress ?: "anon") + ":" + id
                    if (method == Method.HEAD) {
                        return newFixedLengthResponse(Response.Status.OK, "video/mp4", "")
                            .apply { addHeader("Cache-Control", "no-store") }
                    }
                    val stream = VideoTranscoder.openStream(appContext, uri, startSec, sessionKey, stillAllowed)
                    return newChunkedResponse(Response.Status.OK, "video/mp4", stream)
                        .apply { addHeader("Cache-Control", "no-store") }
                }
                serveVideo(uri, session.headers["range"], head = method == Method.HEAD, isDownload = isDownload, stillAllowed = stillAllowed)
            }
            else -> error(Response.Status.NOT_FOUND, "not_found")
        }
    }

    // ---- pairing -------------------------------------------------------------------------------------

    private fun pairRequest(session: IHTTPSession): Response {
        // This needs no token, so anyone on the network can send it: a big body is refused, never read.
        val declared = session.headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
        if (declared > MAX_PAIR_BODY) return error(PAYLOAD_TOO_LARGE, "too_large").also { it.closeConnection(true) }
        runCatching { session.parseBody(HashMap()) } // drain any body so a kept-alive connection stays in sync
        // Check the sharing password before even creating a pairing request.
        val pw = ShareSettings.state.value.password
        if (pw.isNotEmpty() && session.parameters["pw"]?.firstOrNull().orEmpty() != pw) {
            return error(Response.Status.UNAUTHORIZED, "wrong_password")
        }
        // The page sends the random id it keeps for itself (letters and digits only), so screens with the same name stay apart.
        val clientId = session.parameters["cid"]?.firstOrNull()?.takeIf { Regex("[A-Za-z0-9]{8,40}").matches(it) }.orEmpty()
        return when (val result = PairingManager.request(describeClient(session.headers["user-agent"]), session.remoteIpAddress.orEmpty(), clientId)) {
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
                .put("allow_download", ShareSettings.state.value.allowDownload)
            PairStatus.Denied -> body.put("status", "denied")
            PairStatus.Expired -> body.put("status", "expired")
            PairStatus.Unknown -> return error(Response.Status.NOT_FOUND, "unknown_request")
        }
        return json(body)
    }

    private fun pairDisconnect(session: IHTTPSession): Response {
        val token = session.parameters["t"]?.firstOrNull()
        if (token != null) {
            PairingManager.revokeByToken(token)
        }
        return json(JSONObject().put("status", "disconnected"))
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

    // ---- speed test ----------------------------------------------------------------------------------

    /** Serves 4 MB of zero bytes so the caller can measure the LAN throughput to this phone. */
    private fun speedChunk(): Response {
        val bytes = ByteArray(4 * 1024 * 1024)
        return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", ByteArrayInputStream(bytes), bytes.size.toLong())
            .apply { addHeader("Cache-Control", "no-store") }
    }

    // ---- gallery -------------------------------------------------------------------------------------

    private fun tableFor(kind: String): Uri? = when (kind) {
        "v" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        "a" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        "i" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        else -> null
    }

    /** Lists videos ("v"), music ("a") or photos ("i"). */
    private fun listMedia(kind: String, token: String?, query: String? = null, sort: String? = null): Response {
        val table = tableFor(kind) ?: return error(Response.Status.NOT_FOUND, "not_found")
        if (kind != "v" && !extrasAllowed(token)) return error(Response.Status.FORBIDDEN, "extras_off")
        val items = JSONArray()
        val limit = if (kind == "v") MAX_LISTED else MAX_LISTED * 3   // phones hold far more photos than videos
        var truncated = false // more than the limit exist: the page says so instead of silently missing the older ones
        val filterQuery = query?.trim()?.lowercase()?.ifEmpty { null }
        try {
            val folderColumn = when (kind) {
                "v" -> MediaStore.Video.Media.BUCKET_DISPLAY_NAME
                "i" -> MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                "a" -> MediaStore.Audio.Media.ALBUM
                else -> null
            }
            val bucketColumnName = when (kind) {
                "v" -> MediaStore.Video.Media.BUCKET_ID
                "i" -> MediaStore.Images.Media.BUCKET_ID
                "a" -> MediaStore.Audio.Media.ALBUM_ID
                else -> null
            }
            val projection = listOfNotNull(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                if (kind == "i") null else MediaStore.Video.Media.DURATION, // same column name for music
                folderColumn,
                bucketColumnName,
            ).toTypedArray()
            val sortOrder = when (sort) {
                "name" -> "${MediaStore.MediaColumns.DISPLAY_NAME} COLLATE NOCASE ASC"
                "big" -> "${MediaStore.MediaColumns.SIZE} DESC"
                "long" -> if (kind != "i") "${MediaStore.Video.Media.DURATION} DESC" else "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                else -> "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            }
            appContext.contentResolver.query(
                // Music means music: ringtones, alarms, voice notes and call recordings are in the same table.
                table, projection, if (kind == "a") "${MediaStore.Audio.Media.IS_MUSIC} != 0" else null, null,
                sortOrder,
            )?.use { c ->
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
                val folderCol = folderColumn?.let { c.getColumnIndex(it) } ?: -1
                val bucketCol = bucketColumnName?.let { c.getColumnIndex(it) } ?: -1
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val bucket = if (bucketCol >= 0) c.getLong(bucketCol) else null
                    if (kind == "v" && !isWebVideoAllowed(token, id, bucket)) continue
                    if (kind == "i" && !ShareSettings.isPhotoAllowed(id, bucket)) continue
                    if (kind == "a" && !ShareSettings.isMusicAllowed(id, bucket)) continue
                    val itemName = c.getString(nameCol) ?: "item"
                    if (filterQuery != null && !itemName.lowercase().contains(filterQuery)) continue
                    if (items.length() >= limit) {
                        truncated = true
                        break
                    }
                    items.put(
                        JSONObject()
                            .put("id", id.toString())
                            .put("name", itemName)
                            .put("size", c.getLong(sizeCol))
                            .put("durationMs", if (durCol >= 0) c.getLong(durCol) else 0L)
                            .put("folder", if (folderCol >= 0) c.getString(folderCol) ?: "" else ""),
                    )
                }
            }
        } catch (e: SecurityException) {
            return error(Response.Status.FORBIDDEN, "storage_permission")
        }
        val scope = if (PairingManager.isLimited(token) || !PairingManager.isLocal(token) || ShareSettings.state.value.mode != ShareMode.ALL) "selected" else "all"
        return json(
            JSONObject().put("scope", scope).put("extras", ShareSettings.state.value.shareExtras)
                .put("allow_download", ShareSettings.state.value.allowDownload)
                .put("kind", kind).put("videos", items).put("truncated", truncated),
        )
    }

    /**
     * Local casting sees whatever is shared under "Videos to share".
     * Web screens only see videos explicitly selected for that screen in "Limit" (and still shared).
     */
    private fun isWebVideoAllowed(token: String?, id: Long, bucketId: Long? = null): Boolean =
        if (PairingManager.isLocal(token)) ShareSettings.isAllowed(id, bucketId)
        else PairingManager.isLimited(token) && PairingManager.allows(token, id) && ShareSettings.isAllowed(id, bucketId)

    /** Photos and music are all-or-nothing, so a screen limited to some videos is not given them either. */
    private fun extrasAllowed(token: String?) = ShareSettings.state.value.shareExtras && !PairingManager.isLimited(token)

    /**
     * Only numeric MediaStore ids are accepted, so a client can never name an arbitrary file or Uri.
     * Videos must be inside the owner's current selection (or the screen's own limit), and music and photos need the owner's opt-in,
     * so guessing ids can't reveal anything that isn't shared.
     */
    private fun mediaUri(rawId: String, kind: String, token: String?): Uri? {
        val id = rawId.substringBefore('/').toLongOrNull() ?: return null
        val table = tableFor(kind) ?: return null
        val allowed = when (kind) {
            "v" -> isWebVideoAllowed(token, id)
            "i" -> extrasAllowed(token) && ShareSettings.isPhotoAllowed(id)
            "a" -> extrasAllowed(token) && ShareSettings.isMusicAllowed(id)
            else -> false
        }
        if (!allowed) return null
        return ContentUris.withAppendedId(table, id)
    }


    /** Reads the track codecs without decoding anything; unknown fields are simply missing, not an error. */
    private fun videoInfo(uri: Uri?): Response {
        if (uri == null) return error(Response.Status.NOT_FOUND, "not_found")
        val key = ContentUris.parseId(uri)
        infoCache.get(key)?.let { return jsonText(it) }
        val info = JSONObject().put("container", appContext.contentResolver.getType(uri) ?: JSONObject.NULL)
        val audios = JSONArray()
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
                    if (format.containsKey(MediaFormat.KEY_DURATION)) info.put("durationMs", format.getLong(MediaFormat.KEY_DURATION) / 1000L)
                } else if (mime.startsWith("audio/")) {
                    audios.put(mime)
                    if (!info.has("audio") || mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                        info.put("audio", mime)
                    }
                }
            }
            if (audios.length() > 0) info.put("audios", audios)
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

    @Suppress("DEPRECATION")
    private fun legacyThumbnail(uri: Uri, kind: String): Bitmap? {
        val id = ContentUris.parseId(uri)
        val resolver = appContext.contentResolver
        return when (kind) {
            "v" -> MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            "i" -> MediaStore.Images.Thumbnails.getThumbnail(resolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
            else -> null // music has no picture here
        }
    }

    private fun thumbnail(uri: Uri?, kind: String): Response {
        if (uri == null) return error(Response.Status.NOT_FOUND, "not_found")
        // Ids of videos, music and photos overlap, so each kind gets its own range of cache keys.
        val key = ContentUris.parseId(uri) + when (kind) { "a" -> 1L shl 60; "i" -> 1L shl 61; else -> 0L }
        // Decoding a frame is the slowest thing the server does, so each encoded thumbnail is made once.
        val bytes = thumbCache.get(key) ?: run {
            // A page full of thumbnails asks for dozens at once while a video is also playing; decoding them all
            // together is what exhausts memory and the media provider, so only a few run at a time.
            decodeGate.acquire()
            try {
                thumbCache.get(key) ?: run {
                    val bitmap = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            appContext.contentResolver.loadThumbnail(uri, Size(320, 180), null)
                        } else {
                            legacyThumbnail(uri, kind) // Android 9 and older have only the old thumbnail store
                        }
                    } catch (e: Exception) {
                        null
                    } ?: return error(Response.Status.NOT_FOUND, "not_found")
                    try {
                        ByteArrayOutputStream(24 * 1024).also { bitmap.compress(Bitmap.CompressFormat.JPEG, 78, it) }
                            .toByteArray().also { thumbCache.put(key, it) }
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                decodeGate.release()
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong())
            .apply { addHeader("Cache-Control", "private, max-age=86400") }
    }

    // ---- subtitles, conversion, remote ---------------------------------------------------------------

    /**
     * Looks for `name.srt` / `name.vtt` (also `name.en.srt`) beside the video. Best effort: newer Android
     * hides other apps' non-media files, so this works mainly on older phones and for files the app can read.
     */
    private fun subtitles(uri: Uri?): Response {
        if (uri == null) return error(Response.Status.NOT_FOUND, "not_found")
        val text = SubtitleFinder.vtt(appContext, uri) ?: return error(Response.Status.NOT_FOUND, "no_subtitles")
        return newFixedLengthResponse(Response.Status.OK, "text/vtt; charset=utf-8", text)
            .apply { addHeader("Cache-Control", "private, max-age=3600") }
    }

    /** The page reports what it is playing; the reply carries whatever the phone owner pressed since the last report. */
    private fun remote(session: IHTTPSession): Response {
        val p = session.parameters
        val (deviceId, label) = PairingManager.clientOf(p["t"]?.firstOrNull()) ?: return error(Response.Status.UNAUTHORIZED, "unauthorized")
        if (p["s"]?.firstOrNull() == "idle") {
            RemoteControl.idle(deviceId)
        } else {
            RemoteControl.report(
                RemoteState(
                    deviceId, label,
                    title = (p["n"]?.firstOrNull() ?: "").take(120),
                    playing = p["s"]?.firstOrNull() == "playing",
                    positionSec = p["p"]?.firstOrNull()?.toDoubleOrNull()?.toInt() ?: 0,
                    durationSec = p["d"]?.firstOrNull()?.toDoubleOrNull()?.toInt() ?: 0,
                    reportedAt = System.currentTimeMillis(),
                    volume = (p["vol"]?.firstOrNull()?.toIntOrNull() ?: -1).coerceIn(-1, 100),
                ),
            )
        }
        val videoId = p["v"]?.firstOrNull()?.toLongOrNull()
        val token = p["t"]?.firstOrNull()
        val reportedDuration = p["d"]?.firstOrNull()?.toDoubleOrNull()?.toInt() ?: 0
        if (videoId != null && reportedDuration > 0 && (p["k"]?.firstOrNull() ?: "v") == "v" && p["s"]?.firstOrNull() != "idle" &&
            isWebVideoAllowed(token, videoId)
        ) {
            ResumeStore.record(
                videoId,
                p["p"]?.firstOrNull()?.toDoubleOrNull()?.toInt() ?: 0,
                p["d"]?.firstOrNull()?.toDoubleOrNull()?.toInt() ?: 0,
            )
        }
        val cmds = JSONArray()
        RemoteControl.drain(deviceId).forEach { cmds.put(it) }
        return json(JSONObject().put("cmds", cmds))
    }

    /** Where each video was stopped, for the pages to offer "continue". Only videos this screen may see. */
    private fun resumeList(token: String?): Response {
        val items = JSONArray()
        ResumeStore.all().filter { isWebVideoAllowed(token, it.id) }.take(60).forEach {
            items.put(JSONObject().put("id", it.id.toString()).put("pos", it.positionSec).put("dur", it.durationSec).put("at", it.updated))
        }
        return json(JSONObject().put("items", items))
    }

    // ---- streaming (RFC 7233 single range) -----------------------------------------------------------

    private fun serveVideo(
        uri: Uri,
        rangeHeader: String?,
        head: Boolean,
        isDownload: Boolean = false,
        stillAllowed: () -> Boolean,
    ): Response {
        val afd = try {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")
        } catch (e: SecurityException) {
            return error(Response.Status.FORBIDDEN, "storage_permission")
        } catch (e: Exception) {
            null
        } ?: return error(Response.Status.NOT_FOUND, "not_found")

        val mime = runCatching { appContext.contentResolver.getType(uri) }.getOrNull() ?: "video/mp4"
        val response = serveOpened(afd, mime, rangeHeader, head, stillAllowed)
        if (isDownload) {
            response.addHeader("Content-Disposition", "attachment")
        }
        return response
    }

    /** Answers with [afd] (closing it): the whole file, or the one range asked for. */
    private fun serveOpened(afd: AssetFileDescriptor, mime: String, rangeHeader: String?, head: Boolean, stillAllowed: () -> Boolean): Response {
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
            RangeResult.Full -> buildBody(afd, mime, Response.Status.OK, 0, length - 1, length, head, stillAllowed)
            is RangeResult.Partial -> buildBody(afd, mime, Response.Status.PARTIAL_CONTENT, range.start, range.end, length, head, stillAllowed)
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
        stillAllowed: () -> Boolean,
    ): Response {
        val count = if (totalLength == 0L) 0 else end - start + 1
        val response = if (head) {
            afd.closeQuietly()
            newFixedLengthResponse(status, mime, "").apply { addHeader("Content-Length", count.toString()) }
        } else {
            newFixedLengthResponse(status, mime, RangeInputStream(afd, start, count, stillAllowed), count)
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
        private val stillAllowed: () -> Boolean,
    ) : InputStream() {
        // Read from storage in big pieces (the HTTP layer asks for 16 KB at a time, which is too slow for a 4K file).
        private val input: InputStream = try {
            java.io.BufferedInputStream(afd.createInputStream(), minOf(remaining, READ_AHEAD_BYTES).toInt().coerceAtLeast(8 * 1024))
        } catch (e: IOException) {
            afd.closeQuietly()
            throw e
        }
        private var sinceCheck = 0L

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
            if (n > 0) {
                remaining -= n
                // A long request (a whole film in one go) is checked now and then: revoking a device, or un-sharing the
                // video, ends it instead of letting it run to the end.
                sinceCheck += n
                if (sinceCheck >= CHECK_EVERY_BYTES) {
                    sinceCheck = 0
                    if (!stillAllowed()) {
                        close() // the file is let go now, not whenever the response happens to be cleaned up
                        throw IOException("No longer shared")
                    }
                }
            }
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
            if (running.size >= MAX_CONNECTIONS) { // more than any player needs: drop the extra instead of starting another thread
                clientHandler.close()
                return
            }
            running.add(clientHandler)
            try {
                pool.execute(clientHandler)
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                // Raced with stop(): the pool is already shut down. Drop this connection instead of throwing on the
                // accept thread, which has no handler and would end the whole app.
                running.remove(clientHandler)
                clientHandler.close()
            }
        }
    }

    private companion object {
        const val TAG = "LocalMediaServer"
        const val MAX_LISTED = 1000
        const val MAX_PAIR_BODY = 1024L
        const val MAX_CONNECTIONS = 128
        const val CHECK_EVERY_BYTES = 512L * 1024
        const val READ_AHEAD_BYTES = 256L * 1024

        val PAYLOAD_TOO_LARGE = object : Response.IStatus {
            override fun getRequestStatus() = 413
            override fun getDescription() = "413 Payload Too Large"
        }
        val SUBTITLE_EXTENSIONS = setOf("srt", "vtt")
        const val MAX_SUBTITLE_BYTES = 2L * 1024 * 1024
        val SRT_TIMESTAMP = Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})""")

        /** Encoded JPEG thumbnails, capped by bytes (about 6 MB, a few hundred videos' worth). */
        val thumbCache = object : LruCache<Long, ByteArray>(6 * 1024 * 1024) {
            override fun sizeOf(key: Long, value: ByteArray) = value.size
        }
        /** How many thumbnails may be decoded at the same time. */
        val decodeGate = java.util.concurrent.Semaphore(3)
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

    private fun upnpDeviceDescription(): Response {
        val name = ShareSettings.state.value.hostname.ifEmpty { Build.MODEL ?: "Caster" }
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
    <friendlyName>Caster ($name)</friendlyName>
    <manufacturer>Caster</manufacturer>
    <modelName>Caster DLNA Media Server</modelName>
    <UDN>uuid:${ShareSettings.instanceId}</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
        <SCPDURL>/upnp/cds.xml</SCPDURL>
        <controlURL>/upnp/control/cds</controlURL>
        <eventSubURL>/upnp/event/cds</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/upnp/cm.xml</SCPDURL>
        <controlURL>/upnp/control/cm</controlURL>
        <eventSubURL>/upnp/event/cm</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", xml)
    }

    private fun upnpContentDirectoryScpd(): Response {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action>
      <name>Browse</name>
      <argumentList>
        <argument><name>ObjectID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ObjectID</relatedStateVariable></argument>
        <argument><name>BrowseFlag</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_BrowseFlag</relatedStateVariable></argument>
        <argument><name>Filter</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Filter</relatedStateVariable></argument>
        <argument><name>StartingIndex</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Index</relatedStateVariable></argument>
        <argument><name>RequestedCount</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
        <argument><name>SortCriteria</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SortCriteria</relatedStateVariable></argument>
        <argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>
        <argument><name>NumberReturned</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
        <argument><name>TotalMatches</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Count</relatedStateVariable></argument>
        <argument><name>UpdateID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UpdateID</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetSystemUpdateID</name><argumentList><argument><name>Id</name><direction>out</direction><relatedStateVariable>SystemUpdateID</relatedStateVariable></argument></argumentList></action>
    <action><name>GetSortCapabilities</name><argumentList><argument><name>SortCaps</name><direction>out</direction><relatedStateVariable>SortCapabilities</relatedStateVariable></argument></argumentList></action>
    <action><name>GetSearchCapabilities</name><argumentList><argument><name>SearchCaps</name><direction>out</direction><relatedStateVariable>SearchCapabilities</relatedStateVariable></argument></argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ObjectID</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Result</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_BrowseFlag</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Filter</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SortCriteria</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Index</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Count</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_UpdateID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>SortCapabilities</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>SearchCapabilities</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>SystemUpdateID</name><dataType>ui4</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", xml)
    }

    private fun upnpConnectionManagerScpd(): Response {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action>
      <name>GetProtocolInfo</name>
      <argumentList>
        <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
        <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action>
      <name>GetCurrentConnectionIDs</name>
      <argumentList>
        <argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="yes"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", xml)
    }

    private fun upnpControlConnectionManager(): Response {
        val soap = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
      <Source>http-get:*:video/mp4:*,http-get:*:video/*:*,http-get:*:audio/*:*</Source>
      <Sink></Sink>
    </u:GetProtocolInfoResponse>
  </s:Body>
</s:Envelope>"""
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", soap)
    }

    private fun upnpControlContentDirectory(session: IHTTPSession): Response {
        val host = session.headers["host"] ?: "127.0.0.1"
        val didlItems = StringBuilder()
        var count = 0
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.Video.Media.BUCKET_ID,
            )
            appContext.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                val bucketCol = c.getColumnIndex(MediaStore.Video.Media.BUCKET_ID)
                while (c.moveToNext() && count < 100) {
                    val id = c.getLong(0)
                    val bucket = if (bucketCol >= 0) c.getLong(bucketCol) else null
                    if (!ShareSettings.isAllowed(id, bucket)) continue
                    val name = (c.getString(1) ?: "video_$id").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                    val size = c.getLong(2)
                    didlItems.append(
                        """<item id="$id" parentID="0" restricted="1"><dc:title>$name</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:video/mp4:*" size="$size">http://$host/video/$id?upnp=1</res></item>""",
                    )
                    count++
                }
            }
        } catch (_: Exception) {}

        val didlEscaped = ("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            didlItems.toString() + "</DIDL-Lite>")
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        val soap = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <Result>$didlEscaped</Result>
      <NumberReturned>$count</NumberReturned>
      <TotalMatches>$count</TotalMatches>
      <UpdateID>1</UpdateID>
    </u:BrowseResponse>
  </s:Body>
</s:Envelope>"""
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=utf-8", soap)
    }
}
