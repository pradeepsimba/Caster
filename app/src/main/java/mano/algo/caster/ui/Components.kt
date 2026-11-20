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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mano.algo.caster.R
import mano.algo.caster.server.PairRequest

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

/** The app logo; [rings] adds expanding signal rings behind it while sharing is live. */
@Composable
fun PulsingLogo(rings: Boolean, modifier: Modifier = Modifier, ringColor: Color = MaterialTheme.colorScheme.primary) {
    Box(modifier = modifier.size(176.dp), contentAlignment = Alignment.Center) {
        if (rings) Rings(ringColor)
        Image(
            painter = painterResource(R.drawable.ic_caster_logo),
            contentDescription = "Caster",
            modifier = Modifier.size(88.dp),
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

/** The address to type on the other screen, with Copy / QR / Share under it. Meant for the dark gradient card. */
@Composable
fun AddressBox(url: String, compact: Boolean = false, onShare: (() -> Unit)? = null, onQr: (() -> Unit)? = null, onCopy: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.12f),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        ) {
            SelectionContainer {
                Text(
                    url,
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Copy", onCopy, Modifier.weight(1f))
                if (onQr != null) GlassButton("QR code", onQr, Modifier.weight(1f))
                if (onShare != null) GlassButton("Share", onShare, Modifier.weight(1f), icon = Icons.Filled.Share)
            }
        }
    }
}

@Composable
fun PairingCard(request: PairRequest, onApprove: () -> Unit, onDeny: () -> Unit) {
    val haptic = LocalHapticFeedback.current
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
                "${request.label} wants to watch your videos",
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.Reject); onDeny() },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Deny") }
                Button(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onApprove() },
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
