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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateSet
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mano.algo.caster.cast.CastController
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

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

internal fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

internal fun formatSize(bytes: Long): String =
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
    // Saved with the screen, so turning the phone doesn't bring back the old selection.
    val selected = rememberSaveable(
        saver = listSaver<SnapshotStateSet<Long>, Long>(
            save = { it.toList() },
            restore = { mutableStateSetOf<Long>().apply { addAll(it) } },
        ),
    ) { mutableStateSetOf<Long>().apply { addAll(initialSelection) } }
    // Videos that were deleted since they were chosen are no longer counted (an empty list means "couldn't read", not "all deleted").
    LaunchedEffect(videos) {
        videos?.takeIf { it.isNotEmpty() }?.let { list -> selected.retainAll(list.map { it.id }.toSet()) }
    }
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
            onClick = { onDone(selected.toSet()) }, // an empty choice is a choice too ("None" must be possible to keep)
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                if (selected.isEmpty()) "Share no videos"
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

@Suppress("DEPRECATION")
@Composable
internal fun Thumbnail(id: Long, kind: String = "v") {
    val context = LocalContext.current
    val table = if (kind == "i") MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val bitmap by produceState<ImageBitmap?>(initialValue = thumbnailCache.get(id)?.asImageBitmap(), id) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val uri = ContentUris.withAppendedId(table, id)
                        context.contentResolver.loadThumbnail(uri, Size(192, 108), null)
                    } else if (kind == "i") {
                        MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                    } else {
                        MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                    }
                    bitmap?.also { thumbnailCache.put(id, it) }?.asImageBitmap()
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

data class PickerItem(val id: Long, val name: String, val detail: String, val kind: String = "v")

/** Full-screen list of the media that are shared; tapping one hands it to [onPick] (used to start it on a Chromecast). */
@Composable
fun CastPickerScreen(
    onCancel: () -> Unit,
    onPick: (id: Long, name: String, kind: String) -> Unit,
    onPlayAll: (items: List<CastController.CastItem>, shuffle: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val selection by mano.algo.caster.server.ShareSettings.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = buildList {
        add("Videos")
        if (selection.shareExtras) {
            add("Photos")
            add("Music")
        }
    }

    val videos by produceState<List<PickerItem>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            queryVideos(context).filter { mano.algo.caster.server.ShareSettings.isAllowed(it.id) }
                .map { PickerItem(it.id, it.name, "${formatDuration(it.durationMs)} · ${formatSize(it.size)}", "v") }
        }
    }

    val photos by produceState<List<PickerItem>?>(initialValue = null, selection.shareExtras) {
        value = if (!selection.shareExtras) emptyList() else withContext(Dispatchers.IO) {
            queryPhotos(context).filter { mano.algo.caster.server.ShareSettings.isPhotoAllowed(it.id) }
                .map { PickerItem(it.id, it.name, formatSize(it.size), "i") }
        }
    }

    val music by produceState<List<PickerItem>?>(initialValue = null, selection.shareExtras) {
        value = if (!selection.shareExtras) emptyList() else withContext(Dispatchers.IO) {
            queryMusic(context).filter { mano.algo.caster.server.ShareSettings.isMusicAllowed(it.id) }
                .map { PickerItem(it.id, it.name, "${formatDuration(it.durationMs)} · ${formatSize(it.size)}", "a") }
        }
    }

    val currentTab = tabs.getOrElse(selectedTab.coerceIn(0, tabs.size - 1)) { "Videos" }
    val (items, currentKind) = when (currentTab) {
        "Photos" -> photos to "i"
        "Music" -> music to "a"
        else -> videos to "v"
    }

    BackHandler(onBack = onCancel)

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
            Text(
                when (currentKind) {
                    "i" -> "Choose a photo to cast"
                    "a" -> "Choose music to cast"
                    else -> "Choose a video to cast"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (tabs.size > 1) {
            TabRow(selectedTabIndex = selectedTab.coerceIn(0, tabs.size - 1)) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }
        }

        val list = items
        if (!list.isNullOrEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val castItems = list.map { CastController.CastItem(it.id, it.name, currentKind) }
                androidx.compose.material3.FilledTonalButton(
                    onClick = { onPlayAll(castItems, false) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (currentKind == "i") "Start slideshow" else "Play all")
                }
                androidx.compose.material3.FilledTonalButton(
                    onClick = { onPlayAll(castItems, true) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Shuffle")
                }
            }
            Text(
                "Plays up to ${mano.algo.caster.cast.CastController.QUEUE_MAX} items, one after the other.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text(
                    "No shared ${currentTab.lowercase()}. Choose them on the main page first.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(item.id, item.name, currentKind) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (currentKind != "a") {
                                Thumbnail(item.id, kind = currentKind)
                                Spacer(Modifier.width(14.dp))
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------- Photo Picker -----------------

data class PickerPhoto(val id: Long, val name: String, val size: Long)

fun queryPhotos(context: Context): List<PickerPhoto> {
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.SIZE,
    )
    val result = ArrayList<PickerPhoto>()
    try {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                result += PickerPhoto(c.getLong(0), c.getString(1) ?: "photo", c.getLong(2))
            }
        }
    } catch (e: SecurityException) {
    }
    return result
}

