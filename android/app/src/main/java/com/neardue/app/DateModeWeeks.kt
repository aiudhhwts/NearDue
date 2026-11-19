package com.neardue.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * v1.24「日期模式录入」：选起止日期 → 按开学日自动换算成第 N–M 周，写回周次框。
 *
 * 存储仍是周次文本（空 = 每周），**不引新字段、不升备份格式**——日期只是录入时的便捷写法。
 * 换算规则沿用 [CourseReminder.weekIndexOf]：以开学日所在周的 Monday 为第 1 周起点，
 * 每周 7 天，不足一周算第 1 周；起止日期落在开学日之前 → 不合法（返回 null）。
 * 纯 Kotlin，可在 JVM 单测里直接跑。
 */
object DateModeWeeks {
    /** 某一天是学期第几周（1 起）。[weekStartMonday] 是第 1 周的周一。 */
    fun weekOf(weekStartMonday: LocalDate, date: LocalDate): Int? {
        if (date.isBefore(weekStartMonday)) return null
        return (ChronoUnit.DAYS.between(weekStartMonday, date) / 7L).toInt() + 1
    }

    /**
     * 起止日期 → 周次文本（`N` / `N-M`）。
     * 同周只写 `N`；任一天在开学日之前 → null（界面提示重选）。
     * 用户选的可能是周中任意一天，都对齐到「那天属于第几周」。
     */
    fun fromDates(
        weekStartMonday: LocalDate,
        start: LocalDate,
        end: LocalDate,
    ): String? {
        val a = weekOf(weekStartMonday, start) ?: return null
        val b = weekOf(weekStartMonday, end) ?: return null
        val from = minOf(a, b)
        val to = maxOf(a, b)
        return if (from == to) "$from" else "$from-$to"
    }

    /** 把用户选的日期对齐到那一周的周一（与现有开学日设置一致）。 */
    fun mondayOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
}
