package mano.algo.caster.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mano.algo.caster.CrashLog

/** Shows why the app closed, if it did, with a button to copy the full record. */
@Composable
fun DiagnosticsCard() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("If the app keeps closing", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (report.isBlank()) {
                Text(
                    "Nothing recorded yet. If Caster closes by itself, open this page again: it keeps the reason.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    report.takeLast(900),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (report.contains("o-kill", ignoreCase = true) || report.contains("POWER SAVING")) {
                    Text(
                        "Your phone's own battery manager is closing Caster, not a crash. Open the app's settings → Battery, " +
                            "allow background activity and auto-start, and lock Caster in the recent apps list.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }, shape = RoundedCornerShape(14.dp)) { Text("Open app settings") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Caster report", report))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }, shape = RoundedCornerShape(14.dp)) { Text("Copy report") }
                    TextButton(onClick = { CrashLog.clear(context); report = "" }) { Text("Clear") }
                }
            }
        }
    }
}
