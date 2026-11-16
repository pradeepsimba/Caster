package mano.algo.caster.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import mano.algo.caster.server.ServerState
import mano.algo.caster.server.ShareSettings

@Composable
fun SettingsScreen(
    state: ServerState,
    preferredPort: Int,
    onSavePort: (Int) -> Unit,
    preferredName: String,
    onSaveName: (String) -> Unit,
    shareExtras: Boolean,
    onShareExtras: (Boolean) -> Unit,
    autoStopMinutes: Int,
    onAutoStop: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Re-seeded whenever the saved port changes, so Reset and external changes show up in the field.
    var text by rememberSaveable(preferredPort) { mutableStateOf(preferredPort.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && ShareSettings.isValidPort(parsed)
    val changed = parsed != preferredPort
    val focus = LocalFocusManager.current

    val error = when {
        text.isEmpty() -> "Enter a port number"
        parsed == null -> "Digits only"
        parsed < ShareSettings.MIN_PORT -> "Android doesn't allow ports below ${ShareSettings.MIN_PORT}"
        parsed > ShareSettings.MAX_PORT -> "Ports go up to ${ShareSettings.MAX_PORT}"
        else -> null
    }

    fun apply() {
        if (valid && changed) {
            onSavePort(parsed!!)
            focus.clearFocus()
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle("Settings", "Tune how Caster shares")

        CasterCard(high = false) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Port", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "The number at the end of the address, like :$preferredPort. If it's already in use, " +
                        "Caster automatically uses the next free one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(5) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Preferred port") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "Between ${ShareSettings.MIN_PORT} and ${ShareSettings.MAX_PORT}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { apply() }),
                    shape = RoundedCornerShape(14.dp),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::apply,
                        enabled = valid && changed,
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Apply") }
                    TextButton(
                        onClick = { text = ShareSettings.DEFAULT_PORT.toString(); onSavePort(ShareSettings.DEFAULT_PORT); focus.clearFocus() },
                        enabled = preferredPort != ShareSettings.DEFAULT_PORT || text != ShareSettings.DEFAULT_PORT.toString(),
                    ) { Text("Reset to ${ShareSettings.DEFAULT_PORT}") }
                }

                // What is happening right now, so the setting never feels like it silently did nothing.
                val status = when (state) {
                    is ServerState.Running -> when {
                        state.portChanged -> "Port ${state.requestedPort} was busy, so Caster is using ${state.port}."
                        else -> "Listening on port ${state.port}."
                    }
                    ServerState.Stopped -> "Saved. It takes effect the next time you start sharing."
                    ServerState.Starting -> "Starting…"
                    is ServerState.Error -> "Not running right now."
                }
                Text(status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (state is ServerState.Running) {
                    Text(
                        "Changing the port restarts sharing; connected screens may need to reconnect to the new address.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        NameCard(state, preferredName, onSaveName)

        CasterCard(high = false) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Photos and music", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Also show every photo and song on this phone to approved screens. Videos keep following the Library choice.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = shareExtras, onCheckedChange = onShareExtras)
                }
            }
        }

        CasterCard(high = false) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Auto-stop timer", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Stop sharing by itself after this long, counted from when sharing started. Saves battery if you forget.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0 to "Off", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours").forEach { (minutes, label) ->
                        FilterChip(
                            selected = autoStopMinutes == minutes,
                            onClick = { onAutoStop(minutes) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        CasterCard(high = false) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Caster shares your videos only over your own Wi-Fi. Every screen must be approved here first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DiagnosticsCard()
    }
}

@Composable
private fun NameCard(state: ServerState, preferredName: String, onSaveName: (String) -> Unit) {
    var text by rememberSaveable(preferredName) { mutableStateOf(preferredName) }
    val valid = ShareSettings.isValidHostname(text)
    val changed = text != preferredName
    val focus = LocalFocusManager.current

    val error = when {
        text.isEmpty() -> "Enter a name"
        text.startsWith('-') || text.endsWith('-') -> "Can't start or end with a hyphen"
        !valid -> "Letters, numbers and hyphens only"
        else -> null
    }

    fun apply() {
        if (valid && changed) {
            onSaveName(text)
            focus.clearFocus()
        }
    }

    CasterCard(high = false) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Device name", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "How other screens find this phone: http://$preferredName.local. If two phones on the same Wi-Fi " +
                    "share, give each its own name. A name that's already taken gets -2, -3 and so on added.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }.take(32) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name") },
                suffix = { Text(".local") },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: "Up to 32 characters") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                shape = RoundedCornerShape(14.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = ::apply,
                    enabled = valid && changed,
                    modifier = Modifier.height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Apply") }
                TextButton(
                    onClick = { text = ShareSettings.DEFAULT_HOSTNAME; onSaveName(ShareSettings.DEFAULT_HOSTNAME); focus.clearFocus() },
                    enabled = preferredName != ShareSettings.DEFAULT_HOSTNAME || text != ShareSettings.DEFAULT_HOSTNAME,
                ) { Text("Reset to ${ShareSettings.DEFAULT_HOSTNAME}") }
            }

            val status = when (state) {
                is ServerState.Running -> when {
                    state.announcedName == null -> "Couldn't announce a name on this network. Use the IP address instead."
                    state.nameChanged -> "${state.requestedName}.local is already used on this network, so this phone is ${state.announcedName}."
                    else -> "This phone is reachable as ${state.announcedName}."
                }
                ServerState.Stopped -> "Saved. It takes effect when you start sharing."
                ServerState.Starting -> "Starting…"
                is ServerState.Error -> "Not running right now."
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}
