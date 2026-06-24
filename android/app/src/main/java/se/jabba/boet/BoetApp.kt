package se.jabba.boet

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import se.jabba.boet.data.Repository
import se.jabba.boet.data.local.Prefs
import se.jabba.boet.data.local.Settings
import se.jabba.boet.data.remote.ApiClient
import se.jabba.boet.push.BoetMessagingService

// Manual dependency container. Small app, single household — no DI framework needed.
class BoetApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var repository: Repository
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Synchronous snapshots kept fresh from the settings flow, for the API/WS clients.
    @Volatile private var serverUrl: String = Settings.DEFAULT_SERVER
    @Volatile private var identity: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        BoetMessagingService.ensureChannel(this)

        val api = ApiClient(baseUrlProvider = { serverUrl })
        repository = Repository(
            context = this,
            api = api,
            scope = appScope,
            identityProvider = { identity },
            baseUrlProvider = { serverUrl },
        )

        // Keep snapshots and the realtime connection in sync with settings.
        prefs.settings.onEach { s ->
            serverUrl = s.serverUrl
            val changedIdentity = identity != s.identity
            identity = s.identity
            if (s.identity != null && changedIdentity) {
                repository.realtime.connect(s.identity.lowercase(), s.identity)
                registerFcmToken()
            }
        }.launchIn(appScope)
    }

    private fun registerFcmToken() {
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) task.result?.let { repository.registerDevice(it) }
            }
        }
    }

    companion object {
        lateinit var instance: BoetApp
            private set
    }
}
