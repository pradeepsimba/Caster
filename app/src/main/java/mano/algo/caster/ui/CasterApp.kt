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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mano.algo.caster.cast.CastController
import mano.algo.caster.server.ActivityTracker
import mano.algo.caster.server.MediaServerService
import mano.algo.caster.server.PairingManager
import mano.algo.caster.server.RemoteControl
import mano.algo.caster.server.ShareMode
import mano.algo.caster.server.ServerState
import mano.algo.caster.server.ShareSettings
import mano.algo.caster.tv.TvRemote
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import mano.algo.caster.ui.theme.LiveGreen
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import mano.algo.caster.security.PinLock
import mano.algo.caster.server.ResumeStore
import mano.algo.caster.server.WatchLog
import mano.algo.caster.server.NetworkUtils
import mano.algo.caster.server.WifiWatcher
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import mano.algo.caster.cast.ShareIntake
import mano.algo.caster.quick.QuickActionActivity

private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val LOCAL_NETWORK_SDK = 37 // Android 17

/** Every permission worth asking for when the app opens, for the Android version it is running on. */
private fun startupPermissionList(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_VIDEO)
        // (Photos and music are asked for when the owner turns that switch on, not before.)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK) add(LOCAL_NETWORK_PERMISSION)
}

private val STORAGE_PERMISSION =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private val EXTRAS_PERMISSIONS =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

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

/** ColorOS / OxygenOS keep their own per-app power screen; try the known ones, then Android's app settings. */
private fun openBatteryScreen(context: Context) {
    val screens = listOf(
        "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity",
        "com.coloros.oppoguardelf" to "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity",
        "com.coloros.oppoguardelf" to "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
    )
    for ((pkg, cls) in screens) {
        val intent = Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
        } catch (e: SecurityException) {
        }
    }
    openAppSettings(context)
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(intent)
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

/**
 * A screen shown instead of the main page. It paints the page colour (otherwise the dark theme would show black text on the
 * light window) and stays clear of the status bar, the navigation bar and the keyboard.
 */
