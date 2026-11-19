package com.neardue.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 上课提醒的通知（与 [ReminderNotifier] 的「截止提醒」分开一条渠道）。
 *
 * 分渠道的理由：用户在系统设置里可以单独关掉「上课提醒」而保留截止提醒，
 * 反过来也一样。这是 Android 通知的既有能力，不用自己造开关。
 */
object CourseReminderNotifier {

    const val CHANNEL_ID = "neardue_courses"
    const val CHANNEL_NAME = "上课提醒"

    private val PI_FLAGS =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private val ClockFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "快要上课时提醒你，提前量可在设置里改"
                enableVibration(true)
                enableLights(true)
                setShowBadge(false)      // 上课提醒不该在图标上堆红点
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 弹一条上课提醒。
     *
     * 文案照 WakeUp 的克制做法：标题「08:00 高等数学甲I」，正文只放地点。
     * 节次、周数都是锁屏一瞥时的噪音；提前量放副标题，0 分钟不显示。
     */
    fun show(
        context: Context,
        courseId: Long,
        title: String,
        room: String,
        sessionText: String,
        startAt: Long,
        leadMinutes: Int,
    ) {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (needsPermission && !granted) return

        ensureChannel(context)

        val startText = Instant.ofEpochMilli(startAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(ClockFmt)
        val content = if (room.isBlank()) "这节没填教室" else room

        // 点通知直接进课表，不用先到首页再找入口。
        val contentIntent = PendingIntent.getActivity(
            context,
            (courseId.hashCode() and 0x0FFF_FFFF) + 200_000,
            Intent(context, MainActivity::class.java).apply {
                putExtra("open_timetable", true)
            },
            PI_FLAGS
        )

        val icon = Icon.createWithResource(context, R.drawable.ic_stat_reminder)

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle("$startText $title")
            .setContentText(content)
            .setSubText(if (leadMinutes > 0) CourseReminder.leadLabel(leadMinutes) else null)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setColor(0xFF3E63DD.toInt())
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 不同的课用不同的通知 id，同一门课的不同天也不会互相覆盖（按 startAt 取模区分）。
        val notifyId = 400_000 + (courseId.hashCode() and 0x0FFF) + ((startAt / 86_400_000L) % 100).toInt()
        nm.notify(notifyId, notification)
    }
}
