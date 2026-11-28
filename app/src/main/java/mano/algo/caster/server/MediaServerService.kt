package mano.algo.caster.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.View
import android.widget.RemoteViews
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mano.algo.caster.MainActivity
import mano.algo.caster.R
import mano.algo.caster.cast.CastController
import java.io.IOException
import java.net.Inet4Address

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    /**
     * [announcedName] is set only while the phone is successfully advertising itself over mDNS.
     * [requestedPort] is what the owner asked for; [port] differs when that one was busy.
     */
    data class Running(
        val host: String,
        val port: Int,
        val announcedName: String? = null,
        val requestedPort: Int = port,
        val requestedName: String? = null,
        /** The phone is the hotspot; names stop resolving there while the screen is off, so lead with the IP. */
        val hotspot: Boolean = false,
    ) : ServerState {
        val portChanged: Boolean get() = port != requestedPort
        /** The name asked for was already used on this network, so another one was picked. */
        val nameChanged: Boolean get() = announcedName != null && requestedName != null && announcedName != "$requestedName.local"
        val baseUrl: String get() = "http://$host:$port/"
        val nameUrl: String? get() = announcedName?.let { "http://$it:$port/" }
    }
    data class Error(val message: String) : ServerState
}

/**
 * Foreground service that keeps [LocalMediaServer] alive while the screen is off.
 * It re-binds the server when the Wi-Fi address changes (roaming, hotspot toggled, DHCP renewal).
 */
class MediaServerService : Service() {

