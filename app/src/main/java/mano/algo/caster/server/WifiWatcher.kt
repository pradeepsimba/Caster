package mano.algo.caster.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import mano.algo.caster.R
import mano.algo.caster.quick.QuickActionActivity

/**
 * Watches for Wi-Fi coming and going while the app is closed, using a callback that the system keeps for us (it wakes
 * [WifiReceiver], so nothing runs in between). Two optional things hang on it: an offer to start sharing when the phone joins a
 * Wi-Fi network, and stopping when the Wi-Fi is lost. Both are switched in Settings; this follows them. Needs Android 8 or newer.
 */
object WifiWatcher {
    private const val TAG = "WifiWatcher"
    private const val REQUEST_CODE = 41
    private const val OFFER_CHANNEL = "wifi_offer"
    private const val OFFER_NOTIFICATION_ID = 1003
    internal const val PREFS = "caster_wifi"
    internal const val KEY_LAST_OFFER = "last_offer"
    internal const val KEY_QUIET_UNTIL = "quiet_until"
    internal const val OFFER_EVERY_MS = 20 * 60_000L

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, WifiReceiver::class.java),
        // The system fills in what happened, so this one has to be changeable.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Registers or removes the system callback so that it matches the settings. Safe to call whenever. */
    fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val app = context.applicationContext
        ShareSettings.init(app)
        val settings = ShareSettings.state.value
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val intent = pending(app)
        runCatching { cm.unregisterNetworkCallback(intent) } // never twice
        if (!settings.wifiOffer && !settings.stopOnWifiLost) return
        // Registering reports the Wi-Fi that is already there as if it had just arrived: that one must not become an offer.
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_QUIET_UNTIL, System.currentTimeMillis() + 8_000L).apply()
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build(),
                intent,
            )
        }.onFailure { Log.w(TAG, "Couldn't watch the network", it) }
    }

    internal fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        // Any Wi-Fi or Ethernet counts, not just the one Android uses by default.
        @Suppress("DEPRECATION")
        return cm.allNetworks.any {
            val caps = cm.getNetworkCapabilities(it) ?: return@any false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }

    internal fun offerNotification(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(OFFER_CHANNEL, "Wi-Fi offers", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val start = PendingIntent.getActivity(
            context, 42, QuickActionActivity.intent(context, QuickActionActivity.ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, OFFER_CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Share your videos on this Wi-Fi?")
            .setContentText("Tap to start sharing with the screens around you.")
            .setContentIntent(start)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        runCatching { manager.notify(OFFER_NOTIFICATION_ID, notification) }
    }
}

/** Woken by the system when the Wi-Fi changes (or the phone has started again: the callback is gone then and is set up again). */
class WifiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            WifiWatcher.refresh(app)
            return
        }
        val prefs = app.getSharedPreferences(WifiWatcher.PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() < prefs.getLong(WifiWatcher.KEY_QUIET_UNTIL, 0L)) return
        ShareSettings.init(app)
        val settings = ShareSettings.state.value
        val state = MediaServerService.state.value
        // "On" includes the problem state: when the Wi-Fi drops the service reports an error but is still alive and holding the phone awake.
        val running = state !is ServerState.Stopped
        if (WifiWatcher.onWifi(app)) {
            // (An error left on screen is not "sharing": the offer may start it again.)
            if (!settings.wifiOffer || state is ServerState.Running || state == ServerState.Starting) return
            val now = System.currentTimeMillis()
            // One offer at a time: a Wi-Fi that flickers must not fill the shade.
            if (now - prefs.getLong(WifiWatcher.KEY_LAST_OFFER, 0L) < WifiWatcher.OFFER_EVERY_MS) return
            prefs.edit().putLong(WifiWatcher.KEY_LAST_OFFER, now).apply()
            WifiWatcher.offerNotification(app)
        } else if (settings.stopOnWifiLost && running) {
            // A short moment of grace: moving from one access point to another looks like a loss for a second.
            val pending = goAsync()
            Thread {
                try {
                    Thread.sleep(6_000)
                    val still = MediaServerService.state.value
                    val hotspot = runCatching { mano.algo.caster.server.NetworkUtils.isHotspot(app) }.getOrDefault(false)
                    if (!WifiWatcher.onWifi(app) && still !is ServerState.Stopped && !hotspot) MediaServerService.stop(app)
                } catch (_: InterruptedException) {
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}
