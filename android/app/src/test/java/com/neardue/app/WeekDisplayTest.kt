package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WeekDisplayTest {
    private val monday = LocalDate.of(2026, 11, 16)

    @Test
    fun mondayStartKeepsIsoOrder() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), WeekDisplay.orderedDays(false))
        assertEquals(monday, WeekDisplay.firstDate(monday, false))
        assertEquals(monday.plusDays(6), WeekDisplay.dateAt(monday, false, 6))
    }

    @Test
    fun sundayStartPutsSundayFirst() {
        assertEquals(listOf(7, 1, 2, 3, 4, 5, 6), WeekDisplay.orderedDays(true))
        assertEquals(monday.minusDays(1), WeekDisplay.firstDate(monday, true))
        assertEquals(monday.minusDays(1), WeekDisplay.dateAt(monday, true, 0))
        assertEquals(monday.plusDays(5), WeekDisplay.dateAt(monday, true, 6))
    }

    @Test
    fun columnMappingMatchesOrdering() {
        for (startOnSunday in listOf(false, true)) {
            WeekDisplay.orderedDays(startOnSunday).forEachIndexed { column, day ->
                assertEquals(column, WeekDisplay.columnOf(day, startOnSunday))
            }
        }
    }
}
