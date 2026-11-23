package mano.algo.caster.cast

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/** How a video is laid out on the TV's screen. */
enum class Picture(val label: String, val detail: String) {
    /** The whole picture, as it is (a Chromecast does this by itself; bars show where the shapes differ). */
    Fit("Fit", "The whole picture, with bars where the shapes differ."),

    /** The picture is enlarged until it fills the screen, and what sticks out is cut off. */
    Fill("Fill", "Fills the screen; the edges are cut off."),

    /** The picture is pulled to the screen's shape: nothing is cut, but things look wider or thinner. */
    Stretch("Stretch", "Fills the screen without cutting, but looks stretched."),
}

/**
 * A Chromecast shows a video whole and can't be told to fill the screen, so for "Fill" and "Stretch" the phone re-encodes the
 * video into a 1920 x 1080 copy first (what VLC does as well). The copy lives in the cache and is served to the Chromecast
 * like the original. This takes a while for a long video, which is why it reports its progress and can be cancelled.
 */
@OptIn(UnstableApi::class)
object FillConverter {
    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var polling: Runnable? = null
    private var cacheFolder: File? = null

    fun folder(context: Context) = File(context.cacheDir, "cast")

    /** Where the converted copy of video [id] lives (it exists only after a conversion finished). */
    fun file(context: Context, id: Long, picture: Picture) = File(folder(context), "$id-${picture.name.lowercase()}.mp4")

    /**
     * Makes the copy of video [id], or finishes at once if it was made before. Call it on the main thread; the callbacks come
     * on the main thread too. [onProgress] gets 0..1.
     */
    fun start(context: Context, id: Long, picture: Picture, onProgress: (Float) -> Unit, onDone: (File) -> Unit, onError: (String) -> Unit) {
        cancel()
        val app = context.applicationContext
        val out = file(app, id, picture)
        if (out.isFile && out.length() > 0) {
            onDone(out)
            return
        }
        folder(app).mkdirs()
        cacheFolder = folder(app)
        // Only one copy is kept at a time, so the cache doesn't fill up with films.
        folder(app).listFiles()?.forEach { it.delete() }
        val partial = File(folder(app), out.name + ".part")

        val source: Uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
        val layout = if (picture == Picture.Fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_STRETCH_TO_FIT
        val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(WIDTH, HEIGHT, layout))))
            .build()
        val made = Transformer.Builder(app)
            .setVideoMimeType(MimeTypes.VIDEO_H264) // what every Chromecast plays
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finish()
                    if (partial.renameTo(out)) onDone(out) else onError("Couldn't save the converted video.")
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finish()
                    partial.delete()
                    onError("Couldn't prepare this video for the TV (${exportException.errorCodeName}).")
                }
            })
            .build()
        transformer = made
        try {
            made.start(item, partial.absolutePath)
        } catch (e: Exception) {
            finish()
            partial.delete()
            onError("Couldn't prepare this video for the TV: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val holder = ProgressHolder()
        val tick = object : Runnable {
            override fun run() {
                if (transformer !== made) return
                if (made.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                main.postDelayed(this, 500)
            }
        }
        polling = tick
        main.post(tick)
    }

    /** Stops a conversion that is running and throws away what it had made. */
    fun cancel() {
        val running = transformer ?: return
        finish()
        running.cancel()
        cacheFolder?.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun finish() {
        polling?.let(main::removeCallbacks)
        polling = null
        transformer = null
    }

    private const val WIDTH = 1920
    private const val HEIGHT = 1080
}