@Composable
private fun FullScreenPage(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.safeDrawingPadding()) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CasterApp() {
    val context = LocalContext.current
    val state by MediaServerService.state.collectAsStateWithLifecycle()
    val request by PairingManager.pending.collectAsStateWithLifecycle()
    val devices by PairingManager.devices.collectAsStateWithLifecycle()
    val knownScreens by PairingManager.known.collectAsStateWithLifecycle()
    val selection by ShareSettings.state.collectAsStateWithLifecycle()
    val watching by ActivityTracker.watching.collectAsStateWithLifecycle()
    val remotes by RemoteControl.all.collectAsStateWithLifecycle()
    val tvState by TvRemote.state.collectAsStateWithLifecycle()
    val tvHost by TvRemote.savedHost.collectAsStateWithLifecycle()
    val tvFound by TvRemote.found.collectAsStateWithLifecycle()
    val tvSaved by TvRemote.saved.collectAsStateWithLifecycle()
    val tvScanning by TvRemote.scanning.collectAsStateWithLifecycle()
    val tvVolume by TvRemote.volume.collectAsStateWithLifecycle()
    val tvVoiceError by TvRemote.voiceError.collectAsStateWithLifecycle()
    remember(context) { TvRemote.init(context); true }
    remember(context) { PairingManager.init(context); true }
    remember(context) { ShareSettings.init(context); true }   // returns a value, because remember must not return Unit
    remember(context) { ResumeStore.init(context); WatchLog.init(context); true }
    val history by WatchLog.entries.collectAsStateWithLifecycle()
    val historyOn by WatchLog.enabled.collectAsStateWithLifecycle()

    // The PIN: asked when the app opens, and again after it has been out of sight for a while.
    var pinSet by remember { mutableStateOf(PinLock.isSet(context)) }
    var unlocked by remember { mutableStateOf(PinLock.unlocked) }   // from memory, never from a saved state
    var pinDialog by remember { mutableStateOf<String?>(null) }   // "set", "change" (asks the old PIN), "new" or "remove"
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var limitFor by rememberSaveable { mutableStateOf<String?>(null) }     // clientId of the screen being limited
    var limitPicking by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { PinLock.leftAt = android.os.SystemClock.elapsedRealtime() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        val left = PinLock.leftAt
        if (left != 0L && android.os.SystemClock.elapsedRealtime() - left > PinLock.RELOCK_AFTER_MS) {
            PinLock.unlocked = false
            unlocked = false
            pinDialog = null // a PIN dialog left open must not come back after the next unlock
            limitFor = null
            limitPicking = false
        }
        PinLock.leftAt = 0L
    }
    // The Wi-Fi offer is a system callback; this keeps it in step with the settings whenever the app is opened.
    remember(context) { WifiWatcher.refresh(context); true }

    var showRemote by rememberSaveable { mutableStateOf(false) } // the TV remote, as a page that floats over Home
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var photoPicking by rememberSaveable { mutableStateOf(false) }
    var musicPicking by rememberSaveable { mutableStateOf(false) }
    var castPicking by rememberSaveable { mutableStateOf(false) }
    var showHotspotDialog by rememberSaveable { mutableStateOf(false) }

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var hasStorage by remember { mutableStateOf(granted(STORAGE_PERMISSION)) }

    /** Said when access to the videos is refused. Once Android stops asking, the only way back is the app's settings, so they open. */
    fun storageDenied() {
        Toast.makeText(context, "Caster needs access to your videos to share them. Allow it in the app's permissions.", Toast.LENGTH_LONG).show()
        val activity = context as? android.app.Activity
        val stoppedAsking = activity != null && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, STORAGE_PERMISSION)
        if (stoppedAsking) openAppSettings(context)
    }

    // Android 17+ keeps every app away from other devices on the home network until the user allows it: without this
    // the web page can't reach the phone and the TV and Chromecast can't be found.
    fun needsLocalNetwork() = Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK && !granted(LOCAL_NETWORK_PERMISSION)
    var afterLocalNetwork by remember { mutableStateOf<(() -> Unit)?>(null) }
    var runEvenIfDenied by remember { mutableStateOf(true) }
    val localNetworkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val next = afterLocalNetwork
        afterLocalNetwork = null
        if (!ok) {
            Toast.makeText(
                context,
                "Local network access is off, so other devices can't reach Caster. Turn it on in the app's permissions.",
                Toast.LENGTH_LONG,
            ).show()
            val activity = context as? android.app.Activity
            if (activity != null && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, LOCAL_NETWORK_PERMISSION)) {
                openAppSettings(context) // Android won't ask again: the settings screen is the only way
            }
        }
        if (ok || runEvenIfDenied) next?.invoke()
    }
    /** Runs [next] once local network access is allowed (at once when it already is, or isn't needed on this Android). */
    fun withLocalNetwork(runIfDenied: Boolean, next: () -> Unit) {
        if (needsLocalNetwork()) {
            afterLocalNetwork = next
            runEvenIfDenied = runIfDenied
            localNetworkPermission.launch(LOCAL_NETWORK_PERMISSION)
        } else {
            next()
        }
    }

    // Notification permission only affects whether the notification is visible; the server starts either way.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        MediaServerService.start(context)
    }
    fun startAfterLocalNetwork() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            MediaServerService.start(context)
        }
    }
    fun startAfterStorage() {
        if (NetworkUtils.resolveLocalIpv4(context) == null) {
            showHotspotDialog = true
        } else {
            withLocalNetwork(runIfDenied = true) { startAfterLocalNetwork() }
        }
    }

    // Everything the app can use is asked for as soon as it opens, so nothing fails later for a missing permission:
    // videos, photos and music (or the single storage permission on older Android), notifications, and the local network.
    val startupPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasStorage = granted(STORAGE_PERMISSION)
    }
    var askedAtStart by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (askedAtStart) return@LaunchedEffect
        askedAtStart = true
        val missing = startupPermissionList().filter { !granted(it) }
        if (missing.isNotEmpty()) startupPermissions.launch(missing.toTypedArray())
    }
    // Without storage access the page would pair fine and then show an empty gallery, so ask first.
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasStorage = ok
        if (ok) startAfterStorage() else storageDenied()
    }
    // The picker lists the gallery, so it needs the same storage permission as sharing does.
    val pickerPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasStorage = ok
        // (Asked from the "Only some" chip or from a screen's own limit: open the one that asked.)
        if (ok) { if (limitFor != null) limitPicking = true else picking = true } else storageDenied()
    }
    // Only the permission, nothing opens afterwards (the library card's "Allow video access").
    val accessPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasStorage = ok
        if (!ok) storageDenied()
    }
    // Photos and music need their own permissions on Android 13+ (older versions use the one storage permission).
    // The microphone, asked for the first time the voice button of an Android TV remote is held.
    val recordPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) {
            Toast.makeText(context, "Voice needs the microphone. Allow it in the app's permissions.", Toast.LENGTH_LONG).show()
            val activity = context as? android.app.Activity
            if (activity != null && !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)) {
                openAppSettings(context) // Android won't ask again
            }
        }
    }
    val extrasPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.isNotEmpty() && results.values.none { it }) {
            // Everything refused: the switch must not stay on while nothing can be listed. (Allowing only one of the two is fine.)
            ShareSettings.setShareExtras(false)
            Toast.makeText(context, "Photos and music need access to them. Allow it in the app's permissions to share them.", Toast.LENGTH_LONG).show()
        }
    }
    fun openPicker() {
        if (granted(STORAGE_PERMISSION)) picking = true else pickerPermission.launch(STORAGE_PERMISSION)
    }

    // Re-read on every resume: the user changes this in a system screen and comes back.
    var unrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var localNetworkOk by remember { mutableStateOf(!needsLocalNetwork()) }
    LifecycleResumeEffect(Unit) {
        unrestricted = isIgnoringBatteryOptimizations(context)
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        localNetworkOk = !needsLocalNetwork()
        hasStorage = granted(STORAGE_PERMISSION)
        onPauseOrDispose { }
    }

    // A cast picker restored after the app was killed would open with nothing shared: it only makes sense while sharing runs.
    LaunchedEffect(state) { if (state !is ServerState.Running) castPicking = false }

    // A new connection request is urgent: bring the approval card into view.
    // (Once per request: a rotation must not close a page the owner opened afterwards.)
    var seenRequest by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(request?.id) {
        val id = request?.id
        if (id != null && id != seenRequest) {
            seenRequest = id
            showSettings = false; showRemote = false; showHistory = false
            picking = false; castPicking = false; limitPicking = false; limitFor = null
        }
    }

    // Locked: nothing of the app shows until the PIN is typed.
    if (pinSet && !unlocked) {
        FullScreenPage { LockScreen(attempt = { PinLock.attempt(context, it) }, onUnlocked = { PinLock.unlocked = true; unlocked = true }) }
        return
    }

    when (pinDialog) {
        "set", "new" -> SetPinDialog(
            title = if (pinDialog == "set") "Set a PIN" else "Choose the new PIN",
            onDone = { pin ->
                PinLock.set(context, pin)
                pinSet = true
                PinLock.unlocked = true
                unlocked = true
                pinDialog = null
            },
            onDismiss = { pinDialog = null },
        )
        // Changing or removing the PIN needs the PIN that is set now.
        "change" -> VerifyPinDialog(
            title = "Change the PIN",
            attempt = { PinLock.attempt(context, it) },
            onOk = { pinDialog = "new" },
            onDismiss = { pinDialog = null },
        )
        "remove" -> VerifyPinDialog(
            title = "Remove the PIN",
            attempt = { PinLock.attempt(context, it) },
            onOk = { PinLock.clear(context); pinSet = false; pinDialog = null },
            onDismiss = { pinDialog = null },
        )
    }

    // A video sent here from the gallery with "Share": start sharing if needed, then play it once a Chromecast is connected.
    val incoming by ShareIntake.pending.collectAsStateWithLifecycle()
    val castNow by CastController.state.collectAsStateWithLifecycle()

    var lastCastItem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(castNow.device, castNow.title) {
        if (castNow.device != null && castNow.title != null) {
            val key = "${castNow.device}:${castNow.title}"
            if (lastCastItem != key) {
                lastCastItem = key
                showRemote = true
            }
        }
    }
    LaunchedEffect(incoming, state, castNow.device) {
        val item = incoming ?: return@LaunchedEffect
        CastController.init(context)
        if (!CastController.state.value.available) { // no Google Play services: a Chromecast can never be connected
            Toast.makeText(context, "Chromecast needs Google Play services, which this phone doesn't have.", Toast.LENGTH_LONG).show()
            ShareIntake.clear()
            return@LaunchedEffect
        }
        when (val s = state) {
            // (Sharing is started from the dialog below, by the owner, never by another app on its own.)
            ServerState.Stopped, ServerState.Starting -> Unit
            is ServerState.Error -> {
                Toast.makeText(context, "Sharing couldn't start.", Toast.LENGTH_LONG).show()
                ShareIntake.clear()
            }
            is ServerState.Running -> if (!ShareSettings.isAllowed(item.id)) {
                Toast.makeText(context, "This video isn't one of the videos to share. Add it under \"Videos to share\" first.", Toast.LENGTH_LONG).show()
                ShareIntake.clear()
            } else if (!item.fromOutside && castNow.device != null && CastController.cast(context, item.id, item.name, s.baseUrl)) {
                Toast.makeText(context, "Playing ${item.name} on ${castNow.device}", Toast.LENGTH_SHORT).show()
                ShareIntake.clear()
            }
        }
    }
    incoming?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { ShareIntake.clear() },
            title = { Text("Play on a Chromecast") },
            text = {
                Text(
                    if (state is ServerState.Running && item.fromOutside && castNow.device != null) {
                        "Play \"${item.name}\" on ${castNow.device}?"
                    } else if (state is ServerState.Running || state == ServerState.Starting) {
                        "\"${item.name}\" will start as soon as a Chromecast is connected. Choose one in the Chromecast card."
                    } else {
                        "\"${item.name}\" needs sharing to be on, so the Chromecast can fetch it from this phone over your Wi-Fi."
                    },
                )
            },
            confirmButton = {
                val running = state as? ServerState.Running
                if (running != null && item.fromOutside && castNow.device != null) {
                    TextButton(onClick = {
                        if (CastController.cast(context, item.id, item.name, running.baseUrl)) ShareIntake.clear()
                    }) { Text("Play") }
                }
                if (state is ServerState.Stopped) {
                    TextButton(onClick = {
                        if (QuickActionActivity.canStartHere(context)) {
                            MediaServerService.start(context)
                        } else {
                            Toast.makeText(context, "Allow Caster to read your videos first (open its permissions).", Toast.LENGTH_LONG).show()
                            ShareIntake.clear()
                        }
                    }) { Text("Start sharing") }
                }
            },
            dismissButton = { TextButton(onClick = { ShareIntake.clear() }) { Text("Cancel") } },
        )
    }

    // The videos one screen may see: chosen like "Only some", but kept with that screen.
    val limitTarget = knownScreens.firstOrNull { it.clientId == limitFor }
    LaunchedEffect(limitTarget == null) { if (limitTarget == null) { limitPicking = false; limitFor = null } } // the screen is gone: nothing to pick for
    if (limitPicking && limitTarget != null) {
        FullScreenPage {
            VideoPickerScreen(
                initialSelection = limitTarget.limit.orEmpty(),
                onCancel = { limitPicking = false; limitFor = null },
                onDone = { ids ->
                    PairingManager.setLimit(limitTarget.clientId, ids)
                    limitPicking = false
                    limitFor = null
                },
            )
        }
        return
    }
    if (limitTarget != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { limitFor = null },
            title = { Text("Videos for ${limitTarget.label}") },
            text = {
                Text(
                    if (limitTarget.limit == null) "This screen sees every video that is shared. You can let it see only some of them."
                    else "This screen sees only ${limitTarget.limit.size} chosen videos.",
                )
            },
            confirmButton = { TextButton(onClick = { if (granted(STORAGE_PERMISSION)) limitPicking = true else pickerPermission.launch(STORAGE_PERMISSION) }) { Text("Choose videos") } },
            dismissButton = {
                Row {
                    if (limitTarget.limit != null) TextButton(onClick = { PairingManager.setLimit(limitTarget.clientId, null); limitFor = null }) { Text("Remove limit") }
                    TextButton(onClick = { limitFor = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (picking) {
        FullScreenPage {
            VideoPickerScreen(
                initialSelection = selection.ids,
                onCancel = { picking = false },
                onDone = { ids ->
                    ShareSettings.setSelected(ids)
                    ShareSettings.setMode(ShareMode.SELECTED)
                    picking = false
                },
            )
        }
        return
    }

    if (photoPicking) {
        FullScreenPage {
            PhotoPickerScreen(
                initialSelection = selection.photoIds,
                onCancel = { photoPicking = false },
                onDone = { ids ->
                    ShareSettings.setPhotoSelected(ids)
                    ShareSettings.setPhotoMode(ShareMode.SELECTED)
                    photoPicking = false
                },
            )
        }
        return
    }

    if (musicPicking) {
        FullScreenPage {
            MusicPickerScreen(
                initialSelection = selection.musicIds,
                onCancel = { musicPicking = false },
                onDone = { ids ->
                    ShareSettings.setMusicSelected(ids)
                    ShareSettings.setMusicMode(ShareMode.SELECTED)
                    musicPicking = false
                },
            )
        }
        return
    }

    if (castPicking) {
        FullScreenPage {
            CastPickerScreen(
                onCancel = { castPicking = false },
                onPick = { id, name, kind ->
                    val running = state as? ServerState.Running
                    if (running != null) {
                        CastController.cast(context, id, name, running.baseUrl, kind)
                        showRemote = true
                    } else {
                        Toast.makeText(context, "Sharing has stopped. Start sharing, then choose again.", Toast.LENGTH_LONG).show()
                    }
                    castPicking = false
                },
                onPlayAll = { items, shuffle ->
                    val running = state as? ServerState.Running
                    if (running != null) {
                        val list = if (shuffle) items.shuffled() else items
                        CastController.castPlaylist(context, list, 0, running.baseUrl)
                        showRemote = true
                    } else {
                        Toast.makeText(context, "Sharing has stopped. Start sharing, then choose again.", Toast.LENGTH_LONG).show()
                    }
                    castPicking = false
                },
            )
        }
        return
    }

    if (showHistory) {
        BackHandler { showHistory = false }
        FullScreenPage {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showHistory = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text("Watch history", style = MaterialTheme.typography.headlineMedium)
                }
                HistoryScreen(history, historyOn, WatchLog::setEnabled, WatchLog::clear, Modifier.weight(1f))
            }
        }
        return
    }

    // Settings has a page of its own, opened by the gear on Home; the back gesture comes back to Home.
    if (showSettings) {
        BackHandler { showSettings = false }
        FullScreenPage {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showSettings = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text("Settings", style = MaterialTheme.typography.headlineMedium)
                }
                SettingsScreen(
                    state = state,
                    preferredPort = selection.port,
                    onSavePort = ShareSettings::setPort,
                    preferredName = selection.hostname,
                    onSaveName = ShareSettings::setHostname,
                    shareExtras = selection.shareExtras,
                    onShareExtras = { on ->
                        ShareSettings.setShareExtras(on)
                        val needed = EXTRAS_PERMISSIONS.filter { !granted(it) }
                        if (on && needed.isNotEmpty()) extrasPermission.launch(needed.toTypedArray())
                    },
                    autoStopMinutes = selection.autoStopMinutes,
                    onAutoStop = ShareSettings::setAutoStopMinutes,
                    onCopy = { copyToClipboard(context, it) },
                    onShare = { shareAddress(context, it) },
                    lockOn = pinSet,
                    onSetPin = { pinDialog = if (pinSet) "change" else "set" },
                    onRemovePin = { pinDialog = "remove" },
                    onOpenHistory = { showHistory = true },
                    onClearResume = {
                        ResumeStore.clear()
                        Toast.makeText(context, "Resume points forgotten", Toast.LENGTH_SHORT).show()
                    },
                    wifiOffer = selection.wifiOffer,
                    onWifiOffer = { on ->
                        ShareSettings.setWifiOffer(on)
                        WifiWatcher.refresh(context)
                    },
                    stopOnWifiLost = selection.stopOnWifiLost,
                    onStopOnWifiLost = { on ->
                        ShareSettings.setStopOnWifiLost(on)
                        WifiWatcher.refresh(context)
                    },
                    password = selection.password,
                    onSetPassword = ShareSettings::setPassword,
                    modifier = Modifier.weight(1f).imePadding(),
                )
            }
        }
        return
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.displayCutout),
        bottomBar = {
            // The Remote is one wide button along the bottom, always in reach. (A bar given to the Scaffold has to keep clear of
            // the system navigation bar itself.)
            val haptic = LocalHapticFeedback.current
            val connected = tvState is TvRemote.State.Connected
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)))
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        showRemote = true
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                ) {
                    BadgedBox(badge = { if (connected) Badge(containerColor = LiveGreen) }) {
                        Icon(RemoteIcon, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(if (connected) "TV remote · connected" else "TV remote", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) { padding ->
        HomeScreen(
            state = state,
            request = request,
            batteryUnrestricted = unrestricted,
            devices = devices,
            known = knownScreens,
            selection = selection,
            hasStorage = hasStorage,
            warnings = buildList {
                if (!notificationsOn) {
                    add(
                        SetupWarning(
                            title = "Notifications are off",
                            text = "Without them you won't see a TV asking to connect while another app is open, and the request runs out.",
                            button = "Turn on",
                            onClick = { openNotificationSettings(context) },
                        ),
                    )
                }
                if (!localNetworkOk) {
                    add(
                        SetupWarning(
                            title = "Local network access is off",
                            text = "Android is blocking this phone from other devices on your Wi-Fi, so TVs and Chromecasts can't reach it.",
                            button = "Allow",
                            onClick = { openAppSettings(context) },
                        ),
                    )
                }
            },
            onStart = { if (granted(STORAGE_PERMISSION)) startAfterStorage() else storagePermission.launch(STORAGE_PERMISSION) },
            onStop = { MediaServerService.stop(context) },
            onApprove = { duration, videoMode, photoMode, musicMode ->
                ShareSettings.setMode(videoMode)
                photoMode?.let { ShareSettings.setPhotoMode(it) }
                musicMode?.let { ShareSettings.setMusicMode(it) }
                PairingManager.approve(duration)
            },
            onDeny = { PairingManager.deny() },
            onAllowBackground = { requestBatteryExemption(context) },
            onAppSettings = { openBatteryScreen(context) },
            watching = watching,
            remotes = remotes,
            onRemote = RemoteControl::send,
            onCastChoose = { withLocalNetwork(runIfDenied = true) { castPicking = true } },
            onDisconnect = PairingManager::revoke,
            onDisconnectAll = PairingManager::revokeAll,
            onClearScreens = PairingManager::clearUnlimited,
            onAll = { ShareSettings.setMode(ShareMode.ALL) },
            onSelected = {
                // With nothing chosen yet the picker opens first, and "selected" starts when videos are confirmed:
                // cancelling it must not leave the phone sharing nothing.
                if (selection.ids.isEmpty()) openPicker() else ShareSettings.setMode(ShareMode.SELECTED)
            },
            onChoose = { openPicker() },
            onRemoveChosen = { id -> ShareSettings.setSelected(selection.ids - id) },
            onPlayChosen = { id, name ->
                val running = state as? ServerState.Running
                CastController.init(context)
                when {
                    !CastController.state.value.available ->
                        Toast.makeText(context, "Chromecast needs Google Play services, which this phone doesn't have.", Toast.LENGTH_LONG).show()
                    running == null -> Toast.makeText(context, "Start sharing first.", Toast.LENGTH_SHORT).show()
                    castNow.device == null -> {
                        // No Chromecast yet: the video waits, Google's chooser opens, and it plays once one is connected.
                        ShareIntake.offer(id, name)
                        CastController.requestChooser()
                    }
                    CastController.cast(context, id, name, running.baseUrl) -> {
                        Toast.makeText(context, "Playing $name on ${castNow.device}", Toast.LENGTH_SHORT).show()
                        showRemote = true
                    }
                }
            },
            onFoldersMode = { ShareSettings.setMode(ShareMode.FOLDERS) },
            onAllowAccess = { if (!granted(STORAGE_PERMISSION)) accessPermission.launch(STORAGE_PERMISSION) },
            onSaveFolders = { ids ->
                ShareSettings.setFolders(ids)
                ShareSettings.setMode(ShareMode.FOLDERS)
            },
            onPhotoFoldersMode = { ShareSettings.setPhotoMode(ShareMode.FOLDERS) },
            onPhotoSelectedMode = {
                if (selection.photoIds.isEmpty()) photoPicking = true else ShareSettings.setPhotoMode(ShareMode.SELECTED)
            },
            onChoosePhotos = { photoPicking = true },
            onRemovePhoto = { id -> ShareSettings.setPhotoSelected(selection.photoIds - id) },
            onPlayPhoto = { id, name ->
                val running = state as? ServerState.Running
                CastController.init(context)
                when {
                    !CastController.state.value.available ->
                        Toast.makeText(context, "Chromecast needs Google Play services, which this phone doesn't have.", Toast.LENGTH_LONG).show()
                    running == null -> Toast.makeText(context, "Start sharing first.", Toast.LENGTH_SHORT).show()
                    castNow.device == null -> CastController.requestChooser()
                    CastController.cast(context, id, name, running.baseUrl, "i") -> {
                        Toast.makeText(context, "Showing $name on ${castNow.device}", Toast.LENGTH_SHORT).show()
                        showRemote = true
                    }
                }
            },
            onSavePhotoFolders = { ids ->
                ShareSettings.setPhotoFolders(ids)
                ShareSettings.setPhotoMode(ShareMode.FOLDERS)
            },
            onMusicFoldersMode = { ShareSettings.setMusicMode(ShareMode.FOLDERS) },
            onMusicSelectedMode = {
                if (selection.musicIds.isEmpty()) musicPicking = true else ShareSettings.setMusicMode(ShareMode.SELECTED)
            },
            onChooseMusic = { musicPicking = true },
            onRemoveMusic = { id -> ShareSettings.setMusicSelected(selection.musicIds - id) },
            onPlayMusic = { id, name ->
                val running = state as? ServerState.Running
                CastController.init(context)
                when {
                    !CastController.state.value.available ->
                        Toast.makeText(context, "Chromecast needs Google Play services, which this phone doesn't have.", Toast.LENGTH_LONG).show()
                    running == null -> Toast.makeText(context, "Start sharing first.", Toast.LENGTH_SHORT).show()
                    castNow.device == null -> CastController.requestChooser()
                    CastController.cast(context, id, name, running.baseUrl, "a") -> {
                        Toast.makeText(context, "Playing $name on ${castNow.device}", Toast.LENGTH_SHORT).show()
                        showRemote = true
                    }
                }
            },
            onSaveMusicFolders = { ids ->
                ShareSettings.setMusicFolders(ids)
                ShareSettings.setMusicMode(ShareMode.FOLDERS)
            },
            onLimitScreen = { limitFor = it.clientId },
            onOpenSettings = { showSettings = true },
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        )
    }

    if (showHotspotDialog) {
        AlertDialog(
            onDismissRequest = { showHotspotDialog = false },
            title = { Text("No Wi-Fi or hotspot") },
            text = { Text("Connect to a Wi-Fi network, or turn on Mobile Hotspot so other devices can reach Caster.") },
            confirmButton = {
                TextButton(onClick = {
                    showHotspotDialog = false
                    try {
                        context.startActivity(Intent("android.settings.TETHER_SETTINGS"))
                    } catch (e: ActivityNotFoundException) {
                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                    }
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showHotspotDialog = false }) { Text("Cancel") }
            },
        )
    }

    // The TV remote floats over Home as a page of its own, so it is one tap away from anywhere and closes with a swipe down.
    if (showRemote) {
        ModalBottomSheet(
            onDismissRequest = { showRemote = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            RemoteScreen(
                state = tvState,
                savedHost = tvHost,
                found = tvFound,
                saved = tvSaved,
                scanning = tvScanning,
                shareUrl = (state as? ServerState.Running)?.baseUrl,
                onWake = { address, mac ->
                    withLocalNetwork(runIfDenied = false) {
                        if (TvRemote.wake(context, address, mac)) {
                            Toast.makeText(context, "Sent the turn-on signal to $address", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "The TV's MAC address isn't known yet.", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onForget = { address -> TvRemote.forget(context, address) },
                onConnect = { host -> withLocalNetwork(runIfDenied = false) { TvRemote.connect(context, host) } },
                onConnectAdb = { host -> withLocalNetwork(runIfDenied = false) { TvRemote.connectAdb(context, host) } },
                onSubmitPin = { pin -> TvRemote.submitPin(context, pin) },
                onDisconnect = TvRemote::disconnect,
                onScan = { withLocalNetwork(runIfDenied = false) { TvRemote.scan(context) } },
                onOpenBrowser = TvRemote::openBrowser,
                volume = tvVolume,
                onEnableVolume = { TvRemote.enableVolume(context) },
                onKey = TvRemote::key,
                onTyped = TvRemote::typed,
                onOpenCaster = TvRemote::openUrl,
                voiceError = tvVoiceError,
                onVoiceStart = {
                    if (granted(Manifest.permission.RECORD_AUDIO)) {
                        TvRemote.voiceStart()
                    } else {
                        recordPermission.launch(Manifest.permission.RECORD_AUDIO)
                        false
                    }
                },
                onVoiceEnd = TvRemote::voiceEnd,
                modifier = Modifier.imePadding(),
            )
        }
    }
}
