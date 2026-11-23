package mano.algo.caster

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import mano.algo.caster.cast.ShareIntake
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

    /** A video sent here with "Share" from the gallery waits for a Chromecast; anything else sent is explained. */
    private fun takeShared(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (!ShareIntake.handle(this, intent)) {
            Toast.makeText(this, "Caster can only play videos from this phone's gallery. Share it from the Gallery app.", Toast.LENGTH_LONG).show()
        }
        intent.action = null // taken: a later recreation must not do it again
    }
}
