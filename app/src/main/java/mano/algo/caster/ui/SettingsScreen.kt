package mano.algo.caster.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mano.algo.caster.server.ServerState
import mano.algo.caster.server.ShareSettings
import mano.algo.caster.ui.theme.LiveGreen

@Composable
fun SettingsScreen(
    state: ServerState,
    preferredPort: Int,
    onSavePort: (Int) -> Unit,
    preferredName: String,
    onSaveName: (String) -> Unit,
    shareExtras: Boolean,
    onShareExtras: (Boolean) -> Unit,
    autoStopMinutes: Int,
    onAutoStop: (Int) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    lockOn: Boolean,
    onSetPin: () -> Unit,
    onRemovePin: () -> Unit,
    onOpenHistory: () -> Unit,
    onClearResume: () -> Unit,
    wifiOffer: Boolean,
    onWifiOffer: (Boolean) -> Unit,
    stopOnWifiLost: Boolean,
    onStopOnWifiLost: (Boolean) -> Unit,
    password: String,
    onSetPassword: (String) -> Unit,
    allowDownload: Boolean = true,
    onAllowDownload: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // 1. TOP HERO: Web connection address & quick actions
        AddressHeroCard(state = state, onCopy = onCopy, onShare = onShare)

        // 2. SHARING & AUTOMATION
        SectionHeader("Sharing & Automation")
        SettingsGroup {
            SettingsSwitchRow(
                title = "Photos & Music",
                subtitle = "Share phone photos and music alongside videos",
                checked = shareExtras,
                onChange = onShareExtras,
            )

            SettingsDivider()

            SettingsSwitchRow(
                title = "Allow Downloads",
                subtitle = "Permit connected screens and browsers to download media files",
                checked = allowDownload,
                onChange = onAllowDownload,
            )

            SettingsDivider()

            AutoStopSection(
                currentMinutes = autoStopMinutes,
                onSelectMinutes = onAutoStop,
            )

            SettingsDivider()

            SettingsSwitchRow(
                title = "Offer to share on Wi-Fi",
                subtitle = "Show notification to start sharing upon joining Wi-Fi",
                checked = wifiOffer,
                onChange = onWifiOffer,
            )

            SettingsDivider()

            SettingsSwitchRow(
                title = "Auto-disconnect on Wi-Fi loss",
                subtitle = "Stop sharing if the connected Wi-Fi drops",
                checked = stopOnWifiLost,
                onChange = onStopOnWifiLost,
            )
        }

        // 3. SECURITY & PRIVACY
        SectionHeader("Security & Privacy")
        SettingsGroup {
            AppLockRow(
                lockOn = lockOn,
                onSetPin = onSetPin,
                onRemovePin = onRemovePin,
            )

            SettingsDivider()

            WebPasswordRow(
                password = password,
                onSetPassword = onSetPassword,
            )

            SettingsDivider()

            HistoryRow(
                onOpenHistory = onOpenHistory,
                onClearResume = onClearResume,
            )
        }

        // 4. NETWORK & ADVANCED
        SectionHeader("Network & Advanced")
        SettingsGroup {
            SpeedTestRow(
                state = state,
                scope = scope,
            )

            SettingsDivider()

            DeviceNameRow(
                state = state,
                preferredName = preferredName,
                onSaveName = onSaveName,
            )

            SettingsDivider()

            PortRow(
                state = state,
                preferredPort = preferredPort,
                onSavePort = onSavePort,
            )
        }

        // 5. SYSTEM & DIAGNOSTICS
        SectionHeader("System & About")
        SettingsGroup {
            AboutRow()
        }

        DiagnosticsCard()

        Spacer(modifier = Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------------------
// STYLED SECTION COMPONENTS & CONTAINERS
// ---------------------------------------------------------------------------

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 4.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
    )
}

// ---------------------------------------------------------------------------
// 1. TOP HERO: ADDRESS CARD
// ---------------------------------------------------------------------------

