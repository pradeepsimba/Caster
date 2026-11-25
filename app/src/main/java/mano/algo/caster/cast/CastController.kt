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
    private const val RECENT_MAX = 6

    private val _recent = MutableStateFlow<List<Recent>>(emptyList())

    /** Chromecasts connected before, the newest first. */
    val recent: StateFlow<List<Recent>> = _recent.asStateFlow()

    private val _nearby = MutableStateFlow<Set<String>>(emptySet())

    /** The ids of the Chromecasts answering on this Wi-Fi right now (looked for while the Chromecast card is on screen). */
    val nearby: StateFlow<Set<String>> = _nearby.asStateFlow()

    private var appContext: Context? = null
    private var router: MediaRouter? = null

    private var castContext: CastContext? = null
    private var client: RemoteMediaClient? = null

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = refresh()
        override fun onMetadataUpdated() = refresh()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
        override fun onSessionEnded(session: CastSession, error: Int) = detach()
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _state.value = _state.value.copy(error = "Couldn't connect to the Chromecast. Try again.")
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) = detach()
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    /** Safe to call often. Without Google Play services the Cast library can't start, so Chromecast is reported unavailable. */
    fun init(context: Context) {
        if (castContext != null) return
        if (appContext == null) {
            appContext = context.applicationContext
            _recent.value = loadRecent(context.applicationContext)
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
        client?.unregisterCallback(mediaCallback)
        client?.removeProgressListener(progress)
        client = session.remoteMediaClient?.also {
            it.registerCallback(mediaCallback)
            it.addProgressListener(progress, 5_000L)
        }
        val device = session.castDevice
        if (device != null) rememberDevice(device.deviceId, device.friendlyName ?: "Chromecast")
        _state.value = State(device = device?.friendlyName ?: "Chromecast", deviceId = device?.deviceId)
        refresh()
    }

    private fun detach() {
        client?.removeProgressListener(progress)
        client?.unregisterCallback(mediaCallback)
        client = null
        _state.value = State()
    }

    private fun refresh() {
        val c = client ?: return
        val status = c.mediaStatus
        val failed = status?.playerState == MediaStatus.PLAYER_STATE_IDLE &&
            status.idleReason == MediaStatus.IDLE_REASON_ERROR
        val title = c.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE)
        _state.value = _state.value.copy(
            title = title ?: _state.value.title,
            kind = _currentKind.value,
            currentIndex = _currentIndex.value,
            totalCount = _playlist.value.size,
            // Buffering and loading count as playing, so the button reads "Pause" rather than "Play" while it starts.
            playing = status?.playerState.let {
                it == MediaStatus.PLAYER_STATE_PLAYING || it == MediaStatus.PLAYER_STATE_BUFFERING || it == MediaStatus.PLAYER_STATE_LOADING
            },
            error = if (failed) "The Chromecast couldn't play this media. Its format may not be supported." else null,
        )
    }

    /**
     * Starts [name] on the connected Chromecast. [baseUrl] is the media server's address (an IP, because a
     * Chromecast can't resolve `.local` names). Returns false when no Chromecast is connected.
     */
    fun cast(context: Context, id: Long, name: String, baseUrl: String, kind: String = "v"): Boolean {
        val c = client
        if (c == null) {
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

    /** Everything the Chromecast needs to fetch one item: the address (with the pass), a picture, and subtitles when there are any. */
    private fun mediaInfo(context: Context, id: Long, name: String, baseUrl: String, token: String, kind: String = "v"): MediaInfo {
        val query = "?t=" + Uri.encode(token) + "&k=" + Uri.encode(kind)
        return when (kind) {
            "i" -> {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "image/jpeg"
                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO).apply {
                    putString(MediaMetadata.KEY_TITLE, name)
                    addImage(WebImage(Uri.parse(baseUrl + "api/thumb/" + id + query)))
                }
                MediaInfo.Builder(baseUrl + "video/" + id + query)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(mime)
                    .setMetadata(metadata)
                    .build()
            }
            "a" -> {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "audio/mpeg"
                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
                    putString(MediaMetadata.KEY_TITLE, name)
                    addImage(WebImage(Uri.parse(baseUrl + "api/thumb/" + id + query)))
                }
                MediaInfo.Builder(baseUrl + "video/" + id + query)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(mime)
                    .setMetadata(metadata)
                    .build()
            }
            else -> {
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "video/mp4"
                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                    putString(MediaMetadata.KEY_TITLE, name)
                    addImage(WebImage(Uri.parse(baseUrl + "api/thumb/" + id + query)))
                }
                val builder = MediaInfo.Builder(baseUrl + "video/" + id + query)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(mime)
                    .setMetadata(metadata)
                // A .srt or .vtt next to the video: the phone serves it as WebVTT, which the Chromecast shows.
                if (runCatching { SubtitleFinder.find(context, uri) }.getOrNull() != null) {
                    builder.setMediaTracks(
                        listOf(
                            MediaTrack.Builder(SUBTITLE_TRACK, MediaTrack.TYPE_TEXT)
                                .setContentId(baseUrl + "api/sub/" + id + query)
                                .setContentType("text/vtt")
                                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                                .setName("Subtitles")
                                .setLanguage("en")
                                .build(),
                        ),
                    )
                }
                builder.build()
            }
        }
    }

    /** Hands the Chromecast the address of the selected media and starts it (where it was stopped, when that is known). */
    private fun load(context: Context, id: Long, name: String, baseUrl: String, kind: String = "v") {
        if (client == null) return
        val token = PairingManager.issueLocal(TOKEN_LABEL)
        _state.value = _state.value.copy(
            title = name,
            kind = kind,
            error = null,
            currentIndex = _currentIndex.value,
            totalCount = _playlist.value.size.coerceAtLeast(1),
        )
        background.launch {
            val info = mediaInfo(context, id, name, baseUrl, token, kind)
            val request = MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
            if (kind == "v") {
                ResumeStore.get(id)?.let { request.setCurrentTime(it.positionSec * 1000L) }
                if (!info.mediaTracks.isNullOrEmpty()) request.setActiveTrackIds(longArrayOf(SUBTITLE_TRACK))
            }
            withContext(Dispatchers.Main) { client?.load(request.build()) }
        }
    }

    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** At most this many items go to the Chromecast's queue at once (a long list is slow to hand over). */
    const val QUEUE_MAX = 20

    /** Plays [items] one after the other on the connected Chromecast. False when none is connected. */
    fun castPlaylist(context: Context, items: List<CastItem>, startIndex: Int = 0, baseUrl: String): Boolean {
        val c = client
        if (c == null) {
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
            val queueItems = clamped.map { item ->
                MediaQueueItem.Builder(mediaInfo(context, item.id, item.name, baseUrl, token, item.kind))
                    .setAutoplay(true)
                    .setPreloadTime(20.0)
                    .apply { if (item.kind == "v") ResumeStore.get(item.id)?.let { setStartTime(it.positionSec.toDouble()) } }
                    .build()
            }
            withContext(Dispatchers.Main) {
                client?.queueLoad(queueItems.toTypedArray(), start, MediaStatus.REPEAT_MODE_REPEAT_OFF, null)
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

    /** Switches to the next slide, video, or music track. */
    fun next(context: Context? = appContext): Boolean {
        val c = client ?: return false
        val list = _playlist.value
        val nextIdx = _currentIndex.value + 1
        if (list.isNotEmpty() && nextIdx < list.size) {
            _currentIndex.value = nextIdx
            val item = list[nextIdx]
            val ctx = context ?: appContext ?: return false
            load(ctx, item.id, item.name, lastBaseUrl, item.kind)
            return true
        }
        c.queueNext(null)
        return true
    }

    /** Switches to the previous slide, video, or music track. */
    fun prev(context: Context? = appContext): Boolean {
        val c = client ?: return false
        val list = _playlist.value
        val prevIdx = _currentIndex.value - 1
        if (list.isNotEmpty() && prevIdx >= 0) {
            _currentIndex.value = prevIdx
            val item = list[prevIdx]
            val ctx = context ?: appContext ?: return false
            load(ctx, item.id, item.name, lastBaseUrl, item.kind)
            return true
        }
        c.queuePrev(null)
        return true
    }

    /** Remembers where the Chromecast is in what it plays, so another screen (or this one later) can carry on from there. */
    private val progress = RemoteMediaClient.ProgressListener { progressMs, durationMs ->
        val c = client ?: return@ProgressListener
        val state = c.playerState
        // Not while loading: the position is still 0 then, and would wipe the point being resumed from.
        if (durationMs <= 0 || (state != MediaStatus.PLAYER_STATE_PLAYING && state != MediaStatus.PLAYER_STATE_PAUSED)) return@ProgressListener
        val id = c.mediaInfo?.contentId?.let { VIDEO_ID.find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: return@ProgressListener
        ResumeStore.record(id, (progressMs / 1000).toInt(), (durationMs / 1000).toInt())
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

    /** Starts looking for Chromecasts on this Wi-Fi (the list [nearby] follows). Call [stopDiscovery] when the card goes away. */
    fun startDiscovery(context: Context) {
        init(context)
        if (_state.value.device == null) _state.value = _state.value.copy(error = null) // an old "isn't answering" doesn't stay
        val selector = castContext?.mergedSelector ?: return
        val r = MediaRouter.getInstance(context.applicationContext)
        router = r
        r.addCallback(selector, discoveryCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refreshNearby()
    }

    fun stopDiscovery() {
        router?.removeCallback(discoveryCallback)
    }

    private fun idOf(route: MediaRouter.RouteInfo): String? = route.extras?.let { CastDevice.getFromBundle(it)?.deviceId }

    private fun refreshNearby() {
        val r = router ?: return
        val selector = castContext?.mergedSelector ?: return
        _nearby.value = r.routes.filter { it.matchesSelector(selector) }.mapNotNull(::idOf).toSet()
    }

    /** Connects to a Chromecast used before. False when it isn't answering on this Wi-Fi right now. */
    fun connectTo(device: Recent): Boolean {
        val r = router
        val selector = castContext?.mergedSelector
        val route = r?.routes?.firstOrNull { (selector == null || it.matchesSelector(selector)) && idOf(it) == device.id }
        if (r == null || route == null) {
            _state.value = _state.value.copy(error = "${device.name} isn't answering. Check that it is on, and on the same Wi-Fi.")
            return false
        }
        _state.value = _state.value.copy(error = null)
        r.selectRoute(route)
        return true
    }

    private val _chooserRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Something asked for Google's Chromecast chooser to be opened; the Chromecast card (which owns its button) opens it. */
    val chooserRequests: SharedFlow<Unit> = _chooserRequests.asSharedFlow()

    fun requestChooser() {
        _chooserRequests.tryEmit(Unit)
    }

    fun togglePlayback() {
        client?.togglePlayback()
    }

    /** Stops the video and disconnects from the Chromecast. */
    fun stop() {
        castContext?.sessionManager?.endCurrentSession(true)
    }
}
