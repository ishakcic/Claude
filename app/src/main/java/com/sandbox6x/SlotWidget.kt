package com.sandbox6x

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Büyük widget: hesap listesi, satıra dokununca o hesap açılır. */
class SlotWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, build(context))
    }

    companion object {
        private val ROWS = intArrayOf(
            R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5, R.id.row6, R.id.row7, R.id.row8
        )

        fun build(c: Context): RemoteViews {
            val rv = RemoteViews(c.packageName, R.layout.widget)
            val arr = SlotState.array(c)
            val now = System.currentTimeMillis()
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            for (i in ROWS.indices) {
                if (i < arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optInt("id", i + 1)
                    val at = o.optLong("resetAt")
                    val limited = at > now
                    val state = if (limited) "⏳ " + fmt.format(Date(at)) else "✓ müsait"
                    val sp = SpannableString("● " + o.optString("code") + "  " + o.optString("name") + "   " + state)
                    val dot = try { Color.parseColor(o.optString("color", "#D97757")) } catch (_: Exception) { 0xFFD97757.toInt() }
                    sp.setSpan(ForegroundColorSpan(dot), 0, 1, 0)
                    rv.setTextViewText(ROWS[i], sp)
                    rv.setTextColor(ROWS[i], if (limited) 0xFFFB7185.toInt() else 0xFF34D399.toInt())
                    rv.setViewVisibility(ROWS[i], View.VISIBLE)
                    rv.setOnClickPendingIntent(
                        ROWS[i],
                        PendingIntent.getActivity(
                            c, 200 + i, Shortcuts.openIntent(c, id),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                } else {
                    rv.setViewVisibility(ROWS[i], View.GONE)
                }
            }
            rv.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    c, 199, Intent(c, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            return rv
        }

        fun refresh(c: Context) {
            val m = AppWidgetManager.getInstance(c)
            for (id in m.getAppWidgetIds(ComponentName(c, SlotWidget::class.java))) m.updateAppWidget(id, build(c))
            for (id in m.getAppWidgetIds(ComponentName(c, SlotWidgetSmall::class.java))) m.updateAppWidget(id, SlotWidgetSmall.build(c))
        }
    }
}

/** Küçük widget: tek satır özet. */
class SlotWidgetSmall : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, build(context))
    }

    companion object {
        fun build(c: Context): RemoteViews {
            val rv = RemoteViews(c.packageName, R.layout.widget_small)
            val s = SlotState.summary(c)
            val next = if (s.nextAt > 0) "⏳ " + s.nextCode + " " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.nextAt)) else "Hepsi hazır"
            rv.setTextViewText(R.id.small_main, if (s.total == 0) "Uygulamayı aç" else "${s.free}/${s.total} müsait")
            rv.setTextViewText(R.id.small_sub, next)
            rv.setOnClickPendingIntent(
                R.id.widget_small_root,
                PendingIntent.getActivity(
                    c, 198, Intent(c, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            return rv
        }
    }
}
