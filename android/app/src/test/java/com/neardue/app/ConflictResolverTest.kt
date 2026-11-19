package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolverTest {
    private fun course(id: Long, title: String) = Course(
        id = id, title = title, dayOfWeek = 1, sessionStart = 1, sessionEnd = 2,
    )

    @Test
    fun skipOthersWritesSkipForEveryOtherCourse() {
        val a = course(1L, "A")
        val b = course(2L, "B")
        val c = course(3L, "C")
        val result = ConflictResolver.skipOthers(listOf(a, b, c), keepId = 1L, week = 11)
        assertEquals(2, result.size)
        assertEquals(listOf(2L, 3L), result.map { it.courseId })
        assertTrue(result.all { it.skip && it.week == 11 })
        assertTrue(result.all { it.dayOfWeek == 0 && it.sessionStart == 0 && it.room == null })
    }

    @Test
    fun invalidWeekOrMissingKeepDoesNothing() {
        val a = course(1L, "A")
        val b = course(2L, "B")
        assertTrue(ConflictResolver.skipOthers(listOf(a, b), 1L, 0).isEmpty())
        assertTrue(ConflictResolver.skipOthers(listOf(a, b), 99L, 11).isEmpty())
    }

    @Test
    fun summaryNamesTheLosersAndSaysThisWeekOnly() {
        val a = course(1L, "高等数学")
        val b = course(2L, "大学英语")
        val text = ConflictResolver.summary(a, listOf(b), 11)
        assertTrue(text.contains("高等数学"))
        assertTrue(text.contains("大学英语"))
        assertTrue(text.contains("第 11 周"))
        assertTrue(text.contains("其它周不变"))
    }
}
