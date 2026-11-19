package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * v1.16「仅本周改课 / 停课」的模型层测试。
 *
 * 这里只测纯逻辑（[CourseOverride.applyTo] 与 [Timetable.grid] 的落格），
 * 不碰 SQLite —— 数据库那层靠真机验证。
 */
class TimetableOverrideTest {

    private val monday = LocalDate.of(2026, 11, 16)

    private fun course(
        id: Long = 1L,
        title: String = "高等数学甲I",
        day: Int = 1,
        from: Int = 5,
        to: Int = 6,
        weeks: String = "3,4,6-18",
        room: String = "B201",
    ) = Course(
        id = id, title = title, dayOfWeek = day,
        sessionStart = from, sessionEnd = to, weeks = weeks, room = room,
    )

    // ── CourseOverride 自身 ────────────────────────────────────────

    @Test
    fun zeroFieldsMeanDoNotTouch() {
        val c = course()
        val o = CourseOverride(courseId = 1L, week = 9)
        val p = o.applyTo(c)
        assertEquals(c.dayOfWeek, p.dayOfWeek)
        assertEquals(c.sessionStart, p.sessionStart)
        assertEquals(c.sessionEnd, p.sessionEnd)
        assertEquals(c.room, p.room)
        assertFalse(o.changesAnything) // 什么都没改
    }

    @Test
    fun onlyChangedFieldsAreApplied() {
        val c = course()
        // 只把教室换成 A104，别的一概不动。
        val p = CourseOverride(courseId = 1L, week = 9, room = "A104").applyTo(c)
        assertEquals("A104", p.room)
        assertEquals(1, p.dayOfWeek)
        assertEquals(5, p.sessionStart)
        assertEquals(6, p.sessionEnd)
        assertEquals(c.title, p.title) // 名字与周次永远不动
        assertEquals(c.weeks, p.weeks)
    }

    @Test
    fun emptyRoomStringClearsRoom() {
        // 空串 = 这周没教室（跟 null「照旧」是两件事）。
        val p = CourseOverride(courseId = 1L, week = 9, room = "").applyTo(course())
        assertEquals("", p.room)
    }

    @Test
    fun skipIsNotAChange() {
        // 停课单独看 skip，不算「改了点什么」——否则界面上会同时画「改」和「停」。
        val o = CourseOverride(courseId = 1L, week = 9, skip = true, room = "A104")
        assertFalse(o.changesAnything)
    }

    @Test
    fun ofRejectsBadInput() {
        assertNull(CourseOverride.of(0L, 9, false, 1, 1, 2, null))       // 没有课 id
        assertNull(CourseOverride.of(1L, 0, false, 1, 1, 2, null))       // 周次 0
        assertNull(CourseOverride.of(1L, 26, false, 1, 1, 2, null))      // 超过 MAX_WEEK
        assertEquals(7, CourseOverride.of(1L, 9, false, 9, 1, 2, null)!!.dayOfWeek)  // 星期收在 0..7
    }

    @Test
    fun ofRaisesEndToStartWhenInverted() {
        // 用户把「结束节次」调到比开始还小 —— 抬到开始节，别画出一个倒着的块。
        val o = CourseOverride.of(1L, 9, false, 0, 5, 3, null)!!
        assertEquals(5, o.sessionStart)
        assertEquals(5, o.sessionEnd)
    }

    // ── Timetable.grid 叠例外 ─────────────────────────────────────

    @Test
    fun overrideMovesTheCourseInThatWeekOnly() {
        val c = course()
        val o = CourseOverride(courseId = 1L, week = 9, dayOfWeek = 4, sessionStart = 3, sessionEnd = 4)
        val overrides = mapOf(1L to o)

        // 第 9 周：应该落在周四 3-4
        val w9 = Timetable.grid(listOf(c), monday, 9, overrides = overrides)
        assertTrue(w9.at(5, 1).isEmpty())          // 周一 5-6 空了
        assertEquals("高等数学甲I", w9.at(3, 4).firstOrNull()?.title)

        // 第 10 周：还是周一 5-6
        val w10 = Timetable.grid(listOf(c), monday, 10, overrides = overrides)
        assertEquals("高等数学甲I", w10.at(5, 1).firstOrNull()?.title)
        assertTrue(w10.at(3, 4).isEmpty())
    }

    @Test
    fun overrideIsIgnoredInOtherWeeks() {
        val c = course()
        val overrides = mapOf(1L to CourseOverride(courseId = 1L, week = 9, room = "A104"))
        // 第 11 周的格子里，教室里应该还是原来的 B201。
        val w11 = Timetable.grid(listOf(c), monday, 11, overrides = overrides)
        assertEquals("B201", w11.at(5, 1).firstOrNull()?.room)
        // 第 9 周才是 A104。
        val w9 = Timetable.grid(listOf(c), monday, 9, overrides = overrides)
        assertEquals("A104", w9.at(5, 1).firstOrNull()?.room)
    }

    @Test
    fun skipWeekPutsCourseInSkippedNotInCells() {
        val c = course()
        val overrides = mapOf(1L to CourseOverride(courseId = 1L, week = 9, skip = true))
        val w9 = Timetable.grid(listOf(c), monday, 9, overrides = overrides)
        assertTrue(w9.isEmpty())                 // 格子空着
        assertTrue(w9.at(5, 1).isEmpty())
        assertEquals(1, w9.skippedCount())       // 但记着「这周停了一门」
        assertEquals(1, w9.skippedForDay(1).size)
        // ★停课的课不进灰显层：灰显的意思是「每周都有、只是不在本周」，语义不同。
        assertTrue(w9.ghostsAt(5, 1).isEmpty())
        assertEquals(0, w9.otherCount())
    }

    @Test
    fun skipDoesNotLeakIntoOtherWeeks() {
        val c = course()
        val overrides = mapOf(1L to CourseOverride(courseId = 1L, week = 9, skip = true))
        val w10 = Timetable.grid(listOf(c), monday, 10, overrides = overrides)
        assertFalse(w10.isEmpty())
        assertEquals(0, w10.skippedCount())
        assertEquals("高等数学甲I", w10.at(5, 1).firstOrNull()?.title)
    }

    @Test
    fun changedCountFlagsOnlyTheEditedCourse() {
        val a = course(id = 1L, title = "高等数学甲I")
        val b = course(id = 2L, title = "大学英语", day = 2, from = 3, to = 4)
        val overrides = mapOf(1L to CourseOverride(courseId = 1L, week = 9, room = "A104"))
        val g = Timetable.grid(listOf(a, b), monday, 9, overrides = overrides)
        assertTrue(g.isChanged(1L))
        assertFalse(g.isChanged(2L))
        assertEquals(1, g.changedCount())
    }

    @Test
    fun noOverridesMeansOldBehaviour() {
        // 不传 overrides 时，一切跟 v1.15 一样。
        val g = Timetable.grid(listOf(course()), monday, 9)
        assertEquals(1, g.courseCount())
        assertEquals(0, g.changedCount())
        assertEquals(0, g.skippedCount())
    }

    @Test
    fun overrideForCourseNotInWeekIsIgnored() {
        // 这门课第 5 周不上；就算有第 5 周的例外，也不该凭空冒出来。
        val c = course(weeks = "6-18")
        val overrides = mapOf(1L to CourseOverride(courseId = 1L, week = 5, room = "A104"))
        val g = Timetable.grid(listOf(c), monday, 5, overrides = overrides)
        assertTrue(g.isEmpty())
        assertEquals(0, g.skippedCount())
    }
}
