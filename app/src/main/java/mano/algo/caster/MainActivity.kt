package mano.algo.caster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import mano.algo.caster.ui.CasterApp
import mano.algo.caster.ui.theme.CasterTheme

class MainActivity : ComponentActivity() {
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
