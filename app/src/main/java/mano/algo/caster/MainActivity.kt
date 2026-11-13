package mano.algo.caster

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import mano.algo.caster.ui.CasterApp
import mano.algo.caster.ui.theme.CasterTheme

// FragmentActivity (still a ComponentActivity) because the Chromecast device chooser is a dialog fragment.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CasterTheme {
                CasterApp()
            }
        }
    }
}
