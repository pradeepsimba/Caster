package mano.algo.caster.server

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface ConvertState {
    data class Running(val percent: Int) : ConvertState
    data object Done : ConvertState
    data object Failed : ConvertState
}

/**
 * Re-encodes a video the viewer's browser can't play (HEVC, MKV...) into H.264/AAC MP4, using the phone's
 * hardware encoder. Files are kept in the app cache and removed when sharing stops. One job at a time, because
 * the encoder is busy and a second job would just make both slow.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object VideoConverter {
    private const val TAG = "VideoConverter"
    private val main = Handler(Looper.getMainLooper())
    private val states = ConcurrentHashMap<Long, ConvertState>()

    // Touched only on the main thread.
    private var transformer: Transformer? = null
    private var runningId: Long? = null

    private fun dir(context: Context) = File(context.cacheDir, "converted").apply { mkdirs() }

    /** The finished copy. Work in progress lives in [partFor], so a half-written file is never served. */
    fun fileFor(context: Context, id: Long) = File(dir(context), "$id.mp4")

    private fun partFor(context: Context, id: Long) = File(dir(context), "$id.part")

    fun state(context: Context, id: Long): ConvertState? {
        states[id]?.let { return it }
        return if (fileFor(context, id).let { it.exists() && it.length() > 0 }) ConvertState.Done else null
    }

    /** Starts converting [id] unless it is already done or running; returns the state right after the call. */
    fun start(context: Context, id: Long, uri: Uri): ConvertState {
        state(context, id)?.let { if (it !is ConvertState.Failed) return it }
        val app = context.applicationContext
        // Reserve before posting, so two quick requests can't both start a job.
        synchronized(this) {
            if (states.values.any { it is ConvertState.Running }) return ConvertState.Running(0).also { Log.i(TAG, "busy") }
            states[id] = ConvertState.Running(0)
        }
        main.post { begin(app, id, uri) }
        return ConvertState.Running(0)
    }

    private fun begin(context: Context, id: Long, uri: Uri) {
        val out = fileFor(context, id)
        val part = partFor(context, id)
        out.delete()
        part.delete()
        try {
            val t = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        mano.algo.caster.CrashLog.note(context, "Video $id converted")
                        states[id] = if (part.renameTo(out)) ConvertState.Done else ConvertState.Failed
                        finish()
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        Log.w(TAG, "Conversion failed for $id", exportException)
                        mano.algo.caster.CrashLog.note(context, "Converting video $id failed")
                        part.delete()
                        states[id] = ConvertState.Failed
                        finish()
                    }
                })
                .build()
            transformer = t
            runningId = id
            mano.algo.caster.CrashLog.note(context, "Converting video $id started (web page can't play it)")
            t.start(MediaItem.fromUri(uri), part.absolutePath)
            pollProgress(t, id)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start conversion for $id", e)
            states[id] = ConvertState.Failed
            finish()
        }
    }

    private fun pollProgress(t: Transformer, id: Long) {
        main.postDelayed({
            if (transformer !== t || states[id] !is ConvertState.Running) return@postDelayed
            val holder = ProgressHolder()
            if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                states[id] = ConvertState.Running(holder.progress.coerceIn(0, 99))
            }
            pollProgress(t, id)
        }, 1000)
    }

    private fun finish() {
        transformer = null
        runningId = null
    }

    /** Cancels a running job and deletes every converted file. */
    fun clear(context: Context) {
        val app = context.applicationContext
        main.post {
            runCatching { transformer?.cancel() }
            finish()
            states.clear()
            dir(app).listFiles()?.forEach { it.delete() }
        }
    }
}
