package com.neardue.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 上课提醒的闹钟到点了。
 *
 * 三件事，顺序不能乱：
 * 1. 免打扰窗口内不弹，改约到窗口结束（和截止提醒同一套规矩，用户不用学两遍）
 * 2. 弹通知
 * 3. 续排 —— 这个闹钟响完就没了，把窗口重新补满，否则两周后就不响了
 */
class CourseReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(CourseAlarmScheduler.EXTRA_COURSE_TITLE) ?: return
        val courseId = intent.getLongExtra(CourseAlarmScheduler.EXTRA_COURSE_ID, 0L)
        val room = intent.getStringExtra(CourseAlarmScheduler.EXTRA_COURSE_ROOM).orEmpty()
        val sessionText = intent.getStringExtra(CourseAlarmScheduler.EXTRA_SESSION_TEXT).orEmpty()
        val startAt = intent.getLongExtra(CourseAlarmScheduler.EXTRA_START_AT, 0L)
        val quietDeferred = intent.getBooleanExtra(CourseAlarmScheduler.EXTRA_QUIET_DEFERRED, false)
        // v1.31: 通知副标题用这节课实际生效的提前量，不是全局值。
        val lead = intent.getIntExtra(CourseAlarmScheduler.EXTRA_COURSE_LEAD, CourseLead.normalize(Prefs.courseLeadMinutes(context)))

        // 开关被关掉之后残留的闹钟：不响，也不续排。
        if (!Prefs.courseRemindEnabled(context)) return

        if (!quietDeferred && Prefs.quietEnabled(context) && inQuietWindow(context)) {
            val slot = CourseReminder.Slot(
                courseId = courseId,
                title = title,
                room = room,
                sessionStart = 0,
                sessionEnd = 0,
                dayOfWeek = 0,
                week = null,
                startAt = startAt,
                triggerAt = System.currentTimeMillis(),
            )
            CourseAlarmScheduler.scheduleOne(context, slot, quietWindowEndMillis(context))
            return
        }

        CourseReminderNotifier.show(
            context = context,
            courseId = courseId,
            title = title,
            room = room,
            sessionText = sessionText,
            startAt = startAt,
            leadMinutes = lead,
        )

        // 响过就没了，立刻把窗口补满。
        try {
            CourseAlarmScheduler.rescheduleAll(context)
        } catch (ignored: Exception) {
            // 续排失败不能连累通知；下次打开 App 还会重排。
        }
    }

    private fun inQuietWindow(context: Context): Boolean {
        val start = Prefs.quietStart(context).coerceIn(0, 23)
        val end = Prefs.quietEnd(context).coerceIn(0, 23)
        if (start == end) return false
        val hour = LocalDateTime.now().hour
        return if (start < end) hour in start until end else hour >= start || hour < end
    }

    private fun quietWindowEndMillis(context: Context): Long {
        val end = Prefs.quietEnd(context).coerceIn(0, 23)
        val now = LocalDateTime.now()
        var target = now.withHour(end).withMinute(0).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
