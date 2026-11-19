package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionOverflowTest {
    private fun course(title: String, start: Int, end: Int) = Course(
        id = 1L, title = title, dayOfWeek = 1,
        sessionStart = start, sessionEnd = end,
    )

    @Test
    fun noOverflowWhenAllWithinRange() {
        val list = listOf(course("高数", 1, 2), course("英语", 3, 4))
        assertEquals(emptyList<SessionOverflow.Offender>(), SessionOverflow.of(list, 8))
        assertNull(SessionOverflow.message(SessionOverflow.of(list, 8), 8))
    }

    @Test
    fun detectsStartBeyondSessionCount() {
        // 作息改成 8 节后，原本第 10 节的课越界
        val list = listOf(course("体育", 9, 10))
        val offenders = SessionOverflow.of(list, 8)
        assertEquals(1, offenders.size)
        assertEquals("体育", offenders[0].title)
        assertEquals(9, offenders[0].start)
        assertEquals(10, offenders[0].end)
    }

    @Test
    fun detectsEndBeyondSessionCount() {
        val list = listOf(course("实验", 7, 12))
        assertEquals(1, SessionOverflow.of(list, 8).size)
    }

    @Test
    fun distinctByTitle() {
        // 同一门课拆成多行不同周次，只提示一次
        val list = listOf(course("体育", 9, 10), course("体育", 11, 12))
        assertEquals(1, SessionOverflow.of(list, 8).size)
    }

    @Test
    fun invertedRangeIsOffender() {
        val list = listOf(course("排错课", 5, 3))
        assertEquals(1, SessionOverflow.of(list, 8).size)
    }

    @Test
    fun messageListsNamesAndCount() {
        val list = listOf(course("A", 9, 9), course("B", 10, 10), course("C", 11, 11), course("D", 12, 12))
        val msg = SessionOverflow.message(SessionOverflow.of(list, 8), 8)!!
        assert(msg.contains("「A」") && msg.contains("「B」") && msg.contains("「C」"))
        assert(msg.contains("等 4 门"))
        assert(msg.contains("8 节作息"))
    }

    @Test
    fun zeroSessionCountMarksAll() {
        val list = listOf(course("高数", 1, 2))
        assertEquals(1, SessionOverflow.of(list, 0).size)
    }
}
