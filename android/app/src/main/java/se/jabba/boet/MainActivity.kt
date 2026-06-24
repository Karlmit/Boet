package se.jabba.boet

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import se.jabba.boet.data.local.Settings
import se.jabba.boet.ui.BoetNavHost
import se.jabba.boet.ui.theme.BoetTheme

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as BoetApp

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

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
