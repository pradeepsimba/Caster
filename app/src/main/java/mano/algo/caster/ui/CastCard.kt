package mano.algo.caster.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import mano.algo.caster.cast.CastController
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.clip
import mano.algo.caster.ui.theme.LiveGreen
import androidx.compose.runtime.LaunchedEffect

/** The Material "cast" glyph, which the core icon set doesn't include. */
private val CastIcon: ImageVector by lazy {
    ImageVector.Builder("Cast", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(
                "M21,3L3,3c-1.1,0 -2,0.9 -2,2v3h2L3,5h18v14h-7v2h7c1.1,0 2,-0.9 2,-2L23,5c0,-1.1 -0.9,-2 -2,-2z" +
                    "M1,18v3h3c0,-1.66 -1.34,-3 -3,-3z" +
                    "M1,14v2c2.76,0 5,2.24 5,5h2c0,-3.87 -3.13,-7 -7,-7z" +
                    "M1,10v2c4.97,0 9,4.03 9,9h2c0,-6.08 -4.93,-11 -11,-11z",
            ),
            fill = SolidColor(Color.Black),
        )
    }.build()
}

/**
 * Shows a video from this phone on a Chromecast. The Chromecast that is connected is listed first, then the ones used
 * before (tap one to connect; "Nearby" means it answers on this Wi-Fi right now). A new one is found with Google's own
 * device dialog; the video is chosen in the app's picker ([onChooseVideo]).
 */
@Composable
fun CastCard(onChooseVideo: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Before the invisible button below is created, which needs the Cast library started. remember must not return Unit.
    remember(context) { CastController.init(context); true }
    val cast by CastController.state.collectAsStateWithLifecycle()
    val recent by CastController.recent.collectAsStateWithLifecycle()
    val nearby by CastController.nearby.collectAsStateWithLifecycle()
    // Looking for Chromecasts only costs battery while this card is on screen.
    DisposableEffect(cast.available) {
        if (cast.available) CastController.startDiscovery(context)
        onDispose { CastController.stopDiscovery() }
    }

    // Google's chooser dialog is opened by clicking its own (invisible) button; the visible controls are ours.
    var routeButton by remember { mutableStateOf<MediaRouteButton?>(null) }

    // Asked for from elsewhere (a video was tapped while no Chromecast is connected): open the chooser.
    LaunchedEffect(Unit) { CastController.chooserRequests.collect { routeButton?.performClick() } }

    // A Box, so the invisible button adds no spacing to the Home column.
    Box(modifier = modifier.fillMaxWidth()) {
        // Only when the Cast library started: without Google Play services, setting the button up throws.
        if (cast.available) {
            AndroidView(
                factory = { ctx ->
                    MediaRouteButton(ctx).also {
                        runCatching { CastButtonFactory.setUpMediaRouteButton(ctx, it) }
                        routeButton = it
                    }
                },
                modifier = Modifier.size(0.dp).alpha(0f), // never drawn: the glyph would spill outside its 0 size
            )
        }

        CasterCard(modifier = Modifier.fillMaxWidth(), high = true) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(CastIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Chromecast", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (!cast.available) {
                    Text(
                        "Chromecast needs Google Play services, which this phone doesn't have.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                // The connected one first, then the ones used before.
                if (cast.device != null) DeviceRow(cast.device ?: "Chromecast", "Connected", DeviceDot.Connected, onClick = null)
                recent.filter { it.id != cast.deviceId }.forEach { device ->
                    val here = device.id in nearby
                    DeviceRow(device.name, if (here) "Nearby" else "Not found", if (here) DeviceDot.Nearby else DeviceDot.Away) {
                        CastController.connectTo(device)
                    }
                }
                if (cast.device == null && recent.isEmpty()) {
                    Text(
                        "Play a video from this phone on a Chromecast on the same Wi-Fi.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                cast.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

                if (cast.device != null) {
                    cast.title?.let {
                        Text(
                            (if (cast.playing) "Playing " else "") + it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onChooseVideo, shape = RoundedCornerShape(14.dp)) { Text("Choose video") }
                        if (cast.title != null && cast.error == null) {
                            OutlinedButton(onClick = CastController::togglePlayback, shape = RoundedCornerShape(14.dp)) {
                                Text(if (cast.playing) "Pause" else "Play")
                            }
                        }
                        TextButton(onClick = CastController::stop) { Text("Stop") }
                    }
                }
                if (cast.device == null && recent.isEmpty()) {
                    Button(
                        onClick = { routeButton?.performClick() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Choose Chromecast") }
                } else {
                    TextButton(onClick = { routeButton?.performClick() }) { Text("Find another Chromecast") }
                }
            }
        }
    }
}

private enum class DeviceDot { Connected, Nearby, Away }

/** One Chromecast in the list: a coloured dot, its name, and where it stands. Tappable unless it is the connected one. */
@Composable
private fun DeviceRow(name: String, status: String, dot: DeviceDot, onClick: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val color = when (dot) {
        DeviceDot.Connected -> LiveGreen
        DeviceDot.Nearby -> scheme.primary
        DeviceDot.Away -> scheme.outline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (dot == DeviceDot.Connected) scheme.primary.copy(alpha = 0.10f) else scheme.surfaceContainerLow)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
        Text(status, style = MaterialTheme.typography.labelMedium, color = if (dot == DeviceDot.Away) scheme.onSurfaceVariant else color)
    }
}
