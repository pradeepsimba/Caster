package mano.algo.caster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mano.algo.caster.server.ActivityTracker
import mano.algo.caster.server.NowWatching
import mano.algo.caster.server.PairRequest
import mano.algo.caster.server.RemoteControl
import mano.algo.caster.server.RemoteState
import mano.algo.caster.server.ServerState

@Composable
fun HomeScreen(
    state: ServerState,
    request: PairRequest?,
    batteryUnrestricted: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: (String) -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAllowBackground: () -> Unit,
    onAppSettings: () -> Unit,
    watching: List<NowWatching>,
    remotes: List<RemoteState>,
    onRemote: (deviceId: String, command: String) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val running = state is ServerState.Running
    var qrUrl by remember { mutableStateOf<String?>(null) }
    qrUrl?.let { QrDialog(it) { qrUrl = null } }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Caster", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            when (state) {
                is ServerState.Running -> StatusPill("Live", Color(0xFF22C55E), pulsing = true)
                is ServerState.Error -> StatusPill("Problem", scheme.error, pulsing = false)
                ServerState.Starting -> StatusPill("Starting", Color(0xFFF59E0B), pulsing = true)
                ServerState.Stopped -> StatusPill("Off", scheme.outline, pulsing = false)
            }
        }

        // A pending request goes first: it is the thing the user has to act on right now.
        if (running && request != null) PairingCard(request, onApprove, onDeny)

        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (state) {
                    is ServerState.Running -> scheme.primaryContainer
                    is ServerState.Error -> scheme.errorContainer
                    else -> scheme.surfaceContainerHigh
                },
                contentColor = when (state) {
                    is ServerState.Running -> scheme.onPrimaryContainer
                    is ServerState.Error -> scheme.onErrorContainer
                    else -> scheme.onSurface
                },
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PulsingLogo(rings = running)
                Text(
                    when (state) {
                        is ServerState.Running -> "Sharing is on"
                        is ServerState.Error -> "Can't share right now"
                        ServerState.Starting -> "Starting…"
                        ServerState.Stopped -> "Ready to share"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when (state) {
                        is ServerState.Running -> "Open this on your TV or computer"
                        is ServerState.Error -> state.message
                        ServerState.Starting -> "Getting your Wi-Fi address ready…"
                        ServerState.Stopped -> "Stream the videos on this phone to any screen on your Wi-Fi. Nothing leaves your home network."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(0.85f),
                )

                if (state is ServerState.Running) {
                    // On a hotspot the name stops resolving once the screen turns off, the IP keeps working.
                    val primaryUrl = if (state.hotspot) state.baseUrl else state.nameUrl ?: state.baseUrl
                    AddressBox(primaryUrl, onShare = { onShare(primaryUrl) }, onQr = { qrUrl = primaryUrl }) { onCopy(primaryUrl) }
                    if (state.hotspot) {
                        Text(
                            "You're sharing from your hotspot. Use this IP address: the name stops working when the screen is off.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.alpha(0.8f),
                        )
                    } else if (state.nameUrl != null) {
                        Text(
                            "Name not opening? Android and Fire TV can't resolve it. Use the IP address:",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.alpha(0.8f),
                        )
                        AddressBox(state.baseUrl, compact = true, onQr = { qrUrl = state.baseUrl }) { onCopy(state.baseUrl) }
                    }
                    if (state.nameChanged) {
                        Text(
                            "${state.requestedName}.local was already used on this network, so this phone is ${state.announcedName}.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (state.portChanged) {
                        Text(
                            "Port ${state.requestedPort} was busy, so Caster is using ${state.port}. You can change this in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Text(
                        "Both devices must be on the same Wi-Fi. You'll approve each new connection here.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alpha(0.8f),
                    )
                }

                val buttonModifier = Modifier.fillMaxWidth().height(54.dp)
                if (state is ServerState.Stopped || state is ServerState.Error) {
                    Button(onClick = onStart, modifier = buttonModifier, shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start sharing", style = MaterialTheme.typography.titleSmall)
                    }
                } else {
                    FilledTonalButton(
                        onClick = onStop,
                        modifier = buttonModifier,
                        shape = RoundedCornerShape(16.dp),
                        colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                            containerColor = scheme.surface.copy(alpha = 0.7f),
                            contentColor = scheme.onSurface,
                        ),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop sharing", style = MaterialTheme.typography.titleSmall)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        if (running && (watching.isNotEmpty() || remotes.isNotEmpty())) NowWatchingCard(watching, remotes, onRemote)

        if (!batteryUnrestricted) BatteryCard(onAllowBackground, onAppSettings)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun NowWatchingCard(entries: List<NowWatching>, remotes: List<RemoteState>, onRemote: (String, String) -> Unit) {
    // The ticker lives here, so nothing recomposes every few seconds unless somebody is actually watching.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }
    val active = entries.filter { now - it.lastSeen < ActivityTracker.ACTIVE_WINDOW_MS }
    // A page that reports its state is the more precise source: it knows about pauses, photos and music too.
    val live = remotes.filter { now - it.reportedAt < RemoteControl.FRESH_MS * 2 }
    val liveIds = live.map { it.deviceId }.toSet()
    val passive = active.filter { it.deviceId !in liveIds }
    if (passive.isEmpty() && live.isEmpty()) return

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(Color(0xFF22C55E), pulsing = true)
                Text("Now watching", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            live.forEach { r -> RemoteRow(r, onRemote) }
            passive.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.deviceLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            entry.videoName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteRow(r: RemoteState, onRemote: (String, String) -> Unit) {
    fun clock(sec: Int) =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
        else "%d:%02d".format(sec / 60, sec % 60)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(r.deviceLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            r.title.ifBlank { "Playing" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (r.durationSec > 0) {
            // While the finger is down the slider shows the finger; the screen only jumps when it is released.
            var dragging by remember { mutableStateOf<Float?>(null) }
            // After release, keep showing the chosen spot until the screen has had time to report the new one.
            var sent by remember { mutableStateOf<Float?>(null) }
            LaunchedEffect(sent) { if (sent != null) { delay(2_500); sent = null } }
            val shown = dragging ?: sent ?: r.positionSec.toFloat()
            androidx.compose.material3.Slider(
                value = shown.coerceIn(0f, r.durationSec.toFloat()),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onRemote(r.deviceId, "seekto:${it.toInt()}"); sent = it }
                    dragging = null
                },
                valueRange = 0f..r.durationSec.toFloat(),
            )
            Text("${clock(shown.toInt())} / ${clock(r.durationSec)}", style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.TextButton(onClick = { onRemote(r.deviceId, "seek:-10") }) { Text("-10s") }
            FilledTonalButton(onClick = { onRemote(r.deviceId, "toggle") }) { Text(if (r.playing) "Pause" else "Play") }
            androidx.compose.material3.TextButton(onClick = { onRemote(r.deviceId, "seek:10") }) { Text("+10s") }
            androidx.compose.material3.TextButton(onClick = { onRemote(r.deviceId, "next") }) { Text("Next") }
        }
    }
}
