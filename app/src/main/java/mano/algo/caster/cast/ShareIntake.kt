package mano.algo.caster.cast

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A video that another app (the gallery) sent to Caster with "Share": it waits here until it can be played on a Chromecast.
 * Only videos that are in the phone's own video library can be played, because that is all the media server can hand out.
 */
object ShareIntake {
    /** The video waiting to be cast. */
    data class Incoming(val id: Long, val name: String, val fromOutside: Boolean = false)

    private val _pending = MutableStateFlow<Incoming?>(null)
    val pending: StateFlow<Incoming?> = _pending.asStateFlow()

    /** Takes the video out of a SEND intent. Returns false when the intent holds nothing that can be played. */
    fun handle(context: Context, intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return false
        val found = resolve(context.applicationContext, uri)?.copy(fromOutside = true) ?: return false // another app sent it: the owner confirms
        _pending.value = found
        return true
    }

    /** A video the owner chose in the app, to be played as soon as a Chromecast is connected. */
    fun offer(id: Long, name: String) {
        _pending.value = Incoming(id, name)
    }

    fun clear() {
        _pending.value = null
    }

    /** The library entry for [uri]: its own id when it is a library address, otherwise found again by file name and size. */
    internal fun resolve(context: Context, uri: Uri): Incoming? {
        libraryId(uri)?.let { id -> nameOf(context, id)?.let { return Incoming(id, it) } }
        val (name, size) = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null
            }
        }.getOrNull() ?: return null
        if (name.isNullOrBlank() || size <= 0) return null
        return runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.SIZE} = ?", arrayOf(name, size.toString()), null,
            )?.use { c -> if (c.moveToFirst()) Incoming(c.getLong(0), name) else null }
        }.getOrNull()
    }

    /** content://media/external/video/media/123 is video 123. */
    internal fun libraryId(uri: Uri): Long? {
        if (uri.authority != MediaStore.AUTHORITY) return null
        val segments = uri.pathSegments
        // …/video/media/<id> (anything else, such as images, is not a video of the library)
        if (segments.size < 3 || segments[segments.size - 3] != "video" || segments[segments.size - 2] != "media") return null
        return segments.last().toLongOrNull()
    }

    private fun nameOf(context: Context, id: Long): String? = runCatching {
        context.contentResolver.query(
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
            arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
