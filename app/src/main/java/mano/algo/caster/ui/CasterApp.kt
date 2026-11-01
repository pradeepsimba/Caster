package mano.algo.caster.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mano.algo.caster.server.ActivityTracker
import mano.algo.caster.server.MediaServerService
import mano.algo.caster.server.PairingManager
import mano.algo.caster.server.ShareMode
import mano.algo.caster.server.ShareSettings

private val STORAGE_PERMISSION =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun requestBatteryExemption(context: Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(request)
    } catch (e: ActivityNotFoundException) {
        openAppSettings(context)
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

private fun shareAddress(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "Watch my videos on your screen: $url")
    }
    context.startActivity(Intent.createChooser(send, "Share Caster address"))
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Caster address", text))
    // Android 13+ shows its own "copied" confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Library("Library", Icons.AutoMirrored.Filled.List),
    Devices("Devices", Icons.Filled.Phone),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
fun CasterApp() {
    val context = LocalContext.current
    val state by MediaServerService.state.collectAsStateWithLifecycle()
    val request by PairingManager.pending.collectAsStateWithLifecycle()
    val devices by PairingManager.devices.collectAsStateWithLifecycle()
    val selection by ShareSettings.state.collectAsStateWithLifecycle()
    val watching by ActivityTracker.watching.collectAsStateWithLifecycle()
    remember(context) { ShareSettings.init(context) }

    var tab by rememberSaveable { mutableIntStateOf(Tab.Home.ordinal) }
    var picking by rememberSaveable { mutableStateOf(false) }

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var hasStorage by remember { mutableStateOf(granted(STORAGE_PERMISSION)) }

    // Notification permission only affects whether the notification is visible; the server starts either way.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        MediaServerService.start(context)
    }
    fun startAfterStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            MediaServerService.start(context)
        }
    }
    // Without storage access the page would pair fine and then show an empty gallery, so ask first.
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasStorage = ok
        if (ok) startAfterStorage()
    }
    // The picker lists the gallery, so it needs the same storage permission as sharing does.
    val pickerPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasStorage = ok
        if (ok) picking = true
    }
    fun openPicker() {
        if (granted(STORAGE_PERMISSION)) picking = true else pickerPermission.launch(STORAGE_PERMISSION)
    }

    // Re-read on every resume: the user changes this in a system screen and comes back.
    var unrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        unrestricted = isIgnoringBatteryOptimizations(context)
        hasStorage = granted(STORAGE_PERMISSION)
        onPauseOrDispose { }
    }

    // A new connection request is urgent: bring the approval card into view.
    LaunchedEffect(request?.id) { if (request != null) tab = Tab.Home.ordinal }

    if (picking) {
        VideoPickerScreen(
            initialSelection = selection.ids,
            onCancel = { picking = false },
            onDone = { ids ->
                ShareSettings.setSelected(ids)
                ShareSettings.setMode(ShareMode.SELECTED)
                picking = false
            },
        )
        return
    }

    // From any other tab, the back gesture returns to Home before it leaves the app.
    BackHandler(enabled = tab != Tab.Home.ordinal) { tab = Tab.Home.ordinal }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry.ordinal,
                        onClick = { tab = entry.ordinal },
                        icon = {
                            if (entry == Tab.Devices && devices.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text(devices.size.toString()) } }) { Icon(entry.icon, contentDescription = null) }
                            } else {
                                Icon(entry.icon, contentDescription = null)
                            }
                        },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        val screenModifier = Modifier.padding(padding)
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val forward = targetState > initialState
                (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { if (forward) it / 12 else -it / 12 }) togetherWith
                    (fadeOut(tween(120)) + slideOutHorizontally(tween(220)) { if (forward) -it / 12 else it / 12 })
            },
            label = "tabs",
        ) { current ->
        when (Tab.entries[current]) {
            Tab.Home -> HomeScreen(
                state = state,
                request = request,
                batteryUnrestricted = unrestricted,
                onStart = { if (granted(STORAGE_PERMISSION)) startAfterStorage() else storagePermission.launch(STORAGE_PERMISSION) },
                onStop = { MediaServerService.stop(context) },
                onCopy = { copyToClipboard(context, it) },
                onApprove = { PairingManager.approve() },
                onDeny = { PairingManager.deny() },
                onAllowBackground = { requestBatteryExemption(context) },
                onAppSettings = { openAppSettings(context) },
                watching = watching,
                onShare = { shareAddress(context, it) },
                modifier = screenModifier,
            )
            Tab.Library -> LibraryScreen(
                selection = selection,
                hasStorageAccess = hasStorage,
                onAll = { ShareSettings.setMode(ShareMode.ALL) },
                onSelected = {
                    ShareSettings.setMode(ShareMode.SELECTED)
                    if (selection.ids.isEmpty()) openPicker()
                },
                onChoose = { openPicker() },
                modifier = screenModifier,
            )
            Tab.Devices -> DevicesScreen(
                devices = devices,
                onDisconnect = PairingManager::revoke,
                onDisconnectAll = PairingManager::revokeAll,
                modifier = screenModifier,
            )
            Tab.Settings -> SettingsScreen(
                state = state,
                preferredPort = selection.port,
                onSavePort = ShareSettings::setPort,
                preferredName = selection.hostname,
                onSaveName = ShareSettings::setHostname,
                modifier = screenModifier,
            )
        }
        }
    }
}
