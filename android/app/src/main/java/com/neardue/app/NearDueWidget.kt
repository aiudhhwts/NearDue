// 原生 RemoteViews 小组件：透明底 + 最近 3 行，不引入 Glance。
package com.neardue.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class NearDueWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pending = ItemStore(context).list().filter { !it.done }
        val views = RemoteViews(context.packageName, R.layout.widget_near_due)

        views.setOnClickPendingIntent(
            R.id.w_root,
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )

        views.setTextViewText(R.id.w_count, "${pending.size} 件事")

        // B：未来 7 天的考试/比赛聚合条；没有就藏起来。
        val examText = WidgetText.examBar(pending, System.currentTimeMillis())
        if (examText == null) {
            views.setViewVisibility(R.id.w_exambar, View.GONE)
        } else {
            views.setViewVisibility(R.id.w_exambar, View.VISIBLE)
            views.setTextViewText(R.id.w_exambar, examText)
        }

        val lineIds = intArrayOf(R.id.w_line1, R.id.w_line2, R.id.w_line3)
        val today = LocalDate.now()
        for (i in lineIds.indices) {
            val id = lineIds[i]
            val item = pending.getOrNull(i)
            if (item != null) {
                views.setTextViewText(id, WidgetText.line(item, today))
                // A：每行独立 PendingIntent，带 item id 直达编辑（不是只开首页）。
                val open = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse("neardue://item/${item.id}")
                    putExtra(AlarmScheduler.EXTRA_ITEM_ID, item.id)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                views.setOnClickPendingIntent(
                    id,
                    PendingIntent.getActivity(
                        context,
                        item.id.toInt(),
                        open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                views.setViewVisibility(id, View.VISIBLE)
            } else {
                views.setTextViewText(id, "")
                views.setViewVisibility(id, View.GONE)
            }
        }

        if (pending.isEmpty()) {
            views.setViewVisibility(R.id.w_empty, View.VISIBLE)
            views.setTextViewText(R.id.w_empty, "现在，一件都不用惦记")
            for (id in lineIds) views.setViewVisibility(id, View.GONE)
        } else {
            views.setViewVisibility(R.id.w_empty, View.GONE)
        }

        // v1.22 字号档位：用 RemoteViews 动态设置，布局 XML 不动。
        val sp = WidgetText.widgetSp(Prefs.widgetTextSize(context))
        for (id in intArrayOf(R.id.w_line1, R.id.w_line2, R.id.w_line3, R.id.w_empty)) {
            views.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, sp[0].toFloat())
        }
        for (id in intArrayOf(R.id.w_count, R.id.w_exambar)) {
            views.setTextViewTextSize(id, android.util.TypedValue.COMPLEX_UNIT_SP, sp[1].toFloat())
        }

        for (id in intArrayOf(
            R.id.w_count,
            R.id.w_exambar,
            R.id.w_line1,
            R.id.w_line2,
            R.id.w_line3,
            R.id.w_empty
        )) {
            views.setTextColor(id, Color.WHITE)
        }

        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, views)
    }

    companion object {
        fun refresh(context: Context) {
            val ids = AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, NearDueWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, NearDueWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }
}
