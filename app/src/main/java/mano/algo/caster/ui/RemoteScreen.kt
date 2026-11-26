package mano.algo.caster.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mano.algo.caster.cast.CastController
import mano.algo.caster.server.RemoteControl
import mano.algo.caster.server.RemoteState
import mano.algo.caster.tv.TvApp
import mano.algo.caster.tv.TvKey
import mano.algo.caster.tv.TvRemote
import mano.algo.caster.tv.WakeOnLan
import mano.algo.caster.ui.theme.LiveGreen

// ---- Icons matching the sleek remote aesthetic ---------------------------------------------------

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
        addPath(pathData = addPathNodes("M6,6h2v12H6zm3.5,6l8.5,6V6z"), fill = SolidColor(Color.Black))
    }.build()
}

val SkipNextIcon: ImageVector by lazy {
    ImageVector.Builder("SkipNext", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(pathData = addPathNodes("M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z"), fill = SolidColor(Color.Black))
    }.build()
}

val PauseIcon: ImageVector by lazy {
    ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(pathData = addPathNodes("M6,19h4V5H6v14zm8,-14v14h4V5h-4z"), fill = SolidColor(Color.Black))
    }.build()
}

private val PowerIcon: ImageVector by lazy {
    ImageVector.Builder("Power", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(
                "M13,3h-2v10h2V3zm4.83,2.17l-1.42,1.42C17.99,7.86 19,9.81 19,12c0,3.87 -3.13,7 -7,7s-7,-3.13 -7,-7c0,-2.19 1.01,-4.14 2.58,-5.42L6.17,5.17C4.23,6.82 3,9.26 3,12c0,4.97 4.03,9 9,9s9,-4.03 9,-9c0,-2.74 -1.23,-5.18 -3.17,-6.83z",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val VolumeOffIcon: ImageVector by lazy {
    ImageVector.Builder("VolumeOff", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(
                "M16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v2.21l2.45,2.45c0.03,-0.2 0.05,-0.41 0.05,-0.63zm2.5,0c0,0.94 -0.2,1.82 -0.54,2.64l1.51,1.51C20.63,14.91 21,13.5 21,12c0,-4.28 -2.99,-7.86 -7,-8.77v2.06c2.89,0.86 5,3.54 5,6.71zM4.27,3L3,4.27 7.73,9H3v6h4l5,5v-6.73l4.25,4.25c-0.67,0.52 -1.42,0.93 -2.25,1.18v2.06c1.38,-0.31 2.63,-0.95 3.69,-1.81L19.73,21 21,19.73l-9,-9L4.27,3zM12,4L9.91,6.09 12,8.18V4z",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val MicIcon: ImageVector by lazy {
    ImageVector.Builder("Mic", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(
                "M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zm5.3,-3c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val KeyboardIcon: ImageVector by lazy {
    ImageVector.Builder("Keyboard", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(
                "M20,5H4c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zm-9,3h2v2h-2V8zm0,3h2v2h-2v-2zM8,8h2v2H8V8zm0,3h2v2H8v-2zm-1,2H5v-2h2v2zm0,-3H5V8h2v2zm9,7H8v-2h8v2zm0,-4h-2v-2h2v2zm0,-3h-2V8h2v2zm3,3h-2v-2h2v2zm0,-3h-2V8h2v2z",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val DpadIcon: ImageVector by lazy {
    ImageVector.Builder("Dpad", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(
                "M15,7.5V2H9v5.5l3,3 3,-3zM7.5,9H2v6h5.5l3,-3 -3,-3zM9,16.5V22h6v-5.5l-3,-3 -3,3zM16.5,9l-3,3 3,3H22V9h-5.5z",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val TouchpadIcon: ImageVector by lazy {
    ImageVector.Builder("Touchpad", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(
                "M9,11.24V7.5C9,6.12 10.12,5 11.5,5S14,6.12 14,7.5v3.74c1.21,-0.81 2,-2.18 2,-3.74C16,5.01 13.99,3 11.5,3S7,5.01 7,7.5c0,1.56 0.79,2.93 2,3.74zm9.84,4.63l-4.54,-2.26c-0.17,-0.07 -0.35,-0.11 -0.54,-0.11H13v-6c0,-0.83 -0.67,-1.5 -1.5,-1.5S10,6.67 10,7.5v10.74l-3.43,-0.72c-0.08,-0.01 -0.15,-0.03 -0.24,-0.03 -0.31,0 -0.59,0.13 -0.79,0.33l-0.79,0.8 4.94,4.94c0.27,0.27 0.65,0.44 1.06,0.44h6.79c0.75,0 1.33,-0.55 1.44,-1.28l0.75,-5.27c0.01,-0.07 0.02,-0.14 0.02,-0.2 0,-0.62 -0.38,-1.16 -0.91,-1.38z",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

// ---- Remote Palette ------------------------------------------------------------------------------

private val RemoteBg = Color(0xFF1A1A1D)
private val RemoteButtonBg = Color(0xFF333338)
private val RemoteButtonPressed = Color(0xFF47474E)
private val RemoteButtonBorder = Color(0xFF4A4A50)
private val RemotePowerRed = Color(0xFFE05252)
private val RemoteAccentBlue = Color(0xFF5EA8FF)
private val RemoteMicPurple = Color(0xFFA86BFF)

// ---- State & Capabilities ------------------------------------------------------------------------

private enum class LowerMode { DPAD, TOUCHPAD, NUMPAD, KEYBOARD, APPS, DEVICES }

private sealed interface RemoteTarget {
    val id: String
    val label: String
    val subtitle: String

    data class Tv(val host: String, val link: TvRemote.Link) : RemoteTarget {
        override val id: String = "tv"
        override val label: String = when (link) {
            TvRemote.Link.FireTv -> "Fire TV"
            TvRemote.Link.AndroidTv -> "Android TV"
            TvRemote.Link.Samsung -> "Samsung TV"
            TvRemote.Link.Lg -> "LG TV"
            TvRemote.Link.Adb -> "Android TV (ADB)"
        }
        override val subtitle: String = host
    }

    data class Cast(val state: CastController.State) : RemoteTarget {
        override val id: String = "cast"
        override val label: String = state.device ?: "Chromecast"
        override val subtitle: String
            get() {
                val kind = when (state.kind) {
                    "i" -> "Slide"
                    "a" -> "Track"
                    else -> "Video"
                }
                return if (state.totalCount > 1) "$kind ${state.currentIndex + 1} of ${state.totalCount}" else "Casting $kind"
            }
    }

    data class Web(val remote: RemoteState) : RemoteTarget {
        override val id: String = "web:${remote.deviceId}"
        override val label: String = remote.deviceLabel
        override val subtitle: String = if (remote.playing) "Playing on screen" else "Paused on screen"
    }
}

private data class RemoteCapabilities(
    val dpad: Boolean = false,
    val playPause: Boolean = false,
    val seekButtons: Boolean = false,
    val prev: Boolean = false,
    val next: Boolean = false,
    val seekBar: Boolean = false,
    val subtitles: Boolean = false,
    val volumeButtons: Boolean = false,
    val volumeSlider: Boolean = false,
    val volumeSetup: Boolean = false,
    val power: Boolean = false,
    val voice: Boolean = false,
    val keyboard: Boolean = false,
    val trackpad: Boolean = false,
    val numpad: Boolean = false,
    val apps: Boolean = false,
)

private fun capabilitiesFor(target: RemoteTarget?, volume: TvRemote.VolumeState): RemoteCapabilities = when (target) {
    is RemoteTarget.Tv -> {
        val full = target.link != TvRemote.Link.FireTv
        val volReady = full || volume is TvRemote.VolumeState.Ready
        RemoteCapabilities(
            dpad = true,
            playPause = true,
            seekButtons = true,
            volumeButtons = volReady,
            volumeSetup = !volReady && TvRemote.adbSupported,
            power = TvRemote.supportsKey(target.link, TvKey.POWER),
            voice = true,
            keyboard = true,
            trackpad = target.link == TvRemote.Link.Lg,
            numpad = true,
            apps = true,
        )
    }
    is RemoteTarget.Web -> RemoteCapabilities(
        playPause = true,
        seekButtons = true,
        next = true,
        seekBar = target.remote.durationSec > 0,
        subtitles = true,
        volumeSlider = target.remote.volume >= 0,
    )
    is RemoteTarget.Cast -> RemoteCapabilities(
        playPause = true,
        prev = true,
        next = true,
    )
    null -> RemoteCapabilities()
}

private fun formatClock(sec: Int): String =
    if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
    else "%d:%02d".format(sec / 60, sec % 60)

/**
 * Unified, sleek, no-scroll remote UI that adapts dynamically to the connected device's features
 * (Smart TV, Web Screen, or Chromecast) and uses show/hide mode toggles instead of scrolling.
 */
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

    LaunchedEffect(savedHost) {
        if (state is TvRemote.State.Idle && savedHost.isNotBlank()) onConnect(savedHost)
    }
    LaunchedEffect(Unit) {
        if (state is TvRemote.State.Idle && found.isEmpty() && !scanning) onScan()
    }

    val now = System.currentTimeMillis()
    val liveRemotes = remotes.filter { now - it.reportedAt < RemoteControl.FRESH_MS * 2 }

    val targets = buildList {
        if (castState.device != null) add(RemoteTarget.Cast(castState))
        liveRemotes.forEach { add(RemoteTarget.Web(it)) }
        if (state is TvRemote.State.Connected) add(RemoteTarget.Tv(state.host, state.link))
    }

    var selectedTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeTarget = targets.firstOrNull { it.id == selectedTargetId } ?: targets.firstOrNull()
    val caps = capabilitiesFor(activeTarget, volume)

    var lowerMode by rememberSaveable { mutableStateOf(LowerMode.DPAD) }
    val forceDevices = activeTarget == null || state is TvRemote.State.Connecting || state is TvRemote.State.NeedsPin
    val shownMode = when {
        forceDevices -> LowerMode.DEVICES
        lowerMode == LowerMode.TOUCHPAD && !caps.trackpad && !caps.dpad -> LowerMode.DPAD
        lowerMode == LowerMode.NUMPAD && !caps.numpad -> LowerMode.DPAD
        lowerMode == LowerMode.KEYBOARD && !caps.keyboard -> LowerMode.DPAD
        lowerMode == LowerMode.APPS && !caps.apps -> LowerMode.DPAD
        else -> lowerMode
    }

    Surface(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(28.dp),
        color = RemoteBg,
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 1. Sleek Top Bar: Red Power (if supported) + Device title/switcher + More (⋮)
            RemoteTopBar(
                targets = targets,
                active = activeTarget,
                hasPower = caps.power,
                onPower = { onKey(TvKey.POWER, false) },
                onSelectTarget = {
                    selectedTargetId = it.id
                    lowerMode = LowerMode.DPAD
                },
                onToggleDevices = {
                    lowerMode = if (shownMode == LowerMode.DEVICES) LowerMode.DPAD else LowerMode.DEVICES
                },
            )

            // 2. Dynamic Body: adapts layout cleanly to TV vs Web vs Cast vs Devices
            when {
                shownMode == LowerMode.DEVICES -> {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                        DevicesPanel(
                            state = state,
                            savedHost = savedHost,
                            found = found,
                            saved = saved,
                            scanning = scanning,
                            onWake = onWake,
                            onForget = onForget,
                            onConnect = { host ->
                                onConnect(host)
                                lowerMode = LowerMode.DPAD
                            },
                            onConnectAdb = { host ->
                                onConnectAdb(host)
                                lowerMode = LowerMode.DPAD
                            },
                            onSubmitPin = onSubmitPin,
                            onDisconnect = onDisconnect,
                            onScan = onScan,
                        )
                    }
                }

                activeTarget is RemoteTarget.Web -> {
                    WebRemoteBody(
                        remote = activeTarget.remote,
                        caps = caps,
                        onRemote = onRemote,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }

                activeTarget is RemoteTarget.Cast -> {
                    CastRemoteBody(
                        state = activeTarget.state,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }

                activeTarget is RemoteTarget.Tv -> {
                    TvRemoteBody(
                        link = activeTarget.link,
                        caps = caps,
                        volume = volume,
                        shownMode = shownMode,
                        shareUrl = shareUrl,
                        voiceError = voiceError,
                        onEnableVolume = onEnableVolume,
                        onKey = onKey,
                        onTyped = onTyped,
                        onOpenCaster = onOpenCaster,
                        onOpenBrowser = onOpenBrowser,
                        onVoiceStart = onVoiceStart,
                        onVoiceEnd = onVoiceEnd,
                        onChangeMode = { lowerMode = it },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteTopBar(
    targets: List<RemoteTarget>,
    active: RemoteTarget?,
    hasPower: Boolean,
    onPower: () -> Unit,
    onSelectTarget: (RemoteTarget) -> Unit,
    onToggleDevices: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left: Red Power button (only when device supports Power)
            if (hasPower) {
                SleekCircleButton(
                    label = "Power",
                    size = 46.dp,
                    containerColor = RemotePowerRed,
                    borderColor = RemotePowerRed.copy(alpha = 0.6f),
                    onClick = onPower,
                ) {
                    Icon(PowerIcon, contentDescription = "Power", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            } else {
                Spacer(Modifier.size(46.dp))
            }

            // Center: Active device name + status dot
            if (active != null) {
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.size(8.dp).background(LiveGreen, CircleShape))
                        Text(
                            active.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        active.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    "Connect a device",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }

            // Right: More (Devices)
            IconButton(onClick = onToggleDevices, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Devices", tint = Color.White.copy(alpha = 0.85f))
            }
        }

        if (targets.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                targets.forEach { t ->
                    FilterChip(
                        selected = t.id == active?.id,
                        onClick = { onSelectTarget(t) },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        }
    }
}

// ---- Smart TV Remote Body (Matching Reference Images 1, 2, 3) ------------------------------------

@Composable
private fun TvRemoteBody(
    link: TvRemote.Link,
    caps: RemoteCapabilities,
    volume: TvRemote.VolumeState,
    shownMode: LowerMode,
    shareUrl: String?,
    voiceError: String?,
    onEnableVolume: () -> Unit,
    onKey: (Int, Boolean) -> Unit,
    onTyped: (String, String) -> Unit,
    onOpenCaster: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onVoiceStart: () -> Boolean,
    onVoiceEnd: () -> Unit,
    onChangeMode: (LowerMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val holdToTalk = link == TvRemote.Link.AndroidTv
    var typedForDictation by rememberSaveable { mutableStateOf("") }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val said = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (result.resultCode == Activity.RESULT_OK && !said.isNullOrEmpty()) {
            val updated = if (typedForDictation.isEmpty()) said else "$typedForDictation $said"
            onTyped(typedForDictation, updated)
            typedForDictation = updated
        }
    }
    fun dictate() {
        try {
            dictation.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say what to type on the TV"),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "This phone has no speech recognition.", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Upper Cluster: VOL pill + Mute/Play circles + SEEK pill, and Back/Home/Menu circles
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left Vertical Pill: Volume + / - (or Enable Vol for Fire TV)
                if (caps.volumeButtons) {
                    VerticalRockerPill(
                        label = "VOL",
                        topSymbol = "+",
                        bottomSymbol = "−",
                        onTop = { onKey(TvKey.VOLUME_UP, false) },
                        onBottom = { onKey(TvKey.VOLUME_DOWN, false) },
                    )
                } else if (caps.volumeSetup) {
                    Surface(
                        onClick = onEnableVolume,
                        modifier = Modifier.size(width = 66.dp, height = 136.dp),
                        shape = RoundedCornerShape(33.dp),
                        color = RemoteButtonBg,
                        border = BorderStroke(1.dp, RemoteButtonBorder),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(6.dp)) {
                            Text(
                                when (volume) {
                                    is TvRemote.VolumeState.Waiting -> "Allow\non TV"
                                    is TvRemote.VolumeState.Failed -> "Retry\nVol"
                                    else -> "Enable\nVol"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                // Center Column: Mute + Play/Pause
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (caps.volumeButtons) {
                        SleekCircleButton("Mute", size = 62.dp, onClick = { onKey(TvKey.MUTE, false) }) {
                            Icon(VolumeOffIcon, contentDescription = "Mute", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                    SleekCircleButton("Play or pause", size = 62.dp, onClick = { onKey(TvKey.PLAY_PAUSE, false) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play/Pause", tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }

                // Right Vertical Pill: Seek Up / Down chevrons (Fast Forward / Rewind)
                VerticalRockerPill(
                    label = "SEEK",
                    topSymbol = "⌃",
                    bottomSymbol = "⌄",
                    onTop = { onKey(TvKey.FAST_FORWARD, false) },
                    onBottom = { onKey(TvKey.REWIND, false) },
                )
            }

            // Navigation Row: Back, Home, Settings/Menu
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SleekCircleButton(
                    label = "Back",
                    size = 62.dp,
                    onClick = { onKey(TvKey.BACK, false) },
                    onLongPress = { TvRemote.longPress(TvKey.BACK) },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                SleekCircleButton(
                    label = "Home",
                    size = 62.dp,
                    onClick = { onKey(TvKey.HOME, false) },
                    onLongPress = { TvRemote.longPress(TvKey.HOME) },
                ) {
                    Icon(Icons.Filled.Home, contentDescription = "Home", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                SleekCircleButton(
                    label = "Menu",
                    size = 62.dp,
                    onClick = { onKey(TvKey.MENU, false) },
                    onLongPress = { TvRemote.longPress(TvKey.MENU) },
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Menu", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }

        if (holdToTalk && voiceError != null) {
            Text(voiceError, style = MaterialTheme.typography.bodySmall, color = RemotePowerRed)
        }

        // Lower Switchable Area (No Scroll! Fits cleanly in remaining space)
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (shownMode) {
                LowerMode.DPAD -> CircularDpadRing(onKey = onKey)
                LowerMode.TOUCHPAD -> TouchpadArea(
                    isLg = link == TvRemote.Link.Lg,
                    onKey = onKey,
                    onOpenKeyboard = { onChangeMode(LowerMode.KEYBOARD) },
                )
                LowerMode.NUMPAD -> NumberPadGrid(
                    onDigit = { digit -> onTyped("", digit) },
                    onOk = { onKey(TvKey.OK, false) },
                    onBack = { onKey(TvKey.BACK, false) },
                )
                LowerMode.KEYBOARD -> InlineKeyboardArea(
                    fullRemote = link != TvRemote.Link.FireTv,
                    shareUrl = shareUrl,
                    onOpenCaster = onOpenCaster,
                    onOpenBrowser = onOpenBrowser,
                    onTyped = onTyped,
                    onKey = onKey,
                )
                LowerMode.APPS -> InlineAppsArea(onLaunch = { TvRemote.launchApp(it) })
                LowerMode.DEVICES -> Unit
            }
        }

        // Bottom Bar (Matching Images 1, 2, 3): Touchpad | D-Pad | Mic | 123 | Keyboard/Apps
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomModeButton(
                selected = shownMode == LowerMode.TOUCHPAD,
                onClick = { onChangeMode(LowerMode.TOUCHPAD) },
            ) {
                Icon(
                    TouchpadIcon,
                    contentDescription = "Touchpad",
                    tint = if (shownMode == LowerMode.TOUCHPAD) RemoteAccentBlue else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(22.dp),
                )
            }

            BottomModeButton(
                selected = shownMode == LowerMode.DPAD,
                onClick = { onChangeMode(LowerMode.DPAD) },
            ) {
                Icon(
                    DpadIcon,
                    contentDescription = "D-Pad",
                    tint = if (shownMode == LowerMode.DPAD) RemoteAccentBlue else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(22.dp),
                )
            }

            MicCircleButton(
                holdToTalk = holdToTalk,
                onHoldStart = onVoiceStart,
                onHoldEnd = onVoiceEnd,
                onDictate = ::dictate,
            )

            BottomModeButton(
                selected = shownMode == LowerMode.NUMPAD,
                onClick = { onChangeMode(LowerMode.NUMPAD) },
            ) {
                Text(
                    "123",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (shownMode == LowerMode.NUMPAD) RemoteAccentBlue else Color.White.copy(alpha = 0.85f),
                )
            }

            BottomModeButton(
                selected = shownMode == LowerMode.KEYBOARD || shownMode == LowerMode.APPS,
                onClick = {
                    onChangeMode(
                        when (shownMode) {
                            LowerMode.KEYBOARD -> LowerMode.APPS
                            LowerMode.APPS -> LowerMode.DPAD
                            else -> LowerMode.KEYBOARD
                        },
                    )
                },
            ) {
                Icon(
                    KeyboardIcon,
                    contentDescription = "Keyboard & Apps",
                    tint = if (shownMode == LowerMode.KEYBOARD || shownMode == LowerMode.APPS) RemoteAccentBlue else Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

// ---- Web Screen Dynamic Remote Body (Only Web features, NO channels/D-pad) ----------------------

@Composable
private fun WebRemoteBody(
    remote: RemoteState,
    caps: RemoteCapabilities,
    onRemote: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Title & Seekbar
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                remote.title.ifBlank { "Playing on screen" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (caps.seekBar) {
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatClock(shown.toInt()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f))
                    Text(formatClock(remote.durationSec), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f))
                }
            }
        }

        // Main Playback Controls: -10s | Play/Pause | +10s
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SleekCircleButton("-10s", size = 62.dp, onClick = { onRemote(remote.deviceId, "seek:-10") }) {
                Text("-10s", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            SleekCircleButton(
                label = if (remote.playing) "Pause" else "Play",
                size = 82.dp,
                containerColor = RemoteAccentBlue,
                borderColor = RemoteAccentBlue.copy(alpha = 0.5f),
                onClick = { onRemote(remote.deviceId, "toggle") },
            ) {
                Icon(
                    if (remote.playing) PauseIcon else Icons.Filled.PlayArrow,
                    contentDescription = if (remote.playing) "Pause" else "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp),
                )
            }
            SleekCircleButton("+10s", size = 62.dp, onClick = { onRemote(remote.deviceId, "seek:10") }) {
                Text("+10s", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        // Secondary Row: CC (Subtitles) | Next
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            SleekCircleButton("Subtitles", size = 56.dp, onClick = { onRemote(remote.deviceId, "subs") }) {
                Text("CC", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            SleekCircleButton("Next", size = 56.dp, onClick = { onRemote(remote.deviceId, "next") }) {
                Icon(SkipNextIcon, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }

        // Volume Slider (only if page reports volume)
        if (caps.volumeSlider) {
            var volDrag by remember(remote.deviceId) { mutableStateOf<Float?>(null) }
            var volSent by remember(remote.deviceId) { mutableStateOf<Float?>(null) }
            LaunchedEffect(volSent) { if (volSent != null) { delay(2_500); volSent = null } }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Vol", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(end = 10.dp))
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

// ---- Chromecast Dynamic Remote Body --------------------------------------------------------------

@Composable
private fun CastRemoteBody(
    state: CastController.State,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.title?.let { title ->
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            state.error?.let { err ->
                Text(err, style = MaterialTheme.typography.bodySmall, color = RemotePowerRed, textAlign = TextAlign.Center)
            }
        }

        // Main Skip & Play/Pause row: -10s | Play/Pause | +10s
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SleekCircleButton("-10s", size = 62.dp, onClick = { CastController.seekBy(-10_000L) }) {
                Text("-10s", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            SleekCircleButton(
                label = if (state.playing) "Pause" else "Play",
                size = 84.dp,
                containerColor = RemoteAccentBlue,
                borderColor = RemoteAccentBlue.copy(alpha = 0.5f),
                onClick = { CastController.togglePlayback() },
            ) {
                Icon(
                    if (state.playing) PauseIcon else Icons.Filled.PlayArrow,
                    contentDescription = if (state.playing) "Pause" else "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(38.dp),
                )
            }
            SleekCircleButton("+10s", size = 62.dp, onClick = { CastController.seekBy(10_000L) }) {
                Text("+10s", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        // Track / Slide navigation: Previous | Next
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SleekCircleButton("Previous", size = 56.dp, onClick = { CastController.prev() }) {
                Icon(SkipPreviousIcon, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            SleekCircleButton("Next", size = 56.dp, onClick = { CastController.next() }) {
                Icon(SkipNextIcon, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }

        // Volume row: Vol - | Mute | Vol +
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SleekCircleButton("Volume down", size = 56.dp, onClick = { CastController.adjustVolume(-0.08) }) {
                Text("Vol −", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
            SleekCircleButton("Mute", size = 56.dp, onClick = { CastController.toggleMute() }) {
                Icon(VolumeOffIcon, contentDescription = "Mute", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            SleekCircleButton("Volume up", size = 56.dp, onClick = { CastController.adjustVolume(0.08) }) {
                Text("Vol +", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
    }
}

// ---- Lower Switchable Panels (Matching Reference Images) -----------------------------------------

@Composable
private fun CircularDpadRing(onKey: (Int, Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(220.dp)
            .background(RemoteButtonBg, CircleShape)
            .border(1.dp, RemoteButtonBorder, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // Up
        DpadDirectionHitArea("Up", TvKey.UP, onKey, Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) {
            Text("⌃", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }
        // Down
        DpadDirectionHitArea("Down", TvKey.DOWN, onKey, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
            Text("⌄", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        }
        // Left
        DpadDirectionHitArea("Left", TvKey.LEFT, onKey, Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
            Text("‹", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        }
        // Right
        DpadDirectionHitArea("Right", TvKey.RIGHT, onKey, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
            Text("›", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        }
        // Center OK circle with bullseye icon
        Box(
            modifier = Modifier
                .size(86.dp)
                .clip(CircleShape)
                .background(RemoteBg)
                .border(1.dp, RemoteButtonBorder, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onKey(TvKey.OK, false)
                        },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            TvRemote.longPress(TvKey.OK)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(20.dp).border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.size(8.dp).background(Color.White, CircleShape))
            }
        }
    }
}

@Composable
private fun DpadDirectionHitArea(
    label: String,
    keyCode: Int,
    onKey: (Int, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick(label) { onKey(keyCode, false); true }
            }
            .pointerInput(keyCode) {
                detectTapGestures(
                    onPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onKey(keyCode, false)
                        val held = scope.launch {
                            delay(350)
                            if (!TvRemote.holdStart(keyCode)) {
                                while (true) {
                                    onKey(keyCode, true)
                                    delay(130)
                                }
                            }
                        }
                        try {
                            tryAwaitRelease()
                        } finally {
                            withContext(NonCancellable) {
                                held.cancelAndJoin()
                                TvRemote.holdEnd(keyCode)
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun TouchpadArea(
    isLg: Boolean,
    onKey: (Int, Boolean) -> Unit,
    onOpenKeyboard: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(216.dp)
            .background(Color(0xFF28282C), RoundedCornerShape(20.dp))
            .border(1.dp, RemoteButtonBorder.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Main Touchpad surface with top-left Keyboard icon (matching Image 1)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .pointerInput(isLg) {
                    var accX = 0f
                    var accY = 0f
                    detectDragGestures(
                        onDragEnd = { accX = 0f; accY = 0f },
                        onDragCancel = { accX = 0f; accY = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accX += dragAmount.x
                            accY += dragAmount.y
                            if (isLg) {
                                val dx = accX.toInt()
                                val dy = accY.toInt()
                                if (dx != 0 || dy != 0) {
                                    accX -= dx
                                    accY -= dy
                                    TvRemote.pointerMove(dx, dy, false)
                                }
                            } else {
                                val threshold = 48f
                                if (accX > threshold) { accX = 0f; onKey(TvKey.RIGHT, false) }
                                else if (accX < -threshold) { accX = 0f; onKey(TvKey.LEFT, false) }
                                if (accY > threshold) { accY = 0f; onKey(TvKey.DOWN, false) }
                                else if (accY < -threshold) { accY = 0f; onKey(TvKey.UP, false) }
                            }
                        },
                    )
                }
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (isLg) TvRemote.pointerClick() else onKey(TvKey.OK, false)
                },
        ) {
            // Top-left Keyboard icon button (like Image 1)
            Surface(
                onClick = onOpenKeyboard,
                shape = RoundedCornerShape(10.dp),
                color = RemoteButtonBg,
                modifier = Modifier.align(Alignment.TopStart).size(width = 40.dp, height = 28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(KeyboardIcon, contentDescription = "Open Keyboard", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
            }
            Text(
                if (isLg) "Swipe to move · Tap to click" else "Swipe to navigate · Tap for OK",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Right vertical scroll strip (matching Image 1)
        Column(
            modifier = Modifier
                .width(22.dp)
                .fillMaxSize()
                .background(Color(0xFF5A6373).copy(alpha = 0.55f), RoundedCornerShape(11.dp))
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "⌃",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.clickable { onKey(TvKey.UP, false) }.padding(2.dp),
            )
            Text(
                "⌄",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.clickable { onKey(TvKey.DOWN, false) }.padding(2.dp),
            )
        }
    }
}

@Composable
private fun NumberPadGrid(
    onDigit: (String) -> Unit,
    onOk: () -> Unit,
    onBack: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("Back", "0", "OK"),
    )
    Surface(
        modifier = Modifier.fillMaxWidth().height(216.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF28282C),
        border = BorderStroke(1.dp, RemoteButtonBorder.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { label ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    when (label) {
                                        "OK" -> onOk()
                                        "Back" -> onBack()
                                        else -> onDigit(label)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineKeyboardArea(
    fullRemote: Boolean,
    shareUrl: String?,
    onOpenCaster: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onTyped: (String, String) -> Unit,
    onKey: (Int, Boolean) -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    Surface(
        modifier = Modifier.fillMaxWidth().height(216.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF28282C),
        border = BorderStroke(1.dp, RemoteButtonBorder.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { new ->
                    onTyped(typed, new)
                    typed = new
                },
                placeholder = { Text("Type text for TV…", color = Color.White.copy(alpha = 0.5f)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = if (fullRemote) ImeAction.Send else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onSend = {
                    onKey(TvKey.ENTER, false)
                    typed = ""
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (fullRemote) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (typed.isEmpty()) onKey(TvKey.DELETE, false) else {
                                val shorter = typed.dropLast(1)
                                onTyped(typed, shorter)
                                typed = shorter
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text("⌫ Delete", color = Color.White) }
                    Button(
                        onClick = { onKey(TvKey.ENTER, false); typed = "" },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text("Enter") }
                }
            }
            if (shareUrl != null && fullRemote) {
                Button(onClick = { onOpenCaster(shareUrl) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text("Open Caster on TV")
                }
            } else if (shareUrl != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onOpenBrowser, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Text("Open Browser", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { onTyped("", shareUrl); typed = "" },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("Type URL", color = Color.White, maxLines = 1) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineAppsArea(onLaunch: (TvApp) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = Modifier.fillMaxWidth().height(216.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF28282C),
        border = BorderStroke(1.dp, RemoteButtonBorder.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Quick Launch Apps", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TvApp.entries.forEach { app ->
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onLaunch(app)
                        },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, RemoteButtonBorder),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(app.title, color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

// ---- Reusable Sleek Remote Components ------------------------------------------------------------

@Composable
private fun VerticalRockerPill(
    label: String,
    topSymbol: String,
    bottomSymbol: String,
    onTop: () -> Unit,
    onBottom: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = Modifier.size(width = 66.dp, height = 136.dp),
        shape = RoundedCornerShape(33.dp),
        color = RemoteButtonBg,
        border = BorderStroke(1.dp, RemoteButtonBorder),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTop()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(topSymbol, style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Medium)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onBottom()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(bottomSymbol, style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
    }
}

@Composable
private fun SleekCircleButton(
    label: String,
    size: Dp,
    containerColor: Color = RemoteButtonBg,
    borderColor: Color = RemoteButtonBorder,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(80), label = "circlePress")
    val bg = if (pressed && containerColor == RemoteButtonBg) RemoteButtonPressed else containerColor
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(bg, CircleShape)
            .border(1.dp, borderColor, CircleShape)
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick(label) { onClick(); true }
                if (onLongPress != null) onLongClick("$label, held") { onLongPress(); true }
            }
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try {
                            tryAwaitRelease()
                        } finally {
                            pressed = false
                        }
                    },
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    },
                    onLongPress = onLongPress?.let { lp ->
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            lp()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            content()
        }
    }
}

@Composable
private fun BottomModeButton(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(if (selected) RemoteAccentBlue.copy(alpha = 0.14f) else Color.Transparent)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun MicCircleButton(
    holdToTalk: Boolean,
    onHoldStart: () -> Boolean,
    onHoldEnd: () -> Unit,
    onDictate: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (down) 0.92f else 1f, tween(90), label = "mic")
    val label = if (holdToTalk) "Voice, hold and speak" else "Voice, tap and speak"
    Box(
        modifier = Modifier
            .size(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(if (listening) RemotePowerRed else RemoteButtonBg, CircleShape)
            .border(1.dp, if (listening) RemotePowerRed else RemoteButtonBorder, CircleShape)
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
                            if (started) onHoldEnd()
                        }
                    },
                    onTap = { if (!holdToTalk) onDictate() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            MicIcon,
            contentDescription = label,
            tint = if (listening) Color.White else RemoteMicPurple,
            modifier = Modifier.size(26.dp),
        )
    }
}

// ---- Devices / Connection Panel ------------------------------------------------------------------

@Composable
private fun DevicesPanel(
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
    val connecting = state is TvRemote.State.Connecting
    val needsPin = state as? TvRemote.State.NeedsPin
    val androidTvCode = needsPin?.androidTv == true
    val pinLength = if (androidTvCode) 6 else 4
    var pin by rememberSaveable(needsPin != null) { mutableStateOf("") }

    val primaryTv = saved.firstOrNull() ?: if (savedHost.isNotBlank()) TvRemote.SavedTv(savedHost, null, null) else null
    var showManualDialog by rememberSaveable { mutableStateOf(false) }
    var manualHost by rememberSaveable { mutableStateOf(savedHost) }

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
                        ) { Text("Connect via ADB (developer mode)") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onConnect(manualHost); showManualDialog = false }, enabled = manualHost.isNotBlank()) {
                    Text("Connect")
                }
            },
            dismissButton = { TextButton(onClick = { showManualDialog = false }) { Text("Cancel") } },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF252529),
        border = BorderStroke(1.dp, RemoteButtonBorder.copy(alpha = 0.6f)),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (connecting) {
                StatusPill("Connecting…", RemoteAccentBlue, pulsing = true)
                Text(
                    when {
                        state.viaAdb -> "Look at the TV and choose Allow (tick \"Always allow\")."
                        state.needsAllow -> "Look at the TV and choose Allow when it asks to connect."
                        else -> "Connecting to the TV…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                OutlinedButton(onClick = onDisconnect, shape = RoundedCornerShape(14.dp)) { Text("Cancel", color = Color.White) }
            } else if (needsPin != null) {
                StatusPill("Waiting for code", RemoteAccentBlue, pulsing = true)
                Text(
                    if (androidTvCode) "Look at the TV: it shows a 6-character code. Type it here."
                    else "Look at the TV: it shows a 4-digit code. Type it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
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
                    OutlinedButton(onClick = onDisconnect, shape = RoundedCornerShape(14.dp)) { Text("Cancel", color = Color.White) }
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (primaryTv != null) {
                        TvDeviceCard(
                            name = primaryTv.kind ?: "Smart TV",
                            subtitle = "Saved · Tap to connect",
                            primary = true,
                            onClick = { onConnect(primaryTv.address) },
                            onForget = { onForget(primaryTv.address) },
                        )
                        if (primaryTv.mac != null) {
                            OutlinedButton(
                                onClick = { onWake(primaryTv.address, null) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) { Text("Turn on with Wake-on-LAN", color = Color.White) }
                        }
                    }

                    val otherSaved = saved.filter { it.address != primaryTv?.address }
                    if (otherSaved.isNotEmpty()) {
                        Text("Other saved TVs", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f))
                        otherSaved.forEach { device ->
                            TvDeviceCard(
                                name = device.kind ?: "Smart TV",
                                subtitle = "Saved · Tap to connect",
                                primary = false,
                                onClick = { onConnect(device.address) },
                                onForget = { onForget(device.address) },
                            )
                        }
                    }

                    val allSavedAddresses = saved.map { it.address }.toSet() + listOfNotNull(primaryTv?.address)
                    val discoveredOther = found.filter { it.address !in allSavedAddresses }
                    if (discoveredOther.isNotEmpty()) {
                        Text("Discovered TVs on Wi-Fi", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.65f))
                        discoveredOther.forEach { device ->
                            TvDeviceCard(
                                name = device.kind ?: "Smart TV",
                                subtitle = "On Wi-Fi · Tap to connect",
                                primary = false,
                                onClick = { onConnect(device.address) },
                            )
                        }
                    }

                    if (scanning) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = RemoteAccentBlue)
                            Text("Searching for TVs on Wi-Fi…", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
                        }
                    }

                    if (primaryTv == null && found.isEmpty() && !scanning) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("No smart TVs found", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Text(
                                "Make sure your TV is on and connected to the same Wi-Fi network.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.65f),
                                textAlign = TextAlign.Center,
                            )
                            Button(onClick = onScan, shape = RoundedCornerShape(12.dp)) { Text("Search Wi-Fi for TVs") }
                        }
                    }
                }

                TextButton(onClick = { showManualDialog = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Add TV by IP address", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                }
            }
            (state as? TvRemote.State.Failed)?.let {
                Text(it.message, style = MaterialTheme.typography.bodyMedium, color = RemotePowerRed)
            }
        }
    }
}

@Composable
private fun TvDeviceCard(
    name: String,
    subtitle: String,
    primary: Boolean,
    onClick: () -> Unit,
    onForget: (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (primary) RemoteButtonBg else Color(0xFF2D2D32),
        border = BorderStroke(1.dp, if (primary) RemoteAccentBlue.copy(alpha = 0.45f) else RemoteButtonBorder.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(38.dp).background(RemoteBg, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(RemoteIcon, contentDescription = null, tint = if (primary) RemoteAccentBlue else Color.White.copy(alpha = 0.75f), modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onForget != null) {
                IconButton(onClick = onForget, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Forget saved TV", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
        }
    }
}
