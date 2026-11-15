package mano.algo.caster.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

    // Only touched while holding serverLock.
    private var server: LocalMediaServer? = null
    private var boundHost: String? = null
    private var boundRequestedPort = 0
    private var boundRequestedName = ""
    private var mdns: MdnsResponder? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var watchdog: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PairingManager.reset()
        ShareSettings.init(this)
        createNotificationChannel()
        watchPairingRequests()
        // Changing the port in Settings takes effect right away: rebind on the new one.
        scope.launch {
            ShareSettings.state.map { it.port to it.hostname }.distinctUntilChanged().drop(1).collect { reconcile() }
        }
        // Auto-stop timer: counted from when sharing started, and re-read if the owner changes it meanwhile.
        val startedAt = SystemClock.elapsedRealtime()
        scope.launch {
            ShareSettings.state.map { it.autoStopMinutes }.distinctUntilChanged().collectLatest { minutes ->
                if (minutes <= 0) return@collectLatest
                delay((minutes * 60_000L - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0))
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPROVE -> { PairingManager.approve(); return START_NOT_STICKY }
            ACTION_DENY -> { PairingManager.deny(); return START_NOT_STICKY }
        }

        mano.algo.caster.CrashLog.note(this, "Sharing started")
        try {
            acquireLocks()
            registerNetworkCallback()
            _state.value = ServerState.Starting
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
        mano.algo.caster.CrashLog.note(this, "Sharing stopped")
        networkCallback?.let { cb ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        scope.cancel()
        // Cancelled scope means no new reconcile; stop synchronously (closing sockets is quick).
        mdns?.stop()
        mdns = null
        server?.stop()
        server = null
        boundHost = null
        PairingManager.reset()
        CastController.stop()   // the Chromecast's access just ended with the server, so end its session too
        RemoteControl.clear()
        VideoConverter.clear(this)
        releaseLocks()
        // A start that failed leaves its reason on screen instead of replacing it with a plain "stopped".
        if (!startFailed) _state.value = ServerState.Stopped
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
                boundRequestedName = wantedName
                publish(
                    running.copy(announcedName = announced, requestedName = wantedName),
                    "Sharing videos at ${announced ?: host}:${running.port}",
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
                publish(
                    ServerState.Running(
                        host, port, name, requestedPort = wanted, requestedName = wantedName,
                        hotspot = NetworkUtils.isHotspot(this@MediaServerService),
                    ),
                    "Sharing videos at ${name ?: host}:$port" + if (port != wanted) " (port $wanted was busy)" else "",
                )
                return@withLock
            }
            publish(ServerState.Error("Couldn't open any port on $host. Try another port in Settings."), "Server failed to start")
        }
    }

    private fun stopServerLocked() {
        mdns?.stop()
        mdns = null
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
        _state.value = state
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(getString(R.string.app_name), notificationText))
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
            NotificationChannel(CHANNEL_ID, "Media server", NotificationManager.IMPORTANCE_LOW),
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
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Connect ${request.label}?")
            .setContentText("Approve only if the code on its screen is ${request.code}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Approve only if the code on its screen is ${request.code}"))
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Deny", action(2, ACTION_DENY))
            .addAction(android.R.drawable.checkbox_on_background, "Approve", action(3, ACTION_APPROVE))
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
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MediaServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val TAG = "MediaServerService"
        private const val CHANNEL_ID = "media_server"
        private const val NOTIFICATION_ID = 1001
        private const val PAIRING_CHANNEL_ID = "pairing"
        private const val PAIRING_NOTIFICATION_ID = 1002
        private const val ACTION_APPROVE = "mano.algo.caster.action.APPROVE_PAIRING"
        private const val ACTION_DENY = "mano.algo.caster.action.DENY_PAIRING"
        private const val ACTION_STOP = "mano.algo.caster.action.STOP_SERVER"
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
        }
    }
}
