package mano.algo.caster.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** One connected screen and the video it is playing, as seen from the requests it makes. */
data class NowWatching(
    val deviceId: String,
    val deviceLabel: String,
    val videoId: Long,
    val videoName: String,
    val lastSeen: Long,
)

/**
 * Remembers which paired screen is playing which video. A player asks for the file in many small ranges,
 * so [touch] sits on the hot path: it updates a concurrent map and only publishes to the UI when the video
 * changes or every few seconds, never on every chunk.
 */
object ActivityTracker {
    /** A screen counts as watching until it has been silent this long (a paused video stops asking for data). */
    const val ACTIVE_WINDOW_MS = 120_000L
    private const val PUBLISH_EVERY_MS = 3_000L

    private val entries = ConcurrentHashMap<String, NowWatching>()
    private val _watching = MutableStateFlow<List<NowWatching>>(emptyList())

    val watching: StateFlow<List<NowWatching>> = _watching.asStateFlow()

    @Volatile private var lastPublish = 0L

    /** [videoName] is only evaluated when the screen starts a different video, so it may do a query. */
    fun touch(deviceId: String, deviceLabel: String, videoId: Long, videoName: () -> String) {
        val now = System.currentTimeMillis()
        val previous = entries[deviceId]
        val changed = previous == null || previous.videoId != videoId
        val name = if (changed) videoName() else previous!!.videoName
        entries[deviceId] = if (changed) NowWatching(deviceId, deviceLabel, videoId, name, now) else previous!!.copy(lastSeen = now)
        if (changed) WatchLog.record(deviceLabel, name)
        if (changed || now - lastPublish >= PUBLISH_EVERY_MS) publish(now)
    }

    fun forget(deviceId: String) {
        entries.remove(deviceId)
        publish(System.currentTimeMillis())
    }

    fun clear() {
        entries.clear()
        publish(System.currentTimeMillis())
    }

    private fun publish(now: Long) {
        lastPublish = now
        _watching.value = entries.values.sortedByDescending { it.lastSeen }
    }
}
