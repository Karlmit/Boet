package se.jabba.boet.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import se.jabba.boet.BoetApp

// Receives the system's widget lifecycle broadcasts. All rendering lives in
// MatkasseWidget; the periodic onUpdate additionally pulls a fresh snapshot
// from the server first, so a widget on a phone where the app hasn't been
// opened in a while still catches the other member's changes.
class MatkasseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Best-effort freshen (offline → Room already has the last snapshot).
                runCatching { (context.applicationContext as? BoetApp)?.repository?.bootstrap() }
                runCatching { MatkasseWidget.update(context) }
            } finally {
                pending.finish()
            }
        }
    }

    // Re-render on resize so the launcher gets a layout for the new size.
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { MatkasseWidget.update(context) }
            } finally {
                pending.finish()
            }
        }
    }
}
