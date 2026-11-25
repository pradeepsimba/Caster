package mano.algo.caster

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import mano.algo.caster.cast.ShareIntake
import mano.algo.caster.tv.TvKey
import mano.algo.caster.tv.TvRemote
import mano.algo.caster.ui.CasterApp
import mano.algo.caster.ui.theme.CasterTheme

// FragmentActivity (still a ComponentActivity) because the Chromecast device chooser is a dialog fragment.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only the very first start: a recreated activity (rotation) still holds the same intent, which was already taken.
        if (savedInstanceState == null) takeShared(intent)
        setContent {
            CasterTheme {
                CasterApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeShared(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (TvRemote.state.value is TvRemote.State.Connected) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    TvRemote.key(TvKey.VOLUME_UP, repeating = (event?.repeatCount ?: 0) > 0)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    TvRemote.key(TvKey.VOLUME_DOWN, repeating = (event?.repeatCount ?: 0) > 0)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    TvRemote.key(TvKey.MUTE)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (TvRemote.state.value is TvRemote.State.Connected) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_MUTE -> return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /** A video sent here with "Share" from the gallery waits for a Chromecast; anything else sent is explained. */
    private fun takeShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (!ShareIntake.handle(this, intent)) {
            Toast.makeText(this, "Caster can only play videos from this phone's gallery. Share it from the Gallery app.", Toast.LENGTH_LONG).show()
        }
        intent.action = null // taken: a later recreation must not do it again
    }
}
