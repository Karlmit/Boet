package se.jabba.boet.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.jabba.boet.MainActivity
import se.jabba.boet.R
import se.jabba.boet.data.local.BoetDatabase

// Shared render logic for the Matkasse home-screen widget. The widget reads
// straight from the offline-first Room mirror (the same source of truth as the
// app), so it shows the latest local state even offline. Live refreshes are
// driven by the items-flow collector in BoetApp; the provider's periodic
// onUpdate is only a staleness backstop.
object MatkasseWidget {

    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, MatkasseWidgetProvider::class.java))

    // Re-render the header (list name + remaining count) and poke the list
    // adapter so the RemoteViewsFactory re-reads Room. Cheap no-op when no
    // widget is placed on the home screen.
    suspend fun update(context: Context) = withContext(Dispatchers.IO) {
        val ids = ids(context)
        if (ids.isEmpty()) return@withContext
        val manager = AppWidgetManager.getInstance(context)

        val db = BoetDatabase.get(context)
        val listId = db.listDao().firstGroceryListId() ?: db.listDao().anyListId()
        val name = listId?.let { db.listDao().nameById(it) }
            ?: context.getString(R.string.widget_title_fallback)
        val remaining = listId?.let { id ->
            db.itemDao().itemsForListOnce(id).count { !it.checked }
        } ?: 0

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val quickAdd = PendingIntent.getActivity(
            context, 1,
            Intent(context, WidgetAddActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        for (widgetId in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_matkasse)
            views.setTextViewText(R.id.widget_title, name)
            views.setTextViewText(R.id.widget_count, context.getString(R.string.items_left, remaining))
            views.setOnClickPendingIntent(R.id.widget_root, openApp)
            views.setOnClickPendingIntent(R.id.widget_header, openApp)
            views.setOnClickPendingIntent(R.id.widget_add, quickAdd)

            // Item rows come from the RemoteViewsFactory; a unique data URI keeps
            // each widget instance's adapter intent distinct.
            val adapterIntent = Intent(context, MatkasseWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(android.R.id.list, adapterIntent)
            views.setEmptyView(android.R.id.list, R.id.widget_empty)
            // A collection only gets ONE PendingIntent template, so every row
            // (checkbox or name/qty) routes through WidgetActionReceiver, which
            // tells the two apart via the per-view fill-in intent's extras (see
            // MatkasseWidgetService). The template must be mutable for fill-ins
            // to apply (S+ requires saying so explicitly; older versions are
            // mutable by default).
            val mutableFlag =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            views.setPendingIntentTemplate(
                android.R.id.list,
                PendingIntent.getBroadcast(
                    context, 2,
                    Intent(context, WidgetActionReceiver::class.java),
                    mutableFlag or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            manager.updateAppWidget(widgetId, views)
        }
        manager.notifyAppWidgetViewDataChanged(ids, android.R.id.list)
    }
}
