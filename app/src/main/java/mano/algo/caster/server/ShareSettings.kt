package mano.algo.caster.server

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ShareMode { ALL, SELECTED }

/**
 * The owner's sharing settings. [ids] are MediaStore video ids and only matter in [ShareMode.SELECTED].
 * [port] is the port the owner prefers; if it is busy the server moves to the next free one.
 * [hostname] is the `<name>.local` the phone announces; if another device uses it, a `-2`, `-3`... is added.
 */
data class ShareSelection(
    val mode: ShareMode = ShareMode.ALL,
    val ids: Set<Long> = emptySet(),
    val port: Int = ShareSettings.DEFAULT_PORT,
    val hostname: String = ShareSettings.DEFAULT_HOSTNAME,
)

/**
 * The owner's choice between sharing the whole gallery or only picked videos, remembered across launches.
 * The server asks [isAllowed] on every request, so a change applies immediately and a client can never
 * reach a video outside the selection by guessing its id.
 */
object ShareSettings {
    private const val PREFS = "caster_share"
    private const val KEY_MODE = "mode"
    private const val KEY_IDS = "ids"
    private const val KEY_PORT = "port"
    private const val KEY_HOSTNAME = "hostname"
    private const val KEY_INSTANCE_ID = "instance_id"

    const val DEFAULT_HOSTNAME = MdnsResponder.DEFAULT_HOSTNAME
    private val HOSTNAME = Regex("^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$")

    /** A single DNS label: lowercase letters, digits and hyphens, not starting or ending with a hyphen. */
    fun isValidHostname(name: String) = HOSTNAME.matches(name)

    const val DEFAULT_PORT = 8080
    /** Android apps can't bind ports below 1024. */
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    fun isValidPort(port: Int) = port in MIN_PORT..MAX_PORT

    private val _state = MutableStateFlow(ShareSelection())
    val state: StateFlow<ShareSelection> = _state.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null

    /**
     * A random id that stays the same for this install. Pages use it to tell that two addresses (an IP and a
     * name, or an old name and a new one) are the same phone, so it is never listed twice.
     */
    @Volatile var instanceId: String = ""
        private set

    /** Loads the saved choice; safe to call repeatedly from any thread. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val mode = runCatching { ShareMode.valueOf(p.getString(KEY_MODE, null).orEmpty()) }.getOrDefault(ShareMode.ALL)
            val ids = p.getStringSet(KEY_IDS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
            val port = p.getInt(KEY_PORT, DEFAULT_PORT).takeIf(::isValidPort) ?: DEFAULT_PORT
            val hostname = p.getString(KEY_HOSTNAME, null)?.takeIf(::isValidHostname) ?: DEFAULT_HOSTNAME
            _state.value = ShareSelection(mode, ids, port, hostname)
            instanceId = p.getString(KEY_INSTANCE_ID, null) ?: ByteArray(6).also(java.security.SecureRandom()::nextBytes)
                .joinToString("") { "%02x".format(it) }
                .also { p.edit().putString(KEY_INSTANCE_ID, it).apply() }
            prefs = p
        }
    }

    fun setMode(mode: ShareMode) {
        _state.update { it.copy(mode = mode) }
        persist()
    }

    fun setSelected(ids: Set<Long>) {
        _state.update { it.copy(ids = ids) }
        persist()
    }

    /** Ignores out-of-range values; the server restarts on the new port by itself. */
    fun setPort(port: Int) {
        if (!isValidPort(port)) return
        _state.update { it.copy(port = port) }
        persist()
    }

    /** Ignores invalid names; the phone re-announces itself under the new name by itself. */
    fun setHostname(name: String) {
        val clean = name.trim().lowercase()
        if (!isValidHostname(clean)) return
        _state.update { it.copy(hostname = clean) }
        persist()
    }

    fun isAllowed(id: Long): Boolean = state.value.let { it.mode == ShareMode.ALL || id in it.ids }

    private fun persist() {
        val s = _state.value
        prefs?.edit()
            ?.putString(KEY_MODE, s.mode.name)
            ?.putStringSet(KEY_IDS, s.ids.map { it.toString() }.toSet())
            ?.putInt(KEY_PORT, s.port)
            ?.putString(KEY_HOSTNAME, s.hostname)
            ?.apply()
    }
}
