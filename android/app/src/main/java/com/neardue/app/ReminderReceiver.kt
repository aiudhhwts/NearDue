package com.neardue.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 提醒到点、通知按钮点击、免打扰延后的统一入口。
 * 只做轻量工作：读写本地 SQLite、重排闹钟、发通知，不联网、不 goAsync。
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TEST = "com.neardue.app.ACTION_TEST_REMINDER"
        const val ACTION_DONE = "com.neardue.app.ACTION_MARK_DONE"
        const val ACTION_SNOOZE = "com.neardue.app.ACTION_SNOOZE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DONE -> {
                val id = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, AlarmScheduler.TEST_ITEM_ID)
                ItemStore(context).setDone(id, true)
                NearDueWidget.refresh(context)
                AlarmScheduler.scheduleAll(context)
                cancelNotification(context, id)
            }

            ACTION_SNOOZE -> {
                val id = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, AlarmScheduler.TEST_ITEM_ID)
                val store = ItemStore(context)
                val item = store.get(id) ?: return
                store.save(item.copy(dueAt = item.dueAt + 24L * 60 * 60 * 1000))
                NearDueWidget.refresh(context)
                AlarmScheduler.scheduleAll(context)
                cancelNotification(context, id)
            }

            else -> {
                // 普通到点提醒，或 ACTION_TEST 测试广播。
                val id = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, AlarmScheduler.TEST_ITEM_ID)
                val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "将至"
                val dueAt = intent.getLongExtra(
                    AlarmScheduler.EXTRA_DUE_AT, System.currentTimeMillis()
                )
                val categoryOrdinal = intent.getIntExtra(
                    AlarmScheduler.EXTRA_CATEGORY, Category.OTHER.ordinal
                )
                val offsetMinutes = intent.getIntExtra(AlarmScheduler.EXTRA_OFFSET, 0)
                val quietDeferred = intent.getBooleanExtra(
                    AlarmScheduler.EXTRA_QUIET_DEFERRED, false
                )

                if (!quietDeferred && Prefs.quietEnabled(context) && inQuietWindow(context)) {
                    val item = Item(
                        id = id,
                        title = title,
                        dueAt = dueAt,
                        category = Category.of(categoryOrdinal)
                    )
                    AlarmScheduler.scheduleOne(context, item, quietWindowEndMillis(context))
                    return                       // 免打扰期间不弹通知
                }

                ReminderNotifier.show(context, id, title, dueAt, categoryOrdinal, offsetMinutes)
            }
        }
    }

    private fun cancelNotification(context: Context, itemId: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(itemId.toInt())
    }

    /** 当前小时是否落在免打扰窗口内；支持跨午夜（如 23→7）。 */
    private fun inQuietWindow(context: Context): Boolean {
        val start = Prefs.quietStart(context).coerceIn(0, 23)
        val end = Prefs.quietEnd(context).coerceIn(0, 23)
        if (start == end) return false           // 首尾相同视为未开启，避免无限延后
        val hour = LocalDateTime.now().hour
        return if (start < end) {
            hour in start until end
        } else {
            hour >= start || hour < end
        }
    }

    /** 当前这段免打扰窗口的结束时刻（今天或明天），转成 epoch millis。 */
    private fun quietWindowEndMillis(context: Context): Long {
        val end = Prefs.quietEnd(context).coerceIn(0, 23)
        val now = LocalDateTime.now()
        var target = now.withHour(end).withMinute(0).withSecond(0).withNano(0)
        // 跨午夜窗口且现在是深夜时，今天的 end:00 已过去，结束点在明天。
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
