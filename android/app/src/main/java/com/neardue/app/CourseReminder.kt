package com.neardue.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 上课提醒的时间推算。
 *
 * 课表默认只看不响（早期需求把 items 和 courses 分成两张表就是为了这个）。
 * 但「课前 10 分钟提醒」是学生真正需要的：忘带书、忘换教室、忘了今天这节课
 * 从第 3 周才开——都是损失。所以做成**可选**：默认关，用户打开才响。
 *
 * 这里只做纯计算（不碰 Android、不碰数据库），所以能在 JVM 单测里直接跑：
 * 给定课表 + 作息 + 单周例外，算出未来 N 天每次课「该在什么时刻响」。
 *
 * 参考 WakeUp 课程表的做法：提前量是全局一个值（5 / 10 / 15 / 30 分钟），
 * 不做每门课单独设——学生要的是「别让我迟到」，不是一个提醒设置的迷宫。
 */
object CourseReminder {

    /** 提前量默认 10 分钟：够走到教室，又不至于上一节课还没下课就响。 */
    const val DEFAULT_LEAD = 10

    /**
     * 设置页可选的四档。0 = 上课瞬间提醒（「现在就走去教室」），
     * 其余是常用提前量。再多就是让人纠结了。
     */
    val LEAD_OPTIONS = listOf(0, 5, 10, 20)

    /**
     * 一次待提醒的课。
     *
     * [startAt] 是上课时刻，[triggerAt] 是提醒时刻（= startAt - 提前量）。
     * 两者都存，因为通知里要写「10:00 上课」，而闹钟要按 triggerAt 排。
     */
    data class Slot(
        val courseId: Long,
        val title: String,
        val room: String,
        val sessionStart: Int,
        val sessionEnd: Int,
        val dayOfWeek: Int,
        val week: Int?,
        val startAt: Long,
        val triggerAt: Long,
        /** 这门课实际生效的提前量（v1.31）：课自己设的优先，没设用全局。 */
        val leadMinutes: Int = CourseReminder.DEFAULT_LEAD,
    ) {
        /** 「第 5-6 节」这种文案。 */
        fun sessionText(): String =
            if (sessionEnd <= sessionStart) "第 $sessionStart 节" else "第 $sessionStart-$sessionEnd 节"
    }

    /**
     * 未来 [days] 天内所有该提醒的课，按提醒时刻升序，最多 [max] 条。
     *
     * 为什么只排一段而不是排满整学期：整学期 24 门课 × 16 周 = 三四百个闹钟，
     * 系统要维护、重装/改时间都要全量重排，而手机上一周内一定会因为「打开 App」、
     * 「关机重启」、「上一条提醒响过」这些时机重排。所以排未来的窗口 + 每次响完续排
     * 是更稳的做法（也是 WakeUp 一类产品的常见做法）。
     *
     * @param overrides 单周例外：停课的这周不提醒，改过时间/地点的按改后的算。
     */
    fun upcoming(
        courses: List<Course>,
        sessions: List<Pair<Int, Int>>,
        termStart: LocalDate?,
        from: LocalDateTime,
        days: Int,
        leadMinutes: Int = DEFAULT_LEAD,
        overrides: List<CourseOverride> = emptyList(),
        max: Int = 120,
    ): List<Slot> {
        if (courses.isEmpty() || sessions.isEmpty() || days <= 0) return emptyList()
        val zone = ZoneId.systemDefault()
        val nowMillis = from.atZone(zone).toInstant().toEpochMilli()
        val out = mutableListOf<Slot>()
        val startDate = from.toLocalDate()

        for (offset in 0 until days) {
            val date = startDate.plusDays(offset.toLong())
            val week = weekIndexOf(termStart, date)
            // 这一周里每门课的「实际形态」：停课的不算，改过的换成改后的。
            val forDate = courses.mapNotNull { base ->
                val o = if (week == null) null else overrides.firstOrNull {
                    it.courseId == base.id && it.week == week
                }
                if (o?.skip == true) {
                    null
                } else {
                    o?.applyTo(base) ?: base
                }
            }
            for (c in forDate) {
                if (c.dayOfWeek != date.dayOfWeek.value) continue
                // 没设开学日 = 不知道第几周，把这门课当成每周都有（宁可多提醒，不要漏）。
                if (week != null && !CourseWeeks.includes(c.weeks, week)) continue
                val start = sessions.getOrNull(c.sessionStart - 1) ?: continue
                val startAt = date.atTime(start.first, start.second).atZone(zone).toInstant().toEpochMilli()
                // v1.31：每门课可以用自己的提前量，没设的跟全局。
                val effectiveLead = CourseLead.effective(c.leadMinutes, leadMinutes)
                val triggerAt = startAt - effectiveLead * 60_000L
                if (triggerAt <= nowMillis) continue
                out += Slot(
                    courseId = c.id,
                    title = c.title,
                    room = c.room,
                    sessionStart = c.sessionStart,
                    sessionEnd = c.sessionEnd,
                    dayOfWeek = c.dayOfWeek,
                    week = week,
                    startAt = startAt,
                    triggerAt = triggerAt,
                    leadMinutes = effectiveLead,
                )
            }
        }
        return out.sortedBy { it.triggerAt }.take(max)
    }

    /** 提前量的用户文案：0 分钟说「准时」，其余说「提前 N 分钟」。 */
    fun leadLabel(minutes: Int): String = if (minutes <= 0) "准时" else "提前 $minutes 分钟"

    /** 某一日期是学期第几周（1 起）。没设开学日、或还没开学 → null。 */
    fun weekIndexOf(termStartMonday: LocalDate?, date: LocalDate): Int? {
        if (termStartMonday == null) return null
        val start = Timetable.weekStartOf(termStartMonday)
        val weeks = ChronoUnit.DAYS.between(start, date).toDouble() / 7.0
        val n = kotlin.math.floor(weeks).toInt() + 1
        return if (n < 1) null else n
    }

    /** 「周一 10:00」这种给人看的时刻文案。 */
    fun whenText(slot: Slot): String {
        val date = java.time.Instant.ofEpochMilli(slot.startAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        return CourseWeeks.dayName(slot.dayOfWeek) +
            String.format(java.util.Locale.CHINA, " %d:%02d", date.hour, date.minute)
    }
}
