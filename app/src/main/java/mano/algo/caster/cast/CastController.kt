package mano.algo.caster.cast

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mano.algo.caster.server.PairingManager
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaTrack
import mano.algo.caster.server.ResumeStore
import mano.algo.caster.server.SubtitleFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import mano.algo.caster.server.MediaServerService
import mano.algo.caster.server.NetworkUtils
import mano.algo.caster.server.ServerState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plays a video from this phone on a Chromecast. The Chromecast fetches the file itself from the phone's media
 * server, so casting works only while sharing is on and both are on the same network. The phone is just the
 * remote: pausing, or locking the screen, doesn't interrupt the stream.
 */
object CastController {
    /** [device] is the connected Chromecast's name; [title] what it is playing or loading. */
    data class State(
        val available: Boolean = true,
        val device: String? = null,
        val title: String? = null,
        val playing: Boolean = false,
        val error: String? = null,
        /** The id the Cast library gives the connected Chromecast (what [recent] remembers it by). */
        val deviceId: String? = null,
        val kind: String = "v",
        val currentIndex: Int = 0,
        val totalCount: Int = 0,
        val muted: Boolean = false,
        val positionSec: Int = 0,
        val durationSec: Int = 0,
    )

    data class CastItem(val id: Long, val name: String, val kind: String = "v")

    private val _playlist = MutableStateFlow<List<CastItem>>(emptyList())
    val playlist: StateFlow<List<CastItem>> = _playlist.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentKind = MutableStateFlow("v")
    val currentKind: StateFlow<String> = _currentKind.asStateFlow()

    private var lastBaseUrl: String = ""

    /** A Chromecast that was connected before. */
    data class Recent(val id: String, val name: String)

    private const val TOKEN_LABEL = PairingManager.CHROMECAST_LABEL

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private const val PREFS = "caster_cast"
    private const val KEY_RECENT = "recent"
    private const val KEY_DIRECT_HOSTS = "direct_hosts"
    private const val RECENT_MAX = 6

    private val _recent = MutableStateFlow<List<Recent>>(emptyList())

    /** Chromecasts connected before, the newest first. */
    val recent: StateFlow<List<Recent>> = _recent.asStateFlow()

    private val _nearby = MutableStateFlow<Set<String>>(emptySet())

    /** The ids of the Chromecasts answering on this Wi-Fi right now (looked for while the Chromecast card is on screen). */
    val nearby: StateFlow<Set<String>> = _nearby.asStateFlow()

    private val _directDevices = MutableStateFlow<List<DirectCastDevice>>(emptyList())

    /** Chromecasts found directly on the local subnet (used on a phone's hotspot, where Google's scanner sees nothing). */
    val directDevices: StateFlow<List<DirectCastDevice>> = _directDevices.asStateFlow()

    private val _scanningDirect = MutableStateFlow(false)
    val scanningDirect: StateFlow<Boolean> = _scanningDirect.asStateFlow()

    private data class PendingReconnect(
        val deviceId: String,
        val deviceName: String,
        val items: List<CastItem>,
        val index: Int,
        val positionSec: Int,
        val baseUrl: String,
        val muted: Boolean,
    )

    @Volatile private var pendingReconnect: PendingReconnect? = null

    private var appContext: Context? = null
    private var router: MediaRouter? = null

    private var castContext: CastContext? = null
    private var client: RemoteMediaClient? = null
    @Volatile private var directSession: DirectCastSession? = null
    private var directHosts: MutableMap<String, String> = mutableMapOf()

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = refresh()
        override fun onMetadataUpdated() = refresh()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
        override fun onSessionEnded(session: CastSession, error: Int) {
            val pending = pendingReconnect
            detach()
            if (pending != null) {
                background.launch(Dispatchers.Main) {
                    connectPendingRoute(pending)
                }
            }
        }
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pendingReconnect = null
            _state.value = _state.value.copy(error = "Couldn't connect to the Chromecast. Try again.")
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            pendingReconnect = null
            detach()
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    /** Safe to call often. Without Google Play services the Cast library can't start, so Chromecast is reported unavailable. */
    fun init(context: Context) {
        if (castContext != null) return
        if (appContext == null) {
            appContext = context.applicationContext
            _recent.value = loadRecent(context.applicationContext)
            directHosts = loadDirectHosts(context.applicationContext)
        }
        val cc = runCatching { CastContext.getSharedInstance(context.applicationContext) }.getOrNull()
        if (cc == null) {
            _state.value = State(available = false)
            return
        }
        castContext = cc
        cc.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        cc.sessionManager.currentCastSession?.let(::attach)
    }

