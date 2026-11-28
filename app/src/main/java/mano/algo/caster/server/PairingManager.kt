package mano.algo.caster.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** A browser asking for access. [code] is shown on both the browser and the phone so the owner can match them. */
data class PairRequest(
    val id: String,
    val code: String,
    val label: String,
    val expiresAt: Long,
    val requester: String = "",
    /** A random id the browser keeps in its own storage, so two screens with the same name (two Fire TVs) stay two screens. */
    val clientId: String = "",
)

/** A browser the owner has approved. [id] is a short public handle, never the secret token. */
data class PairedDevice(
    val id: String,
    val label: String,
    val pairedAt: Long,
    val lastSeen: Long,
    val clientId: String = "",
    /** When the access ends by itself (0 = never). */
    val expiresAt: Long = 0L,
    /** True when this screen may only see some of the shared videos. */
    val limited: Boolean = false,
)

/** A screen that was approved at some time, remembered so it can be listed (not its token: that dies with the sharing session). */
data class KnownDevice(
    val clientId: String,
    val label: String,
    val lastConnected: Long,
    /** The only videos this screen may see (null: all that are shared). Kept with the screen, so it applies again after re-approval. */
    val limit: Set<Long>? = null,
    /** The only photos this screen may see (null: none when limited, or all shared when unlimited). */
    val photoLimit: Set<Long>? = null,
    /** The only music tracks this screen may see (null: none when limited, or all shared when unlimited). */
    val musicLimit: Set<Long>? = null,
) {
    fun hasAnyLimit(): Boolean = limit != null || photoLimit != null || musicLimit != null
}

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
    /** The label of the access the phone gives its own Chromecast casts; it is not a screen the owner approved. */
    const val CHROMECAST_LABEL = "Chromecast (this phone)"
    const val REQUEST_TTL_MS = 60_000L
    private const val COOLDOWN_MS = 5_000L
    private const val REMEMBERED_OUTCOMES = 20
    private const val LAST_SEEN_PUBLISH_MS = 15_000L

    private val random = SecureRandom()

    private class Client(
        val id: String,
        val label: String,
        val pairedAt: Long,
        val clientId: String,
        val expiresAt: Long = 0L,
        @Volatile var limit: Set<Long>? = null,
        @Volatile var photoLimit: Set<Long>? = null,
        @Volatile var musicLimit: Set<Long>? = null,
    ) {
        @Volatile var lastSeen: Long = pairedAt
        @Volatile var published: Long = pairedAt
        fun hasAnyLimit(): Boolean = limit != null || photoLimit != null || musicLimit != null
    }

    /** token -> client; the hot path (every video chunk) is a single lock-free lookup. */
    private val clients = ConcurrentHashMap<String, Client>()

    private val _pending = MutableStateFlow<PairRequest?>(null)

    /** The request awaiting the owner's decision, for the phone UI and notification. */
    val pending: StateFlow<PairRequest?> = _pending.asStateFlow()

    private const val HISTORY_PREFS = "caster_screens"
    private const val HISTORY_KEY = "known"
    private const val HISTORY_MAX = 10
    private var historyContext: android.content.Context? = null

    private val _known = MutableStateFlow<List<KnownDevice>>(emptyList())

    /** Screens approved before, the newest first. Connected or not, they stay listed until cleared. */
    val known: StateFlow<List<KnownDevice>> = _known.asStateFlow()

    /** Loads the list of screens used before. Safe to call often. */
    @Synchronized
    fun init(context: android.content.Context) {
        if (historyContext != null) return
        historyContext = context.applicationContext
        _known.value = context.applicationContext.getSharedPreferences(HISTORY_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(HISTORY_KEY, "").orEmpty().lines().mapNotNull { line ->
                val parts = line.split('\t', limit = 6)
                val time = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                when {
                    parts.size >= 4 && parts[2].isNotBlank() -> KnownDevice(
                        parts[1], parts[2], time,
                        limit = parseLimit(parts[3]),
                        photoLimit = parts.getOrNull(4)?.let(::parseLimit),
                        musicLimit = parts.getOrNull(5)?.let(::parseLimit),
                    )
                    parts.size == 3 && parts[2].isNotBlank() -> KnownDevice(parts[1], parts[2], time)
                    // The older format had no id: the name stood in for it.
                    parts.size == 2 && parts[1].isNotBlank() -> KnownDevice("label:" + parts[1], parts[1], time)
                    else -> null
                }
            }
    }

    // A limit is saved as nothing (no limit), "none" (the empty set) or the ids with commas between them.
    internal fun formatLimit(limit: Set<Long>?) = when {
        limit == null -> ""
        limit.isEmpty() -> "none"
        else -> limit.joinToString(",")
    }

    internal fun parseLimit(text: String): Set<Long>? = when (text.trim()) {
        "" -> null
        "none" -> emptySet()
        else -> text.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
    }

    private fun saveKnown(list: List<KnownDevice>) {
        _known.value = list
        historyContext?.getSharedPreferences(HISTORY_PREFS, android.content.Context.MODE_PRIVATE)?.edit()
            ?.putString(HISTORY_KEY, list.joinToString("\n") {
                listOf(
                    it.lastConnected.toString(),
                    it.clientId,
                    it.label,
                    formatLimit(it.limit),
                    formatLimit(it.photoLimit),
                    formatLimit(it.musicLimit),
                ).joinToString("\t")
            })?.apply()
    }

    /** Keeps the newest screens, but a screen that has a limit is never dropped to make room: its limit would be lost with it. */
    internal fun trimKnown(list: List<KnownDevice>): List<KnownDevice> {
        var plain = 0
        return list.filter { it.hasAnyLimit() || ++plain <= HISTORY_MAX }
    }

    @Synchronized
    fun clearKnown() = saveKnown(emptyList())

    /** What "Clear list" does: forgets the screens, except those with a limit (forgetting them would lift the limit). */
    @Synchronized
    fun clearUnlimited() = saveKnown(_known.value.filter { it.hasAnyLimit() })

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
    fun request(label: String, requester: String = "", clientId: String = ""): RequestResult {
        val now = System.currentTimeMillis()
        expireLocked(now)
        val pending = _pending.value
        // The same screen asking again (the page was reloaded, or went back and forward) takes the place of its own earlier
        // request; otherwise it would be turned away as "busy" by itself until the old one ran out.
        val sameScreen = pending != null && requester.isNotEmpty() && pending.requester == requester
        if ((pending != null && !sameScreen) || now < cooldownUntil) return RequestResult.Busy
        if (pending != null) outcomes.remove(pending.id)
        val request = PairRequest(
            id = randomHex(16),
            code = String.format(java.util.Locale.ROOT, "%04d", random.nextInt(10_000)), // plain digits in every language
            label = label,
            expiresAt = now + REQUEST_TTL_MS,
            requester = requester,
            clientId = clientId,
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
    fun approve(durationMs: Long = 0L): Boolean {
        expireLocked(System.currentTimeMillis())
        val request = _pending.value ?: return false
        val token = randomHex(16)
        val now = System.currentTimeMillis()
        val label = request.label.replace('\t', ' ').replace('\n', ' ')
        // A screen that already has no id of its own (an old page) is told apart by its name only.
        val cid = request.clientId.ifEmpty { "label:$label" }
        // The same screen approved again replaces its older access instead of leaving two valid tokens behind.
        val older = clients.entries.filter { it.value.clientId == cid }
        older.forEach { entry ->
            clients.remove(entry.key)
            ActivityTracker.forget(entry.value.id)
            RemoteControl.idle(entry.value.id)
        }
        // What this screen was limited to before is still its limit; a time limit counts from now.
        // A screen that comes back with a new id (its browser data was cleared) but the same name as a limited screen is limited
        // too: the id is chosen by the screen itself, so it can't be what keeps a limit in place. The owner can lift it on the row.
        val knownMatch = _known.value.firstOrNull { it.clientId == cid }
            ?: if (_known.value.none { it.clientId == cid }) {
                // (Only from a screen that is not connected now: one that is connected is another device with the same name.)
                _known.value.firstOrNull { k -> k.hasAnyLimit() && k.label == label && clients.values.none { it.clientId == k.clientId } }
            } else null
        val limit = knownMatch?.limit
        val photoLimit = knownMatch?.photoLimit
        val musicLimit = knownMatch?.musicLimit
        clients[token] = Client(
            id = randomHex(4), label = request.label, pairedAt = now, clientId = cid,
            expiresAt = if (durationMs > 0) now + durationMs else 0L,
            limit = limit, photoLimit = photoLimit, musicLimit = musicLimit,
        )
        saveKnown(trimKnown(listOf(KnownDevice(cid, label, now, limit, photoLimit, musicLimit)) + _known.value.filter { it.clientId != cid }))
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
        clients[token] = Client(id = randomHex(4), label = label, pairedAt = System.currentTimeMillis(), clientId = "local:$label")
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
        RemoteControl.idle(deviceId) // its controls and queued commands go with it
        publishDevices()
    }

    /** Disconnects the screen matching [token], if any. */
    @Synchronized
    fun revokeByToken(token: String?) {
        if (token.isNullOrEmpty()) return
        val client = clients.remove(token) ?: return
        ActivityTracker.forget(client.id)
        RemoteControl.idle(client.id)
        publishDevices()
    }

    @Synchronized
    fun revokeAll() {
        ActivityTracker.clear()
        RemoteControl.clear()
        clients.clear()
        publishDevices()
    }

    /** Lets the phone side expire a request nobody is polling for any more. */
    @Synchronized
    fun expireIfDue() {
        val now = System.currentTimeMillis()
        expireLocked(now)
        // Screens whose time is over are signed out even if they never ask for anything again.
        clients.values.filter { it.expiresAt != 0L && now >= it.expiresAt }.forEach { revoke(it.id) }
    }

    /** The id and label behind a token, for showing what each screen is doing; null if the token isn't valid. */
    fun clientOf(token: String?): Pair<String, String>? {
        val client = (if (token == null) null else clients[token]) ?: return null
        return client.id to client.label
    }

    /** Limits [clientId] to video [ids] (null removes the video limit), for the screen as it is now and for when it is approved again. */
    @Synchronized
    fun setLimit(clientId: String, ids: Set<Long>?) {
        saveKnown(_known.value.map { if (it.clientId == clientId) it.copy(limit = ids) else it })
        clients.values.filter { it.clientId == clientId }.forEach { it.limit = ids }
        publishDevices()
    }

    /** Limits [clientId] to photo [ids] (null removes the photo limit). */
    @Synchronized
    fun setPhotoLimit(clientId: String, ids: Set<Long>?) {
        saveKnown(_known.value.map { if (it.clientId == clientId) it.copy(photoLimit = ids) else it })
        clients.values.filter { it.clientId == clientId }.forEach { it.photoLimit = ids }
        publishDevices()
    }

    /** Limits [clientId] to music [ids] (null removes the music limit). */
    @Synchronized
    fun setMusicLimit(clientId: String, ids: Set<Long>?) {
        saveKnown(_known.value.map { if (it.clientId == clientId) it.copy(musicLimit = ids) else it })
        clients.values.filter { it.clientId == clientId }.forEach { it.musicLimit = ids }
        publishDevices()
    }

    /** Removes all video, photo, and music limits for [clientId]. */
    @Synchronized
    fun clearLimit(clientId: String) {
        saveKnown(_known.value.map { if (it.clientId == clientId) it.copy(limit = null, photoLimit = null, musicLimit = null) else it })
        clients.values.filter { it.clientId == clientId }.forEach {
            it.limit = null
            it.photoLimit = null
            it.musicLimit = null
        }
        publishDevices()
    }

    fun limitOf(clientId: String): Set<Long>? = _known.value.firstOrNull { it.clientId == clientId }?.limit

    /** True when the connected screen [deviceId] is allowed to see video [videoId] under its "Limit" settings. */
    fun isVideoAllowedForDevice(deviceId: String, videoId: Long): Boolean {
        val client = clients.values.firstOrNull { it.id == deviceId } ?: return false
        if (!client.hasAnyLimit()) return false
        val limit = client.limit ?: return false
        return videoId in limit
    }

    /** True when [token] belongs to a local in-app player (such as Chromecast). */
    fun isLocal(token: String?): Boolean = (if (token == null) null else clients[token])?.clientId?.startsWith("local:") == true

    /** True when the screen behind [token] has any media limit (videos, photos, or music). */
    fun isLimited(token: String?): Boolean = (if (token == null) null else clients[token])?.hasAnyLimit() == true

    fun hasVideoLimit(token: String?): Boolean = (if (token == null) null else clients[token])?.limit != null

    fun hasPhotoLimit(token: String?): Boolean = (if (token == null) null else clients[token])?.photoLimit != null

    fun hasMusicLimit(token: String?): Boolean = (if (token == null) null else clients[token])?.musicLimit != null

    /** True when the screen behind [token] may see video [id] (the owner's own choice of shared videos is checked separately). */
    fun allows(token: String?, id: Long): Boolean {
        val client = (if (token == null) null else clients[token]) ?: return false
        val limit = client.limit
        return limit == null || id in limit
    }

    fun allowsPhoto(token: String?, id: Long): Boolean {
        val client = (if (token == null) null else clients[token]) ?: return false
        val limit = client.photoLimit
        return limit == null || id in limit
    }

    fun allowsMusic(token: String?, id: Long): Boolean {
        val client = (if (token == null) null else clients[token]) ?: return false
        val limit = client.musicLimit
        return limit == null || id in limit
    }

    /** Called for every authenticated request, so it must stay cheap and lock-free. */
    fun isValid(token: String?): Boolean {
        val client = (if (token == null) null else clients[token]) ?: return false
        val now = System.currentTimeMillis()
        if (client.expiresAt != 0L && now >= client.expiresAt) { // the time the owner gave this screen is over
            revoke(client.id)
            return false
        }
        client.lastSeen = now
        // Re-publishing on every video chunk would flood the UI; show "last active" at a coarse grain.
        if (now - client.published >= LAST_SEEN_PUBLISH_MS) {
            client.published = now
            // Under the lock, and only if the screen is still there: a snapshot taken before a disconnect must not be published after it.
            synchronized(this) { if (clients[token] === client) publishDevices() }
        }
        return true
    }

    private fun publishDevices() {
        _devices.value = clients.values
            .map { PairedDevice(it.id, it.label, it.pairedAt, it.lastSeen, it.clientId, it.expiresAt, it.hasAnyLimit()) }
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
