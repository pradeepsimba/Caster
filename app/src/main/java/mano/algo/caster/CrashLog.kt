package mano.algo.caster

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a short private record of why the app stopped, so "it keeps closing" can be answered with facts:
 * a crash is written with its full stack trace, and on Android 11+ the reason the system gave for each earlier
 * exit (crash, out of memory, frozen, killed...) is added the next time the app starts. Nothing leaves the phone
 * unless the owner copies it from Settings.
 */
object CrashLog {
    private const val FILE = "crash.log"
    private const val PREFS = "caster_crash"
    private const val KEY_LAST_EXIT = "last_exit_ms"
    private const val MAX_BYTES = 24_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Never let the recorder itself get in the way of the normal crash handling.
            runCatching { append(app, "CRASH on thread \"${thread.name}\"\n" + stackTrace(error)) }
            previous?.uncaughtException(thread, error)
        }
        runCatching { recordEarlierExits(app) }
    }

    /** The whole record, newest last; empty when nothing has been recorded. */
    fun read(context: Context): String = runCatching { file(context).takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    private fun append(context: Context, entry: String) {
        val header = "--- ${stamp(System.currentTimeMillis())} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n"
        val all = read(context) + header + entry.trimEnd() + "\n\n"
        // Keep the newest part only, so the record can't grow without limit.
        file(context).writeText(if (all.length > MAX_BYTES) all.takeLast(MAX_BYTES) else all)
    }

    /** What Android says ended the app's earlier runs. Only exits not yet written down are added. */
    private fun recordEarlierExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(KEY_LAST_EXIT, 0L)
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 8)
            .filter { it.timestamp > seen }
            .sortedBy { it.timestamp }
        for (exit in exits) {
            append(
                context,
                "EXIT at ${stamp(exit.timestamp)}: ${reasonName(exit.reason)}" +
                    (exit.description?.let { " — $it" } ?: "") +
                    // "o-kill" is ColorOS / OxygenOS's own app killer, not an error in the app.
                    (if (exit.description?.contains("o-kill", ignoreCase = true) == true) " [the phone's own battery manager closed the app; not a bug in Caster]" else "") +
                    " (memory ${exit.rss / 1024} MB)",
            )
        }
        exits.lastOrNull()?.let { prefs.edit().putLong(KEY_LAST_EXIT, it.timestamp).apply() }
    }

    // The numbers are ApplicationExitInfo.REASON_*; written out because some exist only on newer Android versions.
    private fun reasonName(reason: Int) = when (reason) {
        1 -> "the app closed itself"
        2 -> "killed by a signal"
        3 -> "KILLED FOR LOW MEMORY"
        4 -> "CRASH (an uncaught error)"
        5 -> "NATIVE CRASH"
        6 -> "NOT RESPONDING (ANR)"
        7 -> "failed to start"
        8 -> "permission changed"
        9 -> "KILLED FOR USING TOO MUCH (battery/memory)"
        10 -> "closed by the user"
        11 -> "stopped by the user"
        12 -> "a service it depends on died"
        13 -> "killed for another reason"
        14 -> "FROZEN/KILLED BY THE PHONE'S POWER SAVING"
        15 -> "app state changed"
        16 -> "app was updated"
        else -> "unknown ($reason)"
    }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    private fun stackTrace(error: Throwable): String = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
}
