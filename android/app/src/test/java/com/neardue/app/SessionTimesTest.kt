package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** v1.27：时间段作息 / 删除范围 / 学期状态提示，纯逻辑回归。 */
class SessionTimesTest {

    // ── SessionTimes ────────────────────────────────────────────────

    @Test
    fun fromStartsUsesNextStartAsEndAndDefaultForLast() {
        val slots = SessionTimes.fromStarts(listOf(8 to 0, 8 to 55, 10 to 0))
        assertEquals(3, slots.size)
        assertEquals("08:00-08:55", slots[0].rangeText())
        assertEquals("08:55-10:00", slots[1].rangeText())
        assertEquals("10:00-10:45", slots[2].rangeText())
    }

    @Test
    fun parseOldAndNewFormats() {
        val old = SessionTimes.parse("8:0,8:55,10:0")!!
        assertEquals("08:00-08:55", old[0].rangeText())
        val new = SessionTimes.parse("8:0-8:45,10:0-10:50")!!
        assertEquals("08:00-08:45", new[0].rangeText())
        assertEquals("10:00-10:50", new[1].rangeText())
        assertNull(SessionTimes.parse("garbage"))
    }

    @Test
    fun uniformDurationAndSingleEndEdit() {
        val base = SessionTimes.fromStarts(listOf(8 to 0, 10 to 0))
        val u = SessionTimes.uniform(base, 50)
        assertEquals("08:00-08:50", u[0].rangeText())
        assertEquals("10:00-10:50", u[1].rangeText())
        val edited = SessionTimes.withEnd(u, 0, 8 * 60 + 40)
        assertEquals("08:00-08:40", edited[0].rangeText())
        assertEquals("10:00-10:50", edited[1].rangeText())
        assertEquals(u, SessionTimes.withEnd(u, 0, 7 * 60)) // 结束早于开始 → 不变
    }

    @Test
    fun formatRoundTripAndSlotAt() {
        val slots = SessionTimes.fromStarts(listOf(8 to 0, 8 to 55))
        val text = SessionTimes.format(slots)
        assertEquals(slots, SessionTimes.parse(text))
        assertEquals(1, SessionTimes.slotAt(8 * 60 + 10, slots))
        assertEquals(2, SessionTimes.slotAt(8 * 60 + 56, slots))
        assertNull(SessionTimes.slotAt(7 * 60, slots))
    }

    // ── CourseDeletion ──────────────────────────────────────────────

    @Test
    fun deleteWholeTermRemovesAll() {
        assertEquals("", CourseDeletion.remainingSpec("1-16", CourseDeletion.Scope.ALL, 5))
        assertTrue(CourseDeletion.summary(CourseDeletion.Scope.ALL, 5, "").contains("整学期"))
    }

    @Test
    fun deleteOnlyThisWeekKeepsOthers() {
        assertEquals("1-4,6-8", CourseDeletion.remainingSpec("1-8", CourseDeletion.Scope.THIS_WEEK, 5))
        assertEquals("1-16", CourseDeletion.remainingSpec("1-16", CourseDeletion.Scope.THIS_WEEK, 20))
    }

    @Test
    fun deleteFromThisWeekKeepsEarlier() {
        assertEquals("1-4", CourseDeletion.remainingSpec("1-8", CourseDeletion.Scope.FROM_THIS_WEEK, 5))
        assertEquals("1", CourseDeletion.remainingSpec("1-4", CourseDeletion.Scope.FROM_THIS_WEEK, 2))
    }

    @Test
    fun deleteEveryWeekSpecHandlesEmpty() {
        // 空规格 = 每周，删单周只能展开成具体周次（例外该走 override，这里只算规格）
        assertEquals("1-4,6-25", CourseDeletion.remainingSpec("", CourseDeletion.Scope.THIS_WEEK, 5))
        assertEquals("1-4", CourseDeletion.remainingSpec("", CourseDeletion.Scope.FROM_THIS_WEEK, 5))
    }

    // ── TermStatus ──────────────────────────────────────────────────

    @Test
    fun termStatusCases() {
        val start = LocalDate.of(2026, 9, 7)
        val today = LocalDate.of(2026, 11, 17)
        assertEquals(TermStatus.Kind.NONE, TermStatus.of(null, 16, 10, today).kind)
        assertTrue(TermStatus.of(start, 16, 0, today).message.contains("开学前"))
        assertTrue(TermStatus.of(start, 16, 20, today).kind == TermStatus.Kind.TOO_LATE)
        assertEquals(TermStatus.Kind.OK, TermStatus.of(start, 16, 11, today).kind)
        assertTrue(TermStatus.of(start, null, 20, today).message.contains("第 16 周"))
    }
}
