package se.jabba.boet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import se.jabba.boet.data.local.Settings
import se.jabba.boet.ui.BoetNavHost
import se.jabba.boet.ui.theme.BoetTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as BoetApp

        setContent {
            val settings by app.prefs.settings.collectAsState(initial = Settings())
            BoetTheme {
                BoetNavHost(
                    app = app,
                    settings = settings,
                )
            }
        }
    }
}
