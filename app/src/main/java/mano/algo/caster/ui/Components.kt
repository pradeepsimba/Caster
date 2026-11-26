package mano.algo.caster.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import mano.algo.caster.server.ShareMode
import mano.algo.caster.server.ShareSelection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mano.algo.caster.R
import mano.algo.caster.server.PairRequest
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** The corner size every card in the app shares. */
val CardShape = RoundedCornerShape(28.dp)

/**
 * The app's one card style: a softly tinted surface with a hairline edge instead of a shadow. [high] is one step
 * lighter, for cards that sit on top of the plain ones.
 */
@Composable
fun CasterCard(
    modifier: Modifier = Modifier,
    high: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = if (high) scheme.surfaceContainerHigh else scheme.surfaceContainerLow),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        content = content,
    )
}

/** The deep gradient behind the "sharing is on" card. */
val HeroBrush: Brush
    get() = Brush.linearGradient(
        colors = listOf(
            mano.algo.caster.ui.theme.HeroStart,
            mano.algo.caster.ui.theme.HeroMid,
            mano.algo.caster.ui.theme.HeroEnd,
        ),
    )

/** Shrinks a little while it is pressed, so a button feels like something that is pushed. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, tween(90), label = "pressScale")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** The app logo; [rings] adds expanding signal rings behind it while sharing is live. */
@Composable
fun PulsingLogo(
    rings: Boolean,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.colorScheme.primary,
    size: androidx.compose.ui.unit.Dp = 176.dp,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (rings) Rings(ringColor)
        Image(
            painter = painterResource(R.drawable.ic_caster_logo),
            contentDescription = "Caster",
            modifier = Modifier.size(size / 2),
        )
    }
}

@Composable
private fun Rings(color: Color) {
    val transition = rememberInfiniteTransition(label = "rings")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "phase",
    )
    // The animated value is read inside the draw block, so each frame only redraws; nothing recomposes.
    Canvas(Modifier.fillMaxSize()) {
        val maxRadius = size.minDimension / 2
        // A soft glow under the logo, then the rings spreading out from it.
        drawCircle(
            brush = Brush.radialGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent), radius = maxRadius * 0.9f),
            radius = maxRadius * 0.9f,
        )
        repeat(3) { i ->
            val p = (phase.value + i / 3f) % 1f
            drawCircle(
                color = color.copy(alpha = (1f - p) * 0.45f),
                radius = maxRadius * (0.5f + 0.5f * p),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/** A small coloured dot; when [pulsing] a ring spreads out from it, the way a "live" light does. */
@Composable
fun StatusDot(color: Color, pulsing: Boolean) {
    Box(modifier = Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        if (pulsing) {
            val transition = rememberInfiniteTransition(label = "dot")
            val phase = transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
                label = "dotPhase",
            )
            Box(
                Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        val p = phase.value
                        scaleX = 1f + 1.2f * p
                        scaleY = 1f + 1.2f * p
                        alpha = (1f - p) * 0.55f
                    }
                    .background(color, CircleShape),
            )
        }
        Box(Modifier.size(10.dp).background(color, CircleShape))
    }
}

@Composable
fun StatusPill(label: String, color: Color, pulsing: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusDot(color, pulsing)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A frosted button for use on the dark gradient card. */
@Composable
fun GlassButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.16f),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
fun PairingCard(
    request: PairRequest,
    selection: ShareSelection,
    onApprove: (durationMs: Long, videoMode: ShareMode, photoMode: ShareMode?, musicMode: ShareMode?) -> Unit,
    onDeny: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var access by rememberSaveable { mutableStateOf(0L) }
    var videoMode by rememberSaveable { mutableStateOf(selection.mode) }
    var photoMode by rememberSaveable { mutableStateOf(selection.photoMode) }
    var musicMode by rememberSaveable { mutableStateOf(selection.musicMode) }

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
    ) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Connection request", style = MaterialTheme.typography.labelLarge, modifier = Modifier.alpha(0.8f))
            Text(
                "${request.label} wants to connect",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                request.code.forEach { digit ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                        Box(modifier = Modifier.size(width = 56.dp, height = 72.dp), contentAlignment = Alignment.Center) {
                            Text(digit.toString(), style = MaterialTheme.typography.displaySmall)
                        }
                    }
                }
            }
            Text(
                "Approve only if this code matches the one on that screen.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(0.85f),
            )

            // Video sharing options
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Videos to share", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ShareMode.FOLDERS to "Folders", ShareMode.SELECTED to "Only some").forEach { (mode, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = videoMode == mode,
                            onClick = { videoMode = mode },
                            label = { Text(label) },
                        )
                    }
                }
            }

            // Photos & Music sharing options (without "All" option)
            if (selection.shareExtras) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Photos to share", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ShareMode.FOLDERS to "Folders", ShareMode.SELECTED to "Only some").forEach { (mode, label) ->
                            androidx.compose.material3.FilterChip(
                                selected = photoMode == mode,
                                onClick = { photoMode = mode },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Music to share", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(ShareMode.FOLDERS to "Folders", ShareMode.SELECTED to "Only some").forEach { (mode, label) ->
                            androidx.compose.material3.FilterChip(
                                selected = musicMode == mode,
                                onClick = { musicMode = mode },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0L to "Always", 60 * 60_000L to "1 hour", 24 * 60 * 60_000L to "1 day").forEach { (ms, label) ->
                    androidx.compose.material3.FilterChip(selected = access == ms, onClick = { access = ms }, label = { Text(label) })
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.Reject); onDeny() },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Deny") }
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        onApprove(access, videoMode, if (selection.shareExtras) photoMode else null, if (selection.shareExtras) musicMode else null)
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Approve")
                }
            }
        }
    }
}

/** Something that stops sharing from working properly, and the one button that puts it right. */
class SetupWarning(val title: String, val text: String, val button: String, val onClick: () -> Unit)

@Composable
fun SetupWarningCard(warning: SetupWarning) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(modifier = Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(warning.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(warning.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Button(onClick = warning.onClick, shape = RoundedCornerShape(14.dp)) { Text(warning.button) }
            }
        }
    }
}

@Composable
fun BatteryCard(onAllow: () -> Unit, onSettings: () -> Unit) {
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(modifier = Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Keep sharing when the screen is off",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    "Some phones freeze apps when the screen turns off, which stops sharing. Allow background running to prevent it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onAllow, shape = RoundedCornerShape(14.dp)) { Text("Allow background") }
                    TextButton(onClick = onSettings) { Text("Battery settings") }
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_caster_logo),
            contentDescription = null,
            modifier = Modifier.size(64.dp).alpha(0.35f),
        )
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
