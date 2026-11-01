package mano.algo.caster.ui

import android.provider.MediaStore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mano.algo.caster.server.ShareMode
import mano.algo.caster.server.ShareSelection

@Composable
fun LibraryScreen(
    selection: ShareSelection,
    hasStorageAccess: Boolean,
    onAll: () -> Unit,
    onSelected: () -> Unit,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
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

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle("Library", "Choose what the other screens can see")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("On this phone", total?.toString() ?: "–", "videos", Modifier.weight(1f))
            StatCard("Shared", shared?.toString() ?: "–", if (selection.mode == ShareMode.ALL) "all of them" else "selected", Modifier.weight(1f))
        }

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What to share", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ScopeOption(
                    selected = selection.mode == ShareMode.ALL,
                    title = "All videos",
                    subtitle = "Everything in your gallery, including new videos",
                    onClick = onAll,
                )
                ScopeOption(
                    selected = selection.mode == ShareMode.SELECTED,
                    title = "Selected videos",
                    subtitle = when (selection.ids.size) {
                        0 -> "Pick only the videos you want to share"
                        1 -> "1 video chosen"
                        else -> "${selection.ids.size} videos chosen"
                    },
                    onClick = onSelected,
                )
                if (selection.mode == ShareMode.SELECTED) {
                    FilledTonalButton(
                        onClick = onChoose,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(if (selection.ids.isEmpty()) "Choose videos" else "Change selection") }
                }
            }
        }

        Text(
            "Changes apply immediately. Connected screens just need to tap refresh.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.alpha(0.75f))
            Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.75f))
        }
    }
}

@Composable
private fun ScopeOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) scheme.primaryContainer else scheme.surface,
        border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.alpha(0.8f))
            }
        }
    }
}
