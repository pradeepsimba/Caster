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
        /** 0..1 while a video is being re-encoded to fill the screen (see [Picture]); null when nothing is. */
        val preparing: Float? = null,
        val preparingName: String? = null,
    )

    /** A Chromecast that was connected before. */
    data class Recent(val id: String, val name: String)

    private const val TOKEN_LABEL = "Chromecast (this phone)"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private const val PREFS = "caster_cast"
    private const val KEY_RECENT = "recent"
    private const val RECENT_MAX = 6

    private const val KEY_PICTURE = "picture"

    private val _picture = MutableStateFlow(Picture.Fit)

    /** How the next video is laid out on the TV (and the current one, when it is changed while one is playing). */
    val picture: StateFlow<Picture> = _picture.asStateFlow()

    /** The video that was cast last, to start it again when the picture setting changes. */
    private data class LastCast(val id: Long, val name: String, val baseUrl: String)
    private var last: LastCast? = null

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
            _picture.value = runCatching {
                Picture.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PICTURE, null).orEmpty())
            }.getOrDefault(Picture.Fit)
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
        client = session.remoteMediaClient?.also { it.registerCallback(mediaCallback) }
        val device = session.castDevice
        if (device != null) rememberDevice(device.deviceId, device.friendlyName ?: "Chromecast")
        _state.value = State(device = device?.friendlyName ?: "Chromecast", deviceId = device?.deviceId)
        refresh()
    }

    private fun detach() {
        client?.unregisterCallback(mediaCallback)
        client = null
        _state.value = State()
    }

    private fun refresh() {
        val c = client ?: return
        val status = c.mediaStatus
        val failed = status?.playerState == MediaStatus.PLAYER_STATE_IDLE &&
            status.idleReason == MediaStatus.IDLE_REASON_ERROR
        _state.value = _state.value.copy(
            title = c.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE),
            // Buffering and loading count as playing, so the button reads "Pause" rather than "Play" while it starts.
            playing = status?.playerState.let {
                it == MediaStatus.PLAYER_STATE_PLAYING || it == MediaStatus.PLAYER_STATE_BUFFERING || it == MediaStatus.PLAYER_STATE_LOADING
            },
            error = if (failed) "The Chromecast couldn't play this video. Its format may not be supported." else null,
        )
    }

    /**
     * Starts [name] on the connected Chromecast. [baseUrl] is the media server's address (an IP, because a
     * Chromecast can't resolve `.local` names). Returns false when no Chromecast is connected.
     */
    fun cast(context: Context, id: Long, name: String, baseUrl: String): Boolean {
        val c = client
        if (c == null) {
            // The Chromecast went away (or never connected): say so instead of doing nothing.
            _state.value = _state.value.copy(error = "Not connected to a Chromecast. Choose one first.")
            return false
        }
        last = LastCast(id, name, baseUrl)
        val picture = _picture.value
        if (picture == Picture.Fit) {
            load(context, id, name, baseUrl, "video/$id", 0L)
        } else {
            prepare(context, id, name, baseUrl, picture, 0L)
        }
        return true
    }

    /** Re-encodes the video to fill the screen (it takes a while), then plays the copy. */
    private fun prepare(context: Context, id: Long, name: String, baseUrl: String, picture: Picture, startMs: Long) {
        _state.value = _state.value.copy(preparing = 0f, preparingName = name, error = null)
        FillConverter.start(
            context, id, picture,
            onProgress = { progress -> _state.value = _state.value.copy(preparing = progress) },
            onDone = {
                _state.value = _state.value.copy(preparing = null, preparingName = null)
                load(context, id, name, baseUrl, "fill/$id-${picture.name.lowercase()}", startMs)
            },
            onError = { message -> _state.value = _state.value.copy(preparing = null, preparingName = null, error = message) },
        )
    }

    /** Stops preparing a video (the one asked for isn't played). */
    fun cancelPreparing() {
        FillConverter.cancel()
        _state.value = _state.value.copy(preparing = null, preparingName = null)
    }

    /** Chooses how videos fill the TV. When one is playing it starts again, from where it was, in the new layout. */
    fun setPicture(context: Context, picture: Picture) {
        if (_picture.value == picture) return
        _picture.value = picture
        (appContext ?: context.applicationContext).getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PICTURE, picture.name).apply()
        val now = last ?: return
        val c = client ?: return
        if (c.mediaInfo == null && _state.value.preparing == null) return
        val position = c.approximateStreamPosition
        if (picture == Picture.Fit) {
            FillConverter.cancel()
            _state.value = _state.value.copy(preparing = null, preparingName = null)
            load(context, now.id, now.name, now.baseUrl, "video/${now.id}", position)
        } else {
            prepare(context, now.id, now.name, now.baseUrl, picture, position)
        }
    }

    /** Hands the Chromecast the address of [path] (the original video, or its converted copy) and starts it at [startMs]. */
    private fun load(context: Context, id: Long, name: String, baseUrl: String, path: String, startMs: Long) {
        val c = client ?: return
        val token = PairingManager.issueLocal(TOKEN_LABEL)
        val query = "?t=" + Uri.encode(token) + "&k=v"
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "video/mp4"
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, name)
            addImage(WebImage(Uri.parse(baseUrl + "api/thumb/" + id + query)))
        }
        val info = MediaInfo.Builder(baseUrl + path + query)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setMetadata(metadata)
            .build()
        c.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).setCurrentTime(startMs).build())
        _state.value = _state.value.copy(title = name, error = null)
    }

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

    fun togglePlayback() {
        client?.togglePlayback()
    }

    /** Stops the video and disconnects from the Chromecast. */
    fun stop() {
        FillConverter.cancel()
        _state.value = _state.value.copy(preparing = null, preparingName = null)
        castContext?.sessionManager?.endCurrentSession(true)
    }
}
