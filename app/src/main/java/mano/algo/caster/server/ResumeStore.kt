package mano.algo.caster.server

import android.content.Context

/** Where a video was stopped: the second it had reached, and how long it is. */
data class ResumePoint(val id: Long, val positionSec: Int, val durationSec: Int, val updated: Long)

/**
 * Remembers, on the phone, where each video was stopped, so any screen (another TV, a computer, the Chromecast) can offer
 * "continue where you left off". A video that is nearly finished, or only just begun, is forgotten. Only the newest
 * [MAX] are kept, and the choice of what to keep is the phone's: nothing leaves it.
 */
object ResumeStore {
    private const val PREFS = "caster_resume"
    private const val KEY = "points"
    private const val MAX = 200
    private const val SAVE_EVERY_SEC = 5
    private const val MIN_SEC = 10
    private const val END_MARGIN_SEC = 20

    private val points = LinkedHashMap<Long, ResumePoint>()
    private var context: Context? = null
    private var savedAt = LinkedHashMap<Long, Int>()

    /** What to do with a report: keep it, or forget the video (it is finished, or hardly started). */
    internal fun keeps(positionSec: Int, durationSec: Int) =
        positionSec >= MIN_SEC && (durationSec <= 0 || positionSec < durationSec - END_MARGIN_SEC)

    @Synchronized
    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        points.clear()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty().lines().forEach { line ->
            val p = line.split(',')
            if (p.size == 4) {
                val id = p[0].toLongOrNull(); val pos = p[1].toIntOrNull(); val dur = p[2].toIntOrNull(); val at = p[3].toLongOrNull()
                if (id != null && pos != null && dur != null && at != null) points[id] = ResumePoint(id, pos, dur, at)
            }
        }
    }

    /** Called about once a second while a video plays; it writes to disk only now and then. */
    @Synchronized
    fun record(id: Long, positionSec: Int, durationSec: Int) {
        if (!keeps(positionSec, durationSec)) {
            if (points.remove(id) != null) save()
            savedAt.remove(id)
            return
        }
        points.remove(id) // so the newest is last
        points[id] = ResumePoint(id, positionSec, durationSec, System.currentTimeMillis())
        while (points.size > MAX) points.remove(points.keys.first())
        val last = savedAt[id]
        if (last == null || kotlin.math.abs(positionSec - last) >= SAVE_EVERY_SEC) {
            savedAt[id] = positionSec
            save()
        }
    }

    @Synchronized
    fun get(id: Long): ResumePoint? = points[id]

    /** Newest first. */
    @Synchronized
    fun all(): List<ResumePoint> = points.values.toList().asReversed()

    @Synchronized
    fun clear() {
        points.clear()
        savedAt.clear()
        save()
    }

    private fun save() {
        context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(KEY, points.values.joinToString("\n") { "${it.id},${it.positionSec},${it.durationSec},${it.updated}" })?.apply()
    }
}