    private fun attach(session: CastSession) {
        directSession?.let { old ->
            directSession = null
            background.launch { runCatching { old.stopAndClose() } }
        }
        client?.unregisterCallback(mediaCallback)
        client?.removeProgressListener(progress)
        client = session.remoteMediaClient?.also {
            it.registerCallback(mediaCallback)
            it.addProgressListener(progress, 1_000L)
        }
        val device = session.castDevice
        if (device != null) rememberDevice(device.deviceId, device.friendlyName ?: "Chromecast")
        _state.value = State(device = device?.friendlyName ?: "Chromecast", deviceId = device?.deviceId)
        val pending = pendingReconnect
        val ctx = appContext
        if (pending != null && ctx != null && client != null) {
            pendingReconnect = null
            restorePlayback(ctx, pending)
        } else {
            refresh()
        }
    }

    private fun detach() {
        client?.removeProgressListener(progress)
        client?.unregisterCallback(mediaCallback)
        client = null
        if (directSession == null && pendingReconnect == null) _state.value = State()
    }

    private fun refresh() {
        val c = client ?: return
        val status = c.mediaStatus
        val failed = status?.playerState == MediaStatus.PLAYER_STATE_IDLE &&
            status.idleReason == MediaStatus.IDLE_REASON_ERROR
        val title = c.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE)
        val isMuted = runCatching { castContext?.sessionManager?.currentCastSession?.isMute }.getOrNull() ?: _state.value.muted
        val posSec = (c.approximateStreamPosition / 1000L).toInt().coerceAtLeast(0)
        val durSec = ((c.mediaInfo?.streamDuration ?: 0L) / 1000L).toInt().coerceAtLeast(0)
        _state.value = _state.value.copy(
            title = title ?: _state.value.title,
            kind = _currentKind.value,
            currentIndex = _currentIndex.value,
            totalCount = _playlist.value.size,
            muted = isMuted,
            positionSec = posSec,
            durationSec = if (durSec > 0) durSec else _state.value.durationSec,
            // Buffering and loading count as playing, so the button reads "Pause" rather than "Play" while it starts.
            playing = status?.playerState.let {
                it == MediaStatus.PLAYER_STATE_PLAYING || it == MediaStatus.PLAYER_STATE_BUFFERING || it == MediaStatus.PLAYER_STATE_LOADING
            },
            error = if (failed) "The Chromecast couldn't play this media. Its format may not be supported." else null,
        )
    }

    private fun hasActiveConnection(): Boolean = client != null || directSession != null

    /**
     * Starts [name] on the connected Chromecast. [baseUrl] is the media server's address (an IP, because a
     * Chromecast can't resolve `.local` names). Returns false when no Chromecast is connected.
     */
    fun cast(context: Context, id: Long, name: String, baseUrl: String, kind: String = "v"): Boolean {
        if (!hasActiveConnection()) {
            // The Chromecast went away (or never connected): say so instead of doing nothing.
            _state.value = _state.value.copy(error = "Not connected to a Chromecast. Choose one first.")
            return false
        }
        _playlist.value = listOf(CastItem(id, name, kind))
        _currentIndex.value = 0
        _currentKind.value = kind
        lastBaseUrl = baseUrl
        load(context, id, name, baseUrl, kind)
        return true
    }

    private data class ResolvedMedia(
        val contentUrl: String,
        val thumbUrl: String,
        val mime: String,
        val metadataType: Int,
        val title: String,
        val subtitleUrl: String?,
    )

    private fun resolveMedia(context: Context, id: Long, name: String, baseUrl: String, token: String, kind: String): ResolvedMedia {
        val query = "?t=" + Uri.encode(token) + "&k=" + Uri.encode(kind)
        val (contentUri, fallbackMime, metadataType) = when (kind) {
            "i" -> Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg", MediaMetadata.MEDIA_TYPE_PHOTO)
            "a" -> Triple(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio/mpeg", MediaMetadata.MEDIA_TYPE_MUSIC_TRACK)
            else -> Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/mp4", MediaMetadata.MEDIA_TYPE_MOVIE)
        }
        val uri = ContentUris.withAppendedId(contentUri, id)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: fallbackMime
        val subUrl = if (kind == "v" && runCatching { SubtitleFinder.find(context, uri) }.getOrNull() != null) {
            baseUrl + "api/sub/" + id + query
        } else null
        return ResolvedMedia(
            contentUrl = baseUrl + "video/" + id + query,
            thumbUrl = baseUrl + "api/thumb/" + id + query,
            mime = mime,
            metadataType = metadataType,
            title = name,
            subtitleUrl = subUrl,
        )
    }

    /** Everything the Chromecast needs to fetch one item: the address (with the pass), a picture, and subtitles when there are any. */
    private fun mediaInfo(context: Context, id: Long, name: String, baseUrl: String, token: String, kind: String = "v"): MediaInfo {
        val m = resolveMedia(context, id, name, baseUrl, token, kind)
        val metadata = MediaMetadata(m.metadataType).apply {
            putString(MediaMetadata.KEY_TITLE, m.title)
            addImage(WebImage(Uri.parse(m.thumbUrl)))
        }
        val builder = MediaInfo.Builder(m.contentUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(m.mime)
            .setMetadata(metadata)
        if (m.subtitleUrl != null) {
            builder.setMediaTracks(
                listOf(
                    MediaTrack.Builder(SUBTITLE_TRACK, MediaTrack.TYPE_TEXT)
                        .setContentId(m.subtitleUrl)
                        .setContentType("text/vtt")
                        .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                        .setName("Subtitles")
                        .setLanguage("en")
                        .build(),
                ),
            )
        }
        return builder.build()
    }

    private fun directMediaJson(context: Context, id: Long, name: String, baseUrl: String, token: String, kind: String = "v"): JSONObject {
        val m = resolveMedia(context, id, name, baseUrl, token, kind)
        val metadata = JSONObject()
            .put("metadataType", m.metadataType)
            .put("title", m.title)
            .put("images", JSONArray().put(JSONObject().put("url", m.thumbUrl)))
        val media = JSONObject()
            .put("contentId", m.contentUrl)
            .put("streamType", "BUFFERED")
            .put("contentType", m.mime)
            .put("metadata", metadata)
        if (m.subtitleUrl != null) {
            val track = JSONObject()
                .put("trackId", SUBTITLE_TRACK)
                .put("type", "TEXT")
                .put("trackContentId", m.subtitleUrl)
                .put("trackContentType", "text/vtt")
                .put("subtype", "SUBTITLES")
                .put("name", "Subtitles")
                .put("language", "en")
            media.put("tracks", JSONArray().put(track))
        }
        return media
    }

    /** Hands the Chromecast the address of the selected media and starts it (where it was stopped, when that is known). */
    private fun load(context: Context, id: Long, name: String, baseUrl: String, kind: String = "v", startPositionSec: Int? = null) {
        val direct = directSession
        if (client == null && direct == null) return
        val token = PairingManager.issueLocal(TOKEN_LABEL)
        _state.value = _state.value.copy(
            title = name,
            kind = kind,
            error = null,
            currentIndex = _currentIndex.value,
            totalCount = _playlist.value.size.coerceAtLeast(1),
        )
        background.launch {
            val resumeSec = startPositionSec?.takeIf { it > 0 } ?: if (kind == "v") ResumeStore.get(id)?.positionSec else null
            if (direct != null) {
                val media = directMediaJson(context, id, name, baseUrl, token, kind)
                val tracks = if (kind == "v" && media.has("tracks")) longArrayOf(SUBTITLE_TRACK) else null
                direct.load(media, autoplay = true, currentTimeSec = resumeSec?.toDouble(), activeTrackIds = tracks)
            } else {
                val info = mediaInfo(context, id, name, baseUrl, token, kind)
                val request = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
                resumeSec?.let { request.setCurrentTime(it * 1000L) }
                if (kind == "v" && !info.mediaTracks.isNullOrEmpty()) request.setActiveTrackIds(longArrayOf(SUBTITLE_TRACK))
                withContext(Dispatchers.Main) { client?.load(request.build()) }
            }
        }
    }

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** At most this many items go to the Chromecast's queue at once (a long list is slow to hand over). */
    const val QUEUE_MAX = 20

    /** Plays [items] one after the other on the connected Chromecast. False when none is connected. */
    fun castPlaylist(context: Context, items: List<CastItem>, startIndex: Int = 0, baseUrl: String): Boolean =
        castPlaylistInternal(context, items, startIndex, baseUrl, startPositionSec = null)

    private fun castPlaylistInternal(
        context: Context,
        items: List<CastItem>,
        startIndex: Int,
        baseUrl: String,
        startPositionSec: Int?,
    ): Boolean {
        val direct = directSession
        if (client == null && direct == null) {
            _state.value = _state.value.copy(error = "Not connected to a Chromecast. Choose one first.")
            return false
        }
        val clamped = items.take(QUEUE_MAX)
        if (clamped.isEmpty()) return false
        val start = startIndex.coerceIn(0, clamped.size - 1)
        _playlist.value = clamped
        _currentIndex.value = start
        val current = clamped[start]
        _currentKind.value = current.kind
        lastBaseUrl = baseUrl
        val token = PairingManager.issueLocal(TOKEN_LABEL)
        _state.value = _state.value.copy(
            title = current.name,
            kind = current.kind,
            error = null,
            currentIndex = start,
            totalCount = clamped.size,
        )

        background.launch {
            if (direct != null) {
                val arr = JSONArray()
                clamped.forEachIndexed { idx, item ->
                    val media = directMediaJson(context, item.id, item.name, baseUrl, token, item.kind)
                    val qItem = JSONObject()
                        .put("media", media)
                        .put("autoplay", true)
                        .put("preloadTime", 20.0)
                    val resumeSec = if (idx == start && startPositionSec != null && startPositionSec > 0) {
                        startPositionSec
                    } else if (item.kind == "v") {
                        ResumeStore.get(item.id)?.positionSec
                    } else null
                    resumeSec?.let { qItem.put("startTime", it.toDouble()) }
                    if (item.kind == "v" && media.has("tracks")) {
                        qItem.put("activeTrackIds", JSONArray().put(SUBTITLE_TRACK))
                    }
                    arr.put(qItem)
                }
                direct.queueLoad(arr, start)
            } else {
                val queueItems = clamped.mapIndexed { idx, item ->
                    val info = mediaInfo(context, item.id, item.name, baseUrl, token, item.kind)
                    val resumeSec = if (idx == start && startPositionSec != null && startPositionSec > 0) {
                        startPositionSec
                    } else if (item.kind == "v") {
                        ResumeStore.get(item.id)?.positionSec
                    } else null
                    MediaQueueItem.Builder(info)
                        .setAutoplay(true)
                        .setPreloadTime(20.0)
                        .apply {
                            resumeSec?.let { setStartTime(it.toDouble()) }
                            if (item.kind == "v" && !info.mediaTracks.isNullOrEmpty()) {
                                setActiveTrackIds(longArrayOf(SUBTITLE_TRACK))
                            }
                        }
                        .build()
                }
                withContext(Dispatchers.Main) {
                    client?.queueLoad(queueItems.toTypedArray(), start, MediaStatus.REPEAT_MODE_REPEAT_OFF, null)
                }
            }
        }
        return true
    }

    /** Plays [videos] one after the other on the connected Chromecast, in this order or mixed. False when none is connected. */
    fun castAll(context: Context, videos: List<Pair<Long, String>>, baseUrl: String, shuffle: Boolean): Boolean {
        val list = (if (shuffle) videos.shuffled() else videos).take(QUEUE_MAX)
        if (list.isEmpty()) return false
        val items = list.map { CastItem(it.first, it.second, "v") }
        return castPlaylist(context, items, 0, baseUrl)
    }

    /** Jumps to [index] in the current Chromecast playlist. */
    fun playAt(index: Int, context: Context? = appContext): Boolean {
        if (!hasActiveConnection()) return false
        val list = _playlist.value
        if (index !in list.indices) return false
        _currentIndex.value = index
        val item = list[index]
        val ctx = context ?: appContext ?: return false
        load(ctx, item.id, item.name, lastBaseUrl, item.kind)
        return true
    }

    /** Switches to the next slide, video, or music track. */
    fun next(context: Context? = appContext): Boolean {
        val c = client
        val direct = directSession
        if (c == null && direct == null) return false
        val list = _playlist.value
        val nextIdx = _currentIndex.value + 1
        if (list.isNotEmpty() && nextIdx < list.size) {
            _currentIndex.value = nextIdx
            val item = list[nextIdx]
            val ctx = context ?: appContext ?: return false
            load(ctx, item.id, item.name, lastBaseUrl, item.kind)
            return true
        }
        c?.queueNext(null)
        if (direct != null) background.launch { runCatching { direct.queueNext() } }
        return true
    }

    /** Switches to the previous slide, video, or music track. */
    fun prev(context: Context? = appContext): Boolean {
        val c = client
        val direct = directSession
        if (c == null && direct == null) return false
        val list = _playlist.value
        val prevIdx = _currentIndex.value - 1
        if (list.isNotEmpty() && prevIdx >= 0) {
            _currentIndex.value = prevIdx
            val item = list[prevIdx]
            val ctx = context ?: appContext ?: return false
            load(ctx, item.id, item.name, lastBaseUrl, item.kind)
            return true
        }
        c?.queuePrev(null)
        if (direct != null) background.launch { runCatching { direct.queuePrev() } }
        return true
    }

    /** Remembers where the Chromecast is in what it plays, so another screen (or this one later) can carry on from there. */
    private val progress = RemoteMediaClient.ProgressListener { progressMs, durationMs ->
        val c = client ?: return@ProgressListener
        val state = c.playerState
        // Not while loading: the position is still 0 then, and would wipe the point being resumed from.
        if (durationMs <= 0 || (state != MediaStatus.PLAYER_STATE_PLAYING && state != MediaStatus.PLAYER_STATE_PAUSED)) return@ProgressListener
        val posSec = (progressMs / 1000L).toInt().coerceAtLeast(0)
        val durSec = (durationMs / 1000L).toInt().coerceAtLeast(0)
        _state.value = _state.value.copy(positionSec = posSec, durationSec = durSec)
        val id = c.mediaInfo?.contentId?.let { VIDEO_ID.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: return@ProgressListener
        ResumeStore.record(id, posSec, durSec)
    }

    private val VIDEO_ID = Regex("/video/(\\d+)")
    private const val SUBTITLE_TRACK = 1L

    // --- Chromecasts used before, and the ones in range now ---------------------------------------------------------

    private fun loadRecent(context: Context): List<Recent> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECENT, "").orEmpty()
            .lines().mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank()) Recent(parts[0], parts[1]) else null
            }

    /** Puts [id] first in the list of Chromecasts used before (and keeps the list short). */
    private fun rememberDevice(id: String, name: String) {
        val context = appContext ?: return
        val updated = (listOf(Recent(id, name.replace('\t', ' ').replace('\n', ' '))) + _recent.value.filter { it.id != id }).take(RECENT_MAX)
        _recent.value = updated
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECENT, updated.joinToString("\n") { it.id + "\t" + it.name }).apply()
    }

    /** Forgets a Chromecast from the list of ones used before. */
    fun forget(id: String) {
        val context = appContext ?: return
        val updated = _recent.value.filter { it.id != id }
        _recent.value = updated
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECENT, updated.joinToString("\n") { it.id + "\t" + it.name }).apply()
    }

    private val discoveryCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshNearby()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshNearby()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshNearby()
    }

    private fun loadDirectHosts(context: Context): MutableMap<String, String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DIRECT_HOSTS, "").orEmpty()
            .lines().mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0] to parts[1] else null
            }.toMap().toMutableMap()

    private fun saveDirectHost(id: String, host: String) {
        val context = appContext ?: return
        directHosts[id] = host
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_DIRECT_HOSTS, directHosts.entries.joinToString("\n") { it.key + "\t" + it.value }).apply()
    }

    /** Starts looking for Chromecasts on this Wi-Fi (the list [nearby] follows). Call [stopDiscovery] when the card goes away. */
    fun startDiscovery(context: Context) {
        init(context)
        if (_state.value.device == null) _state.value = _state.value.copy(error = null) // an old "isn't answering" doesn't stay
        val selector = castContext?.mergedSelector
        if (selector != null) {
            val r = MediaRouter.getInstance(context.applicationContext)
            router = r
            r.addCallback(selector, discoveryCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        }
        refreshNearby()
        if (NetworkUtils.isHotspot(context.applicationContext) || !hasGmsRoutes()) {
            scanDirect(context)
        }
    }

    fun stopDiscovery() {
        router?.removeCallback(discoveryCallback)
    }

    fun hasGmsRoutes(): Boolean {
        val r = router ?: return false
        val selector = castContext?.mergedSelector ?: return false
        return r.routes.any { it.matchesSelector(selector) }
    }

    /** Scans the local subnet directly for Chromecasts (needed on a phone's hotspot, where Google's scanner sees nothing). */
    fun scanDirect(context: Context) {
        if (_scanningDirect.value) return
        _scanningDirect.value = true
        background.launch {
            try {
                val found = DirectCastDiscovery.scan(context.applicationContext)
                _directDevices.value = found
                found.forEach { saveDirectHost(it.id, it.host) }
                withContext(Dispatchers.Main) { refreshNearby() }
            } finally {
                _scanningDirect.value = false
            }
        }
    }

    private fun idOf(route: MediaRouter.RouteInfo): String? = route.extras?.let { CastDevice.getFromBundle(it)?.deviceId }

    private fun refreshNearby() {
        val r = router
        val selector = castContext?.mergedSelector
        val gmsIds = if (r != null && selector != null) {
            r.routes.filter { it.matchesSelector(selector) }.mapNotNull(::idOf).toSet()
        } else emptySet()
        val directIds = _directDevices.value.map { it.id }.toSet()
        _nearby.value = gmsIds + directIds
    }

    /** Connects to a Chromecast used before. False when it isn't answering on this Wi-Fi right now. */
    fun connectTo(device: Recent): Boolean {
        val r = router
        val selector = castContext?.mergedSelector
        val route = r?.routes?.firstOrNull { (selector == null || it.matchesSelector(selector)) && idOf(it) == device.id }
        if (r != null && route != null) {
            _state.value = _state.value.copy(error = null)
            r.selectRoute(route)
            return true
        }
        val directHost = _directDevices.value.firstOrNull { it.id == device.id }?.host ?: directHosts[device.id]
        val ctx = appContext
        if (directHost != null && ctx != null) {
            connectDirect(ctx, DirectCastDevice(device.id, device.name, directHost))
            return true
        }
        _state.value = _state.value.copy(error = "${device.name} isn't answering. Check that it is on, and on the same Wi-Fi.")
        return false
    }

    /** Connects directly over Cast v2 TLS (port 8009), bypassing Google Play services' Wi-Fi-only discovery. */
    fun connectDirect(context: Context, device: DirectCastDevice) {
        init(context)
        castContext?.sessionManager?.endCurrentSession(true)
        directSession?.let { old ->
            directSession = null
            background.launch { runCatching { old.stopAndClose() } }
        }
        saveDirectHost(device.id, device.host)
        _state.value = State(device = device.name, deviceId = device.id)
        val appId = DirectCastSession.receiverAppId(context.applicationContext)
        lateinit var session: DirectCastSession
        session = DirectCastSession(
            device = device,
            appId = appId,
            onStatus = { status ->
                if (directSession !== session) return@DirectCastSession
                _state.value = _state.value.copy(
                    title = status.title ?: _state.value.title,
                    kind = _currentKind.value,
                    currentIndex = _currentIndex.value,
                    totalCount = _playlist.value.size,
                    muted = status.muted,
                    playing = status.playing,
                    positionSec = status.positionSec,
                    durationSec = if (status.durationSec > 0) status.durationSec else _state.value.durationSec,
                    error = status.error,
                )
                if (status.recordResume) {
                    val id = status.contentId?.let { VIDEO_ID.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                    if (id != null) ResumeStore.record(id, status.positionSec, status.durationSec)
                }
            },
            onClosed = { err ->
                if (directSession === session) {
                    directSession = null
                    _state.value = if (err != null) State(error = err) else State()
                }
            },
        )
        directSession = session
        background.launch {
            if (session.connect()) {
                rememberDevice(device.id, device.name)
                val pending = pendingReconnect
                if (pending != null && pending.deviceId == device.id && directSession === session) {
                    pendingReconnect = null
                    restorePlayback(context.applicationContext, pending)
                }
            } else if (pendingReconnect?.deviceId == device.id) {
                pendingReconnect = null
            }
        }
    }

    /**
     * Restores the previous Chromecast connection (for example after the TV's Home button exited the Cast app)
     * and resumes playback where it left off with the previously selected items and setup.
     */
    fun reconnect(context: Context) {
        init(context)
        val current = _state.value
        val deviceId = current.deviceId ?: return
        val deviceName = current.device ?: "Chromecast"
        val items = _playlist.value
        val index = _currentIndex.value.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val baseUrl = lastBaseUrl.ifBlank {
            (MediaServerService.state.value as? ServerState.Running)?.baseUrl.orEmpty()
        }
        val currentItem = items.getOrNull(index)
        val resumeSec = maxOf(
            current.positionSec,
            currentItem?.let { if (it.kind == "v") ResumeStore.get(it.id)?.positionSec else null } ?: 0,
        )
        val pending = PendingReconnect(
            deviceId = deviceId,
            deviceName = deviceName,
            items = items,
            index = index,
            positionSec = resumeSec,
            baseUrl = baseUrl,
            muted = current.muted,
        )
        pendingReconnect = pending

        val currentDirect = directSession
        if (currentDirect != null) {
            connectDirect(context, currentDirect.device)
            return
        }

        val sm = castContext?.sessionManager
        if (sm?.currentCastSession != null) {
            // Ending the stale GMS CastSession triggers onSessionEnded, which re-selects the route.
            sm.endCurrentSession(true)
            return
        }

        connectPendingRoute(pending)
    }

    private fun connectPendingRoute(pending: PendingReconnect) {
        val ctx = appContext
        val r = router ?: ctx?.let { MediaRouter.getInstance(it).also { mr -> router = mr } }
        val selector = castContext?.mergedSelector
        val route = r?.routes?.firstOrNull { (selector == null || it.matchesSelector(selector)) && idOf(it) == pending.deviceId }
        if (r != null && route != null) {
            _state.value = _state.value.copy(device = pending.deviceName, deviceId = pending.deviceId, error = null)
            r.selectRoute(route)
            return
        }
        val directHost = _directDevices.value.firstOrNull { it.id == pending.deviceId }?.host ?: directHosts[pending.deviceId]
        if (directHost != null && ctx != null) {
            connectDirect(ctx, DirectCastDevice(pending.deviceId, pending.deviceName, directHost))
            return
        }
        pendingReconnect = null
        _state.value = State(error = "${pending.deviceName} isn't answering. Check that it is on, and on the same Wi-Fi.")
    }

    private fun restorePlayback(context: Context, pending: PendingReconnect) {
        if (pending.muted) {
            directSession?.let { s ->
                background.launch { runCatching { s.toggleMute() } }
            } ?: runCatching { castContext?.sessionManager?.currentCastSession?.isMute = true }
        }
        if (pending.items.isEmpty() || pending.baseUrl.isBlank()) return
        _playlist.value = pending.items
        val idx = pending.index.coerceIn(0, pending.items.size - 1)
        _currentIndex.value = idx
        val current = pending.items[idx]
        _currentKind.value = current.kind
        lastBaseUrl = pending.baseUrl
        if (pending.items.size == 1) {
            load(context, current.id, current.name, pending.baseUrl, current.kind, startPositionSec = pending.positionSec)
        } else {
            castPlaylistInternal(context, pending.items, idx, pending.baseUrl, startPositionSec = pending.positionSec)
        }
    }

    private val _chooserRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Something asked for Google's Chromecast chooser to be opened; the Chromecast card (which owns its button) opens it. */
    val chooserRequests: SharedFlow<Unit> = _chooserRequests.asSharedFlow()

    fun requestChooser() {
        _chooserRequests.tryEmit(Unit)
    }

    fun hasLoadedMedia(): Boolean {
        directSession?.let { return it.hasActiveMedia() }
        val ps = client?.mediaStatus?.playerState ?: return false
        return ps == MediaStatus.PLAYER_STATE_PLAYING ||
            ps == MediaStatus.PLAYER_STATE_PAUSED ||
            ps == MediaStatus.PLAYER_STATE_BUFFERING ||
            ps == MediaStatus.PLAYER_STATE_LOADING
    }

    fun togglePlayback() {
        if (!hasLoadedMedia() && _playlist.value.isNotEmpty() && lastBaseUrl.isNotBlank()) {
            playAt(_currentIndex.value)
            return
        }
        directSession?.let { s ->
            background.launch { runCatching { s.togglePlayback() } }
            return
        }
        client?.togglePlayback()
    }

    /** Seeks to an absolute [positionMs] milliseconds on the active Chromecast stream. */
    fun seekTo(positionMs: Long) {
        val clampedMs = positionMs.coerceAtLeast(0L)
        _state.value = _state.value.copy(positionSec = (clampedMs / 1000L).toInt())
        directSession?.let { s ->
            background.launch { runCatching { s.seek(clampedMs / 1000.0) } }
            return
        }
        val c = client ?: return
        c.seek(com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition(clampedMs).build())
    }

    /** Seeks forward or backward by [deltaMs] milliseconds on the active Chromecast stream. */
    fun seekBy(deltaMs: Long) {
        directSession?.let { s ->
            background.launch { runCatching { s.seek((s.approximatePositionSec() + deltaMs / 1000.0).coerceAtLeast(0.0)) } }
            return
        }
        val c = client ?: return
        val target = (c.approximateStreamPosition + deltaMs).coerceAtLeast(0L)
        c.seek(com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition(target).build())
    }

    /** Adjusts the Chromecast receiver volume by [delta] (clamped to 0.0..1.0). */
    fun adjustVolume(delta: Double) {
        directSession?.let { s ->
            background.launch { runCatching { s.adjustVolume(delta) } }
            return
        }
        val session = castContext?.sessionManager?.currentCastSession ?: return
        runCatching { session.volume = (session.volume + delta).coerceIn(0.0, 1.0) }
    }

    /** Toggles mute on the active Chromecast session. */
    fun toggleMute() {
        directSession?.let { s ->
            val next = !_state.value.muted
            _state.value = _state.value.copy(muted = next)
            background.launch { runCatching { _state.value = _state.value.copy(muted = s.toggleMute()) } }
            return
        }
        val session = castContext?.sessionManager?.currentCastSession ?: return
        val next = !session.isMute
        runCatching { session.isMute = next }
        _state.value = _state.value.copy(muted = next)
    }

    /** Stops the video and disconnects from the Chromecast. */
    fun stop() {
        pendingReconnect = null
        _playlist.value = emptyList()
        _currentIndex.value = 0
        lastBaseUrl = ""
        directSession?.let { s ->
            directSession = null
            background.launch { runCatching { s.stopAndClose() } }
            _state.value = State()
            return
        }
        castContext?.sessionManager?.endCurrentSession(true)
    }
}
