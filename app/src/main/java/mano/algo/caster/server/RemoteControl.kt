package mano.algo.caster.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** What a connected screen says it is playing right now (reported by its page about once a second). */
data class RemoteState(
    val deviceId: String,
    val deviceLabel: String,
    val title: String,
    val playing: Boolean,
    val positionSec: Int,
    val durationSec: Int,
    val reportedAt: Long,
)

/**
 * Lets the phone act as a remote. Pages report their state with [report] and pick up queued commands
 * ("toggle", "seek:-10", "next", "stop"...) in the same request, so no extra connection is needed.
 */
object RemoteControl {
    /** A page that hasn't reported for this long is treated as gone. */
    const val FRESH_MS = 6_000L

    private val states = ConcurrentHashMap<String, RemoteState>()
    private val queues = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    private val _all = MutableStateFlow<List<RemoteState>>(emptyList())

    val all: StateFlow<List<RemoteState>> = _all.asStateFlow()

    fun report(state: RemoteState) {
        states[state.deviceId] = state
        publish()
    }

    fun idle(deviceId: String) {
        states.remove(deviceId)
        queues.remove(deviceId)
        publish()
    }

    fun send(deviceId: String, command: String) {
        queues.getOrPut(deviceId) { ConcurrentLinkedQueue() }.apply {
            if (size < MAX_QUEUED) add(command)
        }
    }

    /** Returns and clears the commands waiting for [deviceId]. */
    fun drain(deviceId: String): List<String> {
        val queue = queues[deviceId] ?: return emptyList()
        return generateSequence { queue.poll() }.toList()
    }

    fun clear() {
        states.clear()
        queues.clear()
        publish()
    }

    private fun publish() {
        _all.value = states.values.sortedByDescending { it.reportedAt }
    }

    private const val MAX_QUEUED = 20
}
