package se.jabba.boet.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import se.jabba.boet.BoetApp
import se.jabba.boet.MainActivity
import se.jabba.boet.data.local.BoetDatabase

// A RemoteViews collection (the widget's ListView) only supports one
// PendingIntent template, so every row tap — checkbox or name/qty — funnels
// through this one receiver; the fill-in intent set per view (see
// MatkasseWidgetService) tells us which case we're in via EXTRA_ITEM_ID.
class WidgetActionReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ITEM_ID = "item_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
        if (itemId == null) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }

        // Checkbox tap: flip the item to checked (it then drops out of the
        // widget's unchecked-only list) without ever launching the app.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    val app = context.applicationContext as BoetApp
                    val item = BoetDatabase.get(context).itemDao().byId(itemId)
                    if (item != null) app.repository.toggleChecked(item)
                }
                runCatching { MatkasseWidget.update(context) }
            } finally {
                pending.finish()
            }
        }
    }
}
