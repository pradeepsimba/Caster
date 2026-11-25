package mano.algo.caster.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import mano.algo.caster.tv.TvKey
import mano.algo.caster.tv.TvRemote
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.clickable
import mano.algo.caster.tv.WakeOnLan
import mano.algo.caster.cast.CastController
import mano.algo.caster.server.RemoteControl
import mano.algo.caster.server.RemoteState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import mano.algo.caster.ui.theme.LiveGreen

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

val SkipPreviousIcon: ImageVector by lazy {
    ImageVector.Builder("SkipPrevious", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M6,6h2v12H6zm3.5,6l8.5,6V6z"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

val SkipNextIcon: ImageVector by lazy {
    ImageVector.Builder("SkipNext", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

val PauseIcon: ImageVector by lazy {
    ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M6,19h4V5H6v14zm8,-14v14h4V5h-4z"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

/** Phone-as-remote for a Fire TV, Android TV, Samsung or LG TV: connect once, then use the buttons like the real remote. */
@Composable
fun RemoteScreen(
    state: TvRemote.State,
    savedHost: String,
    found: List<TvRemote.FoundDevice>,
    saved: List<TvRemote.SavedTv>,
    scanning: Boolean,
    shareUrl: String?,
    remotes: List<RemoteState> = emptyList(),
    onRemote: (String, String) -> Unit = { _, _ -> },
    onWake: (address: String, mac: String?) -> Unit,
    onForget: (String) -> Unit,
    onConnect: (String) -> Unit,
    onConnectAdb: (String) -> Unit,
    onSubmitPin: (String) -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
    onOpenBrowser: () -> Unit,
    volume: TvRemote.VolumeState,
    onEnableVolume: () -> Unit,
    onKey: (code: Int, repeating: Boolean) -> Unit,
    onTyped: (old: String, new: String) -> Unit,
    onOpenCaster: (String) -> Unit,
    voiceError: String?,
    onVoiceStart: () -> Boolean,
    onVoiceEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val castState by CastController.state.collectAsStateWithLifecycle()

    // Automatically connect to the saved TV if remote is opened while idle
    LaunchedEffect(savedHost) {
        if (state is TvRemote.State.Idle && savedHost.isNotBlank()) {
            onConnect(savedHost)
        }
    }

    // Automatically scan for smart TVs on the local Wi-Fi in the background
    LaunchedEffect(Unit) {
        if (state is TvRemote.State.Idle && found.isEmpty() && !scanning) {
            onScan()
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle("Remote", "Control connected devices and playback from this phone.")

        // 1. Any active Chromecast remote
        if (castState.device != null) {
            ChromecastRemoteCard(
                state = castState,
                onPrev = { CastController.prev() },
                onNext = { CastController.next() },
                onTogglePlay = { CastController.togglePlayback() },
                onStop = { CastController.stop() },
            )
        }

        // 2. Any active Paired Web Screen / Device remotes
        val now = System.currentTimeMillis()
        val liveRemotes = remotes.filter { now - it.reportedAt < RemoteControl.FRESH_MS * 2 }
        liveRemotes.forEach { r ->
            WebDeviceRemoteCard(remote = r, onRemote = onRemote)
        }

        if (!TvRemote.supported) {
            EmptyState("Needs Android 8 or newer", "The TV remote can't run on this version of Android.")
            return@Column
        }

        // 3. Smart TV connection & remote
        ConnectionCard(state, savedHost, found, saved, scanning, onWake, onForget, onConnect, onConnectAdb, onSubmitPin, onDisconnect, onScan)
        if (state is TvRemote.State.Connected) {
            RemotePad(state.link, volume, onEnableVolume, onKey, onTyped, shareUrl, onOpenCaster, onOpenBrowser, voiceError, onVoiceStart, onVoiceEnd)
        }
    }
}

@Composable
fun ChromecastRemoteCard(
    state: CastController.State,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val kindLabel = when (state.kind) {
        "i" -> "Slide"
        "a" -> "Track"
        else -> "Video"
    }
    CasterCard(high = true) {
        Column(
            modifier = Modifier.padding(18.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(42.dp).background(LiveGreen.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(CastIcon, contentDescription = null, tint = LiveGreen, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.device ?: "Chromecast",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (state.totalCount > 1) "$kindLabel ${state.currentIndex + 1} of ${state.totalCount}"
                        else "Casting $kindLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onStop) {
                    Text("Disconnect")
                }
            }

            state.title?.let { title ->
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            state.error?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onPrev,
                    shape = CircleShape,
                    modifier = Modifier.size(54.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Icon(SkipPreviousIcon, contentDescription = "Previous $kindLabel")
                }

                Button(
                    onClick = onTogglePlay,
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Icon(
                        if (state.playing) PauseIcon else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                    )
                }

                OutlinedButton(
                    onClick = onNext,
                    shape = CircleShape,
                    modifier = Modifier.size(54.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Icon(SkipNextIcon, contentDescription = "Next $kindLabel")
                }
            }
        }
    }
}

@Composable
fun WebDeviceRemoteCard(
    remote: RemoteState,
    onRemote: (String, String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    fun clock(sec: Int) =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
        else "%d:%02d".format(sec / 60, sec % 60)

    CasterCard(high = true) {
        Column(
            modifier = Modifier.padding(18.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(42.dp).background(LiveGreen.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(RemoteIcon, contentDescription = null, tint = LiveGreen, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        remote.deviceLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (remote.playing) "Playing on screen" else "Paused on screen",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (remote.playing) LiveGreen else scheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onRemote(remote.deviceId, "stop") }) {
                    Text("Stop")
                }
            }

            if (remote.title.isNotBlank()) {
                Text(
                    remote.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (remote.durationSec > 0) {
                var dragging by remember(remote.deviceId) { mutableStateOf<Float?>(null) }
                var sent by remember(remote.deviceId) { mutableStateOf<Float?>(null) }
                LaunchedEffect(sent) { if (sent != null) { delay(2_500); sent = null } }
                val shown = dragging ?: sent ?: remote.positionSec.toFloat()

                Slider(
                    value = shown.coerceIn(0f, remote.durationSec.toFloat()),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { onRemote(remote.deviceId, "seekto:${it.toInt()}"); sent = it }
                        dragging = null
                    },
                    valueRange = 0f..remote.durationSec.toFloat(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(clock(shown.toInt()), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    Text(clock(remote.durationSec), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onRemote(remote.deviceId, "seek:-10") },
                    shape = CircleShape,
                    modifier = Modifier.size(54.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("-10s", style = MaterialTheme.typography.labelMedium)
                }

                Button(
                    onClick = { onRemote(remote.deviceId, "toggle") },
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Icon(
                        if (remote.playing) PauseIcon else Icons.Filled.PlayArrow,
                        contentDescription = if (remote.playing) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                    )
                }

                OutlinedButton(
                    onClick = { onRemote(remote.deviceId, "seek:10") },
                    shape = CircleShape,
                    modifier = Modifier.size(54.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("+10s", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = { onRemote(remote.deviceId, "next") },
                    shape = CircleShape,
                    modifier = Modifier.size(54.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Icon(SkipNextIcon, contentDescription = "Next")
                }
            }

            if (remote.volume >= 0) {
                var volDrag by remember(remote.deviceId) { mutableStateOf<Float?>(null) }
                var volSent by remember(remote.deviceId) { mutableStateOf<Float?>(null) }
                LaunchedEffect(volSent) { if (volSent != null) { delay(2_500); volSent = null } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                    Slider(
                        value = (volDrag ?: volSent ?: remote.volume.toFloat()).coerceIn(0f, 100f),
                        onValueChange = { volDrag = it },
                        onValueChangeFinished = {
                            volDrag?.let { onRemote(remote.deviceId, "volume:${"%.2f".format(java.util.Locale.US, it / 100f)}"); volSent = it }
                            volDrag = null
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: TvRemote.State,
    savedHost: String,
    found: List<TvRemote.FoundDevice>,
    saved: List<TvRemote.SavedTv>,
    scanning: Boolean,
    onWake: (address: String, mac: String?) -> Unit,
    onForget: (String) -> Unit,
    onConnect: (String) -> Unit,
    onConnectAdb: (String) -> Unit,
    onSubmitPin: (String) -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
) {
    val connected = state is TvRemote.State.Connected
    val connecting = state is TvRemote.State.Connecting
    val needsPin = state as? TvRemote.State.NeedsPin
    val androidTvCode = needsPin?.androidTv == true
    val pinLength = if (androidTvCode) 6 else 4
    var pin by rememberSaveable(needsPin != null) { mutableStateOf("") }

    // When connected to the TV, show a clean compact status banner instead of manual IP entry
    if (connected) {
        val connectedLink = (state as? TvRemote.State.Connected)?.link
        val host = (state as? TvRemote.State.Connected)?.host.orEmpty()
        CasterCard(high = false) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.size(10.dp).background(LiveGreen, CircleShape))
                    Column {
                        Text(
                            when (connectedLink) {
                                TvRemote.Link.FireTv -> "Fire TV"
                                TvRemote.Link.AndroidTv -> "Android TV"
                                TvRemote.Link.Samsung -> "Samsung"
                                TvRemote.Link.Lg -> "LG"
                                TvRemote.Link.Adb -> "Android TV (ADB)"
                                null -> "Smart TV"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = onDisconnect) {
                    Text("Disconnect")
                }
            }
        }
        return
    }

    val primaryTv = saved.firstOrNull() ?: if (savedHost.isNotBlank()) TvRemote.SavedTv(savedHost, null, null) else null
    var showManualDialog by rememberSaveable { mutableStateOf(false) }
    var manualHost by rememberSaveable { mutableStateOf(savedHost) }
    val busy = connecting || needsPin != null

    var macFor by rememberSaveable { mutableStateOf<String?>(null) }
    macFor?.let { address ->
        var mac by rememberSaveable(address) { mutableStateOf(saved.firstOrNull { it.address == address }?.mac.orEmpty()) }
        val valid = WakeOnLan.normalize(mac) != null
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { macFor = null },
            title = { Text("Turn on $address") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Type the TV's MAC address once (in the TV's network settings, like aa:bb:cc:dd:ee:ff). " +
                            "The TV must allow being turned on from the network (Wake-on-LAN).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = mac,
                        onValueChange = { mac = it.take(17) },
                        label = { Text("MAC address") },
                        singleLine = true,
                        isError = mac.isNotEmpty() && !valid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onWake(address, mac); macFor = null }, enabled = valid) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { macFor = null }) { Text("Cancel") } },
        )
    }

    if (showManualDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showManualDialog = false },
            title = { Text("Connect TV by IP") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "If your TV was not automatically detected on Wi-Fi, enter its IP address from the TV's network settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it.trim() },
                        label = { Text("TV IP address") },
                        placeholder = { Text("192.168.1.50") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (TvRemote.adbSupported) {
                        TextButton(
                            onClick = {
                                onConnectAdb(manualHost)
                                showManualDialog = false
                            },
                            enabled = manualHost.isNotBlank(),
                        ) {
                            Text("Connect via ADB (developer mode)")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConnect(manualHost)
                        showManualDialog = false
                    },
                    enabled = manualHost.isNotBlank(),
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    CasterCard(high = true) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (connecting) {
                StatusPill("Connecting…", MaterialTheme.colorScheme.primary, pulsing = true)
                Text(
                    when {
                        state.viaAdb -> "Look at the TV and choose Allow (tick \"Always allow\")."
                        state.needsAllow -> "Look at the TV and choose Allow when it asks to connect."
                        else -> "Connecting to the TV…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                OutlinedButton(onClick = onDisconnect, shape = RoundedCornerShape(14.dp)) {
                    Text("Cancel")
                }
            } else if (needsPin != null) {
                StatusPill("Waiting for code", MaterialTheme.colorScheme.primary, pulsing = true)
                Text(
                    if (androidTvCode) "Look at the TV: it shows a 6-character code. Type it here."
                    else "Look at the TV: it shows a 4-digit code. Type it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { typed ->
                        pin = if (androidTvCode) {
                            typed.filter { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }.take(pinLength).uppercase()
                        } else {
                            typed.filter { it in '0'..'9' }.take(pinLength)
                        }
                    },
                    label = { Text("Code from the TV") },
                    singleLine = true,
                    isError = needsPin.error != null,
                    supportingText = needsPin.error?.let { message -> { Text(message) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (androidTvCode) KeyboardType.Ascii else KeyboardType.NumberPassword,
                        capitalization = if (androidTvCode) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmitPin(pin) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSubmitPin(pin) }, enabled = pin.length == pinLength, shape = RoundedCornerShape(14.dp)) { Text("Pair") }
                    OutlinedButton(onClick = onDisconnect, shape = RoundedCornerShape(14.dp)) { Text("Cancel") }
                }
            } else {
                // Not connected yet: display paired and discovered devices automatically
                if (primaryTv != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(46.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(RemoteIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                primaryTv.kind ?: "Smart TV",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Saved device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = { onConnect(primaryTv.address) },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Connect")
                        }
                        IconButton(
                            onClick = { onForget(primaryTv.address) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Forget saved TV", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (primaryTv.mac != null) {
                        OutlinedButton(
                            onClick = { onWake(primaryTv.address, null) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Turn on with Wake-on-LAN")
                        }
                    }
                }

                // Additional saved TVs (if more than one is saved)
                val otherSaved = saved.filter { it.address != primaryTv?.address }
                if (otherSaved.isNotEmpty()) {
                    Text(
                        "Other saved TVs",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    otherSaved.forEach { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(RemoteIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    device.kind ?: "Smart TV",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "Saved device",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = { onConnect(device.address) },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Connect")
                            }
                            IconButton(
                                onClick = { onForget(device.address) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Forget saved TV", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // Discovered TVs on Wi-Fi (excluding any TV already saved)
                val allSavedAddresses = saved.map { it.address }.toSet() + listOfNotNull(primaryTv?.address)
                val discoveredOther = found.filter { it.address !in allSavedAddresses }
                if (discoveredOther.isNotEmpty()) {
                    if (primaryTv != null || otherSaved.isNotEmpty()) {
                        Text(
                            "Discovered TVs on Wi-Fi",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    discoveredOther.forEach { device ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(RemoteIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    device.kind ?: "Smart TV",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "Discovered on Wi-Fi",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = { onConnect(device.address) },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Connect")
                            }
                        }
                    }
                }

                // Wi-Fi discovery scanning status
                if (scanning) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Searching for TVs on Wi-Fi…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Empty state when no device has been saved or found yet and not scanning
                if (primaryTv == null && found.isEmpty() && !scanning) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "No smart TVs found",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Make sure your TV is on and connected to the same Wi-Fi network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = onScan,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text("Search Wi-Fi for TVs")
                        }
                    }
                }

                // Subtle option for manual IP in case of complex network subnets
                TextButton(
                    onClick = { showManualDialog = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        "Add TV by IP address",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            (state as? TvRemote.State.Failed)?.let {
                Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RemotePad(
    link: TvRemote.Link,
    volume: TvRemote.VolumeState,
    onEnableVolume: () -> Unit,
    onKey: (Int, Boolean) -> Unit,
    onTyped: (String, String) -> Unit,
    shareUrl: String?,
    onOpenCaster: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    voiceError: String?,
    onVoiceStart: () -> Boolean,
    onVoiceEnd: () -> Unit,
) {
    val fullRemote = link != TvRemote.Link.FireTv // ADB and Android TV have volume, the keyboard keys and link sending
    var typed by rememberSaveable { mutableStateOf("") }
    // When the keyboard opens the page shrinks; this scrolls the typing box into the part that is still visible.
    val typingBox = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    // Voice. An Android TV has its own assistant, which is talked to by holding the button. The others have no way in over the
    // network, so the phone listens itself (its own speech window) and what it hears is typed on the TV like the keyboard does.
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val holdToTalk = link == TvRemote.Link.AndroidTv
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val said = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (result.resultCode == Activity.RESULT_OK && !said.isNullOrEmpty()) {
            val updated = if (typed.isEmpty()) said else "$typed $said"
            onTyped(typed, updated)
            typed = updated
        }
    }
    fun dictate() {
        try {
            dictation.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to type on the TV"),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "This phone has no speech recognition.", Toast.LENGTH_LONG).show()
        }
    }

    CasterCard(high = true) {
        Column(
            modifier = Modifier.padding(18.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer72()
                MicButton(holdToTalk, onVoiceStart, onVoiceEnd, ::dictate)
                if (TvRemote.supportsKey(link, TvKey.POWER)) {
                    PadButton("Power", TvKey.POWER, onKey) { Text("⏻", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error) }
                } else {
                    Spacer72()
                }
            }
            if (holdToTalk && voiceError != null) {
                Text(voiceError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer72()
                PadButton("Up", TvKey.UP, onKey, repeat = true) { Text("▲", style = MaterialTheme.typography.titleLarge) }
                Spacer72()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadButton("Left", TvKey.LEFT, onKey, repeat = true) { Text("◀", style = MaterialTheme.typography.titleLarge) }
                PadButton("OK", TvKey.OK, onKey, accent = true, onLongPress = { TvRemote.longPress(TvKey.OK) }) { Text("OK", fontWeight = FontWeight.Bold) }
                PadButton("Right", TvKey.RIGHT, onKey, repeat = true) { Text("▶", style = MaterialTheme.typography.titleLarge) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Spacer72()
                PadButton("Down", TvKey.DOWN, onKey, repeat = true) { Text("▼", style = MaterialTheme.typography.titleLarge) }
                Spacer72()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadButton("Back", TvKey.BACK, onKey, onLongPress = { TvRemote.longPress(TvKey.BACK) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                PadButton("Home", TvKey.HOME, onKey, onLongPress = { TvRemote.longPress(TvKey.HOME) }) { Icon(Icons.Filled.Home, contentDescription = null) }
                PadButton("Menu", TvKey.MENU, onKey, onLongPress = { TvRemote.longPress(TvKey.MENU) }) { Text("≡", style = MaterialTheme.typography.titleLarge) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PadButton("Rewind", TvKey.REWIND, onKey, repeat = true, deferTap = true) { Text("⏪") }
                PadButton("Play or pause", TvKey.PLAY_PAUSE, onKey) { Text("⏯") }
                PadButton("Fast forward", TvKey.FAST_FORWARD, onKey, repeat = true, deferTap = true) { Text("⏩") }
            }
            if (fullRemote || volume is TvRemote.VolumeState.Ready) { // on a Fire TV volume goes through ADB, once it is turned on
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PadButton("Volume down", TvKey.VOLUME_DOWN, onKey, repeat = true) { Text("Vol −") }
                    PadButton("Mute", TvKey.MUTE, onKey) { Text("Mute") }
                    PadButton("Volume up", TvKey.VOLUME_UP, onKey, repeat = true) { Text("Vol +") }
                }
            }
            if (!fullRemote && TvRemote.adbSupported && volume !is TvRemote.VolumeState.Ready) VolumeSetup(volume, onEnableVolume)
            Text(
                if (holdToTalk) "Voice: hold the microphone and speak, like on the real remote. It talks to the TV's assistant."
                else "Voice: tap the microphone and speak. What you say is typed on the TV, so open its search box first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Tip: like the real remote, you can hold Home, OK, Back and Menu, and hold the rewind and fast-forward buttons.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (link == TvRemote.Link.Lg) {
        CasterCard(high = true) {
            Column(modifier = Modifier.padding(18.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pointer Trackpad", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .pointerInput(Unit) {
                            var accX = 0f
                            var accY = 0f
                            detectDragGestures(
                                onDragEnd = { accX = 0f; accY = 0f },
                                onDragCancel = { accX = 0f; accY = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    accX += dragAmount.x
                                    accY += dragAmount.y
                                    val dx = accX.toInt()
                                    val dy = accY.toInt()
                                    if (dx != 0 || dy != 0) {
                                        accX -= dx
                                        accY -= dy
                                        TvRemote.pointerMove(dx, dy, false)
                                    }
                                },
                            )
                        }
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            TvRemote.pointerClick()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Swipe to move pointer · Tap to click",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    CasterCard(high = true) {
        Column(modifier = Modifier.padding(18.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Quick Launch Apps", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                mano.algo.caster.tv.TvApp.entries.forEach { app ->
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            TvRemote.launchApp(app)
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(app.title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
    }

    CasterCard(high = true) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Open Caster on the TV", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (shareUrl != null && fullRemote) {
                Text(
                    "Opens Caster in the TV's browser, so nobody has to type the address. The TV needs a web browser installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { onOpenCaster(shareUrl) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("Send to TV")
                }
            } else if (shareUrl != null) {
                Text(
                    "Open the TV's browser, select its address bar, then tap \"Type the address on the TV\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("Open the TV's browser")
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        onTyped("", shareUrl) // sent to the TV without being written out on this screen
                        typed = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Type the address on the TV") }
            } else {
                Text(
                    "Start sharing on the main page first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Keyboard", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                if (fullRemote) {
                    "Select a search box on the TV, then type here: each letter goes to the TV as you type it. " +
                        "Letters the TV can't take (accents, emoji) are skipped."
                } else {
                    "Select a text box on the TV, then type here: it goes to the TV as you type."
                },
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
                    imeAction = if (fullRemote) ImeAction.Send else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onSend = {
                    onKey(TvKey.ENTER, false)
                    typed = ""
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(typingBox)
                    .onFocusEvent { focus ->
                        if (focus.isFocused) scope.launch {
                            delay(300) // after the keyboard has finished opening
                            typingBox.bringIntoView()
                        }
                    },
            )
            if (fullRemote) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** Fire TV only: the remote service has no volume, so it is turned on through ADB, once. */
@Composable
private fun VolumeSetup(volume: TvRemote.VolumeState, onEnable: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Volume", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        when (volume) {
            is TvRemote.VolumeState.Waiting -> Text(
                "Look at the TV and choose Allow (tick \"Always allow\").",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            else -> {
                Text(
                    "The Fire TV's own remote service has no volume buttons, so this needs one extra step: on the TV open " +
                        "Settings → My Fire TV → Developer options → ADB debugging and turn it on. No Developer options? " +
                        "Open About and press OK on the device name 7 times.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (volume as? TvRemote.VolumeState.Failed)?.let {
                    Text(it.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(onClick = onEnable, shape = RoundedCornerShape(14.dp)) {
                    Text(if (volume is TvRemote.VolumeState.Failed) "Try again" else "Turn on volume")
                }
            }
        }
    }
}

@Composable
private fun Spacer72() = Box(Modifier.size(width = 72.dp, height = 56.dp))

/**
 * The microphone button. On an Android TV it is held while speaking (it turns red while the phone's microphone is going to
 * the TV); elsewhere a tap opens the phone's speech window.
 */
@Composable
private fun MicButton(holdToTalk: Boolean, onHoldStart: () -> Boolean, onHoldEnd: () -> Unit, onDictate: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    var down by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (down) 0.92f else 1f, tween(90), label = "mic")
    val label = if (holdToTalk) "Voice, hold and speak" else "Voice, tap and speak"
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(if (listening) scheme.error else scheme.primary, RoundedCornerShape(28.dp))
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick(label) { if (!holdToTalk) onDictate(); true }
            }
            .pointerInput(holdToTalk) {
                detectTapGestures(
                    onPress = {
                        down = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val started = holdToTalk && onHoldStart()
                        listening = started
                        try {
                            tryAwaitRelease()
                        } finally {
                            down = false
                            listening = false
                            if (started) onHoldEnd() // always let go, even if the screen is left while it is held
                        }
                    },
                    onTap = { if (!holdToTalk) onDictate() },
                )
            },
        contentAlignment = Alignment.Center,
    ) { Text("🎤", color = scheme.onPrimary) }
}

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
    onLongPress: (() -> Unit)? = null,
    deferTap: Boolean = false, // send a tap only when the finger lifts, so a hold doesn't also count as a tap
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var pressed by remember { mutableStateOf(false) }
    // A press shrinks the button a little and tints it, so it feels like something was pushed.
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(90), label = "press")
    val container = when {
        accent -> if (pressed) scheme.primary.copy(alpha = 0.8f) else scheme.primary
        else -> if (pressed) scheme.surfaceContainerHighest else scheme.surfaceContainerLow
    }
    val onContainer = if (accent) scheme.onPrimary else scheme.onSurface
    Box(
        modifier = Modifier
            .size(width = 72.dp, height = 56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(container, RoundedCornerShape(if (accent) 28.dp else 18.dp))
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick(label) { onKey(keyCode, false); true }
                if (onLongPress != null) onLongClick("$label, held") { onLongPress(); true }
            }
            .pointerInput(keyCode, repeat) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        // A button with a long press (Home) waits to see which it is: a tap is sent when the finger lifts.
                        var longFired = false
                        val longJob = if (onLongPress != null) scope.launch {
                            delay(550)
                            longFired = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        } else null
                        if (onLongPress == null && !deferTap) onKey(keyCode, false)
                        var holdStarted = false
                        val held = if (repeat) scope.launch {
                            delay(350)
                            holdStarted = true
                            // A Fire TV can hold a direction by itself, which is far smoother; otherwise repeat single presses.
                            if (!TvRemote.holdStart(keyCode)) {
                                // No native hold here: the press that was waiting for the finger to lift goes out now.
                                if (deferTap) onKey(keyCode, false)
                                while (true) {
                                    onKey(keyCode, true)
                                    delay(130)
                                }
                            }
                        } else null
                        var lifted = false
                        try {
                            lifted = tryAwaitRelease()
                        } finally {
                            pressed = false
                            // Always let go, even if the screen is left while the button is down.
                            withContext(NonCancellable) {
                                longJob?.cancelAndJoin()
                                held?.cancelAndJoin()
                                TvRemote.holdEnd(keyCode)
                            }
                        }
                        if (lifted && ((onLongPress != null && !longFired) || (deferTap && !holdStarted))) onKey(keyCode, false)
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
