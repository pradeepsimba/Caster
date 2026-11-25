package mano.algo.caster.ui

import android.provider.MediaStore
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mano.algo.caster.server.PairedDevice
import mano.algo.caster.server.ShareMode
import mano.algo.caster.server.ShareSelection
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import mano.algo.caster.server.KnownDevice
import mano.algo.caster.server.PairingManager
import mano.algo.caster.ui.theme.LiveGreen
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/**
 * Every screen that was connected before, each with its state: green "Connected" (with a Disconnect), or "Not connected".
 * Tapping a screen starts sharing when it is off (the screen can then connect again), and opens the connect window when it is on.
 */
@Composable
fun YourScreensSection(
    known: List<KnownDevice>,
    devices: List<PairedDevice>,
    running: Boolean,
    onTap: () -> Unit,
    onDisconnect: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    onClear: () -> Unit,
    onLimit: (KnownDevice) -> Unit,
) {
    // The phone's own Chromecast access is not a screen. Every other screen has its own row, found by the id its browser keeps.
    val connected = devices.filter { it.label != PairingManager.CHROMECAST_LABEL }
    // A screen that is connected but not in the saved list (approved before the list existed) is still shown.
    val entries = known + connected
        .filter { d -> known.none { it.clientId == d.clientId } }
        .map { KnownDevice(it.clientId, it.label, it.pairedAt) }
    if (entries.isEmpty()) {
        if (running) {
            Text(
                "Screens you connect will be listed here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // "Active 2 min ago" ticks forward without anything else changing.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Your screens",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (connected.size > 1) TextButton(onClick = onDisconnectAll) { Text("Disconnect all") }
        }
        entries.forEach { entry ->
            val live = connected.filter { it.clientId == entry.clientId }
            // Two screens with the same name get a short ending from their id, so they can be told apart.
            val twins = entries.count { it.label == entry.label } > 1
            val name = if (twins) entry.label + " · " + entry.clientId.takeLast(4) else entry.label
            ScreenRow(name, live, entry.lastConnected, entry.limit, now, onTap, { onLimit(entry) }) { live.forEach { onDisconnect(it.id) } }
        }
        // (A screen with a limit stays in the list, so there is nothing to clear when only those are left.)
        if (connected.isEmpty() && known.any { it.limit == null }) {
            TextButton(onClick = onClear) { Text("Clear list") }
        }
    }
}

@Composable
private fun ScreenRow(
    label: String,
    live: List<PairedDevice>,
    last: Long?,
    limit: Set<Long>?,
    now: Long,
    onTap: () -> Unit,
    onLimit: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    CasterCard(high = live.isNotEmpty()) {
        Row(
            modifier = Modifier.clickable(onClick = onTap).padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(scheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Phone, contentDescription = null, tint = scheme.onPrimaryContainer)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(8.dp).background(if (live.isNotEmpty()) LiveGreen else scheme.outline, CircleShape))
                    Text(
                        when {
                            live.size > 1 -> "Connected · ${live.size} devices"
                            live.isNotEmpty() -> "Connected"
                            else -> "Not connected"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (live.isNotEmpty()) LiveGreen else scheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    )
                    val endsAt = live.maxOfOrNull { it.expiresAt } ?: 0L
                    if (endsAt > now) {
                        Text(
                            "· ends " + DateUtils.getRelativeTimeSpanString(endsAt, now, DateUtils.MINUTE_IN_MILLIS).toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    val when_ = if (live.isNotEmpty()) live.maxOf { it.lastSeen } else last
                    if (when_ != null) {
                        Text(
                            "· " + (if (live.isNotEmpty()) "active " else "last ") + relative(when_, now),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (live.isNotEmpty()) TextButton(onClick = onDisconnect) { Text("Disconnect") }
                TextButton(onClick = onLimit) { Text(if (limit == null) "Limit" else "Limited · ${limit.size}") }
            }
        }
    }
}

private fun relative(time: Long, now: Long): String =
    if (now - time < 45_000) "just now"
    else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/** A folder of videos on the phone. */
internal data class FolderInfo(val id: Long, val name: String, val count: Int)

internal fun queryFolders(context: android.content.Context): List<FolderInfo> {
    val found = LinkedHashMap<Long, FolderInfo>()
    runCatching {
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val old = found[id]
                found[id] = if (old == null) FolderInfo(id, c.getString(1) ?: "Videos", 1) else old.copy(count = old.count + 1)
            }
        }
    }
    return found.values.sortedBy { it.name.lowercase() }
}

/** Which videos the other screens can see: all of them, the videos of some folders (new ones included), or only some chosen ones. */
@Composable
fun LibrarySection(
    selection: ShareSelection,
    hasStorageAccess: Boolean,
    onAll: () -> Unit,
    onSelected: () -> Unit,
    onChoose: () -> Unit,
    onRemove: (Long) -> Unit,
    onPlay: (id: Long, name: String) -> Unit,
    onFoldersMode: () -> Unit,
    onSaveFolders: (Set<Long>) -> Unit,
    onAllowAccess: () -> Unit,
) {
    val context = LocalContext.current
    // A COUNT query is far cheaper than loading rows; it only runs when access is granted or changes.
    val total by produceState<Int?>(initialValue = null, hasStorageAccess) {
        value = if (!hasStorageAccess) null else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID), null, null, null,
                )?.use { it.count }
            }.getOrNull()
        }
    }
    var showFolders by rememberSaveable { mutableStateOf(false) }
    // The folders are only listed when they are shown (the mode is on, or the chooser is open).
    val needFolders = hasStorageAccess && (selection.mode == ShareMode.FOLDERS || showFolders)
    // null: still looking (or not needed yet); empty: looked, and there are none.
    val folders by produceState<List<FolderInfo>?>(initialValue = null, needFolders) {
        value = if (!needFolders) null else withContext(Dispatchers.IO) { queryFolders(context) }
    }
    val chosenFolders = folders.orEmpty().filter { it.id in selection.folders }
    val shared = when (selection.mode) {
        ShareMode.ALL -> total
        ShareMode.SELECTED -> selection.ids.size
        ShareMode.FOLDERS -> if (needFolders && folders == null) null else chosenFolders.sumOf { it.count }
    }

    if (showFolders) {
        FoldersDialog(folders, selection.folders, onDismiss = { showFolders = false }) { ids ->
            showFolders = false
            onSaveFolders(ids)
        }
    }

    CasterCard(high = false) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Videos to share", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !hasStorageAccess -> "Video access is off"
                        total == null || shared == null -> "Counting…"
                        selection.mode == ShareMode.ALL -> "All $total videos are shared"
                        else -> "$shared of $total videos shared"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!hasStorageAccess) TextButton(onClick = onAllowAccess) { Text("Allow video access") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selection.mode == ShareMode.ALL, onClick = onAll, label = { Text("All") }, modifier = Modifier.weight(1f))
                FilterChip(
                    selected = selection.mode == ShareMode.FOLDERS,
                    onClick = { if (!hasStorageAccess) onAllowAccess() else if (selection.folders.isEmpty()) showFolders = true else onFoldersMode() },
                    label = { Text("Folders") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(selected = selection.mode == ShareMode.SELECTED, onClick = onSelected, label = { Text("Only some") }, modifier = Modifier.weight(1f))
            }
            when (selection.mode) {
                ShareMode.SELECTED -> ChosenVideos(selection.ids, onChoose, onRemove, onPlay)
                ShareMode.FOLDERS -> Column(modifier = Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Shared folders · ${selection.folders.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showFolders = true }) { Text("Change") }
                    }
                    chosenFolders.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${folder.count} videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "New videos in these folders are shared automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ShareMode.ALL -> Unit
            }
        }
    }
}

