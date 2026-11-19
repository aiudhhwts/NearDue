package com.neardue.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 上课提醒的闹钟排程。
 *
 * 与 [AlarmScheduler]（事项截止提醒）是两套：那边按 item.id 分配 requestCode，
 * 这边按「哪一门课、哪一天、第几节」算一个稳定的 requestCode，两边命名空间
 * 通过 [PENDING_KEY] 前缀 + 独立的 prefs 文件隔开，不会互相覆盖。
 *
 * 只排未来 [HORIZON_DAYS] 天：一次课一个闹钟，24 门课两周大概 40 个，系统扛得住；
 * 排满整学期会有三四百个，改时间/重装/换开学日都要全量重排，反而更容易出错。
 * 每响一次就在接收器里再续排一次，窗口永远是满的。
 */
object CourseAlarmScheduler {

    const val EXTRA_COURSE_ID = "com.neardue.app.EXTRA_COURSE_ID"
    const val EXTRA_COURSE_TITLE = "com.neardue.app.EXTRA_COURSE_TITLE"
    const val EXTRA_COURSE_ROOM = "com.neardue.app.EXTRA_COURSE_ROOM"
    const val EXTRA_SESSION_TEXT = "com.neardue.app.EXTRA_SESSION_TEXT"
    const val EXTRA_START_AT = "com.neardue.app.EXTRA_START_AT"
    /** v1.31: per-course lead time passed to receiver so notification subtext matches. */
    const val EXTRA_COURSE_LEAD = "com.neardue.app.EXTRA_COURSE_LEAD"
    const val EXTRA_QUIET_DEFERRED = "com.neardue.app.EXTRA_QUIET_DEFERRED"

    /** 往前排几天。两周：足够覆盖「手机放抽屉里三天没打开」的情况。 */
    const val HORIZON_DAYS = 14

    /** 上限保护：课再多也不会一次排爆。 */
    private const val MAX_ALARMS = 90

    private const val PREFS = "neardue_course_alarms"
    private const val KEY_CODES = "codes"

