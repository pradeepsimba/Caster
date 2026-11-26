package mano.algo.caster.quick

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mano.algo.caster.MainActivity
import mano.algo.caster.R
import mano.algo.caster.server.MediaServerService
import mano.algo.caster.server.NetworkUtils
import mano.algo.caster.server.ServerState
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import mano.algo.caster.security.PinLock
import mano.algo.caster.ui.VerifyPinDialog
import mano.algo.caster.ui.theme.CasterTheme

/**
 * Start or stop sharing without opening the app: the home-screen shortcuts, the Quick Settings tile, the widget and the Wi-Fi
 * notification all come through here. It has no screen; it does the one thing and closes. It is an activity (not a button on the
 * notification) because Android only lets an app start sharing in the foreground from something the user can see.
 */
class QuickActionActivity : ComponentActivity() {
    private var asking by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // With a PIN set, starting or stopping from outside the app asks for it (unless it was just typed in the app).
        if (!PinLock.isSet(this) || PinLock.sessionOpen()) {
            perform()
            finish()
            return
        }
        asking = true
        setContent {
            CasterTheme {
                if (asking) {
                    VerifyPinDialog(
                        title = "Enter your PIN",
                        attempt = { PinLock.attempt(this, it) },
                        onOk = { asking = false; perform(); finish() },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    private fun perform() {
        val on = isOn(MediaServerService.state.value)
        val wantOn = when (intent?.action) {
            ACTION_START -> true
            ACTION_STOP -> false
            else -> !on
        }
        // A leftover error (a start that failed, or the Wi-Fi lost) counts as "on" for Stop, but Start has to begin again.
        val restart = wantOn && MediaServerService.state.value is ServerState.Error
        if (wantOn && (!on || restart)) {
            if (canStartHere(this)) {
                if (NetworkUtils.resolveLocalIpv4(this) == null) {
                    Toast.makeText(this, "No Wi-Fi or hotspot. Turn on Mobile Hotspot to share.", Toast.LENGTH_LONG).show()
                } else {
                    if (restart) MediaServerService.stop(this)
                    MediaServerService.start(this)
                }
            } else {
                // Something has to be allowed first, and only the app can ask.
                Toast.makeText(this, "Open Caster once to allow access to your videos.", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else if (!wantOn && on) {
            MediaServerService.stop(this)
        }
    }

    companion object {
        const val ACTION_START = "mano.algo.caster.action.QUICK_START"
        const val ACTION_STOP = "mano.algo.caster.action.QUICK_STOP"
        const val ACTION_TOGGLE = "mano.algo.caster.action.QUICK_TOGGLE"

        fun intent(context: Context, action: String): Intent =
            Intent(context, QuickActionActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Sharing needs access to the videos (and the local network on the newest Android): without it only the app can ask. */
        fun canStartHere(context: Context): Boolean {
            val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO
            else Manifest.permission.READ_EXTERNAL_STORAGE
            fun has(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
            return has(storage) && (Build.VERSION.SDK_INT < 37 || has("android.permission.ACCESS_LOCAL_NETWORK"))
        }

        /** Anything but stopped: a service that reports a problem (Wi-Fi lost) is still alive, and the tile and Stop must reach it. */
        fun isOn(state: ServerState) = state !is ServerState.Stopped
    }
}

/** The tile in the Quick Settings panel: lit while sharing, tap to start or stop. */
class ShareTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onTileAdded() {
        super.onTileAdded()
        render(MediaServerService.state.value)
    }

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = scope.launch { MediaServerService.state.collect(::render) }
    }

    override fun onStopListening() {
        job?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(state: ServerState) {
        val tile = qsTile ?: return
        val on = QuickActionActivity.isOn(state)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (on) "Sharing" else "Off"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = QuickActionActivity.intent(this, QuickActionActivity.ACTION_TOGGLE)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            // Only Android 14 and newer have the PendingIntent form; older versions need this one.
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

/** The home-screen widget: shows whether sharing is on, and a tap starts or stops it. */
class ShareWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        /** Redraws every placed widget from the current state. Called whenever sharing starts or stops. */
        fun update(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, ShareWidget::class.java))
            if (ids.isNotEmpty()) render(app, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val on = QuickActionActivity.isOn(MediaServerService.state.value)
            val views = RemoteViews(context.packageName, R.layout.widget_share)
            views.setTextViewText(R.id.widget_status, if (on) "Sharing is on · tap to stop" else "Sharing is off · tap to start")
            views.setInt(R.id.widget_root, "setBackgroundResource", if (on) R.drawable.widget_bg_on else R.drawable.widget_bg_off)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 0, QuickActionActivity.intent(context, QuickActionActivity.ACTION_TOGGLE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            manager.updateAppWidget(ids, views)
        }
    }
}

/** The single app-icon shortcut: shows "Start sharing" (play icon) when off, or "Stop sharing" (stop icon) when on. */
object ShareShortcut {
    private const val ID = "toggle_sharing"

    /** Publishes the single dynamic shortcut matching the current sharing state. */
    fun update(context: Context) {
        val app = context.applicationContext
        val on = QuickActionActivity.isOn(MediaServerService.state.value)
        val shortcut = ShortcutInfoCompat.Builder(app, ID)
            .setShortLabel(app.getString(if (on) R.string.shortcut_stop_short else R.string.shortcut_start_short))
            .setLongLabel(app.getString(if (on) R.string.shortcut_stop_long else R.string.shortcut_start_long))
            .setIcon(IconCompat.createWithResource(app, if (on) R.drawable.ic_shortcut_stop else R.drawable.ic_shortcut_play))
            .setIntent(QuickActionActivity.intent(app, QuickActionActivity.ACTION_TOGGLE))
            .build()
        ShortcutManagerCompat.setDynamicShortcuts(app, listOf(shortcut))
    }
}
