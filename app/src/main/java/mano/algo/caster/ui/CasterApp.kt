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
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.ScaffoldDefaults

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

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Library("Library", Icons.AutoMirrored.Filled.List),
    Remote("Remote", RemoteIcon),
    Devices("Devices", Icons.Filled.Phone),
    Settings("Settings", Icons.Filled.Settings),
}

/**
 * A screen shown instead of the tabs. It paints the page colour (otherwise the dark theme would show black text on the
 * light window) and stays clear of the status bar, the navigation bar and the keyboard.
 */
@Composable
private fun FullScreenPage(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.safeDrawingPadding()) { content() }
    }
}

@Composable
fun CasterApp() {
    val context = LocalContext.current
    val state by MediaServerService.state.collectAsStateWithLifecycle()
    val request by PairingManager.pending.collectAsStateWithLifecycle()
    val devices by PairingManager.devices.collectAsStateWithLifecycle()
    val selection by ShareSettings.state.collectAsStateWithLifecycle()
    val watching by ActivityTracker.watching.collectAsStateWithLifecycle()
    val remotes by RemoteControl.all.collectAsStateWithLifecycle()
    val tvState by TvRemote.state.collectAsStateWithLifecycle()
    val tvHost by TvRemote.savedHost.collectAsStateWithLifecycle()
    val tvFound by TvRemote.found.collectAsStateWithLifecycle()
    val tvScanning by TvRemote.scanning.collectAsStateWithLifecycle()
    val tvVolume by TvRemote.volume.collectAsStateWithLifecycle()
    val tvVoiceError by TvRemote.voiceError.collectAsStateWithLifecycle()
    remember(context) { TvRemote.init(context); true }
    remember(context) { ShareSettings.init(context); true }   // returns a value, because remember must not return Unit

    var tab by rememberSaveable { mutableIntStateOf(Tab.Home.ordinal) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var castPicking by rememberSaveable { mutableStateOf(false) }

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
    fun startAfterStorage() = withLocalNetwork(runIfDenied = true) { startAfterLocalNetwork() }

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
        if (ok) picking = true else storageDenied()
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

    // A new connection request is urgent: bring the approval card into view.
    LaunchedEffect(request?.id) { if (request != null) tab = Tab.Home.ordinal }

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

    if (castPicking) {
        FullScreenPage {
            CastPickerScreen(
                onCancel = { castPicking = false },
                onPick = { id, name ->
                    val running = state as? ServerState.Running
                    if (running != null) {
                        CastController.cast(context, id, name, running.baseUrl)
                    } else {
                        Toast.makeText(context, "Sharing has stopped. Start sharing, then choose the video again.", Toast.LENGTH_LONG).show()
                    }
                    castPicking = false
                },
            )
        }
        return
    }

    // From any other tab, the back gesture returns to Home before it leaves the app.
    BackHandler(enabled = tab != Tab.Home.ordinal) { tab = Tab.Home.ordinal }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.displayCutout),
        bottomBar = {
            val haptic = LocalHapticFeedback.current
            val scheme = MaterialTheme.colorScheme
            NavigationBar(
                containerColor = scheme.surfaceContainer,
                windowInsets = NavigationBarDefaults.windowInsets.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
            ) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry.ordinal,
                        onClick = {
                            if (tab != entry.ordinal) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            tab = entry.ordinal
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = scheme.primary,
                            selectedTextColor = scheme.primary,
                            indicatorColor = scheme.primary.copy(alpha = 0.16f),
                            unselectedIconColor = scheme.onSurfaceVariant,
                            unselectedTextColor = scheme.onSurfaceVariant,
                        ),
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
        // The keyboard shrinks the page (beyond the bar it already covers) so a focused text box stays in view.
        val screenModifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding()
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
                onCopy = { copyToClipboard(context, it) },
                onApprove = { PairingManager.approve() },
                onDeny = { PairingManager.deny() },
                onAllowBackground = { requestBatteryExemption(context) },
                onAppSettings = { openBatteryScreen(context) },
                watching = watching,
                remotes = remotes,
                onRemote = RemoteControl::send,
                onShare = { shareAddress(context, it) },
                onCastChoose = { withLocalNetwork(runIfDenied = true) { castPicking = true } },
                modifier = screenModifier,
            )
            Tab.Library -> LibraryScreen(
                selection = selection,
                hasStorageAccess = hasStorage,
                onAll = { ShareSettings.setMode(ShareMode.ALL) },
                onSelected = {
                    // With nothing chosen yet the picker opens first, and "selected" starts when videos are confirmed:
                    // cancelling it must not leave the phone sharing nothing.
                    if (selection.ids.isEmpty()) openPicker() else ShareSettings.setMode(ShareMode.SELECTED)
                },
                onChoose = { openPicker() },
                modifier = screenModifier,
            )
            Tab.Remote -> RemoteScreen(
                state = tvState,
                savedHost = tvHost,
                found = tvFound,
                scanning = tvScanning,
                shareUrl = (state as? ServerState.Running)?.baseUrl,
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
                shareExtras = selection.shareExtras,
                onShareExtras = { on ->
                    ShareSettings.setShareExtras(on)
                    val needed = EXTRAS_PERMISSIONS.filter { !granted(it) }
                    if (on && needed.isNotEmpty()) extrasPermission.launch(needed.toTypedArray())
                },
                autoStopMinutes = selection.autoStopMinutes,
                onAutoStop = ShareSettings::setAutoStopMinutes,
                modifier = screenModifier,
            )
        }
        }
    }
}
