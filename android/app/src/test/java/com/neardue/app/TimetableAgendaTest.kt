package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TimetableAgendaTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 11, 18)
    private val other = LocalDate.of(2026, 11, 19)
    private fun at(epochDay: LocalDate, hour: Int = 12): Long =
        epochDay.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    @Test
    fun skipsDoneAndOutOfScope() {
        val items = listOf(
            Item(id = 1, title = "keep", dueAt = at(day)),
            Item(id = 2, title = "done", dueAt = at(day), done = true),
            Item(id = 3, title = "elsewhere", dueAt = at(other.plusDays(5))),
        )
        val out = TimetableAgenda.group(items, listOf(day, other), zone)
        assertEquals(listOf(1L), out[day]!!.shown.map { it.id })
        assertEquals(0, out[day]!!.hidden)
        assertTrue(out[other]!!.shown.isEmpty())
    }

    @Test
    fun groupsByLocalDateAcrossDays() {
        val items = listOf(
            Item(id = 1, title = "a", dueAt = at(day, 9)),
            Item(id = 2, title = "b", dueAt = at(other, 20)),
        )
        val out = TimetableAgenda.group(items, listOf(day, other), zone)
        assertEquals(listOf(1L), out[day]!!.shown.map { it.id })
        assertEquals(listOf(2L), out[other]!!.shown.map { it.id })
    }

    @Test
    fun showsAtMostTwoAndCountsHidden() {
        val items = (1..4).map { Item(id = it.toLong(), title = "t$it", dueAt = at(day, it + 8)) }
        val out = TimetableAgenda.group(items, listOf(day), zone)
        assertEquals(listOf(1L, 2L), out[day]!!.shown.map { it.id })
        assertEquals(2, out[day]!!.hidden)
    }

    @Test
    fun sortedByDueTimeNotInputOrder() {
        val items = listOf(
            Item(id = 1, title = "late", dueAt = at(day, 20)),
            Item(id = 2, title = "early", dueAt = at(day, 8)),
        )
        val out = TimetableAgenda.group(items, listOf(day), zone)
        assertEquals(listOf(2L, 1L), out[day]!!.shown.map { it.id })
    }

    @Test
    fun respectsCustomLimit() {
        val items = (1..3).map { Item(id = it.toLong(), title = "t", dueAt = at(day, it + 8)) }
        val out = TimetableAgenda.group(items, listOf(day), zone, limit = 1)
        assertEquals(listOf(1L), out[day]!!.shown.map { it.id })
        assertEquals(2, out[day]!!.hidden)
    }

    @Test
    fun lateNightUsesLocalDateBoundary() {
        // 23:59 本地时间仍算当天：验证时区是调用方传入的 zone，而不是 UTC。
        val items = listOf(Item(id = 1, title = "night", dueAt = at(day, 23) + 59 * 60_000L))
        val out = TimetableAgenda.group(items, listOf(day), zone)
        assertEquals(listOf(1L), out[day]!!.shown.map { it.id })
    }
}
