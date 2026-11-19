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
import android.net.Uri
import android.os.Build
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 平台原生 android.app.Notification 实现，未引入任何额外依赖。 */
object ReminderNotifier {

    const val CHANNEL_ID = "neardue_reminders"
    const val CHANNEL_NAME = "截止提醒"

    private val PI_FLAGS =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    private val DueFmt = DateTimeFormatter.ofPattern("M月d日 E HH:mm", Locale.CHINA)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "任务截止前的提醒"
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    fun show(
        context: Context,
        itemId: Long,
        title: String,
        dueAt: Long,
        categoryOrdinal: Int,
        offsetMinutes: Int
    ) {
        // Android 13+ 需要 POST_NOTIFICATIONS；未授权时静默跳过，绝不抛异常。
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val permissionGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission && !permissionGranted) return

        ensureChannel(context)

        val dueText = Instant.ofEpochMilli(dueAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DueFmt)
        val content = "${remainingText(dueAt, offsetMinutes)} · $dueText 截止"

        // 点通知直接打开那件事的编辑框（v1.35）。
        // 与小组件共用同一条 neardue://item/<id> 深链，MainActivity.readIncoming 已经认它；
        // 以前这里只给了一个光秃秃的 Intent，点进来永远停在列表顶部，用户还得自己找。
        val contentIntent = PendingIntent.getActivity(
            context,
            (itemId * 100 + 80).toInt(),
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("neardue://item/$itemId")
                putExtra(AlarmScheduler.EXTRA_ITEM_ID, itemId)
            },
            PI_FLAGS
        )

        // 「标记完成」与「延后1天」：action 不同，requestCode 也刻意避开
        // AlarmScheduler 使用的 0..9 / 99 槽位，保证互不覆盖。
        val donePi = PendingIntent.getBroadcast(
            context,
            (itemId * 100 + 90).toInt(),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_DONE
                putExtra(AlarmScheduler.EXTRA_ITEM_ID, itemId)
            },
            PI_FLAGS
        )
        val snoozePi = PendingIntent.getBroadcast(
            context,
            (itemId * 100 + 91).toInt(),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ITEM_ID, itemId)
            },
            PI_FLAGS
        )

        // 通知小图标必须是纯白单色矢量；自适应启动图标不能直接当 smallIcon 用。
        val icon = Icon.createWithResource(context, R.drawable.ic_stat_reminder)
        val doneAction = Notification.Action.Builder(icon, "标记完成", donePi).build()
        val snoozeAction = Notification.Action.Builder(icon, "延后1天", snoozePi).build()

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setColor(Category.of(categoryOrdinal).argb.toInt())
            .addAction(doneAction)
            .addAction(snoozeAction)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(itemId.toInt(), notification)
    }

    private fun remainingText(dueAt: Long, offsetMinutes: Int): String {
        if (offsetMinutes == -1440) return "还有 1 天"
        if (offsetMinutes == -120) return "还有 2 小时"
        val diff = dueAt - System.currentTimeMillis()
        return when {
            diff <= 0L -> "已到截止时间"
            diff < 60 * 60_000L -> "还有 ${(diff / 60_000L).coerceAtLeast(1L)} 分钟"
            diff < 24 * 60 * 60_000L -> "还有 ${diff / (60 * 60_000L)} 小时"
            else -> "还有 ${diff / (24 * 60 * 60_000L)} 天"
        }
    }
}