@Composable
private fun AddressHeroCard(
    state: ServerState,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val isRunning = state is ServerState.Running
    val urls = if (isRunning) {
        val s = state as ServerState.Running
        if (s.hotspot) listOf(s.baseUrl) else listOfNotNull(s.nameUrl, s.baseUrl)
    } else emptyList()

    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(
            width = if (isRunning) 1.5.dp else 1.dp,
            color = if (isRunning) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(if (isRunning) LiveGreen else MaterialTheme.colorScheme.outline, CircleShape),
                    )
                    Text(
                        text = if (isRunning) "SERVER ACTIVE" else "SERVER IDLE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = if (isRunning) LiveGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isRunning && (state as ServerState.Running).hotspot) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            "Hotspot",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            if (!isRunning) {
                Text(
                    text = "Start sharing from the home screen to access your media link for TV or computer browsers.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Browser Access Link",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            text = urls.last(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (urls.size > 1) {
                        Text(
                            text = "Or local name: ${urls.first()}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { onCopy(urls.last()) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Copy Address")
                    }
                    FilledTonalButton(
                        onClick = { onShare(urls.last()) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Share Link")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 2. SHARING & AUTOMATION ROWS
// ---------------------------------------------------------------------------

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AutoStopSection(
    currentMinutes: Int,
    onSelectMinutes: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Auto-Stop Timer", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Stop sharing after selected duration to preserve battery",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val options = listOf(0 to "Off", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours")
            options.forEach { (minutes, label) ->
                val selected = currentMinutes == minutes
                FilterChip(
                    selected = selected,
                    onClick = { onSelectMinutes(minutes) },
                    label = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 3. SECURITY & PRIVACY ROWS
// ---------------------------------------------------------------------------

@Composable
private fun AppLockRow(
    lockOn: Boolean,
    onSetPin: () -> Unit,
    onRemovePin: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("App Lock PIN", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (lockOn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        if (lockOn) "Active" else "Disabled",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (lockOn) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                if (lockOn) "Requires PIN to open Caster" else "Protect app settings and sharing with a PIN",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onSetPin,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (lockOn) "Change" else "Set PIN")
            }
            if (lockOn) {
                TextButton(onClick = onRemovePin) {
                    Text("Remove")
                }
            }
        }
    }
}

@Composable
private fun WebPasswordRow(
    password: String,
    onSetPassword: (String) -> Unit,
) {
    var passwordText by rememberSaveable(password) { mutableStateOf(password) }
    val passwordChanged = passwordText != password
    val focus = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Web Access Password", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (password.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            "Protected",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                if (password.isEmpty()) "Allow any screen on Wi-Fi to request pairing, or set a password to gate pairing."
                else "Screens must enter this password before they can pair.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = passwordText,
                onValueChange = { passwordText = it.take(50) },
                modifier = Modifier.weight(1f),
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (passwordChanged) {
                        onSetPassword(passwordText)
                        focus.clearFocus()
                    }
                }),
                shape = RoundedCornerShape(14.dp),
            )

            Button(
                onClick = {
                    onSetPassword(passwordText)
                    focus.clearFocus()
                },
                enabled = passwordChanged,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (password.isEmpty()) "Set" else "Update")
            }

            if (password.isNotEmpty()) {
                TextButton(onClick = {
                    passwordText = ""
                    onSetPassword("")
                }) {
                    Text("Clear")
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    onOpenHistory: () -> Unit,
    onClearResume: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Playback & History", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Manage watched video log and saved resume positions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onOpenHistory,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("Watch History")
            }
            OutlinedButton(
                onClick = onClearResume,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("Clear Resume")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 4. NETWORK & ADVANCED ROWS
// ---------------------------------------------------------------------------

@Composable
private fun SpeedTestRow(
    state: ServerState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var speedResult by rememberSaveable { mutableStateOf<String?>(null) }
    var speedTesting by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Local Wi-Fi Speed Test", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                speedResult ?: if (state is ServerState.Running) "Benchmark throughput between phone and network" else "Start sharing first to test",
                style = MaterialTheme.typography.bodySmall,
                color = if (speedResult != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (speedResult != null) FontWeight.Medium else FontWeight.Normal,
            )
        }

        Button(
            onClick = {
                scope.launch {
                    speedTesting = true
                    speedResult = null
                    try {
                        val port = (state as? ServerState.Running)?.port ?: ShareSettings.DEFAULT_PORT
                        val start = System.nanoTime()
                        val bytes = withContext(Dispatchers.IO) {
                            java.net.URL("http://127.0.0.1:$port/api/speed").readBytes()
                        }
                        val elapsed = (System.nanoTime() - start) / 1e9
                        val mbps = bytes.size / elapsed / 1_000_000
                        speedResult = "%.1f MB/s (%.0f Mbps)".format(mbps, mbps * 8)
                    } catch (_: Exception) {
                        speedResult = "Failed"
                    }
                    speedTesting = false
                }
            },
            enabled = state is ServerState.Running && !speedTesting,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(if (speedTesting) "Testing…" else "Run Test")
        }
    }
}

@Composable
private fun DeviceNameRow(
    state: ServerState,
    preferredName: String,
    onSaveName: (String) -> Unit,
) {
    var text by rememberSaveable(preferredName) { mutableStateOf(preferredName) }
    val valid = ShareSettings.isValidHostname(text)
    val changed = text != preferredName
    val focus = LocalFocusManager.current

    val error = when {
        text.isEmpty() -> "Enter a name"
        text.startsWith('-') || text.endsWith('-') -> "Can't start or end with a hyphen"
        !valid -> "Letters, numbers and hyphens only"
        else -> null
    }

    fun apply() {
        if (valid && changed) {
            onSaveName(text)
            focus.clearFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Device Hostname", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Announced as http://$preferredName.local on local networks (Android 16+)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    text = input.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }.take(32)
                },
                modifier = Modifier.weight(1f),
                label = { Text("Name") },
                suffix = { Text(".local") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                shape = RoundedCornerShape(14.dp),
            )

            Button(
                onClick = ::apply,
                enabled = valid && changed,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Apply")
            }

            if (preferredName != ShareSettings.DEFAULT_HOSTNAME || text != ShareSettings.DEFAULT_HOSTNAME) {
                TextButton(onClick = {
                    text = ShareSettings.DEFAULT_HOSTNAME
                    onSaveName(ShareSettings.DEFAULT_HOSTNAME)
                    focus.clearFocus()
                }) {
                    Text("Reset")
                }
            }
        }
    }
}

@Composable
private fun PortRow(
    state: ServerState,
    preferredPort: Int,
    onSavePort: (Int) -> Unit,
) {
    var text by rememberSaveable(preferredPort) { mutableStateOf(preferredPort.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && ShareSettings.isValidPort(parsed)
    val changed = parsed != preferredPort
    val focus = LocalFocusManager.current

    val error = when {
        text.isEmpty() -> "Enter port"
        parsed == null -> "Digits only"
        parsed < ShareSettings.MIN_PORT -> "Min ${ShareSettings.MIN_PORT}"
        parsed > ShareSettings.MAX_PORT -> "Max ${ShareSettings.MAX_PORT}"
        else -> null
    }

    fun apply() {
        if (valid && changed) {
            onSavePort(parsed!!)
            focus.clearFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Listening Port", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            val portStatus = when (state) {
                is ServerState.Running -> if (state.portChanged) "Port ${state.requestedPort} busy; using :${state.port}" else "Listening on :${state.port}"
                else -> "Default :${ShareSettings.DEFAULT_PORT}. Auto-adjusts if occupied."
            }
            Text(
                portStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.weight(1f),
                label = { Text("Port") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                shape = RoundedCornerShape(14.dp),
            )

            Button(
                onClick = ::apply,
                enabled = valid && changed,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Apply")
            }

            if (preferredPort != ShareSettings.DEFAULT_PORT || text != ShareSettings.DEFAULT_PORT.toString()) {
                TextButton(onClick = {
                    text = ShareSettings.DEFAULT_PORT.toString()
                    onSavePort(ShareSettings.DEFAULT_PORT)
                    focus.clearFocus()
                }) {
                    Text("Reset")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 5. SYSTEM & ABOUT ROWS
// ---------------------------------------------------------------------------

@Composable
private fun AboutRow() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Caster Local Media Server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    "v1.0",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            "Caster streams media exclusively across your local Wi-Fi network. No cloud, no tracking, and no external relay servers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
