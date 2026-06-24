package se.jabba.boet

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import se.jabba.boet.data.local.Prefs
import se.jabba.boet.data.local.Settings
import se.jabba.boet.ui.BoetNavHost
import se.jabba.boet.ui.theme.BoetTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Language the activity was created with — used to recreate on change.
    private var appliedLang: String = "sv"

    // Apply the saved UI language to the whole activity (incl. dialogs/sheets,
    // which live in separate windows and so can't inherit a Compose-local locale).
    override fun attachBaseContext(newBase: Context) {
        val lang = runCatching { runBlocking { Prefs(newBase).settings.first().language } }
            .getOrDefault("sv")
        appliedLang = lang
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(Locale(lang))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as BoetApp

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settings by app.prefs.settings.collectAsState(initial = Settings(language = appliedLang))

            // Re-create the activity when the language changes so attachBaseContext
            // re-applies the locale everywhere.
            LaunchedEffect(settings.language) {
                if (settings.language != appliedLang) recreate()
            }

            BoetTheme {
                BoetNavHost(app = app, settings = settings)
            }
        }
    }
}
