package se.jabba.boet.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import se.jabba.boet.BoetApp
import se.jabba.boet.MainActivity
import se.jabba.boet.R

const val ACTIVITY_CHANNEL_ID = "boet_activity"

class BoetMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Register the refreshed token with the backend.
        (application as? BoetApp)?.repository?.registerDevice(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification
        val title = n?.title ?: message.data["title"] ?: "Boet"
        val body = n?.body ?: message.data["body"] ?: return
        showNotification(this, title, body)
    }

    companion object {
        fun ensureChannel(context: Context) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                ACTIVITY_CHANNEL_ID,
                "Delad aktivitet",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Notiser när den andra personen ändrar en lista" }
            mgr.createNotificationChannel(channel)
        }

        fun showNotification(context: Context, title: String, body: String) {
            ensureChannel(context)
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, ACTIVITY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_boet)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java)
            mgr?.notify(System.currentTimeMillis().toInt(), notification)
        }
    }
}
