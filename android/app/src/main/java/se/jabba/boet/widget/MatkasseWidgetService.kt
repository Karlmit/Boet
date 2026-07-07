package se.jabba.boet.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.coroutines.runBlocking
import se.jabba.boet.R
import se.jabba.boet.ai.formatNumber
import se.jabba.boet.ai.parseQuantity
import se.jabba.boet.data.local.BoetDatabase

// Feeds the widget's ListView. Reads the Matkasse straight from Room (unchecked
// items only), grouped under small category labels in the same order as the app.
class MatkasseWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        MatkasseRemoteViewsFactory(applicationContext)
}

private class MatkasseRemoteViewsFactory(
    private val context: Context,
) : RemoteViewsService.RemoteViewsFactory {

    private sealed interface Row {
        data class Section(val name: String) : Row
        data class Item(val name: String, val qty: String?) : Row
    }

    private var rows: List<Row> = emptyList()

    override fun onCreate() {}
    override fun onDestroy() {}

    // Called on a binder thread — blocking Room reads are fine here.
    override fun onDataSetChanged() {
        rows = runBlocking { load() }
    }

    private suspend fun load(): List<Row> {
        val db = BoetDatabase.get(context)
        val listId = db.listDao().firstGroceryListId() ?: db.listDao().anyListId() ?: return emptyList()
        val categories = db.categoryDao().categoriesForListOnce(listId).sortedBy { it.position }
        val active = db.itemDao().itemsForListOnce(listId).filter { !it.checked }
        val byCat = active.groupBy { it.categoryId }
        return buildList {
            for (cat in categories) {
                val items = (byCat[cat.id] ?: emptyList()).sortedBy { it.position }
                if (items.isEmpty()) continue
                add(Row.Section(cat.name))
                items.forEach { add(Row.Item(it.name, badge(it.quantity))) }
            }
            // Items with no (or a stale) category — same fallback bucket as the app.
            val orphan = active.filter { it.categoryId == null || categories.none { c -> c.id == it.categoryId } }
            if (orphan.isNotEmpty()) {
                add(Row.Section("Övrigt"))
                orphan.sortedBy { it.position }.forEach { add(Row.Item(it.name, badge(it.quantity))) }
            }
        }
    }

    // Badge text like the in-app list: bare counts as "×N", measures verbatim.
    private fun badge(quantity: String?): String? {
        val q = quantity?.trim().orEmpty()
        if (q.isEmpty()) return null
        val a = parseQuantity(q)
        return if (a.unit == null && a.value > 1.0) "×${formatNumber(a.value)}" else q
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews =
        when (val row = rows.getOrNull(position)) {
            is Row.Section -> RemoteViews(context.packageName, R.layout.widget_matkasse_section).apply {
                setTextViewText(R.id.section_name, row.name)
            }
            is Row.Item -> RemoteViews(context.packageName, R.layout.widget_matkasse_row).apply {
                setTextViewText(R.id.row_name, row.name)
                if (row.qty != null) {
                    setTextViewText(R.id.row_qty, row.qty)
                    setViewVisibility(R.id.row_qty, View.VISIBLE)
                } else {
                    setViewVisibility(R.id.row_qty, View.GONE)
                }
                // Empty fill-in: the row tap fires the widget's open-app template.
                setOnClickFillInIntent(R.id.row_root, Intent())
            }
            null -> RemoteViews(context.packageName, R.layout.widget_matkasse_row)
        }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 2
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
}
