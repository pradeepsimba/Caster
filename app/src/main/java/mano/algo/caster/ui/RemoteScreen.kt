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
    saved: List<TvRemote.SavedTv>,
    scanning: Boolean,
    shareUrl: String?,
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
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionTitle("TV remote", "Control a Fire TV, Android TV, Samsung or LG TV from this phone.")
        if (!TvRemote.supported) {
            EmptyState("Needs Android 8 or newer", "The TV remote can't run on this version of Android.")
            return@Column
        }
        ConnectionCard(state, savedHost, found, saved, scanning, onWake, onForget, onConnect, onConnectAdb, onSubmitPin, onDisconnect, onScan)
        if (state is TvRemote.State.Connected) {
            RemotePad(state.link, volume, onEnableVolume, onKey, onTyped, shareUrl, onOpenCaster, onOpenBrowser, voiceError, onVoiceStart, onVoiceEnd)
        }
    }
}

@Composable
private fun ConnectionCard(
    state: TvRemote.State,
    savedHost: String,
    found: List<String>,
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
    var host by rememberSaveable(savedHost) { mutableStateOf(savedHost) }
    val needsPin = state as? TvRemote.State.NeedsPin
    val androidTvCode = needsPin?.androidTv == true // an Android TV shows 6 letters and digits, a Fire TV 4 digits
    val pinLength = if (androidTvCode) 6 else 4
    var pin by rememberSaveable(needsPin != null) { mutableStateOf("") } // a fresh box for every pairing
    val connected = state is TvRemote.State.Connected
    val connecting = state is TvRemote.State.Connecting
    val busy = connected || connecting || needsPin != null
    // The TV that is being asked for its MAC address, to switch it on.
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
                            "The TV must allow being turned on from the network (often called Wake on LAN or \"Turn on with mobile\").",
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

    CasterCard(high = true) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                is TvRemote.State.Connected -> StatusPill("Connected", Color(0xFF22C55E), pulsing = true)
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
                            typed.filter { it in '0'..'9' }.take(pinLength) // plain digits only: other scripts' digits are not what the TV shows
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
            if (!busy && saved.isNotEmpty()) {
                Text("Your TVs", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                saved.forEach { tv ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp))
                            .padding(start = 14.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).clickable { host = tv.address; onConnect(tv.address) }.padding(vertical = 8.dp),
                        ) {
                            Text(tv.address, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(
                                tv.kind ?: "Tap to connect",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { if (tv.mac != null) onWake(tv.address, null) else macFor = tv.address }) { Text("Turn on") }
                        // The MAC address is read once and remembered: this is how a wrong one is put right.
                        TextButton(onClick = { macFor = tv.address }) { Text("MAC") }
                        TextButton(onClick = { onForget(tv.address) }) { Text("Forget") }
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
                        "Turn the TV on and tap Connect. The first time, the TV shows a code to type here, or asks you to choose Allow.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!busy) {
                if (TvRemote.adbSupported) TextButton(onClick = { onConnectAdb(host) }) { Text("Connect with ADB instead (advanced)") }
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
                    "Open the TV's browser, then type the address from Settings (the gear on the main page) with the keyboard below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("Open the TV's browser")
                }
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
