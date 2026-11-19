package com.neardue.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * v1.36 课表列头事项条：把「未完成事项」按日期分到当前显示的七天里，
 * 每天最多摆两条，多的折成「还有 N 件」。
 *
 * 只显示，不响铃、不改数据：课表依旧独立于事项清单（用户说过课表不用变成事项提醒）。
 * 纯函数：时区由调用方传入，单测可以钉死任意日期，不 mock 系统时钟。
 */
object TimetableAgenda {
    data class DayItems(val shown: List<Item>, val hidden: Int)

    fun group(
        items: List<Item>,
        days: List<LocalDate>,
        zone: ZoneId = ZoneId.systemDefault(),
        limit: Int = 2,
    ): Map<LocalDate, DayItems> {
        val inScope = days.toSet()
        val byDay = LinkedHashMap<LocalDate, MutableList<Item>>()
        for (item in items) {
            if (item.done) continue
            val day = Instant.ofEpochMilli(item.dueAt).atZone(zone).toLocalDate()
            if (day !in inScope) continue
            byDay.getOrPut(day) { mutableListOf() }.add(item)
        }
        val out = LinkedHashMap<LocalDate, DayItems>()
        for (day in days) {
            val sorted = byDay[day]?.sortedBy { it.dueAt }.orEmpty()
            out[day] = DayItems(
                shown = sorted.take(limit),
                hidden = (sorted.size - limit).coerceAtLeast(0),
            )
        }
        return out
    }
}
