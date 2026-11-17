package com.adam.insightjournal.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.adam.insightjournal.R

/**
 * Home screen widget: one bar that opens [QuickNoteActivity] to jot a note
 * without opening the app.
 *
 * It is a plain AppWidgetProvider with RemoteViews (no Glance): the widget shows
 * nothing that changes, so all it needs is a layout and a click.
 */
class QuickNoteWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        val openCapture = PendingIntent.getActivity(
            context,
            0,
            Intent(context, QuickNoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            // Immutable: nothing outside the app needs to change this intent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val views = RemoteViews(context.packageName, R.layout.widget_quick_note).apply {
            setOnClickPendingIntent(R.id.widget_root, openCapture)
        }
        widgetIds.forEach { manager.updateAppWidget(it, views) }
    }
}
