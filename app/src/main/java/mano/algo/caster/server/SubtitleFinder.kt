package mano.algo.caster.server

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/** Looks for `name.srt` / `name.vtt` (also `name.en.srt`) beside a video and hands it out as WebVTT, which browsers and Chromecasts read. */
object SubtitleFinder {
    private val EXTENSIONS = setOf("srt", "vtt")
    private const val MAX_BYTES = 2L * 1024 * 1024
    private val SRT_TIMESTAMP = Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})""")

    /** Best effort: newer Android hides other apps' non-media files, so this works mainly on older phones and for files the app can read. */
    fun find(context: Context, video: Uri): File? = runCatching {
        val path = context.contentResolver.query(video, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return@runCatching null
        val file = File(path)
        val base = file.nameWithoutExtension
        file.parentFile?.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$base.") && it.extension.lowercase() in EXTENSIONS && it.length() < MAX_BYTES }
            ?.minByOrNull { it.name.length }
    }.getOrNull()

    fun toVtt(raw: String): String {
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n")
        return if (text.trimStart().startsWith("WEBVTT")) text else "WEBVTT\n\n" + SRT_TIMESTAMP.replace(text, "$1.$2")
    }

    fun vtt(context: Context, video: Uri): String? = find(context, video)?.let { runCatching { toVtt(it.readText()) }.getOrNull() }
}
