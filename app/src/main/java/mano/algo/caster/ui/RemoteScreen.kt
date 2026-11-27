package mano.algo.caster.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mano.algo.caster.cast.CastController
import mano.algo.caster.server.RemoteControl
import mano.algo.caster.server.RemoteState
import mano.algo.caster.tv.InstalledTvApp
import mano.algo.caster.tv.TvApp
import mano.algo.caster.tv.TvInput
import mano.algo.caster.tv.TvKey
import mano.algo.caster.tv.TvRemote
import mano.algo.caster.tv.WakeOnLan
import mano.algo.caster.ui.theme.LiveGreen
import java.util.Locale

// ---- Custom Icons matching the reference remote aesthetic ----------------------------------------

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

private val InputPlugIcon: ImageVector by lazy {
    ImageVector.Builder("InputPlug", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M16,7V3h-2v4h-4V3H8v4h-1c-1.1,0 -2,0.9 -2,2v5.5L8.5,18v3h7v-3L19,14.5V9c0,-1.1 -0.9,-2 -2,-2h-1z"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val ChevronDownIcon: ImageVector by lazy {
    ImageVector.Builder("ChevronDown", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(pathData = addPathNodes("M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z"), fill = SolidColor(Color.Black))
    }.build()
}

private val GuideListIcon: ImageVector by lazy {
    ImageVector.Builder("GuideList", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M4,8h4v2H4V8zm6,0h10v2H10V8zm-6,6h4v2H4v-2zm6,0h10v2H10v-2z"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val PlayPauseIcon: ImageVector by lazy {
    ImageVector.Builder("PlayPause", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(pathData = addPathNodes("M4,18l8.5,-6L4,6v12zm9,-12v12h2V6h-2zm4,0v12h2V6h-2z"), fill = SolidColor(Color.Black))
    }.build()
}

private val RewindIcon: ImageVector by lazy {
    ImageVector.Builder("Rewind", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(pathData = addPathNodes("M11,18V6l-8.5,6 8.5,6zm0.5,-6l8.5,6V6l-8.5,6z"), fill = SolidColor(Color.Black))
    }.build()
}

private val FastForwardIcon: ImageVector by lazy {
    ImageVector.Builder("FastForward", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(pathData = addPathNodes("M4,18l8.5,-6L4,6v12zm9,-12v12l8.5,-6L13,6z"), fill = SolidColor(Color.Black))
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

private val VolumeUpIcon: ImageVector by lazy {
    ImageVector.Builder("VolumeUp", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(
                "M3,9v6h4l5,5V4L7,9H3zm13.5,3c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val UndoBackIcon: ImageVector by lazy {
    ImageVector.Builder("UndoBack", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(
                "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val HomeOutlineIcon: ImageVector by lazy {
    ImageVector.Builder("HomeOutline", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M12,5.69l5,4.5V18h-2v-6H9v6H7v-7.81l5,-4.5M12,3L2,12h3v8h6v-6h2v6h6v-8h3L12,3z"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val AssistantLoopIcon: ImageVector by lazy {
    ImageVector.Builder("AssistantLoop", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(
                "M18.6,6.62c-1.44,0 -2.8,0.56 -3.77,1.53L12,10.66 10.48,12h0.01L7.8,14.39c-0.64,0.64 -1.49,0.99 -2.4,0.99 -1.87,0 -3.39,-1.51 -3.39,-3.38S3.53,8.62 5.4,8.62c0.91,0 1.76,0.35 2.44,1.03l1.13,1 1.51,-1.34L9.22,8.2C8.2,7.18 6.84,6.62 5.4,6.62 2.42,6.62 0,9.04 0,12s2.42,5.38 5.4,5.38c1.44,0 2.8,-0.56 3.77,-1.53l2.83,-2.5 0.01,-0.01L13.52,12h-0.01l2.69,-2.39c0.64,-0.64 1.49,-0.99 2.4,-0.99 1.87,0 3.39,1.51 3.39,3.38s-1.52,3.38 -3.39,3.38c-0.9,0 -1.76,-0.35 -2.44,-1.03l-1.14,-1.01 -1.51,1.34 1.27,1.12c1.02,1.01 2.37,1.57 3.82,1.57 2.98,0 5.4,-2.41 5.4,-5.38s-2.42,-5.37 -5.4,-5.37z",
            ),
            fill = Brush.linearGradient(
                listOf(Color(0xFF4F8DFF), Color(0xFFA855F7), Color(0xFFEC4899)),
            ),
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

private val LiveTvIcon: ImageVector by lazy {
    ImageVector.Builder("LiveTv", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zm0,14H3V5h18v12z"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val SetTopBoxIcon: ImageVector by lazy {
    ImageVector.Builder("SetTopBox", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M20,8H4c-1.1,0 -2,0.9 -2,2v4c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2v-4c0,-1.1 -0.9,-2 -2,-2zm0,6H4v-4h16v4zM6,11h6v2H6v-2zm8,0h2v2h-2v-2zm3,0h1v2h-1v-2z"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val HdmiPortIcon: ImageVector by lazy {
    ImageVector.Builder("HdmiPort", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M18,7H6C4.9,7 4,7.9 4,9v4l2,3h12l2,-3V9C20,7.9 19.1,7 18,7zM18,12.3l-1.2,1.7H7.2L6,12.3V9h12V12.3zM8,10.5h8v1.5H8V10.5z"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val WebGlobeIcon: ImageVector by lazy {
    ImageVector.Builder("WebGlobe", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zm-1,17.93c-3.95,-0.49 -7,-3.85 -7,-7.93 0,-0.62 0.08,-1.21 0.21,-1.79L9,15v1c0,1.1 0.9,2 2,2v1.93zm6.9,-2.54c-0.26,-0.81 -1,-1.39 -1.9,-1.39h-1v-3c0,-0.55 -0.45,-1 -1,-1H8v-2h2c0.55,0 1,-0.45 1,-1V7h2c1.1,0 2,-0.9 2,-2v-0.41c2.93,1.19 5,4.06 5,7.41 0,2.08 -0.8,3.97 -2.1,5.39z"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

private val StopSquareIcon: ImageVector by lazy {
    ImageVector.Builder("StopSquare", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(pathData = addPathNodes("M6,6h12v12H6z"), fill = SolidColor(Color.Black))
    }.build()
}

private val FullscreenIcon: ImageVector by lazy {
    ImageVector.Builder("Fullscreen", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes("M7,14H5v5h5v-2H7v-3zm-2,-4h2V7h3V5H5v5zm12,7h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z"),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

// ---- Remote Palette (matching the dark reference screenshots) ------------------------------------

private val RemoteBg = Color(0xFF141416)
private val RemoteSheetBg = Color(0xFF1D1D20)
private val RemotePillDark = Color(0xFF0A0A0C)
private val RemoteTileBg = Color(0xFF3A3A3E)
private val RemoteTilePressed = Color(0xFF4E4E54)
private val RemoteWellBg = Color(0xFF070708)
private val RemoteOkRingBg = Color(0xFF424246)
private val RemoteBottomBarBg = Color(0xFF1E1E22)
private val RemotePowerRed = Color(0xFFE03A3A)
private val RemoteInputBlue = Color(0xFF5E6BF5)
private val RemoteAccentBlue = Color(0xFF5EA8FF)
private val RemoteBorderSubtle = Color(0xFF2C2C30)

// ---- State & Capabilities ------------------------------------------------------------------------

private enum class LowerMode { DPAD, TOUCHPAD, NUMPAD }

private enum class UpperPage { TV, MEDIA, APPS, EXTRA }

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
            TvRemote.Link.Adb -> "Android TV"
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
        override val subtitle: String = when {
            remote.title.isBlank() -> "Browsing index page"
            remote.playing -> "Playing on screen"
            else -> "Paused on screen"
        }
    }
}

private data class RemoteCapabilities(
    val power: Boolean = false,
    val dpad: Boolean = false,
    val back: Boolean = false,
    val home: Boolean = false,
    val playPause: Boolean = false,
    val seekButtons: Boolean = false,
    val prev: Boolean = false,
    val next: Boolean = false,
    val stop: Boolean = false,
    val seekBar: Boolean = false,
    val subtitles: Boolean = false,
    val volumeButtons: Boolean = false,
    val volumeSlider: Boolean = false,
    val volumeSetup: Boolean = false,
    val channels: Boolean = false,
    val guide: Boolean = false,
    val numpad: Boolean = false,
    val inputs: Boolean = false,
    val trackpad: Boolean = false,
    val voice: Boolean = false,
    val keyboard: Boolean = false,
    val apps: Boolean = false,
)

private fun capabilitiesFor(target: RemoteTarget?, volume: TvRemote.VolumeState): RemoteCapabilities = when (target) {
    is RemoteTarget.Tv -> {
        val full = target.link != TvRemote.Link.FireTv
        val volReady = full || volume is TvRemote.VolumeState.Ready
        RemoteCapabilities(
            power = TvRemote.supportsKey(target.link, TvKey.POWER),
            dpad = true,
            back = true,
            home = true,
            playPause = true,
            seekButtons = true,
            volumeButtons = volReady,
            volumeSetup = !volReady && TvRemote.adbSupported,
            channels = full,
            guide = full,
            numpad = full,
            inputs = full,
            trackpad = true,
            voice = true,
            keyboard = true,
            apps = full,
        )
    }
    is RemoteTarget.Web -> {
        val hasVideo = target.remote.title.isNotBlank()
        RemoteCapabilities(
            dpad = true,
            back = hasVideo,
            playPause = hasVideo,
            seekButtons = hasVideo,
            seekBar = hasVideo && target.remote.durationSec > 0,
            subtitles = hasVideo,
            volumeButtons = hasVideo && target.remote.volume >= 0,
            volumeSlider = hasVideo && target.remote.volume >= 0,
        )
    }
    is RemoteTarget.Cast -> RemoteCapabilities(
        playPause = true,
        seekButtons = true,
        prev = true,
        next = true,
        stop = true,
        volumeButtons = true,
        volumeSlider = false,
    )
    null -> RemoteCapabilities()
}

private fun formatClock(sec: Int): String =
    if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
    else "%d:%02d".format(sec / 60, sec % 60)

/**
 * Unified adaptive remote UI for TV (Fire TV, Android TV, Samsung, LG, ADB), Chromecast, and Web Screens.
 * Features and pages are limited dynamically based on what the connected device supports.
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
    val tvInputs by TvRemote.inputs.collectAsStateWithLifecycle()
    val activeTvInputId by TvRemote.activeInputId.collectAsStateWithLifecycle()
    val installedApps by TvRemote.installedApps.collectAsStateWithLifecycle()

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

    // Automatically switch remote when a device starts playing or connects
    val playingWebId = liveRemotes.firstOrNull { it.playing }?.let { "web:${it.deviceId}" }
    LaunchedEffect(castState.device, castState.playing) {
        if (castState.device != null && castState.playing) selectedTargetId = "cast"
    }
    LaunchedEffect(playingWebId) {
        if (playingWebId != null) selectedTargetId = playingWebId
    }
    LaunchedEffect(state) {
        if (state is TvRemote.State.Connected) selectedTargetId = "tv"
    }

    val activeTarget = targets.firstOrNull { it.id == selectedTargetId } ?: targets.firstOrNull()
    val caps = capabilitiesFor(activeTarget, volume)

    var lowerMode by rememberSaveable { mutableStateOf(LowerMode.DPAD) }
    var showDevicesPanel by rememberSaveable { mutableStateOf(false) }
    var showInputsSheet by rememberSaveable { mutableStateOf(false) }

    val forceDevices = activeTarget == null || state is TvRemote.State.Connecting || state is TvRemote.State.NeedsPin
    val showingDevices = forceDevices || showDevicesPanel

    val effectiveLowerMode = when {
        lowerMode == LowerMode.TOUCHPAD && !caps.trackpad -> LowerMode.DPAD
        lowerMode == LowerMode.NUMPAD && !caps.numpad -> LowerMode.DPAD
        else -> lowerMode
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(RemoteBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top Bar: Power (left) | Inputs / Active Device v (center) | Settings (right)
            RemoteTopBar(
                active = activeTarget,
                multiTarget = targets.size > 1,
                hasPower = caps.power && !showingDevices,
                onPower = { onKey(TvKey.POWER, false) },
                onOpenInputs = { showInputsSheet = true },
                onToggleSettings = {
                    if (!forceDevices) showDevicesPanel = !showDevicesPanel
                },
            )

            Spacer(Modifier.height(10.dp))

            if (showingDevices) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                            showDevicesPanel = false
                            lowerMode = LowerMode.DPAD
                        },
                        onConnectAdb = { host ->
                            onConnectAdb(host)
                            showDevicesPanel = false
                            lowerMode = LowerMode.DPAD
                        },
                        onSubmitPin = onSubmitPin,
                        onDisconnect = {
                            onDisconnect()
                            showDevicesPanel = false
                        },
                        onScan = onScan,
                    )
                }
            } else {
                UnifiedRemoteBody(
                    target = activeTarget,
                    caps = caps,
                    volume = volume,
                    lowerMode = effectiveLowerMode,
                    installedApps = installedApps,
                    shareUrl = shareUrl,
                    voiceError = voiceError,
                    onEnableVolume = onEnableVolume,
                    onKey = onKey,
                    onTyped = onTyped,
                    onOpenCaster = onOpenCaster,
                    onOpenBrowser = onOpenBrowser,
                    onVoiceStart = onVoiceStart,
                    onVoiceEnd = onVoiceEnd,
                    onRemote = onRemote,
                    onChangeLowerMode = { lowerMode = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }

        // 1st Image: Inputs bottom sheet overlay showing connected device & available ports
        AnimatedVisibility(
            visible = showInputsSheet,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.62f))
                    .clickable(onClick = { showInputsSheet = false }),
            )
        }

        AnimatedVisibility(
            visible = showInputsSheet,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            InputsSheetOverlay(
                targets = targets,
                activeTarget = activeTarget,
                tvInputs = if (activeTarget is RemoteTarget.Tv && caps.inputs) tvInputs else emptyList(),
                activeTvInputId = activeTvInputId,
                onSelectTarget = { target ->
                    selectedTargetId = target.id
                    showDevicesPanel = false
                    lowerMode = LowerMode.DPAD
                    showInputsSheet = false
                },
                onSelectTvInput = { input ->
                    TvRemote.switchInput(input)
                    showInputsSheet = false
                },
                onManageDevices = {
                    showInputsSheet = false
                    showDevicesPanel = true
                },
            )
        }
    }
}

@Composable
private fun RemoteTopBar(
    active: RemoteTarget?,
    multiTarget: Boolean,
    hasPower: Boolean,
    onPower: () -> Unit,
    onOpenInputs: () -> Unit,
    onToggleSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Left: Red Power button (only when device supports Power)
        if (hasPower) {
            CircleRemoteButton(
                label = "Power",
                size = 44.dp,
                containerColor = RemotePowerRed,
                onClick = onPower,
            ) {
                Icon(PowerIcon, contentDescription = "Power", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        } else {
            Spacer(Modifier.size(44.dp))
        }

        // Center: Inputs / Device switcher pill
        Surface(
            onClick = onOpenInputs,
            shape = RoundedCornerShape(22.dp),
            color = RemotePillDark,
            modifier = Modifier.height(44.dp).width(176.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Icon(InputPlugIcon, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
                Text(
                    text = when {
                        active == null -> "Devices"
                        multiTarget || active !is RemoteTarget.Tv -> active.label
                        else -> "Inputs"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(ChevronDownIcon, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
            }
        }

        // Right: Settings / Devices gear button
        CircleRemoteButton(
            label = "Devices and settings",
            size = 44.dp,
            containerColor = RemotePillDark,
            onClick = onToggleSettings,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Devices", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

// ---- Unified Remote Body (adapts pages, lower area, and bottom bar to device support) ----------

@Composable
private fun UnifiedRemoteBody(
    target: RemoteTarget?,
    caps: RemoteCapabilities,
    volume: TvRemote.VolumeState,
    lowerMode: LowerMode,
    installedApps: List<InstalledTvApp>,
    shareUrl: String?,
    voiceError: String?,
    onEnableVolume: () -> Unit,
    onKey: (Int, Boolean) -> Unit,
    onTyped: (String, String) -> Unit,
    onOpenCaster: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onVoiceStart: () -> Boolean,
    onVoiceEnd: () -> Unit,
    onRemote: (String, String) -> Unit,
    onChangeLowerMode: (LowerMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holdToTalk = (target as? RemoteTarget.Tv)?.link == TvRemote.Link.AndroidTv

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

    val pages = remember(caps) {
        buildList {
            if (caps.channels || caps.guide || caps.numpad) add(UpperPage.TV)
            if (caps.playPause || caps.seekButtons) add(UpperPage.MEDIA)
            if (caps.apps) add(UpperPage.APPS)
            if (caps.keyboard) add(UpperPage.EXTRA)
        }
    }
    val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 1. Upper Paged Controls (TV / Media / Apps / Keyboard)
        if (pages.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (pages.size > 1) 166.dp else 152.dp),
            ) { index ->
                when (pages[index]) {
                    UpperPage.TV -> TvUpperPage(
                        caps = caps,
                        volume = volume,
                        onEnableVolume = onEnableVolume,
                        onKey = onKey,
                        onToggleNumpad = {
                            onChangeLowerMode(if (lowerMode == LowerMode.NUMPAD) LowerMode.DPAD else LowerMode.NUMPAD)
                        },
                    )
                    UpperPage.MEDIA -> MediaUpperPage(
                        target = target,
                        caps = caps,
                        volume = volume,
                        onEnableVolume = onEnableVolume,
                        onKey = onKey,
                        onRemote = onRemote,
                    )
                    UpperPage.APPS -> AppsUpperPage(
                        apps = installedApps,
                        onLaunch = { app -> TvRemote.launchAppById(app.id, app.fallbackApp) },
                    )
                    UpperPage.EXTRA -> ExtraUpperPage(
                        target = target,
                        caps = caps,
                        shareUrl = shareUrl,
                        onTyped = onTyped,
                        onKey = onKey,
                        onOpenCaster = onOpenCaster,
                        onOpenBrowser = onOpenBrowser,
                        onRemote = onRemote,
                    )
                }
            }

            // Page indicator dots
            if (pages.size > 1) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(pages.size) { index ->
                        val active = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (active) Color(0xFFB5B5BC) else Color(0xFF424248))
                                .clickable { scope.launch { pagerState.animateScrollToPage(index) } },
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }

        if (holdToTalk && voiceError != null) {
            Text(voiceError, style = MaterialTheme.typography.bodySmall, color = RemotePowerRed)
        }

        // 2. Lower Control Area (D-pad / Touchpad / Numpad for TV; Media & Volume Pad for Cast/Web)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                target is RemoteTarget.Tv && lowerMode == LowerMode.NUMPAD && caps.numpad -> {
                    NumpadLowerCard(
                        onKey = onKey,
                        onBackToDpad = { onChangeLowerMode(LowerMode.DPAD) },
                    )
                }
                target is RemoteTarget.Tv && lowerMode == LowerMode.TOUCHPAD && caps.trackpad -> {
                    TouchpadLowerCard(
                        isLg = target.link == TvRemote.Link.Lg,
                        hasMute = caps.volumeButtons,
                        onKey = onKey,
                        onSwitchToDpad = { onChangeLowerMode(LowerMode.DPAD) },
                    )
                }
                target is RemoteTarget.Tv -> {
                    DpadLowerArea(
                        hasMute = caps.volumeButtons,
                        hasTouchpad = caps.trackpad,
                        onKey = onKey,
                        onEnableTouchpad = { onChangeLowerMode(LowerMode.TOUCHPAD) },
                    )
                }
                else -> {
                    CastOrWebLowerCard(
                        target = target,
                        caps = caps,
                        onRemote = onRemote,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 3. Bottom Bar: 3 wide pill buttons (Back | Home | Assistant for TV; Prev | Stop | Next/CC for Cast/Web)
        RemoteBottomBar(
            target = target,
            caps = caps,
            holdToTalk = holdToTalk,
            onKey = onKey,
            onVoiceStart = onVoiceStart,
            onVoiceEnd = onVoiceEnd,
            onDictate = ::dictate,
            onRemote = onRemote,
        )
    }
}

// ---- Upper Pages ---------------------------------------------------------------------------------

@Composable
private fun TvUpperPage(
    caps: RemoteCapabilities,
    volume: TvRemote.VolumeState,
    onEnableVolume: () -> Unit,
    onKey: (Int, Boolean) -> Unit,
    onToggleNumpad: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: VOL pill
        VolumePillOrSetup(
            caps = caps,
            volume = volume,
            onEnableVolume = onEnableVolume,
            onVolUp = { onKey(TvKey.VOLUME_UP, false) },
            onVolDown = { onKey(TvKey.VOLUME_DOWN, false) },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )

        // Middle: 123 (with 4 color dots) + GUIDE
        Column(
            modifier = Modifier.weight(1.05f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                onClick = onToggleNumpad,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = RemoteTileBg,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("123", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5)).forEach { c ->
                            Box(modifier = Modifier.size(6.dp).background(c, CircleShape))
                        }
                    }
                }
            }

            Surface(
                onClick = { onKey(TvKey.GUIDE, false) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = RemoteTileBg,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(GuideListIcon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Text("GUIDE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }

        // Right: CH pill
        VerticalRockerTile(
            label = "CH",
            topSymbol = "⌃",
            bottomSymbol = "⌄",
            onTop = { onKey(TvKey.CHANNEL_UP, false) },
            onBottom = { onKey(TvKey.CHANNEL_DOWN, false) },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun MediaUpperPage(
    target: RemoteTarget?,
    caps: RemoteCapabilities,
    volume: TvRemote.VolumeState,
    onEnableVolume: () -> Unit,
    onKey: (Int, Boolean) -> Unit,
    onRemote: (String, String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (caps.volumeButtons || caps.volumeSetup) {
            VolumePillOrSetup(
                caps = caps,
                volume = volume,
                onEnableVolume = onEnableVolume,
                onVolUp = {
                    when (target) {
                        is RemoteTarget.Tv -> onKey(TvKey.VOLUME_UP, false)
                        is RemoteTarget.Cast -> CastController.adjustVolume(0.08)
                        is RemoteTarget.Web -> {
                            val next = ((target.remote.volume + 8).coerceIn(0, 100)) / 100f
                            onRemote(target.remote.deviceId, "volume:${"%.2f".format(Locale.US, next)}")
                        }
                        null -> Unit
                    }
                },
                onVolDown = {
                    when (target) {
                        is RemoteTarget.Tv -> onKey(TvKey.VOLUME_DOWN, false)
                        is RemoteTarget.Cast -> CastController.adjustVolume(-0.08)
                        is RemoteTarget.Web -> {
                            val next = ((target.remote.volume - 8).coerceIn(0, 100)) / 100f
                            onRemote(target.remote.deviceId, "volume:${"%.2f".format(Locale.US, next)}")
                        }
                        null -> Unit
                    }
                },
                modifier = Modifier.width(96.dp).fillMaxHeight(),
            )
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Top wide Play/Pause pill
            Surface(
                onClick = {
                    when (target) {
                        is RemoteTarget.Tv -> onKey(TvKey.PLAY_PAUSE, false)
                        is RemoteTarget.Cast -> CastController.togglePlayback()
                        is RemoteTarget.Web -> onRemote(target.remote.deviceId, "toggle")
                        null -> Unit
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = RemoteTileBg,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val icon = when (target) {
                        is RemoteTarget.Cast -> if (target.state.playing) PauseIcon else Icons.Filled.PlayArrow
                        is RemoteTarget.Web -> if (target.remote.playing) PauseIcon else Icons.Filled.PlayArrow
                        else -> PlayPauseIcon
                    }
                    Icon(icon, contentDescription = "Play or pause", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Bottom wide Rewind | Fast-Forward pill
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = RemoteTileBg,
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable {
                                when (target) {
                                    is RemoteTarget.Tv -> onKey(TvKey.REWIND, false)
                                    is RemoteTarget.Cast -> CastController.seekBy(-10_000L)
                                    is RemoteTarget.Web -> onRemote(target.remote.deviceId, "seek:-10")
                                    null -> Unit
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(RewindIcon, contentDescription = "Rewind", tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable {
                                when (target) {
                                    is RemoteTarget.Tv -> onKey(TvKey.FAST_FORWARD, false)
                                    is RemoteTarget.Cast -> CastController.seekBy(10_000L)
                                    is RemoteTarget.Web -> onRemote(target.remote.deviceId, "seek:10")
                                    null -> Unit
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(FastForwardIcon, contentDescription = "Fast forward", tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AppsUpperPage(
    apps: List<InstalledTvApp>,
    onLaunch: (InstalledTvApp) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var showAllDialog by rememberSaveable { mutableStateOf(false) }

    if (showAllDialog) {
        AlertDialog(
            onDismissRequest = { showAllDialog = false },
            title = { Text("Installed Apps") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    apps.forEach { app ->
                        Surface(
                            onClick = {
                                onLaunch(app)
                                showAllDialog = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                app.title,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAllDialog = false }) { Text("Close") } },
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        shape = RoundedCornerShape(28.dp),
        color = RemoteTileBg,
    ) {
        if (apps.isEmpty()) {
            Box(contentAlignment = Alignment.Center) {
                Text("No apps reported by TV", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            val hasOverflow = apps.size > 8
            val shown = if (hasOverflow) apps.take(7) else apps.take(8)
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val firstRow = shown.take(4)
                val secondRow = shown.drop(4)
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    firstRow.forEach { app ->
                        AppTileButton(
                            app = app,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onLaunch(app)
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    repeat(4 - firstRow.size) { Spacer(Modifier.weight(1f)) }
                }
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    secondRow.forEach { app ->
                        AppTileButton(
                            app = app,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onLaunch(app)
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    if (hasOverflow) {
                        Surface(
                            onClick = { showAllDialog = true },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF28282C),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("•••", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    val used = secondRow.size + if (hasOverflow) 1 else 0
                    repeat((4 - used).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AppTileButton(
    app: InstalledTvApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lower = app.title.lowercase()
    val bg = when {
        "youtube" in lower -> Color(0xFFFFFFFF)
        "netflix" in lower -> Color(0xFF141414)
        "prime" in lower -> Color(0xFF00A8E1)
        "hotstar" in lower -> Color(0xFF0F79E0)
        "spotify" in lower -> Color(0xFF1DB954)
        "xstream" in lower -> Color(0xFFB71C1C)
        "browser" in lower || "web" in lower -> Color(0xFF5C6BC0)
        else -> Color(0xFF505056)
    }
    val fg = if ("youtube" in lower) Color(0xFFFF0000) else Color.White

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = bg,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(6.dp)) {
            Text(
                text = app.title,
                color = fg,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ExtraUpperPage(
    target: RemoteTarget?,
    caps: RemoteCapabilities,
    shareUrl: String?,
    onTyped: (String, String) -> Unit,
    onKey: (Int, Boolean) -> Unit,
    onOpenCaster: (String) -> Unit,
    onOpenBrowser: () -> Unit,
    onRemote: (String, String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        shape = RoundedCornerShape(28.dp),
        color = RemoteTileBg,
    ) {
        when {
            target is RemoteTarget.Web -> {
                val remote = target.remote
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        remote.title.ifBlank { "Playing on screen" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                            Text(formatClock(shown.toInt()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                            Text(formatClock(remote.durationSec), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                    if (caps.subtitles) {
                        OutlinedButton(
                            onClick = { onRemote(remote.deviceId, "subs") },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                        ) {
                            Text("Subtitles (CC)", color = Color.White)
                        }
                    }
                }
            }
            target is RemoteTarget.Tv -> {
                val fullRemote = target.link != TvRemote.Link.FireTv
                var typed by rememberSaveable { mutableStateOf("") }
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
                        placeholder = { Text("Type text for TV…", color = Color.White.copy(alpha = 0.55f)) },
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (fullRemote) {
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
                            ) { Text("⌫", color = Color.White) }
                            Button(
                                onClick = { onKey(TvKey.ENTER, false); typed = "" },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                            ) { Text("Enter") }
                        } else if (shareUrl != null) {
                            Button(onClick = onOpenBrowser, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                                Text("Browser", maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { onTyped("", shareUrl); typed = "" },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                            ) { Text("Type URL", color = Color.White, maxLines = 1) }
                        }
                        if (shareUrl != null && fullRemote) {
                            Button(
                                onClick = { onOpenCaster(shareUrl) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1.4f),
                            ) { Text("Open Caster", maxLines = 1) }
                        }
                    }
                }
            }
        }
    }
}

// ---- Lower Control Areas -------------------------------------------------------------------------

@Composable
private fun DpadLowerArea(
    hasMute: Boolean,
    hasTouchpad: Boolean,
    onKey: (Int, Boolean) -> Unit,
    onEnableTouchpad: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(modifier = Modifier.fillMaxSize()) {
        if (hasMute) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onKey(TvKey.MUTE, false)
                },
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
            ) {
                Icon(VolumeOffIcon, contentDescription = "Mute", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(24.dp))
            }
        }

        // Large dark circular D-pad matching Image 2
        Box(
            modifier = Modifier
                .size(242.dp)
                .align(Alignment.Center)
                .background(RemoteWellBg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DpadDirectionHitArea("Up", TvKey.UP, onKey, Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) {
                Text("▲", fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
            }
            DpadDirectionHitArea("Down", TvKey.DOWN, onKey, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                Text("▼", fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
            }
            DpadDirectionHitArea("Left", TvKey.LEFT, onKey, Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
                Text("◀", fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
            }
            DpadDirectionHitArea("Right", TvKey.RIGHT, onKey, Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
                Text("▶", fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f))
            }

            // Center grey ring + dark rounded-square OK button
            Box(
                modifier = Modifier
                    .size(94.dp)
                    .clip(CircleShape)
                    .background(RemoteOkRingBg)
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
                    modifier = Modifier
                        .size(54.dp)
                        .background(RemoteWellBg, RoundedCornerShape(14.dp)),
                )
            }
        }

        // Bottom-left: Enable Touchpad button (only when supported)
        if (hasTouchpad) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onEnableTouchpad()
                },
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            ) {
                Icon(TouchpadIcon, contentDescription = "Enable touchpad", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun TouchpadLowerCard(
    isLg: Boolean,
    hasMute: Boolean,
    onKey: (Int, Boolean) -> Unit,
    onSwitchToDpad: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var sliderFrac by remember { mutableFloatStateOf(0.5f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(26.dp))
            .background(RemoteWellBg)
            .padding(14.dp),
    ) {
        // Main swipe + tap touchpad area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 36.dp)
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
        )

        if (hasMute) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onKey(TvKey.MUTE, false)
                },
                modifier = Modifier.align(Alignment.TopStart).size(36.dp),
            ) {
                Icon(VolumeOffIcon, contentDescription = "Mute", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
            }
        }

        // Bottom-left: Switch back to D-pad
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSwitchToDpad()
            },
            modifier = Modifier.align(Alignment.BottomStart).size(36.dp),
        ) {
            Icon(DpadIcon, contentDescription = "Switch to D-pad", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
        }

        // Right-side vertical scroll/slider strip matching Image 3 & 4
        VerticalTouchSlider(
            fraction = sliderFrac,
            onStepUp = {
                sliderFrac = (sliderFrac - 0.15f).coerceIn(0.15f, 0.85f)
                onKey(TvKey.UP, false)
            },
            onStepDown = {
                sliderFrac = (sliderFrac + 0.15f).coerceIn(0.15f, 0.85f)
                onKey(TvKey.DOWN, false)
            },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.82f),
        )
    }
}

@Composable
private fun CastOrWebLowerCard(
    target: RemoteTarget?,
    caps: RemoteCapabilities,
    onRemote: (String, String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var lastNonZeroWebVol by remember(target?.id) { mutableStateOf(100) }
    var optimisticWebMuted by remember(target?.id) { mutableStateOf<Boolean?>(null) }
    var optimisticCastMuted by remember(target?.id) { mutableStateOf(false) }

    val webVol = (target as? RemoteTarget.Web)?.remote?.volume ?: -1
    LaunchedEffect(webVol) {
        if (webVol > 0) lastNonZeroWebVol = webVol
        optimisticWebMuted = null
    }
    val isMuted = when (target) {
        is RemoteTarget.Web -> optimisticWebMuted ?: (webVol == 0)
        is RemoteTarget.Cast -> optimisticCastMuted
        else -> false
    }

    if (target is RemoteTarget.Web) {
        val remote = target.remote
        val hasVideo = remote.title.isNotBlank()
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Compact title & seekbar when a video is open
            if (hasVideo) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        remote.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                            modifier = Modifier.height(28.dp),
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatClock(shown.toInt()), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                            Text(formatClock(remote.durationSec), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            } else {
                Text(
                    "Browse index & select a video",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }

            // D-pad (Up, Down, Left, Right, Click) + corner action buttons
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (caps.volumeButtons) {
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (isMuted) {
                                val restore = lastNonZeroWebVol.coerceIn(10, 100)
                                optimisticWebMuted = false
                                onRemote(remote.deviceId, "volume:${"%.2f".format(Locale.US, restore / 100f)}")
                            } else {
                                if (webVol > 0) lastNonZeroWebVol = webVol
                                optimisticWebMuted = true
                                onRemote(remote.deviceId, "volume:0")
                            }
                        },
                        modifier = Modifier.align(Alignment.TopStart).size(44.dp),
                        shape = CircleShape,
                        color = if (isMuted) RemoteInputBlue else RemoteTileBg,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isMuted) VolumeOffIcon else VolumeUpIcon,
                                contentDescription = if (isMuted) "Unmute" else "Mute",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                if (hasVideo && caps.subtitles) {
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onRemote(remote.deviceId, "subs")
                        },
                        modifier = Modifier.align(Alignment.TopEnd).size(44.dp),
                        shape = CircleShape,
                        color = RemoteTileBg,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("CC", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (hasVideo) {
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onRemote(remote.deviceId, "fs")
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).size(44.dp),
                        shape = CircleShape,
                        color = RemoteTileBg,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(FullscreenIcon, contentDescription = "Fullscreen", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // Circular D-Pad for index page & player navigation
                val dpadSize = if (hasVideo) 182.dp else 222.dp
                val okRingSize = if (hasVideo) 72.dp else 88.dp
                val okInnerSize = if (hasVideo) 42.dp else 50.dp
                Box(
                    modifier = Modifier
                        .size(dpadSize)
                        .align(Alignment.Center)
                        .background(RemoteWellBg, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .size(54.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRemote(remote.deviceId, "nav:up")
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("▲", fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f)) }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .size(54.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRemote(remote.deviceId, "nav:down")
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("▼", fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f)) }

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 10.dp)
                            .size(54.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRemote(remote.deviceId, "nav:left")
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("◀", fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f)) }

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp)
                            .size(54.dp)
                            .clip(CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRemote(remote.deviceId, "nav:right")
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("▶", fontSize = 14.sp, color = Color.White.copy(alpha = 0.85f)) }

                    // Center Click / OK button
                    Box(
                        modifier = Modifier
                            .size(okRingSize)
                            .clip(CircleShape)
                            .background(RemoteOkRingBg)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRemote(remote.deviceId, "nav:ok")
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(okInnerSize)
                                .background(RemoteWellBg, RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
        }
        return
    }

    // Chromecast lower card
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(26.dp))
            .background(RemoteWellBg)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val title = (target as? RemoteTarget.Cast)?.state?.title ?: target?.label ?: "No media"
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                target?.subtitle.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (caps.volumeButtons) {
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        optimisticCastMuted = !optimisticCastMuted
                        CastController.toggleMute()
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = if (isMuted) RemoteInputBlue else RemoteTileBg,
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (isMuted) VolumeOffIcon else VolumeUpIcon,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            if (isMuted) "Unmute" else "Mute",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (caps.seekButtons) {
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        CastController.seekBy(-10_000L)
                    },
                    modifier = Modifier.weight(0.75f).height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = RemoteTileBg,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("−10s", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        CastController.seekBy(10_000L)
                    },
                    modifier = Modifier.weight(0.75f).height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = RemoteTileBg,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("+10s", style = MaterialTheme.typography.labelLarge, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun VerticalTouchSlider(
    fraction: Float,
    onStepUp: () -> Unit,
    onStepDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragAcc by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier
            .width(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF7A7A82))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { dragAcc = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragAcc += dragAmount
                        if (dragAcc < -28f) {
                            dragAcc = 0f
                            onStepUp()
                        } else if (dragAcc > 28f) {
                            dragAcc = 0f
                            onStepDown()
                        }
                    },
                )
            }
            .padding(3.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable(onClick = onStepUp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(RemoteWellBg, RoundedCornerShape(9.dp)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clickable(onClick = onStepDown),
            )
        }
    }
}

@Composable
private fun NumpadLowerCard(
    onKey: (Int, Boolean) -> Unit,
    onBackToDpad: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val digitKeys = mapOf(
        "0" to TvKey.DIGIT_0, "1" to TvKey.DIGIT_1, "2" to TvKey.DIGIT_2,
        "3" to TvKey.DIGIT_3, "4" to TvKey.DIGIT_4, "5" to TvKey.DIGIT_5,
        "6" to TvKey.DIGIT_6, "7" to TvKey.DIGIT_7, "8" to TvKey.DIGIT_8,
        "9" to TvKey.DIGIT_9,
    )
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("D-Pad", "0", "OK"),
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(26.dp))
            .background(RemoteWellBg)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // 4 Color buttons matching the 4 dots on "123"
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listOf(
                Color(0xFFE53935) to TvKey.PROG_RED,
                Color(0xFFFDD835) to TvKey.PROG_YELLOW,
                Color(0xFF43A047) to TvKey.PROG_GREEN,
                Color(0xFF1E88E5) to TvKey.PROG_BLUE,
            ).forEach { (color, key) ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(color)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onKey(key, false)
                        },
                )
            }
        }

        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { label ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                when (label) {
                                    "D-Pad" -> onBackToDpad()
                                    "OK" -> onKey(TvKey.OK, false)
                                    else -> digitKeys[label]?.let { onKey(it, false) }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = Color.White)
                    }
                }
            }
        }
    }
}

// ---- Bottom Bar (3 wide pills) -------------------------------------------------------------------

@Composable
private fun RemoteBottomBar(
    target: RemoteTarget?,
    caps: RemoteCapabilities,
    holdToTalk: Boolean,
    onKey: (Int, Boolean) -> Unit,
    onVoiceStart: () -> Boolean,
    onVoiceEnd: () -> Unit,
    onDictate: () -> Unit,
    onRemote: (String, String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (target) {
            is RemoteTarget.Tv -> {
                BottomPillButton(
                    label = "Back",
                    onClick = { onKey(TvKey.BACK, false) },
                    onLongPress = { TvRemote.longPress(TvKey.BACK) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(UndoBackIcon, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(22.dp))
                }

                BottomPillButton(
                    label = "Home",
                    onClick = { onKey(TvKey.HOME, false) },
                    onLongPress = { TvRemote.longPress(TvKey.HOME) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HomeOutlineIcon, contentDescription = "Home", tint = Color.White, modifier = Modifier.size(24.dp))
                }

                VoiceBottomPill(
                    holdToTalk = holdToTalk,
                    onHoldStart = onVoiceStart,
                    onHoldEnd = onVoiceEnd,
                    onDictate = onDictate,
                    modifier = Modifier.weight(1f),
                )
            }
            is RemoteTarget.Cast -> {
                BottomPillButton(
                    label = "Previous",
                    onClick = { CastController.prev() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(SkipPreviousIcon, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                BottomPillButton(
                    label = "Stop",
                    onClick = { CastController.stop() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(StopSquareIcon, contentDescription = "Stop", tint = Color.White, modifier = Modifier.size(22.dp))
                }
                BottomPillButton(
                    label = "Next",
                    onClick = { CastController.next() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(SkipNextIcon, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
            is RemoteTarget.Web -> {
                if (caps.back) {
                    BottomPillButton(
                        label = "Back",
                        onClick = { onRemote(target.remote.deviceId, "nav:back") },
                        modifier = Modifier.fillMaxWidth(0.45f),
                    ) {
                        Icon(UndoBackIcon, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                }
            }
            null -> Spacer(Modifier.height(52.dp))
        }
    }
}

// ---- 1st Image: Inputs Bottom Sheet Overlay ------------------------------------------------------

@Composable
private fun InputsSheetOverlay(
    targets: List<RemoteTarget>,
    activeTarget: RemoteTarget?,
    tvInputs: List<TvInput>,
    activeTvInputId: String?,
    onSelectTarget: (RemoteTarget) -> Unit,
    onSelectTvInput: (TvInput) -> Unit,
    onManageDevices: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = false, onClick = {}),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = RemoteSheetBg,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 42.dp, height = 4.dp)
                    .background(Color(0xFF56565E), RoundedCornerShape(2.dp)),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Inputs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                TextButton(onClick = onManageDevices) {
                    Text("Manage devices", color = RemoteAccentBlue)
                }
            }

            // Connected devices row (Chromecast, Web, TV)
            if (targets.size > 1 || (targets.isNotEmpty() && tvInputs.isEmpty())) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (tvInputs.isNotEmpty()) {
                        Text("Connected devices", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        targets.forEach { target ->
                            val selected = target.id == activeTarget?.id
                            val icon = when (target) {
                                is RemoteTarget.Cast -> CastIcon
                                is RemoteTarget.Web -> WebGlobeIcon
                                is RemoteTarget.Tv -> LiveTvIcon
                            }
                            InputCircleItem(
                                label = target.label,
                                icon = icon,
                                selected = selected,
                                connected = true,
                                onClick = { onSelectTarget(target) },
                            )
                        }
                    }
                }
            }

            // TV Ports / Inputs row (matching Image 1: Live TV, Set-Top Box, HDMI 2, HDMI 3)
            if (tvInputs.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (targets.size > 1) {
                        Text("${activeTarget?.label ?: "TV"} ports", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        tvInputs.forEach { input ->
                            val isSelected = input.id == activeTvInputId
                            val icon = when (input.kind) {
                                TvInput.InputKind.LIVE_TV -> LiveTvIcon
                                TvInput.InputKind.STB -> SetTopBoxIcon
                                TvInput.InputKind.HDMI, TvInput.InputKind.AV, TvInput.InputKind.OTHER -> HdmiPortIcon
                            }
                            InputCircleItem(
                                label = input.label,
                                icon = icon,
                                selected = isSelected,
                                connected = input.connected || isSelected,
                                onClick = { onSelectTvInput(input) },
                            )
                        }
                    }
                }
            }

            if (targets.isEmpty()) {
                Text(
                    "No devices connected yet. Tap \"Manage devices\" to connect a TV, Chromecast, or Web screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun InputCircleItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    connected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(74.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(64.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        when {
                            selected -> RemoteInputBlue
                            connected -> Color(0xFF4C4C52)
                            else -> Color(0xFF2E2E32)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (connected || selected) Color.White else Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(28.dp),
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.TopStart)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = "Active", tint = RemoteInputBlue, modifier = Modifier.size(14.dp))
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (connected || selected) Color.White else Color.White.copy(alpha = 0.5f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// ---- Reusable Remote Building Blocks -------------------------------------------------------------

@Composable
private fun VolumePillOrSetup(
    caps: RemoteCapabilities,
    volume: TvRemote.VolumeState,
    onEnableVolume: () -> Unit,
    onVolUp: () -> Unit,
    onVolDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (caps.volumeButtons) {
        VerticalRockerTile(
            label = "VOL",
            topSymbol = "+",
            bottomSymbol = "−",
            onTop = onVolUp,
            onBottom = onVolDown,
            modifier = modifier,
        )
    } else if (caps.volumeSetup) {
        Surface(
            onClick = onEnableVolume,
            modifier = modifier,
            shape = RoundedCornerShape(28.dp),
            color = RemoteTileBg,
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                Text(
                    when (volume) {
                        is TvRemote.VolumeState.Waiting -> "Allow\non TV"
                        is TvRemote.VolumeState.Failed -> "Retry\nVol"
                        else -> "Enable\nVol"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun VerticalRockerTile(
    label: String,
    topSymbol: String,
    bottomSymbol: String,
    onTop: () -> Unit,
    onBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = RemoteTileBg,
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
                Text(topSymbol, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Medium)
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
                Text(bottomSymbol, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            }
        }
    }
}

@Composable
private fun CircleRemoteButton(
    label: String,
    size: Dp,
    containerColor: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(80), label = "circlePress")
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(containerColor, CircleShape)
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick(label) { onClick(); true }
            }
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        try { tryAwaitRelease() } finally { pressed = false }
                    },
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
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
private fun BottomPillButton(
    label: String,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(80), label = "pillPress")
    Box(
        modifier = modifier
            .height(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(26.dp))
            .background(if (pressed) RemoteTilePressed else RemoteBottomBarBg)
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
                        try { tryAwaitRelease() } finally { pressed = false }
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
        content()
    }
}

@Composable
private fun VoiceBottomPill(
    holdToTalk: Boolean,
    onHoldStart: () -> Boolean,
    onHoldEnd: () -> Unit,
    onDictate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (down) 0.94f else 1f, tween(90), label = "voicePill")
    val label = if (holdToTalk) "Voice, hold and speak" else "Voice, tap and speak"
    Box(
        modifier = modifier
            .height(52.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(26.dp))
            .background(if (listening) RemotePowerRed else RemoteBottomBarBg)
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
                        try { tryAwaitRelease() } finally {
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
        Icon(AssistantLoopIcon, contentDescription = label, tint = Color.Unspecified, modifier = Modifier.size(26.dp))
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
            .size(62.dp)
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
        AlertDialog(
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
        AlertDialog(
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
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF202024),
        border = BorderStroke(1.dp, RemoteBorderSubtle),
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
                    if (state is TvRemote.State.Connected) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(modifier = Modifier.size(8.dp).background(LiveGreen, CircleShape))
                                Text("Connected to ${state.host}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            }
                            TextButton(onClick = onDisconnect) { Text("Disconnect", color = RemotePowerRed) }
                        }
                    }

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
                        } else {
                            TextButton(onClick = { macFor = primaryTv.address }) {
                                Text("Set up Wake-on-LAN", color = Color.White.copy(alpha = 0.7f))
                            }
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

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onScan, enabled = !scanning) {
                        Text("Rescan Wi-Fi", style = MaterialTheme.typography.labelSmall, color = RemoteAccentBlue)
                    }
                    TextButton(onClick = { showManualDialog = true }) {
                        Text("Add TV by IP", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
                    }
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
        color = if (primary) RemoteTileBg else Color(0xFF2B2B30),
        border = BorderStroke(1.dp, if (primary) RemoteAccentBlue.copy(alpha = 0.45f) else RemoteBorderSubtle),
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
