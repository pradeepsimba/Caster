package mano.algo.caster.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

/** Phone-as-remote for a Fire TV, Android TV, Samsung or LG TV: connect once, then use the buttons like the real remote. */
@Composable
fun RemoteScreen(
    state: TvRemote.State,
    savedHost: String,
    found: List<String>,
    scanning: Boolean,
    shareUrl: String?,
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle("TV remote", "Control a Fire TV, Android TV, Samsung or LG TV from this phone.")
        if (!TvRemote.supported) {
            EmptyState("Needs Android 8 or newer", "The TV remote can't run on this version of Android.")
            return@Column
        }
        ConnectionCard(state, savedHost, found, scanning, onConnect, onConnectAdb, onSubmitPin, onDisconnect, onScan)
        if (state is TvRemote.State.Connected) {
            RemotePad(state.link, volume, onEnableVolume, onKey, onTyped, shareUrl, onOpenCaster, onOpenBrowser)
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
    onConnectAdb: (String) -> Unit,
    onSubmitPin: (String) -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
) {
    var host by rememberSaveable(savedHost) { mutableStateOf(savedHost) }
    val needsPin = state as? TvRemote.State.NeedsPin
    val androidTvCode = needsPin?.androidTv == true // an Android TV shows 6 letters and digits, a Fire TV 4 digits
    val pinLength = if (androidTvCode) 6 else 4
    var pin by rememberSaveable(needsPin != null) { mutableStateOf("") } // a fresh box for every pairing
    val connected = state is TvRemote.State.Connected
    val connecting = state is TvRemote.State.Connecting
    val busy = connected || connecting || needsPin != null

    CasterCard(high = true) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                is TvRemote.State.Connected -> StatusPill("Connected to ${state.host}", Color(0xFF22C55E), pulsing = true)
                is TvRemote.State.Connecting -> StatusPill("Connecting…", MaterialTheme.colorScheme.primary, pulsing = true)
                is TvRemote.State.NeedsPin -> StatusPill("Waiting for the code", MaterialTheme.colorScheme.primary, pulsing = true)
                else -> StatusPill("Not connected", MaterialTheme.colorScheme.outline, pulsing = false)
            }
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("TV address") },
                placeholder = { Text("192.168.0.50") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), // has the dot and the colon of 192.168.0.50:5555
                modifier = Modifier.fillMaxWidth(),
            )
            if (needsPin != null) {
                Text(
                    if (androidTvCode) "Look at the TV: it shows a 6-character code (numbers and the letters A to F). Type it here."
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
                            typed.filter(Char::isDigit).take(pinLength)
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
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (needsPin != null) {
                    Button(onClick = { onSubmitPin(pin) }, enabled = pin.length == pinLength, shape = RoundedCornerShape(14.dp)) { Text("Pair") }
                    OutlinedButton(onClick = onDisconnect, shape = RoundedCornerShape(14.dp)) { Text("Cancel") }
                } else if (connected || connecting) {
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
            if (!busy) {
                found.forEach { address ->
                    TextButton(onClick = { host = address; onConnect(address) }) { Text("Use $address") }
                }
                if (found.isEmpty() && !scanning) {
                    // Shown after a search that found nothing, and as the first-time help.
                    Text(
                        "Turn the TV on and tap Connect. Fire TV, Android TV, Samsung and LG TVs work. The first time, a Fire TV " +
                            "shows a 4-digit code and an Android TV a 6-character one: type it here. A Samsung or LG TV asks on its " +
                            "screen to allow this phone: choose Allow. Nothing needs to be switched on in the TV's settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!busy) {
                TextButton(onClick = { onConnectAdb(host) }) { Text("Connect with ADB instead (advanced)") }
            }
            if (state is TvRemote.State.Connecting) {
                Text(
                    when {
                        state.viaAdb -> "Look at the TV and choose Allow (tick \"Always allow\" so it doesn't ask again)."
                        state.needsAllow -> "Look at the TV and choose Allow (or Yes) when it asks to connect this phone."
                        else -> "Connecting to the TV…"
                    },
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
    link: TvRemote.Link,
    volume: TvRemote.VolumeState,
    onEnableVolume: () -> Unit,
    onKey: (Int, Boolean) -> Unit,
    onTyped: (String, String) -> Unit,
    shareUrl: String?,
    onOpenCaster: (String) -> Unit,
    onOpenBrowser: () -> Unit,
) {
    val fullRemote = link != TvRemote.Link.FireTv // ADB and Android TV have volume, the keyboard keys and link sending
    var typed by rememberSaveable { mutableStateOf("") }
    // When the keyboard opens the page shrinks; this scrolls the typing box into the part that is still visible.
    val typingBox = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    CasterCard(high = true) {
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
            if (!fullRemote && volume !is TvRemote.VolumeState.Ready) VolumeSetup(volume, onEnableVolume)
            Text(
                "Tip: like the real remote, you can hold Home, OK, Back and Menu, and hold the rewind and fast-forward buttons.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    CasterCard(high = true) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Open Caster on the TV", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (shareUrl != null && fullRemote) {
                Text(
                    "Opens $shareUrl on the TV, so nobody has to type the address. The TV needs a web browser installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { onOpenCaster(shareUrl) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("Send to TV")
                }
            } else if (shareUrl != null) {
                Text(
                    "Open the TV's browser, then type this address with the keyboard below: $shareUrl",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("Open the TV's browser")
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
