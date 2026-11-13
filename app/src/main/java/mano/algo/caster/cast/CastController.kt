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
    )

    private const val TOKEN_LABEL = "Chromecast (this phone)"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

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
        _state.value = State(device = session.castDevice?.friendlyName ?: "Chromecast")
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
        val token = PairingManager.issueLocal(TOKEN_LABEL)
        val query = "?t=" + Uri.encode(token) + "&k=v"
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "video/mp4"
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, name)
            addImage(WebImage(Uri.parse(baseUrl + "api/thumb/" + id + query)))
        }
        val info = MediaInfo.Builder(baseUrl + "video/" + id + query)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setMetadata(metadata)
            .build()
        c.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
        _state.value = _state.value.copy(title = name, error = null)
        return true
    }

    fun togglePlayback() {
        client?.togglePlayback()
    }

    /** Stops the video and disconnects from the Chromecast. */
    fun stop() {
        castContext?.sessionManager?.endCurrentSession(true)
    }
}