@Composable
fun PhotoPickerScreen(
    initialSelection: Set<Long>,
    onCancel: () -> Unit,
    onDone: (Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val photos by produceState<List<PickerPhoto>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { queryPhotos(context) }
    }
    val selected = rememberSaveable(
        saver = listSaver<SnapshotStateSet<Long>, Long>(
            save = { it.toList() },
            restore = { mutableStateSetOf<Long>().apply { addAll(it) } },
        ),
    ) { mutableStateSetOf<Long>().apply { addAll(initialSelection) } }

    LaunchedEffect(photos) {
        photos?.takeIf { it.isNotEmpty() }?.let { list -> selected.retainAll(list.map { it.id }.toSet()) }
    }
    BackHandler(onBack = onCancel)

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
            Column(modifier = Modifier.weight(1f)) {
                Text("Choose photos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${selected.size} selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { photos?.let { list -> selected.addAll(list.map { it.id }) } }) { Text("All") }
            TextButton(onClick = { selected.clear() }) { Text("None") }
        }

        val list = photos
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text("No photos found on this phone.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { photo ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (!selected.add(photo.id)) selected.remove(photo.id) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Thumbnail(photo.id, kind = "i")
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(photo.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(formatSize(photo.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(checked = photo.id in selected, onCheckedChange = null)
                        }
                    }
                }
            }
        }

        Button(
            onClick = { onDone(selected.toSet()) },
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(if (selected.isEmpty()) "Share no photos" else "Share ${selected.size} photo${if (selected.size == 1) "" else "s"}")
        }
    }
}

// ----------------- Music Picker -----------------

data class PickerMusic(val id: Long, val name: String, val durationMs: Long, val size: Long)

fun queryMusic(context: Context): List<PickerMusic> {
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.SIZE,
    )
    val result = ArrayList<PickerMusic>()
    try {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                result += PickerMusic(c.getLong(0), c.getString(1) ?: "track", c.getLong(2), c.getLong(3))
            }
        }
    } catch (e: SecurityException) {
    }
    return result
}

@Composable
fun MusicPickerScreen(
    initialSelection: Set<Long>,
    onCancel: () -> Unit,
    onDone: (Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val music by produceState<List<PickerMusic>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { queryMusic(context) }
    }
    val selected = rememberSaveable(
        saver = listSaver<SnapshotStateSet<Long>, Long>(
            save = { it.toList() },
            restore = { mutableStateSetOf<Long>().apply { addAll(it) } },
        ),
    ) { mutableStateSetOf<Long>().apply { addAll(initialSelection) } }

    LaunchedEffect(music) {
        music?.takeIf { it.isNotEmpty() }?.let { list -> selected.retainAll(list.map { it.id }.toSet()) }
    }
    BackHandler(onBack = onCancel)

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
            Column(modifier = Modifier.weight(1f)) {
                Text("Choose music", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${selected.size} selected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { music?.let { list -> selected.addAll(list.map { it.id }) } }) { Text("All") }
            TextButton(onClick = { selected.clear() }) { Text("None") }
        }

        val list = music
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text("No music found on this phone.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (!selected.add(track.id)) selected.remove(track.id) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${formatDuration(track.durationMs)} · ${formatSize(track.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(checked = track.id in selected, onCheckedChange = null)
                        }
                    }
                }
            }
        }

        Button(
            onClick = { onDone(selected.toSet()) },
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(if (selected.isEmpty()) "Share no music" else "Share ${selected.size} track${if (selected.size == 1) "" else "s"}")
        }
    }
}
