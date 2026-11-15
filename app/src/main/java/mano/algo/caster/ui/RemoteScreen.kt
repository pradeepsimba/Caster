package mano.algo.caster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mano.algo.caster.tv.TvKey
import mano.algo.caster.tv.TvRemote

/** The Material "settings remote" glyph, which the core icon set doesn't include. */
val RemoteIcon: ImageVector by lazy {
    ImageVector.Builder("Remote", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(
                "M15,9H9c-0.55,0 -1,0.45 -1,1v12c0,0.55 0.45,1 1,1h6c0.55,0 1,-0.45 1,-1V10c0,-0.55 -0.45,-1 -1,-1z" +
                    "M12,15c-0.83,0 -1.5,-0.67 -1.5,-1.5S11.17,12 12,12s1.5,0.67 1.5,1.5S12.83,15 12,15z" +
                    "M7.05,6.05l1.41,1.41C9.37,6.56 10.62,6 12,6s2.63,0.56 3.54,1.46l1.41,-1.41C15.68,4.78 13.93,4 12,4S8.32,4.78 7.05,6.05z" +
                    "M12,0C8.96,0 6.21,1.23 4.22,3.22l1.41,1.41C7.26,3.01 9.51,2 12,2s4.74,1.01 6.36,2.64l1.41,-1.41C17.79,1.23 15.04,0 12,0z",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

/** Phone-as-remote for a Fire TV: connect once, then use the buttons like the real remote. */
@Composable
fun RemoteScreen(
    state: TvRemote.State,
    savedHost: String,
    found: List<String>,
    scanning: Boolean,
    shareUrl: String?,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
    onKey: (code: Int, repeating: Boolean) -> Unit,
    onTyped: (old: String, new: String) -> Unit,
    onOpenCaster: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle("TV remote", "Control a Fire TV from this phone.")
        if (!TvRemote.supported) {
            EmptyState("Needs Android 8 or newer", "The TV remote can't run on this version of Android.")
            return@Column
        }
        ConnectionCard(state, savedHost, found, scanning, onConnect, onDisconnect, onScan)
        if (state is TvRemote.State.Connected) {
            RemotePad(onKey, onTyped, shareUrl, onOpenCaster)
        }
    }
}

@Composable
private fun ConnectionCard(
    state: TvRemote.State,
    savedHost: String,
    found: List<String>,
    scanning: Boolean,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
) {
    var host by rememberSaveable(savedHost) { mutableStateOf(savedHost) }
    val connected = state is TvRemote.State.Connected
    val connecting = state is TvRemote.State.Connecting

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                is TvRemote.State.Connected -> StatusPill("Connected to ${state.host}", Color(0xFF22C55E), pulsing = true)
                is TvRemote.State.Connecting -> StatusPill("Connecting…", MaterialTheme.colorScheme.primary, pulsing = true)
                else -> StatusPill("Not connected", MaterialTheme.colorScheme.outline, pulsing = false)
            }
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("TV address") },
                placeholder = { Text("192.168.0.50") },
                singleLine = true,
                enabled = !connected && !connecting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), // has the dot and the colon of 192.168.0.50:5555
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (connected || connecting) {
                    OutlinedButton(onClick = onDisconnect, shape = RoundedCornerShape(14.dp)) {
                        Text(if (connecting) "Cancel" else "Disconnect")
                    }
                } else {
                    Button(onClick = { onConnect(host) }, shape = RoundedCornerShape(14.dp)) { Text("Connect") }
                    OutlinedButton(onClick = onScan, enabled = !scanning, shape = RoundedCornerShape(14.dp)) {
                        Text(if (scanning) "Searching…" else "Find on network")
                    }
                }
            }
            if (!connected && !connecting) {
                found.forEach { address ->
                    TextButton(onClick = { host = address; onConnect(address) }) { Text("Use $address") }
                }
                if (found.isEmpty() && !scanning) {
                    // Shown after a search that found nothing, and as the first-time help.
                    Text(
                        "First time: on the Fire TV open Settings → My Fire TV → Developer options and turn on ADB debugging. " +
                            "No Developer options? Open About, then press OK on the device name 7 times.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (connecting) {
                Text(
                    "Look at the TV and choose Allow (tick \"Always allow\" so it doesn't ask again).",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            (state as? TvRemote.State.Failed)?.let {
                Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RemotePad(
    onKey: (Int, Boolean) -> Unit,
    onTyped: (String, String) -> Unit,
    shareUrl: String?,
    onOpenCaster: (String) -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(18.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer72()
                PadButton("Up", TvKey.UP, onKey, repeat = true) { Text("▲", style = MaterialTheme.typography.titleLarge) }
                Spacer72()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadButton("Left", TvKey.LEFT, onKey, repeat = true) { Text("◀", style = MaterialTheme.typography.titleLarge) }
                PadButton("OK", TvKey.OK, onKey, accent = true) { Text("OK", fontWeight = FontWeight.Bold) }
                PadButton("Right", TvKey.RIGHT, onKey, repeat = true) { Text("▶", style = MaterialTheme.typography.titleLarge) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer72()
                PadButton("Down", TvKey.DOWN, onKey, repeat = true) { Text("▼", style = MaterialTheme.typography.titleLarge) }
                Spacer72()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadButton("Back", TvKey.BACK, onKey) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                PadButton("Home", TvKey.HOME, onKey) { Icon(Icons.Filled.Home, contentDescription = null) }
                PadButton("Menu", TvKey.MENU, onKey) { Text("≡", style = MaterialTheme.typography.titleLarge) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadButton("Rewind", TvKey.REWIND, onKey, repeat = true) { Text("⏪") }
                PadButton("Play or pause", TvKey.PLAY_PAUSE, onKey) { Text("⏯") }
                PadButton("Fast forward", TvKey.FAST_FORWARD, onKey, repeat = true) { Text("⏩") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadButton("Volume down", TvKey.VOLUME_DOWN, onKey, repeat = true) { Text("Vol −") }
                PadButton("Mute", TvKey.MUTE, onKey) { Text("Mute") }
                PadButton("Volume up", TvKey.VOLUME_UP, onKey, repeat = true) { Text("Vol +") }
            }
        }
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Open Caster on the TV", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (shareUrl != null) {
                Text(
                    "Opens $shareUrl in the TV's browser, so nobody has to type the address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { onOpenCaster(shareUrl) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("Send to TV")
                }
            } else {
                Text(
                    "Start sharing on the Home tab first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Keyboard", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Select a search box on the TV, then type here: each letter goes to the TV as you type it. " +
                    "Letters the TV can't take (accents, emoji) are skipped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { new ->
                    onTyped(typed, new)
                    typed = new
                },
                placeholder = { Text("Type here") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false, // a correction would rewrite letters already sent to the TV
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = {
                    onKey(TvKey.ENTER, false)
                    typed = ""
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    // Keep the box and the TV in step: remove the last letter in both, or just the TV's if the box is empty.
                    if (typed.isEmpty()) onKey(TvKey.DELETE, false) else {
                        val shorter = typed.dropLast(1)
                        onTyped(typed, shorter)
                        typed = shorter
                    }
                }, shape = RoundedCornerShape(14.dp)) { Text("⌫ Delete") }
                Button(onClick = { onKey(TvKey.ENTER, false); typed = "" }, shape = RoundedCornerShape(14.dp)) { Text("Enter") }
            }
        }
    }
}

@Composable
private fun Spacer72() = Box(Modifier.size(width = 72.dp, height = 56.dp))

/**
 * One remote button. A tap sends the key; with [repeat], holding it keeps sending (for moving through menus,
 * volume and seeking). The sound and feel are light on purpose: the phone should feel like a remote.
 */
@Composable
private fun PadButton(
    label: String,
    keyCode: Int,
    onKey: (Int, Boolean) -> Unit,
    repeat: Boolean = false,
    accent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val container = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val onContainer = if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 56.dp)
            .background(container, RoundedCornerShape(16.dp))
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick(label) { onKey(keyCode, false); true }
            }
            .pointerInput(keyCode, repeat) {
                detectTapGestures(
                    onPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onKey(keyCode, false)
                        val held = if (repeat) scope.launch {
                            delay(450)
                            while (true) {
                                onKey(keyCode, true)
                                delay(130)
                            }
                        } else null
                        tryAwaitRelease()
                        held?.cancel()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides onContainer,
        ) { content() }
    }
}
