package mano.algo.caster.server

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One video that a screen started: when, on which screen, and its name. */
data class WatchEntry(val time: Long, val screen: String, val video: String)

/**
 * A list, kept on the phone only, of what was watched and when. It can be switched off and cleared in Settings, is never sent
 * anywhere, and holds the newest [MAX] entries.
 */
object WatchLog {
    private const val PREFS = "caster_history"
    private const val KEY = "entries"
    private const val KEY_ON = "on"
    private const val MAX = 300

    private var context: Context? = null
    private val _entries = MutableStateFlow<List<WatchEntry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<WatchEntry>> = _entries.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = p.getBoolean(KEY_ON, true)
        _entries.value = p.getString(KEY, "").orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            val time = parts.getOrNull(0)?.toLongOrNull()
            if (time != null && parts.size == 3) WatchEntry(time, parts[1], parts[2]) else null
        }
    }

    private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').take(120)

    @Synchronized
    fun record(screen: String, video: String) {
        if (!_enabled.value) return
        save((listOf(WatchEntry(System.currentTimeMillis(), clean(screen), clean(video))) + _entries.value).take(MAX))
    }

    @Synchronized
    fun clear() = save(emptyList())

    @Synchronized
    fun setEnabled(on: Boolean) {
        _enabled.value = on
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_ON, on)?.apply()
    }

    private fun save(list: List<WatchEntry>) {
        _entries.value = list
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY, list.joinToString("\n") { "${it.time}\t${it.screen}\t${it.video}" })?.apply()
    }
}
