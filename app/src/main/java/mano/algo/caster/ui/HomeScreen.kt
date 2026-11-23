package mano.algo.caster.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mano.algo.caster.server.ActivityTracker
import mano.algo.caster.server.NowWatching
import mano.algo.caster.server.PairRequest
import mano.algo.caster.server.RemoteControl
import mano.algo.caster.server.RemoteState
import mano.algo.caster.server.ServerState
import mano.algo.caster.ui.theme.LiveGreen
import mano.algo.caster.ui.theme.WarnAmber
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import mano.algo.caster.server.PairedDevice
import mano.algo.caster.server.ShareSelection

/**
 * The Home page, kept to what matters: whether sharing is on, one big button, how many screens are watching, and the
 * Chromecast. The address and the QR code are one tap away behind "Connect a screen", not on the page.
 */
@Composable
fun HomeScreen(
    state: ServerState,
    request: PairRequest?,
    batteryUnrestricted: Boolean,
    warnings: List<SetupWarning>,
    devices: List<PairedDevice>,
    selection: ShareSelection,
    hasStorage: Boolean,
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
    onCastChoose: () -> Unit,
    onDisconnect: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    onAll: () -> Unit,
    onSelected: () -> Unit,
    onChoose: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val running = state is ServerState.Running
    val screens = devices.size
    var showConnect by remember { mutableStateOf(false) }
    LaunchedEffect(running) { if (!running) showConnect = false } // a closed-and-reopened share starts without the dialog
    if (showConnect && state is ServerState.Running) {
        ConnectScreenDialog(state, onCopy = onCopy, onShare = onShare) { showConnect = false }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Caster", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            when (state) {
                is ServerState.Running -> StatusPill("Live", LiveGreen, pulsing = true)
                is ServerState.Error -> StatusPill("Problem", scheme.error, pulsing = false)
                ServerState.Starting -> StatusPill("Starting", WarnAmber, pulsing = true)
                ServerState.Stopped -> StatusPill("Off", scheme.outline, pulsing = false)
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = scheme.onSurfaceVariant)
            }
        }

        // A pending request goes first: it is the thing the user has to act on right now.
        // (Only composed while there is a request: an always-present empty slot would still add a gap above the card.)
        if (running && request != null) {
            AnimatedVisibility(
                visibleState = remember { MutableTransitionState(false).apply { targetState = true } },
                enter = expandVertically() + fadeIn(),
            ) { PairingCard(request, onApprove, onDeny) }
        }

        // The big card: a deep gradient while sharing, so "live" is obvious at a glance; soft and plain when off.
        val background: Brush = when (state) {
            is ServerState.Running -> HeroBrush
            is ServerState.Error -> SolidColor(scheme.errorContainer)
            else -> Brush.verticalGradient(listOf(scheme.primary.copy(alpha = 0.14f), scheme.surfaceContainerLow))
        }
        Card(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            shape = CardShape,
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent,
                contentColor = when (state) {
                    is ServerState.Running -> Color.White
                    is ServerState.Error -> scheme.onErrorContainer
                    else -> scheme.onSurface
                },
            ),
            border = if (running) null else BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().background(background).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // The logo, a title and one line, side by side: the card stays small.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PulsingLogo(rings = running, ringColor = Color.White, size = 84.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            when (state) {
                                is ServerState.Running -> "Sharing is on"
                                is ServerState.Error -> "Can't share right now"
                                ServerState.Starting -> "Starting…"
                                ServerState.Stopped -> "Share your videos"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when (state) {
                                is ServerState.Running -> when (screens) {
                                    0 -> "Ready for a screen to connect"
                                    1 -> "1 screen is connected"
                                    else -> "$screens screens are connected"
                                }
                                is ServerState.Error -> state.message
                                ServerState.Starting -> "Getting ready…"
                                ServerState.Stopped -> "On any TV or computer"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.alpha(0.85f),
                        )
                    }
                }

                when (state) {
                    ServerState.Stopped, is ServerState.Error -> {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start sharing", style = MaterialTheme.typography.titleSmall)
                        }
                        // After an error (Wi-Fi lost, say) the service may still be alive and holding the phone awake: it can be stopped here.
                        if (state is ServerState.Error) {
                            TextButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop sharing") }
                        }
                    }
                    is ServerState.Running -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { showConnect = true },
                                modifier = Modifier.weight(1.6f).height(46.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF1E3A8A)),
                            ) { Text("Connect a screen", style = MaterialTheme.typography.titleSmall) }
                            GlassButton("Stop", onStop, Modifier.weight(1f), icon = Icons.Filled.Close)
                        }
                    }
                    ServerState.Starting -> {
                        FilledTonalButton(
                            onClick = onStop,
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Cancel", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }

        ConnectedScreensSection(devices, running, onDisconnect, onDisconnectAll)

        LibrarySection(selection, hasStorage, onAll, onSelected, onChoose)

        if (running) CastCard(onChooseVideo = onCastChoose)

        if (running && (watching.isNotEmpty() || remotes.isNotEmpty())) NowWatchingCard(watching, remotes, onRemote)

        warnings.forEach { SetupWarningCard(it) }
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

    CasterCard(high = true) {
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
