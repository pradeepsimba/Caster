package mano.algo.caster.ui

import android.provider.MediaStore
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.FilledTonalButton
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

/** The screens connected right now, each with a Disconnect. Hidden while nothing is shared and nobody is connected. */
@Composable
fun ConnectedScreensSection(
    devices: List<PairedDevice>,
    running: Boolean,
    onDisconnect: (String) -> Unit,
    onDisconnectAll: () -> Unit,
) {
    if (devices.isEmpty() && !running) return
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
                if (devices.isEmpty()) "Connected screens" else "Connected screens · ${devices.size}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (devices.size > 1) TextButton(onClick = onDisconnectAll) { Text("Disconnect all") }
        }
        if (devices.isEmpty()) {
            Text(
                "None yet. Tap \"Connect a screen\", open the address on your TV or computer, then approve it here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            devices.forEach { device -> ConnectedScreenRow(device, now, onDisconnect) }
        }
    }
}

@Composable
private fun ConnectedScreenRow(device: PairedDevice, now: Long, onDisconnect: (String) -> Unit) {
    CasterCard(high = true) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(device.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    "Active " + relative(device.lastSeen, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onDisconnect(device.id) }) { Text("Disconnect") }
        }
    }
}

private fun relative(time: Long, now: Long): String =
    if (now - time < 45_000) "just now"
    else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/** Which videos the other screens can see: all of them, or only the ones chosen. */
@Composable
fun LibrarySection(
    selection: ShareSelection,
    hasStorageAccess: Boolean,
    onAll: () -> Unit,
    onSelected: () -> Unit,
    onChoose: () -> Unit,
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
    val shared = if (selection.mode == ShareMode.ALL) total else selection.ids.size

    CasterCard(high = false) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Videos to share", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !hasStorageAccess -> "Video access is off"
                        total == null -> "Counting…"
                        else -> "${shared ?: 0} of $total videos shared"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selection.mode == ShareMode.ALL,
                    onClick = onAll,
                    label = { Text("All videos") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = selection.mode == ShareMode.SELECTED,
                    onClick = onSelected,
                    label = { Text("Only some") },
                    modifier = Modifier.weight(1f),
                )
            }
            if (selection.mode == ShareMode.SELECTED) {
                FilledTonalButton(
                    onClick = onChoose,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(if (selection.ids.isEmpty()) "Choose videos" else "Change selection") }
            }
        }
    }
}
