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
    /** The page's volume, 0 to 100 (-1 when the page doesn't say). */
    val volume: Int = -1,
    /** Media kind reported by the page: "v" (video), "a" (music/audio), or "i" (image). */
    val kind: String = "v",
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
    private val waiters = ConcurrentHashMap<String, Any>()
    private val _all = MutableStateFlow<List<RemoteState>>(emptyList())

    val all: StateFlow<List<RemoteState>> = _all.asStateFlow()

    fun report(state: RemoteState) {
        states[state.deviceId] = state
        publish()
    }

    fun idle(deviceId: String) {
        states.remove(deviceId)
        queues.remove(deviceId)
        waiters[deviceId]?.let { synchronized(it) { (it as Object).notifyAll() } }
        publish()
    }

    fun send(deviceId: String, command: String) {
        queues.getOrPut(deviceId) { ConcurrentLinkedQueue() }.apply {
            if (size < MAX_QUEUED) add(command)
        }
        waiters[deviceId]?.let { synchronized(it) { (it as Object).notifyAll() } }
    }

    /** Returns and clears the commands waiting for [deviceId]. */
    fun drain(deviceId: String): List<String> {
        val queue = queues[deviceId] ?: return emptyList()
        return generateSequence { queue.poll() }.toList()
    }

    /** Wakes anything waiting on [deviceId]'s commands, so a stream that was replaced can notice and end. */
    fun wake(deviceId: String) {
        waiters[deviceId]?.let { synchronized(it) { (it as Object).notifyAll() } }
    }

    /**
     * Returns waiting commands immediately, or waits up to [waitMs] for a new command. [wanted] is checked before
     * anything is taken off the queue, so a caller that has been replaced never swallows a command meant for its successor.
     */
    fun drainOrWait(deviceId: String, waitMs: Long, wanted: () -> Boolean = { true }): List<String> {
        if (!wanted()) return emptyList()
        drain(deviceId).takeIf { it.isNotEmpty() }?.let { return it }
        if (waitMs <= 0L) return emptyList()
        val waiter = waiters.getOrPut(deviceId) { Any() }
        synchronized(waiter) {
            if (!wanted()) return emptyList()
            drain(deviceId).takeIf { it.isNotEmpty() }?.let { return it }
            runCatching { (waiter as Object).wait(waitMs) }
            if (!wanted()) return emptyList()
        }
        return drain(deviceId)
    }

    fun clear() {
        states.clear()
        queues.clear()
        waiters.values.forEach { synchronized(it) { (it as Object).notifyAll() } }
        waiters.clear()
        publish()
    }

    private fun publish() {
        _all.value = states.values.sortedByDescending { it.reportedAt }
    }

    private const val MAX_QUEUED = 20
}
