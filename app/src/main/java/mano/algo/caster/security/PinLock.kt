package mano.algo.caster.security

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A PIN that has to be entered to open Caster (so a child or a guest can't change what is shared). Only a salted, slow hash of it
 * is kept, in the app's private storage, and it is left out of backups.
 */
object PinLock {
    private const val PREFS = "caster_lock"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"
    private const val ITERATIONS = 20_000
    private const val KEY_FAILS = "fails"
    private const val KEY_BLOCKED_UNTIL = "blocked_until"
    private const val KEY_BLOCKED_ELAPSED = "blocked_elapsed"
    private const val KEY_BLOCKED_BOOT = "blocked_boot"
    private const val MAX_WAIT_MS = 5 * 60_000L
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    fun isValid(pin: String) = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    internal fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).encoded

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun isSet(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HASH, null) != null

    fun set(context: Context, pin: String) {
        require(isValid(pin)) { "A PIN is ${MIN_LENGTH} to ${MAX_LENGTH} digits" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SALT, hex(salt)).putString(KEY_HASH, hex(hash(pin, salt))).apply()
    }

    fun verify(context: Context, pin: String): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val salt = p.getString(KEY_SALT, null)?.let(::unhex) ?: return false
        val expected = p.getString(KEY_HASH, null)?.let(::unhex) ?: return false
        return isValid(pin) && MessageDigest.isEqual(expected, hash(pin, salt))
    }

    /**
     * Whether the PIN has been typed in this run of the app. It lives in memory only, so it is gone when the system ends the app
     * (a saved screen state must never bring the app back already open).
     */
    @Volatile var unlocked = false

    /** How long the app may be out of sight before the PIN is asked again. */
    const val RELOCK_AFTER_MS = 30_000L

    /** True while the PIN was typed recently enough (and the app has not been out of sight for long). */
    fun sessionOpen(): Boolean {
        val left = leftAt
        return unlocked && (left == 0L || android.os.SystemClock.elapsedRealtime() - left <= RELOCK_AFTER_MS)
    }

    /** When the app went out of sight (elapsed time), or 0 while it is in front. */
    @Volatile var leftAt = 0L

    sealed interface Attempt {
        data object Ok : Attempt
        data object Wrong : Attempt
        /** Too many wrong tries: nothing is checked for [ms] more milliseconds. */
        data class Wait(val ms: Long) : Attempt
    }

    /** One try at the PIN. Three wrong ones in a row make each next try wait longer; the count is kept across restarts of the app. */
    fun attempt(context: Context, pin: String): Attempt {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        val boot = bootCount(context)
        // The wait is counted on the phone's own running time (a clock change can't move it) while the phone has not restarted;
        // the clock only counts after a restart. A clock set back can't make it longer than the longest wait either.
        val byRunTime = if (p.getInt(KEY_BLOCKED_BOOT, -2) == boot) p.getLong(KEY_BLOCKED_ELAPSED, 0L) - elapsed else 0L
        val byClock = minOf(p.getLong(KEY_BLOCKED_UNTIL, 0L) - now, MAX_WAIT_MS)
        val wait = maxOf(minOf(byRunTime, MAX_WAIT_MS), byClock)
        if (wait > 0) return Attempt.Wait(wait)
        if (verify(context, pin)) {
            p.edit().putInt(KEY_FAILS, 0).putLong(KEY_BLOCKED_UNTIL, 0L).apply()
            return Attempt.Ok
        }
        val fails = p.getInt(KEY_FAILS, 0) + 1
        val edit = p.edit().putInt(KEY_FAILS, fails)
        if (fails >= 3) {
            val wait = minOf(MAX_WAIT_MS, 5_000L shl minOf(fails - 3, 6))
            edit.putLong(KEY_BLOCKED_UNTIL, now + wait).putLong(KEY_BLOCKED_ELAPSED, elapsed + wait).putInt(KEY_BLOCKED_BOOT, boot)
        }
        edit.apply()
        return Attempt.Wrong
    }

    /** How many times the phone has started: a block counted on running time only holds within one start. */
    private fun bootCount(context: Context): Int =
        runCatching { android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
