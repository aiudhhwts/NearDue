package com.neardue.app

import java.time.LocalDate

/**
 * 一周从哪天开始（WakeUp「设置周日为每周第一天」开关，v1.28）。
 *
 * 课程数据里的 dayOfWeek 永远是 ISO 口径（周一=1 … 周日=7），
 * 改开关只动「展示顺序」和「这一周的日期跨度」，不动课程数据、不动周次换算、
 * 也不动提醒 —— 否则老备份/老闹钟全乱。
 */
object WeekDisplay {
    /** 界面七列的 dayOfWeek 顺序；周日开头时周日排第一。 */
    fun orderedDays(startOnSunday: Boolean): List<Int> =
        if (startOnSunday) listOf(7, 1, 2, 3, 4, 5, 6) else listOf(1, 2, 3, 4, 5, 6, 7)

    /** 展示这一周的第一天：周日开头时是周一的前一天，否则就是周一。 */
    fun firstDate(weekMonday: LocalDate, startOnSunday: Boolean): LocalDate =
        if (startOnSunday) weekMonday.minusDays(1) else weekMonday

    /** 第 column 列（0 起）对应的日期。 */
    fun dateAt(weekMonday: LocalDate, startOnSunday: Boolean, column: Int): LocalDate =
        firstDate(weekMonday, startOnSunday).plusDays(column.toLong())

    /** ISO dayOfWeek 在当前展示顺序里的列号（0 起）；不在这七天里时返回 null。 */
    fun columnOf(dayOfWeek: Int, startOnSunday: Boolean): Int? =
        orderedDays(startOnSunday).indexOf(dayOfWeek).takeIf { it >= 0 }
}
