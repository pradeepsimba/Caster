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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
 * Shows a video from this phone on a Chromecast. Choosing the Chromecast uses Google's own device dialog; picking
 * the video is done in the app's picker ([onChooseVideo]).
 */
@Composable
fun CastCard(onChooseVideo: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Before the invisible button below is created, which needs the Cast library started. remember must not return Unit.
    remember(context) { CastController.init(context); true }
    val cast by CastController.state.collectAsStateWithLifecycle()

    // Google's chooser dialog is opened by clicking its own (invisible) button; the visible controls are ours.
    var routeButton by remember { mutableStateOf<MediaRouteButton?>(null) }

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
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(CastIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Chromecast", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                when {
                    !cast.available -> Text(
                        "Chromecast needs Google Play services, which this phone doesn't have.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    cast.device == null -> {
                        Text(
                            "Play a video from this phone on a Chromecast on the same Wi-Fi.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        cast.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Button(
                            onClick = { routeButton?.performClick() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("Choose Chromecast") }
                    }
                    else -> {
                        Text("Connected to ${cast.device}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        cast.title?.let {
                            Text(
                                (if (cast.playing) "Playing " else "") + it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        cast.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
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
                }
            }
        }
    }
}