/** Choose the folders whose videos are shared. */
@Composable
private fun FoldersDialog(folders: List<FolderInfo>?, saved: Set<Long>, onDismiss: () -> Unit, onSave: (Set<Long>) -> Unit) {
    var picked by rememberSaveable { mutableStateOf(saved.toList()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share these folders") },
        text = {
            if (folders == null) {
                Text("Looking for folders…", style = MaterialTheme.typography.bodyMedium)
            } else if (folders.isEmpty()) {
                Text("No videos were found on this phone.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    folders.forEach { folder ->
                        val on = folder.id in picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { picked = if (on) picked - folder.id else picked + folder.id }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(checked = on, onCheckedChange = null)
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${folder.count} videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        // Unticking every folder is allowed: then no folder is shared.
        confirmButton = { TextButton(onClick = { onSave(picked.toSet()) }) { Text(if (picked.isEmpty()) "Share none" else "Share ${picked.size}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private data class ChosenVideo(val id: Long, val name: String, val durationMs: Long, val size: Long)

/** How many chosen videos are listed before "Show all". */
private const val CHOSEN_SHOWN = 4

/**
 * The videos that were chosen with "Only some", listed right here: each with a thumbnail and a button to take it out, and one
 * "Add more" to choose others. Only the chosen ids are looked up (one query per 400), never the whole gallery.
 */
@Composable
private fun ChosenVideos(ids: Set<Long>, onAdd: () -> Unit, onRemove: (Long) -> Unit, onPlay: (Long, String) -> Unit) {
    val context = LocalContext.current
    val chosen by produceState<List<ChosenVideo>?>(initialValue = null, ids) {
        value = if (ids.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            runCatching {
                ids.toList().chunked(400).flatMap { part ->
                    // The ids are numbers, written straight into the query: nothing typed by anyone can end up in it.
                    context.contentResolver.query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(
                            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
                        ),
                        "${MediaStore.Video.Media._ID} IN (${part.joinToString(",")})", null,
                        "${MediaStore.Video.Media.DATE_ADDED} DESC",
                    )?.use { c ->
                        buildList {
                            while (c.moveToNext()) add(ChosenVideo(c.getLong(0), c.getString(1) ?: "video", c.getLong(2), c.getLong(3)))
                        }
                    }.orEmpty()
                }
            }.getOrDefault(emptyList())
        }
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val list = chosen.orEmpty()
    val scheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ids.isEmpty()) "No videos chosen yet" else "Chosen videos · ${ids.size}",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAdd) { Text(if (ids.isEmpty()) "Choose videos" else "Add more") }
        }
        if (ids.isNotEmpty()) {
            Text("Tap a video to play it on the connected Chromecast.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        val shown = if (expanded) list else list.take(CHOSEN_SHOWN)
        shown.forEach { video ->
            key(video.id) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.surfaceContainerLow)
                        .clickable { onPlay(video.id, video.name) } // plays it on the connected Chromecast
                        .padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Thumbnail(video.id)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(video.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            formatDuration(video.durationMs) + " · " + formatSize(video.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRemove(video.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove ${video.name}", tint = scheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (list.size > CHOSEN_SHOWN) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "Show fewer" else "Show all ${list.size}")
            }
        }
    }
}