    // No job in here may take the app down: the network check, the notification and the timers just log and carry on.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "Background job failed", e) },
    )
    private val serverLock = Mutex()

    /** Set when starting failed, so the reason stays on screen after the service closes (other stops show "stopped"). */
    private var startFailed = false
    @Volatile private var started = false // sharing really started (not just a stray notification button)
    @Volatile private var destroyed = false

    // Only touched while holding serverLock.
    private var server: LocalMediaServer? = null
    private var boundHost: String? = null
    private var boundRequestedPort = 0
    private var boundRequestedName = ""
    private var mdns: MdnsResponder? = null
    private var upnp: UpnpServer? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var watchdog: kotlinx.coroutines.Job? = null
    private var lastNotificationText = "Starting video sharing…"
    private var mediaSession: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PairingManager.init(this)
        ResumeStore.init(this)
        WatchLog.init(this)
        PairingManager.reset()
        ShareSettings.init(this)
        createNotificationChannel()
        watchPairingRequests()
        watchMediaPlayback()
        // The home-screen widget and app-icon shortcut follow the state.
        scope.launch {
            state.collect {
                mano.algo.caster.quick.ShareWidget.update(applicationContext)
                mano.algo.caster.quick.ShareShortcut.update(applicationContext)
            }
        }
        // Changing the port in Settings takes effect right away: rebind on the new one.
        scope.launch {
            ShareSettings.state.map { it.port to it.hostname }.distinctUntilChanged().drop(1).collect { reconcile() }
        }
        // Auto-stop timer: counted from when sharing started, and re-read if the owner changes it meanwhile.
        val startedAt = SystemClock.elapsedRealtime()
        scope.launch {
            var from = startedAt
            var first = true
            ShareSettings.state.map { it.autoStopMinutes }.distinctUntilChanged().collectLatest { minutes ->
                // A limit changed later counts from that moment: choosing a shorter one must not end sharing at once.
                if (!first) from = SystemClock.elapsedRealtime()
                first = false
                if (minutes <= 0) return@collectLatest
                delay((minutes * 60_000L - (SystemClock.elapsedRealtime() - from)).coerceAtLeast(0))
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's buttons start this with a plain startService(): nothing to promote, and a button tapped after
        // sharing ended must not bring up a service that has nothing to do.
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPROVE, ACTION_DENY -> {
                if (!started) {
                    stopSelf()
                } else if (intent?.action == ACTION_APPROVE) {
                    // With a PIN set the button only opens the app; this is for a stale notification.
                    if (!mano.algo.caster.security.PinLock.isSet(this)) PairingManager.approve()
                } else {
                    PairingManager.deny()
                }
                return START_NOT_STICKY
            }
            ACTION_PLAY_PAUSE, ACTION_SEEK_BACK, ACTION_SEEK_FORWARD, ACTION_MUTE, ACTION_NEXT, ACTION_PREV -> {
                if (!started) {
                    stopSelf()
                } else {
                    handleMediaAction(intent?.action.orEmpty())
                }
                return START_NOT_STICKY
            }
        }
        // Must be promoted within seconds of startForegroundService(), so do it before anything else.
        try {
            promoteToForeground(buildNotification(getString(R.string.app_name), "Starting video sharing…"))
        } catch (e: Exception) {
            // The system refused (a missing permission, or the app isn't allowed to start it right now): close quietly
            // rather than crash the app.
            Log.e(TAG, "Couldn't start sharing in the foreground", e)
            startFailed = true
            _state.value = ServerState.Error("Couldn't start sharing: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }

        started = true
        mano.algo.caster.CrashLog.note(this, "Sharing started")
        try {
            acquireLocks()
            registerNetworkCallback()
            // (A second start while already sharing must not show "Starting" again: nothing would change it back.)
            if (_state.value !is ServerState.Running) _state.value = ServerState.Starting
            scope.launch { reconcile() }
            startAddressWatchdog()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't start sharing", e)
            startFailed = true
            _state.value = ServerState.Error("Couldn't start sharing: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY // Registered media dies with the process, so a restart would serve nothing.
    }

    override fun onDestroy() {
        destroyed = true // a reconcile that is still binding a port sees this and stops what it just made
        mano.algo.caster.CrashLog.note(this, "Sharing stopped")
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        scope.cancel()
        getSystemService(NotificationManager::class.java).cancel(PAIRING_NOTIFICATION_ID) // no question left to answer
        // Cancelled scope means no new reconcile; stop synchronously (closing sockets is quick).
        // Wake up any blocked SSE streams before stopping the HTTP server.
        PairingManager.reset()
        CastController.stop()   // the Chromecast's access just ended with the server, so end its session too
        RemoteControl.clear()
        releaseMediaSession()
        mdns?.stop()
        mdns = null
        upnp?.stop()
        upnp = null
        server?.stop()
        server = null
        boundHost = null
        releaseLocks()
        // A start that failed leaves its reason on screen instead of replacing it with a plain "stopped".
        if (!startFailed) _state.value = ServerState.Stopped
        mano.algo.caster.quick.ShareWidget.update(applicationContext) // the collector above is gone: say "off" ourselves
        mano.algo.caster.quick.ShareShortcut.update(applicationContext)
        super.onDestroy()
    }

    /** Brings the server in line with the current network: (re)binds, or stops if no LAN is available. */
    private suspend fun reconcile() {
        try {
            reconcileOnce()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep sharing (and the 30-second check that calls this) alive; the next pass tries again.
            Log.e(TAG, "Couldn't update the network connection", e)
        }
    }

    private suspend fun reconcileOnce() = withContext(Dispatchers.IO) {
        serverLock.withLock {
            if (destroyed) return@withLock
            val ip = NetworkUtils.resolveLocalIpv4(this@MediaServerService)
            val host = ip?.hostAddress

            if (host == null) {
                stopServerLocked()
                publish(ServerState.Error("Not connected to a Wi-Fi network"), "Waiting for Wi-Fi…")
                return@withLock
            }
            val wanted = ShareSettings.state.value.port
            val wantedName = ShareSettings.state.value.hostname
            if (host == boundHost && boundRequestedPort == wanted && server?.isAlive == true) {
                if (boundRequestedName == wantedName) return@withLock // unchanged
                // Only the name changed: keep the server (and any video playing) and just announce a new name.
                mdns?.stop()
                mdns = null
                val running = _state.value as? ServerState.Running ?: return@withLock
                val announced = startMdns(ip!!, wantedName)
                if (destroyed) { // the service ended while the new name was being checked
                    mdns?.stop()
                    mdns = null
                    return@withLock
                }
                boundRequestedName = wantedName
                publish(
                    running.copy(announcedName = announced, requestedName = wantedName),
                    "Sharing your videos",
                )
                return@withLock
            }

            stopServerLocked()
            // The preferred port first, then the next ones up; the OS picks any free port (0) as the last resort.
            val candidates = (wanted..minOf(wanted + PORT_FALLBACKS, ShareSettings.MAX_PORT)) + 0
            for (candidatePort in candidates) {
                val candidate = LocalMediaServer(this@MediaServerService, host, candidatePort)
                try {
                    candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                } catch (e: IOException) {
                    Log.w(TAG, "Port $candidatePort unavailable on $host", e)
                    continue
                }
                val port = candidate.listeningPort   // differs from candidatePort only when that was 0
                server = candidate
                boundHost = host
                boundRequestedPort = wanted
                boundRequestedName = wantedName
                val name = startMdns(ip!!, wantedName)
                upnp?.stop()
                upnp = runCatching {
                    UpnpServer(ip, port, wantedName, ShareSettings.instanceId).also { it.start() }
                }.getOrNull()
                if (destroyed) { // the service ended while this was binding
                    stopServerLocked()
                    return@withLock
                }
                publish(
                    ServerState.Running(
                        host, port, name, requestedPort = wanted, requestedName = wantedName,
                        hotspot = NetworkUtils.isHotspot(this@MediaServerService),
                    ),
                    "Sharing your videos",
                )
                return@withLock
            }
            publish(ServerState.Error("Couldn't open a port on this network. Try another port in Settings."), "Server failed to start")
        }
    }

    private fun stopServerLocked() {
        mdns?.stop()
        mdns = null
        upnp?.stop()
        upnp = null
        server?.stop()
        server = null
        boundHost = null
    }

    /**
     * Announces `<name>.local` so browsers can find the phone without scanning, and returns the full name that
     * ended up in use (or null if announcing isn't possible; the server works without it). If another device
     * already has the name, `-2`, `-3`... is tried so two phones on one network don't fight over it.
     */
    private fun startMdns(ip: Inet4Address, wanted: String): String? {
        // The Cast library runs its own mDNS search inside this app, on the same port (5353) as this responder. On Android 10 the
        // responder in the way left the Chromecast search finding nothing; it is only known to be harmless on Android 16, so
        // that is the only place the name is announced (the IP address works the same everywhere).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            Log.i(TAG, "Not announcing a .local name on Android ${Build.VERSION.RELEASE}: it could block the Chromecast search")
            return null
        }
        val name = (1..MAX_NAME_ATTEMPTS).asSequence()
            .map { if (it == 1) wanted else "$wanted-$it" }
            .firstOrNull { !MdnsResponder.isNameTaken(ip, it) }
            ?: return null.also { Log.w(TAG, "No free .local name based on $wanted") }
        mdns = runCatching { MdnsResponder(ip, name).also { it.start() } }
            .onFailure { Log.w(TAG, "mDNS announcement unavailable", it) }
            .getOrNull()
        return if (mdns != null) "$name.local" else null
    }

    private fun publish(state: ServerState, notificationText: String) {
        if (destroyed) return
        _state.value = state
        lastNotificationText = notificationText
        refreshNotification()
    }

    private fun refreshNotification() {
        if (destroyed || !started) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(getString(R.string.app_name), lastNotificationText))
    }

    private fun watchMediaPlayback() {
        scope.launch {
            var lastTarget: MediaNotificationTarget? = null
            combine(CastController.state, RemoteControl.all) { cast, remotes ->
                activeMediaTarget(cast, remotes)
            }.collectLatest { target ->
                if (target != lastTarget) {
                    lastTarget = target
                    refreshNotification()
                }
                if (target is MediaNotificationTarget.Web) {
                    delay(RemoteControl.FRESH_MS * 2)
                    val current = activeMediaTarget(CastController.state.value, RemoteControl.all.value)
                    if (current != lastTarget) {
                        lastTarget = current
                        refreshNotification()
                    }
                }
            }
        }
    }

    private fun handleMediaAction(action: String) {
        when (val target = activeMediaTarget(CastController.state.value, RemoteControl.all.value)) {
            is MediaNotificationTarget.Cast -> when (action) {
                ACTION_PLAY_PAUSE -> CastController.togglePlayback()
                ACTION_SEEK_BACK -> CastController.seekBy(-10_000L)
                ACTION_SEEK_FORWARD -> CastController.seekBy(10_000L)
                ACTION_MUTE -> CastController.toggleMute()
                ACTION_NEXT -> CastController.next()
                ACTION_PREV -> CastController.prev()
            }
            is MediaNotificationTarget.Web -> {
                val cmd = when (action) {
                    ACTION_PLAY_PAUSE -> "toggle"
                    ACTION_SEEK_BACK -> "seek:-10"
                    ACTION_SEEK_FORWARD -> "seek:10"
                    ACTION_MUTE -> "mute"
                    ACTION_NEXT -> "next"
                    ACTION_PREV -> "prev"
                    else -> return
                }
                RemoteControl.send(target.deviceId, cmd)
            }
            null -> Unit
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkChanged()
            override fun onLost(network: Network) = onNetworkChanged()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) =
                onNetworkChanged()
        }
        // Callbacks arrive on a binder thread; reconcile() is idempotent and serialized by serverLock.
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "Network callback unavailable", it) }
    }

    /**
     * A phone hotspot is not a Wi-Fi network the callback sees, so toggling it goes unnoticed. Re-check the
     * address every [WATCHDOG_INTERVAL_MS]; reconcile() does nothing when the address and server are unchanged.
     */
    private fun startAddressWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                PairingManager.expireIfDue() // screens whose time is over are signed out
                reconcile()
            }
        }
    }

    private fun onNetworkChanged() {
        scope.launch { reconcile() }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:MediaServer")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (multicastLock == null) {
            // Without this the Wi-Fi chip drops multicast packets, so mDNS queries would never arrive.
            multicastLock = applicationContext.getSystemService(WifiManager::class.java)
                .createMulticastLock("$packageName:Mdns")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            // LOW_LATENCY only takes effect while the screen is on and the app is in front, so with the screen
            // off Wi-Fi falls into power-save and the server stops answering. HIGH_PERF keeps the radio awake
            // screen-off up to Android 13 (newer versions treat it as LOW_LATENCY, which is the best available).
            @Suppress("DEPRECATION")
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:MediaServer")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
        wakeLock = null
        wifiLock = null
    }

    private fun promoteToForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Media server", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        // Silent default-importance channel so active media controls appear on the lock screen without alerting.
        manager.createNotificationChannel(
            NotificationChannel(MEDIA_CHANNEL_ID, "Media playback controls", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        // High importance so a connection request pops up even while the screen is off or another app is open.
        manager.createNotificationChannel(
            NotificationChannel(PAIRING_CHANNEL_ID, "Connection requests", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun watchPairingRequests() {
        scope.launch {
            PairingManager.pending.collectLatest { request ->
                val manager = getSystemService(NotificationManager::class.java)
                if (request == null) {
                    manager.cancel(PAIRING_NOTIFICATION_ID)
                    return@collectLatest
                }
                manager.notify(PAIRING_NOTIFICATION_ID, buildPairingNotification(request))
                delay((request.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
                PairingManager.expireIfDue() // nobody answered; collectLatest then clears the notification
            }
        }
    }

    private fun buildPairingNotification(request: PairRequest): Notification {
        fun action(requestCode: Int, name: String) = PendingIntent.getService(
            this, requestCode, Intent(this, MediaServerService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, PAIRING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Connect ${request.label}?")
            .setContentText("Approve only if the code on its screen is ${request.code}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Approve only if the code on its screen is ${request.code}"))
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Deny", action(2, ACTION_DENY))
            // With a PIN set, approving happens inside the app, which asks for the PIN first.
            .addAction(
                android.R.drawable.checkbox_on_background,
                if (mano.algo.caster.security.PinLock.isSet(this)) "Open to approve" else "Approve",
                if (mano.algo.caster.security.PinLock.isSet(this)) openApp else action(3, ACTION_APPROVE),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setTimeoutAfter(PairingManager.REQUEST_TTL_MS)
            .build()
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun serviceAction(requestCode: Int, action: String) = PendingIntent.getService(
            this, requestCode, Intent(this, MediaServerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = serviceAction(1, ACTION_STOP)
        val media = activeMediaTarget(CastController.state.value, RemoteControl.all.value)
        if (media == null) {
            releaseMediaSession()
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }

        val session = updateMediaSession(media)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, MEDIA_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val compact = buildMediaRemoteViews(media, ::serviceAction, includeStop = false)
        val expanded = buildMediaRemoteViews(media, ::serviceAction, includeStop = true)
        builder
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(openApp)
            .setCustomContentView(compact)
            .setCustomBigContentView(expanded)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        builder.style = Notification.DecoratedMediaCustomViewStyle()
            .setMediaSession(session.sessionToken)
        return builder.build()
    }

    private fun buildMediaRemoteViews(
        media: MediaNotificationTarget,
        actionIntent: (Int, String) -> PendingIntent,
        includeStop: Boolean,
    ): RemoteViews {
        val views = RemoteViews(packageName, R.layout.notification_media_controls)
        val isVideo = media.kind == "v"
        val isMusic = media.kind == "a"
        val isImage = media.kind == "i"

        views.setViewVisibility(R.id.notif_btn_rew, if (isVideo) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.notif_btn_ff, if (isVideo) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.notif_btn_mute, if (isVideo) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.notif_btn_play_pause, if (isVideo || isMusic) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.notif_btn_next_music, if (isMusic) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.notif_btn_prev, if (isImage) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.notif_btn_next_image, if (isImage) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.notif_btn_stop, if (includeStop) View.VISIBLE else View.GONE)

        views.setImageViewResource(
            R.id.notif_btn_play_pause,
            if (media.playing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
        )
        views.setImageViewResource(
            R.id.notif_btn_mute,
            if (media.muted) R.drawable.ic_notif_volume_off else R.drawable.ic_notif_volume_up,
        )

        views.setOnClickPendingIntent(R.id.notif_btn_rew, actionIntent(10, ACTION_SEEK_BACK))
        views.setOnClickPendingIntent(R.id.notif_btn_play_pause, actionIntent(11, ACTION_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.notif_btn_ff, actionIntent(12, ACTION_SEEK_FORWARD))
        views.setOnClickPendingIntent(R.id.notif_btn_mute, actionIntent(13, ACTION_MUTE))
        views.setOnClickPendingIntent(R.id.notif_btn_next_music, actionIntent(14, ACTION_NEXT))
        views.setOnClickPendingIntent(R.id.notif_btn_prev, actionIntent(15, ACTION_PREV))
        views.setOnClickPendingIntent(R.id.notif_btn_next_image, actionIntent(16, ACTION_NEXT))
        if (includeStop) {
            views.setOnClickPendingIntent(R.id.notif_btn_stop, actionIntent(1, ACTION_STOP))
        }
        return views
    }

    private fun updateMediaSession(media: MediaNotificationTarget): MediaSession {
        val session = mediaSession ?: MediaSession(this, TAG).also { created ->
            created.setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = handleMediaAction(ACTION_PLAY_PAUSE)
                    override fun onPause() = handleMediaAction(ACTION_PLAY_PAUSE)
                    override fun onRewind() = handleMediaAction(ACTION_SEEK_BACK)
                    override fun onFastForward() = handleMediaAction(ACTION_SEEK_FORWARD)
                    override fun onSkipToNext() = handleMediaAction(ACTION_NEXT)
                    override fun onSkipToPrevious() = handleMediaAction(ACTION_PREV)
                    override fun onStop() = stopSelf()
                    override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                        handleMediaAction(action)
                    }
                },
            )
            created.isActive = true
            mediaSession = created
        }

        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "")
                .build(),
        )

        val actions = when (media.kind) {
            "a" -> PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP
            "i" -> PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_STOP
            else -> PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_STOP
        }
        val stateBuilder = PlaybackState.Builder()
            .setState(
                if (media.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (media.playing) 1.0f else 0.0f,
            )
            .setActions(actions)
        if (media.kind == "v") {
            stateBuilder.addCustomAction(
                PlaybackState.CustomAction.Builder(
                    ACTION_MUTE,
                    if (media.muted) "Unmute" else "Mute",
                    if (media.muted) R.drawable.ic_notif_volume_off else R.drawable.ic_notif_volume_up,
                ).build(),
            )
        }
        session.setPlaybackState(stateBuilder.build())
        return session
    }

    private fun releaseMediaSession() {
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
    }

    companion object {
        private const val TAG = "MediaServerService"
        private const val CHANNEL_ID = "media_server"
        private const val MEDIA_CHANNEL_ID = "media_controls"
        private const val NOTIFICATION_ID = 1001
        private const val PAIRING_CHANNEL_ID = "pairing"
        private const val PAIRING_NOTIFICATION_ID = 1002
        private const val ACTION_APPROVE = "mano.algo.caster.action.APPROVE_PAIRING"
        private const val ACTION_DENY = "mano.algo.caster.action.DENY_PAIRING"
        private const val ACTION_STOP = "mano.algo.caster.action.STOP_SERVER"
        private const val ACTION_PLAY_PAUSE = "mano.algo.caster.action.MEDIA_PLAY_PAUSE"
        private const val ACTION_SEEK_BACK = "mano.algo.caster.action.MEDIA_SEEK_BACK"
        private const val ACTION_SEEK_FORWARD = "mano.algo.caster.action.MEDIA_SEEK_FORWARD"
        private const val ACTION_MUTE = "mano.algo.caster.action.MEDIA_MUTE"
        private const val ACTION_NEXT = "mano.algo.caster.action.MEDIA_NEXT"
        private const val ACTION_PREV = "mano.algo.caster.action.MEDIA_PREV"
        /** `name`, `name-2` ... up to this many, when other devices already use the name. */
        private const val MAX_NAME_ATTEMPTS = 9
        private const val WATCHDOG_INTERVAL_MS = 30_000L

        /** How many ports above the preferred one to try before letting the OS choose. */
        private const val PORT_FALLBACKS = 19

        private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)

        /** Observable server status for the UI; safe to collect from any thread. */
        val state: StateFlow<ServerState> = _state.asStateFlow()

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MediaServerService::class.java))
            } catch (e: Exception) {
                // For example the system not allowing a foreground service to start at this moment.
                Log.e(TAG, "Couldn't start the sharing service", e)
                _state.value = ServerState.Error("Couldn't start sharing: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaServerService::class.java))
            // A start that failed leaves its error on screen and no service to stop: Stop then clears the error.
            if (_state.value is ServerState.Error) _state.value = ServerState.Stopped
        }
    }
}

internal sealed interface MediaNotificationTarget {
    val title: String
    val subtitle: String
    val kind: String
    val playing: Boolean
    val muted: Boolean

    data class Cast(
        override val title: String,
        override val subtitle: String,
        override val kind: String,
        override val playing: Boolean,
        override val muted: Boolean,
    ) : MediaNotificationTarget

    data class Web(
        val deviceId: String,
        override val title: String,
        override val subtitle: String,
        override val kind: String,
        override val playing: Boolean,
        override val muted: Boolean,
    ) : MediaNotificationTarget
}

internal fun activeMediaTarget(
    cast: CastController.State,
    remotes: List<RemoteState>,
    nowMs: Long = System.currentTimeMillis(),
): MediaNotificationTarget? {
    val castTitle = cast.title?.takeIf { it.isNotBlank() }
    if (cast.device != null && castTitle != null) {
        return MediaNotificationTarget.Cast(
            title = castTitle,
            subtitle = cast.device,
            kind = cast.kind,
            playing = cast.playing,
            muted = cast.muted,
        )
    }
    val web = remotes.firstOrNull { nowMs - it.reportedAt < RemoteControl.FRESH_MS * 2 && it.title.isNotBlank() }
        ?: return null
    return MediaNotificationTarget.Web(
        deviceId = web.deviceId,
        title = web.title,
        subtitle = web.deviceLabel,
        kind = web.kind,
        playing = web.playing,
        muted = web.volume == 0,
    )
}
