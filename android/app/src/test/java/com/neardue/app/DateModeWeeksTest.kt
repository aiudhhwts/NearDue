package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** v1.24 日期模式：选起止日期换算周次，纯函数回归。 */
class DateModeWeeksTest {
    private val start = LocalDate.of(2026, 9, 7) // 周一，第 1 周起点

    @Test
    fun firstWeekIsOne() {
        assertEquals(1, DateModeWeeks.weekOf(start, start))
        assertEquals(1, DateModeWeeks.weekOf(start, start.plusDays(6)))
        assertEquals(2, DateModeWeeks.weekOf(start, start.plusDays(7)))
    }

    @Test
    fun beforeStartIsNull() {
        assertNull(DateModeWeeks.weekOf(start, start.minusDays(1)))
        assertNull(DateModeWeeks.fromDates(start, start.minusDays(3), start.plusDays(10)))
    }

    @Test
    fun sameWeekIsSingleNumber() {
        // 9/7 周一 与 9/10 周四 都在第 1 周。
        assertEquals("1", DateModeWeeks.fromDates(start, start, start.plusDays(3)))
    }

    @Test
    fun crossWeekIsRange() {
        // 9/7 第 1 周 → 9/22 第 3 周。
        assertEquals("1-3", DateModeWeeks.fromDates(start, start, start.plusDays(15)))
    }

    @Test
    fun reversedDatesStillGiveRange() {
        val a = start.plusDays(7)  // 第 2 周
        val b = start.plusDays(21) // 第 4 周
        assertEquals("2-4", DateModeWeeks.fromDates(start, b, a))
    }

    @Test
    fun mondayOfAlignsAnyDayToWeekStart() {
        assertEquals(start, DateModeWeeks.mondayOf(LocalDate.of(2026, 9, 10))) // 周四 → 周一
        assertEquals(start.plusDays(7), DateModeWeeks.mondayOf(LocalDate.of(2026, 9, 14)))
    }
}
