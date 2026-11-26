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
import androidx.compose.ui.unit.dp
import mano.algo.caster.server.PairRequest
import mano.algo.caster.server.ServerState
import mano.algo.caster.ui.theme.LiveGreen
import mano.algo.caster.ui.theme.WarnAmber
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import mano.algo.caster.server.PairedDevice
import mano.algo.caster.server.ShareSelection
import mano.algo.caster.server.KnownDevice
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Offset
import mano.algo.caster.ui.theme.HeroEnd
import mano.algo.caster.ui.theme.HeroStart
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.layout.offset

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
    known: List<KnownDevice>,
    selection: ShareSelection,
    hasStorage: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onApprove: (durationMs: Long, videoMode: mano.algo.caster.server.ShareMode, photoMode: mano.algo.caster.server.ShareMode?, musicMode: mano.algo.caster.server.ShareMode?) -> Unit,
    onDeny: () -> Unit,
    onAllowBackground: () -> Unit,
    onAppSettings: () -> Unit,
    onCastChoose: () -> Unit,
    onDisconnect: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    onClearScreens: () -> Unit,
    onAll: () -> Unit,
    onSelected: () -> Unit,
    onChoose: () -> Unit,
    onRemoveChosen: (Long) -> Unit,
    onPlayChosen: (id: Long, name: String) -> Unit,
    onFoldersMode: () -> Unit,
    onSaveFolders: (Set<Long>) -> Unit,
    onAllowAccess: () -> Unit,
    onLimitScreen: (KnownDevice) -> Unit,
    onOpenSettings: () -> Unit,
    onPhotoFoldersMode: () -> Unit = {},
    onPhotoSelectedMode: () -> Unit = {},
    onChoosePhotos: () -> Unit = {},
    onRemovePhoto: (Long) -> Unit = {},
    onPlayPhoto: (id: Long, name: String) -> Unit = { _, _ -> },
    onSavePhotoFolders: (Set<Long>) -> Unit = {},
    onMusicFoldersMode: () -> Unit = {},
    onMusicSelectedMode: () -> Unit = {},
    onChooseMusic: () -> Unit = {},
    onRemoveMusic: (Long) -> Unit = {},
    onPlayMusic: (id: Long, name: String) -> Unit = { _, _ -> },
    onSaveMusicFolders: (Set<Long>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val running = state is ServerState.Running
    val screens = devices.count { it.label != mano.algo.caster.server.PairingManager.CHROMECAST_LABEL }
    var showConnect by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(running) { if (!running) showConnect = false } // a closed-and-reopened share starts without the dialog
    if (showConnect && state is ServerState.Running) {
        ConnectScreenDialog(state) { showConnect = false }
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
            ) { PairingCard(request, selection, onApprove, onDeny) }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .background(background)
                    // While sharing, a soft light in the top corner gives the deep gradient some depth.
                    .then(
                        if (running) {
                            Modifier.background(
                                Brush.radialGradient(
                                    listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                                    center = Offset(Float.POSITIVE_INFINITY, 0f),
                                    radius = 700f,
                                ),
                            )
                        } else Modifier,
                    )
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // The logo, a title and one line, side by side: the card stays small. While sharing, an info button sits in the top corner.
                Box(modifier = Modifier.fillMaxWidth()) {
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
                if (state is ServerState.Running) {
                    // How to connect a screen (the QR code and steps), kept out of the way as a small round "i".
                    Surface(
                        onClick = { showConnect = true },
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(38.dp),
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.22f),
                        contentColor = Color.White,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Info, contentDescription = "Connect a screen", modifier = Modifier.size(22.dp))
                        }
                    }
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
                        val stopSource = remember { MutableInteractionSource() }
                        // Stop: a wide pill in soft red glass. It is easy to find, but it does not shout.
                        Surface(
                            onClick = onStop,
                            interactionSource = stopSource,
                            modifier = Modifier.fillMaxWidth().height(48.dp).pressScale(stopSource),
                            shape = CircleShape,
                            color = Color(0xFFFF5A5F).copy(alpha = 0.26f),
                            contentColor = Color.White,
                            border = BorderStroke(1.dp, Color(0xFFFF9AA0).copy(alpha = 0.6f)),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Stop sharing", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            }
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

        LibrarySection(
            selection = selection,
            hasStorageAccess = hasStorage,
            onAll = onAll,
            onSelected = onSelected,
            onChoose = onChoose,
            onRemove = onRemoveChosen,
            onPlay = onPlayChosen,
            onFoldersMode = onFoldersMode,
            onSaveFolders = onSaveFolders,
            onAllowAccess = onAllowAccess,
            onPhotoFoldersMode = onPhotoFoldersMode,
            onPhotoSelectedMode = onPhotoSelectedMode,
            onChoosePhotos = onChoosePhotos,
            onRemovePhoto = onRemovePhoto,
            onPlayPhoto = onPlayPhoto,
            onSavePhotoFolders = onSavePhotoFolders,
            onMusicFoldersMode = onMusicFoldersMode,
            onMusicSelectedMode = onMusicSelectedMode,
            onChooseMusic = onChooseMusic,
            onRemoveMusic = onRemoveMusic,
            onPlayMusic = onPlayMusic,
            onSaveMusicFolders = onSaveMusicFolders,
        )

        YourScreensSection(
            known = known,
            devices = devices,
            running = running,
            // A screen tapped while sharing is off turns sharing on; while it is on, the connect window opens.
            onTap = {
                when (state) {
                    ServerState.Stopped, is ServerState.Error -> onStart()
                    is ServerState.Running -> showConnect = true
                    ServerState.Starting -> Unit
                }
            },
            onDisconnect = onDisconnect,
            onDisconnectAll = onDisconnectAll,
            onClear = onClearScreens,
            onLimit = onLimitScreen,
        )

        if (running) CastCard(onChooseVideo = onCastChoose)

        warnings.forEach { SetupWarningCard(it) }
        if (!batteryUnrestricted) BatteryCard(onAllowBackground, onAppSettings)
        Spacer(Modifier.height(8.dp))
    }
}
