package mano.algo.caster.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** A browser asking for access. [code] is shown on both the browser and the phone so the owner can match them. */
data class PairRequest(val id: String, val code: String, val label: String, val expiresAt: Long)

/** A browser the owner has approved. [id] is a short public handle, never the secret token. */
data class PairedDevice(val id: String, val label: String, val pairedAt: Long, val lastSeen: Long)

sealed interface RequestResult {
    data class Created(val request: PairRequest, val ttlMs: Long) : RequestResult
    data object Busy : RequestResult
}

sealed interface PairStatus {
    data object Pending : PairStatus
    data class Approved(val token: String) : PairStatus
    data object Denied : PairStatus
    data object Expired : PairStatus
    data object Unknown : PairStatus
}

/**
 * Gate in front of the phone's gallery. A browser asks to connect, the phone owner approves it after
 * checking that both screens show the same code, and only then does the browser receive a token.
 *
 * Only one request can wait at a time and a refused or unanswered one starts a short cooldown, so a
 * device on the network can't flood the phone with prompts. Requests expire after [REQUEST_TTL_MS].
 * Approved browsers are listed in [devices] and can be disconnected, which invalidates their token.
 */
object PairingManager {
    const val REQUEST_TTL_MS = 60_000L
    private const val COOLDOWN_MS = 5_000L
    private const val REMEMBERED_OUTCOMES = 20
    private const val LAST_SEEN_PUBLISH_MS = 15_000L

    private val random = SecureRandom()

    private class Client(val id: String, val label: String, val pairedAt: Long) {
        @Volatile var lastSeen: Long = pairedAt
        @Volatile var published: Long = pairedAt
    }

    /** token -> client; the hot path (every video chunk) is a single lock-free lookup. */
    private val clients = ConcurrentHashMap<String, Client>()

    private val _pending = MutableStateFlow<PairRequest?>(null)

    /** The request awaiting the owner's decision, for the phone UI and notification. */
    val pending: StateFlow<PairRequest?> = _pending.asStateFlow()

    private val _devices = MutableStateFlow<List<PairedDevice>>(emptyList())

    /** Approved browsers, most recently paired first. */
    val devices: StateFlow<List<PairedDevice>> = _devices.asStateFlow()

    // Guarded by this.
    private var cooldownUntil = 0L
    private val outcomes = object : LinkedHashMap<String, PairStatus>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PairStatus>) =
            size > REMEMBERED_OUTCOMES
    }

    /** Forgets every request and paired browser. Called when the service starts and stops. */
    @Synchronized
    fun reset() {
        ActivityTracker.clear()
        clients.clear()
        outcomes.clear()
        cooldownUntil = 0L
        _pending.value = null
        publishDevices()
    }

    @Synchronized
    fun request(label: String): RequestResult {
        val now = System.currentTimeMillis()
        expireLocked(now)
        if (_pending.value != null || now < cooldownUntil) return RequestResult.Busy
        val request = PairRequest(
            id = randomHex(16),
            code = "%04d".format(random.nextInt(10_000)),
            label = label,
            expiresAt = now + REQUEST_TTL_MS,
        )
        _pending.value = request
        return RequestResult.Created(request, REQUEST_TTL_MS)
    }

    @Synchronized
    fun status(id: String): PairStatus {
        expireLocked(System.currentTimeMillis())
        if (_pending.value?.id == id) return PairStatus.Pending
        return outcomes[id] ?: PairStatus.Unknown
    }

    /** Owner pressed Approve. Returns false if the request already expired or was answered. */
    @Synchronized
    fun approve(): Boolean {
        expireLocked(System.currentTimeMillis())
        val request = _pending.value ?: return false
        val token = randomHex(16)
        clients[token] = Client(id = randomHex(4), label = request.label, pairedAt = System.currentTimeMillis())
        outcomes[request.id] = PairStatus.Approved(token)
        _pending.value = null
        publishDevices()
        return true
    }

    /**
     * A token for something the owner starts on this phone itself (casting to a Chromecast), so there is no
     * approval step. It is listed under Devices and can be disconnected like any other; the same label reuses its token.
     */
    @Synchronized
    fun issueLocal(label: String): String {
        clients.entries.firstOrNull { it.value.label == label }?.let { return it.key }
        val token = randomHex(16)
        clients[token] = Client(id = randomHex(4), label = label, pairedAt = System.currentTimeMillis())
        publishDevices()
        return token
    }

    @Synchronized
    fun deny() {
        val request = _pending.value ?: return
        outcomes[request.id] = PairStatus.Denied
        _pending.value = null
        cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
    }

    /** Disconnects one approved browser; its next request gets 401 and it has to ask again. */
    @Synchronized
    fun revoke(deviceId: String) {
        clients.entries.removeAll { it.value.id == deviceId }
        ActivityTracker.forget(deviceId)
        publishDevices()
    }

    @Synchronized
    fun revokeAll() {
        ActivityTracker.clear()
        clients.clear()
        publishDevices()
    }

    /** Lets the phone side expire a request nobody is polling for any more. */
    @Synchronized
    fun expireIfDue() = expireLocked(System.currentTimeMillis())

    /** The id and label behind a token, for showing what each screen is doing; null if the token isn't valid. */
    fun clientOf(token: String?): Pair<String, String>? {
        val client = (if (token == null) null else clients[token]) ?: return null
        return client.id to client.label
    }

    /** Called for every authenticated request, so it must stay cheap and lock-free. */
    fun isValid(token: String?): Boolean {
        val client = (if (token == null) null else clients[token]) ?: return false
        val now = System.currentTimeMillis()
        client.lastSeen = now
        // Re-publishing on every video chunk would flood the UI; show "last active" at a coarse grain.
        if (now - client.published >= LAST_SEEN_PUBLISH_MS) {
            client.published = now
            publishDevices()
        }
        return true
    }

    private fun publishDevices() {
        _devices.value = clients.values
            .map { PairedDevice(it.id, it.label, it.pairedAt, it.lastSeen) }
            .sortedByDescending { it.pairedAt }
    }

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun expireLocked(now: Long) {
        val request = _pending.value ?: return
        if (now < request.expiresAt) return
        outcomes[request.id] = PairStatus.Expired
        _pending.value = null
        cooldownUntil = now + COOLDOWN_MS
    }
}