    private val PI_FLAGS =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun am(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * requestCode：由「课程 + 第几节 + 哪一天」算一个稳定值。
     * 同一天同一个格子的闹钟重排时命中同一个 requestCode（FLAG_UPDATE_CURRENT 覆盖），
     * 不同日期 / 不同课则不会撞。用 String.hashCode，Java 规范保证跨进程稳定。
     */
    private fun requestCodeOf(slot: CourseReminder.Slot): Int {
        val key = "${slot.courseId}|${slot.sessionStart}|${slot.startAt / 86_400_000L}"
        return key.hashCode() and 0x7FFF_FFFF
    }

    private fun intentFor(context: Context, slot: CourseReminder.Slot): Intent =
        Intent(context, CourseReminderReceiver::class.java).apply {
            putExtra(EXTRA_COURSE_ID, slot.courseId)
            putExtra(EXTRA_COURSE_TITLE, slot.title)
            putExtra(EXTRA_COURSE_ROOM, slot.room)
            putExtra(EXTRA_SESSION_TEXT, slot.sessionText())
            putExtra(EXTRA_START_AT, slot.startAt)
            putExtra(EXTRA_COURSE_LEAD, slot.leadMinutes)
        }

    private fun piFor(context: Context, slot: CourseReminder.Slot): PendingIntent =
        PendingIntent.getBroadcast(context, requestCodeOf(slot), intentFor(context, slot), PI_FLAGS)

    /** setAlarmClock 在国产 ROM 上最可靠；拒绝时退回 setExactAndAllowWhileIdle。 */
    private fun arm(manager: AlarmManager, triggerAt: Long, pi: PendingIntent) {
        try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pi), pi)
            return
        } catch (ignored: SecurityException) {
            // 少数 ROM 拒绝闹钟类闹钟，退回精确闹钟。
        }
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (ignored: SecurityException) {
            // 没有精确闹钟权限时静默放弃，绝不让调用方崩溃。
        }
    }

    private fun rememberCodes(context: Context, codes: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_CODES, codes).apply()
    }

    private fun rememberedCodes(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_CODES, emptySet()) ?: emptySet()

    /** 撤销上一次排下的所有上课闹钟。靠记住的 requestCode 精确取消，不靠猜。 */
    fun cancelAll(context: Context) {
        val manager = am(context)
        val intent = Intent(context, CourseReminderReceiver::class.java)
        for (code in rememberedCodes(context)) {
            val rc = code.toIntOrNull() ?: continue
            val pi = PendingIntent.getBroadcast(context, rc, intent, PI_FLAGS)
            manager.cancel(pi)
            pi.cancel()
        }
        rememberCodes(context, emptySet())
    }

    /** 距下一次响还有多久（毫秒）；没排或已关则 null。给设置页显示用。 */
    fun nextTriggerAt(context: Context): Long? {
        val slots = upcoming(context)
        return slots.firstOrNull()?.triggerAt
    }

    private fun upcoming(context: Context): List<CourseReminder.Slot> {
        if (!Prefs.courseRemindEnabled(context)) return emptyList()
        // 只排**当前学期**的课：别的学期没在上的课不该把你叫醒。
        val term = currentTerm(context)
        val courses = CourseStore(context).list(term.id)
        if (courses.isEmpty()) return emptyList()
        return CourseReminder.upcoming(
            courses = courses,
            sessions = Prefs.sessionTimes(context, term.id),
            termStart = term.startMonday ?: Prefs.termStartMonday(context),
            from = java.time.LocalDateTime.now(),
            days = HORIZON_DAYS,
            leadMinutes = Prefs.courseLeadMinutes(context),
            overrides = CourseStore(context).listOverrides(),
            max = MAX_ALARMS,
        )
    }

    /** 当前学期。没选过 / id 失效就回落到第一个学期，绝不返回 null 让排程空转。 */
    fun currentTerm(context: Context): Term {
        val store = TermStore(context)
        val id = Prefs.currentTermId(context)
        if (id > 0L) store.get(id)?.let { if (!it.archived) return it }
        val term = store.ensureAtLeastOne(context)
        // v1.31：归档的学期不响铃；回落到第一个活动学期。
        val active = TermArchive.active(store.list()).firstOrNull() ?: term
        if (Prefs.currentTermId(context) != active.id) Prefs.setCurrentTermId(context, active.id)
        return active
    }

    /**
     * 全量重排：先撤旧的，再按未来窗口下发。
     * 开关关掉时只撤不排——这就是「默认只看不响」的落实点。
     */
    fun rescheduleAll(context: Context): Int {
        cancelAll(context)
        if (!Prefs.courseRemindEnabled(context)) return 0
        if (!AlarmScheduler.canSchedule(context)) {
            // 没有精确闹钟权限：仍然排（setAlarmClock 多半可用），但可能不准。
        }
        val slots = upcoming(context)
        val manager = am(context)
        val codes = mutableSetOf<String>()
        for (slot in slots) {
            val rc = requestCodeOf(slot)
            arm(manager, slot.triggerAt, piFor(context, slot))
            codes += rc.toString()
        }
        rememberCodes(context, codes)
        return slots.size
    }

    /** 免打扰窗口结束时补发的那一次。 */
    fun scheduleOne(context: Context, slot: CourseReminder.Slot, triggerAt: Long) {
        val pi = PendingIntent.getBroadcast(
            context,
            requestCodeOf(slot),
            intentFor(context, slot).apply { putExtra(EXTRA_QUIET_DEFERRED, true) },
            PI_FLAGS
        )
        arm(am(context), triggerAt, pi)
    }

    /** 设置页「发一条上课提醒」：立刻走完整通知链路，不看免打扰。 */
    fun fireTest(context: Context) {
        val now = System.currentTimeMillis()
        val slot = CourseReminder.Slot(
            courseId = 0L,
            title = "高等数学甲I",
            room = "B201",
            sessionStart = 5,
            sessionEnd = 6,
            dayOfWeek = java.time.LocalDate.now().dayOfWeek.value,
            week = null,
            startAt = now + 10 * 60_000L,
            triggerAt = now,
        )
        val intent = Intent(context, CourseReminderReceiver::class.java).apply {
            putExtra(EXTRA_COURSE_ID, slot.courseId)
            putExtra(EXTRA_COURSE_TITLE, slot.title)
            putExtra(EXTRA_COURSE_ROOM, slot.room)
            putExtra(EXTRA_SESSION_TEXT, slot.sessionText())
            putExtra(EXTRA_START_AT, slot.startAt)
            putExtra(EXTRA_COURSE_LEAD, slot.leadMinutes)
            putExtra(EXTRA_QUIET_DEFERRED, true)
        }
        context.sendBroadcast(intent)
    }
}
