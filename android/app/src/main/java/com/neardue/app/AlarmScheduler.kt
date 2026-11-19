package com.neardue.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 精确提醒闹钟的排程与取消。
 *
 * 全部使用显式组件 Intent(context, ReminderReceiver::class.java)，因此清单里
 * ReminderReceiver 可以 exported=false。所有 PendingIntent 都带 FLAG_IMMUTABLE。
 */
object AlarmScheduler {

    const val EXTRA_ITEM_ID = "com.neardue.app.EXTRA_ITEM_ID"
    const val EXTRA_TITLE = "com.neardue.app.EXTRA_TITLE"
    const val EXTRA_DUE_AT = "com.neardue.app.EXTRA_DUE_AT"
    const val EXTRA_CATEGORY = "com.neardue.app.EXTRA_CATEGORY"
    const val EXTRA_OFFSET = "com.neardue.app.EXTRA_OFFSET"
    const val EXTRA_QUIET_DEFERRED = "com.neardue.app.EXTRA_QUIET_DEFERRED"

    // 测试通知用的哨兵 id：正数、不对应任何真实数据，notify/cancel 都安全。
    const val TEST_ITEM_ID = 9_999_999L

    private val PI_FLAGS =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    // 提醒档位是小列表（<10 个），因此每个 item 预留 10 个 requestCode 槽位；
    // 99 号槽位专门给「免打扰延后」的单次闹钟使用，二者不会冲突。
    private const val OFFSET_SLOTS = 10
    private const val DEFER_SLOT = 99

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** SDK 31+ 需要「闹钟和提醒」特殊权限；以下版本恒为 true。 */
    fun canSchedule(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager(context).canScheduleExactAlarms()
        } else {
            true
        }
    }

    /** SDK 31+ 且未获授权时，跳到系统设置页申请；异常一律吞掉。 */
    fun requestExactAlarmPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (alarmManager(context).canScheduleExactAlarms()) return
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + context.packageName)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (ignored: Exception) {
            // 部分 ROM 没有对应的设置页，忽略即可。
        }
    }

    private fun baseIntent(context: Context, item: Item, offsetMinutes: Int): Intent =
        Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_ITEM_ID, item.id)
            putExtra(EXTRA_TITLE, item.title)
            putExtra(EXTRA_DUE_AT, item.dueAt)
            putExtra(EXTRA_CATEGORY, item.category.ordinal)
            putExtra(EXTRA_OFFSET, offsetMinutes)
        }

    /** cancel 与 set 共用同一公式，保证能对上。 */
    private fun pendingIntent(
        context: Context,
        item: Item,
        offsetIndex: Int,
        offsetMinutes: Int
    ): PendingIntent {
        val requestCode = (item.id * 100 + offsetIndex).toInt()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            baseIntent(context, item, offsetMinutes),
            PI_FLAGS
        )
    }

    /** 免打扰延后后的单次闹钟，带 EXTRA_QUIET_DEFERRED，接收器不再二次延后。 */
    private fun deferredPendingIntent(context: Context, item: Item): PendingIntent {
        val intent = baseIntent(context, item, 0).apply {
            putExtra(EXTRA_QUIET_DEFERRED, true)
        }
        val requestCode = (item.id * 100 + DEFER_SLOT).toInt()
        return PendingIntent.getBroadcast(context, requestCode, intent, PI_FLAGS)
    }

    /** setAlarmClock 是用户可见闹钟，不依赖精确闹钟特殊权限，OEM ROM 上最可靠。 */
    private fun arm(am: AlarmManager, triggerAt: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)
                return
            } catch (ignored: SecurityException) {
                // 极少数 ROM 会拒绝，退回到精确闹钟。
            }
        }
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (ignored: SecurityException) {
            // 没有精确闹钟权限时静默放弃，避免让广播接收器崩溃。
        }
    }

    /** 取消当前所有 item 的全部提醒槽位 + 延后闹钟。 */
    fun cancelAll(context: Context) {
        val am = alarmManager(context)
        for (item in ItemStore(context).list()) {
            for (index in 0 until OFFSET_SLOTS) {
                am.cancel(pendingIntent(context, item, index, 0))
            }
            am.cancel(deferredPendingIntent(context, item))
        }
    }

    /** 重新排程所有未完成事项；先全量取消，再按每个 offset 逐个下发。 */
    fun scheduleAll(context: Context) {
        cancelAll(context)
        val am = alarmManager(context)
        val offsets = Prefs.remindOffsets(context)
        val now = System.currentTimeMillis()
        val items = ItemStore(context).list().filter { !it.done }
        for (item in items) {
            for ((index, offset) in offsets.withIndex()) {
                if (index >= OFFSET_SLOTS) break
                val triggerAt = item.dueAt + offset * 60_000L
                if (triggerAt <= now) continue          // 已过期的档位不再补发
                arm(am, triggerAt, pendingIntent(context, item, index, offset))
            }
        }
    }

    /** 免打扰窗口结束后补发一次；receiver 见 EXTRA_QUIET_DEFERRED 不再延后。 */
    fun scheduleOne(context: Context, item: Item, triggerAt: Long) {
        arm(alarmManager(context), triggerAt, deferredPendingIntent(context, item))
    }

    /** 设置页「发一条测试通知」：立刻发一条显式广播，走完整通知链路。 */
    fun fireTest(context: Context) {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_TEST
            putExtra(EXTRA_ITEM_ID, TEST_ITEM_ID)     // 哨兵值：不对应任何真实数据
            putExtra(EXTRA_TITLE, "测试提醒")
            putExtra(EXTRA_DUE_AT, System.currentTimeMillis() + 60 * 60_000L)
            putExtra(EXTRA_CATEGORY, Category.OTHER.ordinal)
            putExtra(EXTRA_OFFSET, 0)
            // 测试通知要保证立刻可见，即使当前正处于免打扰窗口也不延后。
            putExtra(EXTRA_QUIET_DEFERRED, true)
        }
        context.sendBroadcast(intent)
    }
}
