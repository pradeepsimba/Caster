package mano.algo.caster.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mano.algo.caster.server.ShareSettings

private data class PickerVideo(val id: Long, val name: String, val durationMs: Long, val size: Long)

private fun queryVideos(context: Context): List<PickerVideo> {
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
    )
    val result = ArrayList<PickerVideo>()
    try {
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                result += PickerVideo(c.getLong(0), c.getString(1) ?: "video", c.getLong(2), c.getLong(3))
            }
        }
    } catch (e: SecurityException) {
        // No storage permission yet: show the empty state rather than crash.
    }
    return result
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${maxOf(1L, bytes / 1_000_000)} MB"

/** Full-screen list of the phone's videos with checkboxes; [onDone] receives the chosen MediaStore ids. */
@Composable
fun VideoPickerScreen(
    initialSelection: Set<Long>,
    onCancel: () -> Unit,
    onDone: (Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val videos by produceState<List<PickerVideo>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { queryVideos(context) }
    }
    val selected = remember { mutableStateSetOf<Long>().apply { addAll(initialSelection) } }
    BackHandler(onBack = onCancel)

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
            Column(modifier = Modifier.weight(1f)) {
                Text("Choose videos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${selected.size} selected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { videos?.let { list -> selected.addAll(list.map { it.id }) } }) { Text("All") }
            TextButton(onClick = { selected.clear() }) { Text("None") }
        }

        val list = videos
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text(
                    "No videos found on this phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { video ->
                        VideoRow(
                            video = video,
                            checked = video.id in selected,
                            onToggle = { if (!selected.add(video.id)) selected.remove(video.id) },
                        )
                    }
                }
            }
        }

        Button(
            onClick = { onDone(selected.toSet()) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                if (selected.isEmpty()) "Select at least one video"
                else "Share ${selected.size} video${if (selected.size == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun VideoRow(video: PickerVideo, checked: Boolean?, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(video.id)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(video.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatDuration(video.durationMs)} · ${formatSize(video.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (checked != null) Checkbox(checked = checked, onCheckedChange = null)
    }
}

/** Decoded thumbnails, capped by bytes, so scrolling back up (or reopening the picker) is instant. */
private val thumbnailCache = object : LruCache<Long, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
}

@Composable
private fun Thumbnail(id: Long) {
    val context = LocalContext.current
    // Loaded lazily per visible row; older Android has no cheap thumbnail API, so it shows the placeholder.
    val bitmap by produceState<ImageBitmap?>(initialValue = thumbnailCache.get(id)?.asImageBitmap(), id) {
        if (value == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    context.contentResolver.loadThumbnail(uri, Size(192, 108), null)
                        .also { thumbnailCache.put(id, it) }
                        .asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 54.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Full-screen list of the videos that are shared; tapping one hands it to [onPick] (used to start it on a Chromecast). */
@Composable
fun CastPickerScreen(
    onCancel: () -> Unit,
    onPick: (id: Long, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val videos by produceState<List<PickerVideo>?>(initialValue = null) {
        // Only what the owner shares: the server refuses the rest, so the Chromecast could never fetch it.
        value = withContext(Dispatchers.IO) { queryVideos(context).filter { ShareSettings.isAllowed(it.id) } }
    }
    BackHandler(onBack = onCancel)

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
            Text("Choose a video to cast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        val list = videos
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text(
                    "No shared videos. Choose videos in the Library tab first.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { video ->
                        VideoRow(video = video, checked = null, onToggle = { onPick(video.id, video.name) })
                    }
                }
            }
        }
    }
}
