package com.neardue.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表投影的测试（v1.9：课表已独立成 [Course] 表，不再从 [Item] 投影）。
 *
 * 全部用固定日期，不依赖「今天」—— 否则过一阵子就红了。
 */
class TimetableTest {

    // 2026-11-16 是周一。
    private val monday = LocalDate.of(2026, 11, 16)

    private fun course(
        title: String,
        day: Int,
        from: Int,
        to: Int = from,
        weeks: String = "",
        room: String = "",
    ) = Course(
        title = title,
        dayOfWeek = day,
        sessionStart = from,
        sessionEnd = to,
        weeks = weeks,
        room = room,
    )

    @Test
    fun weekStartOfReturnsMonday() {
        assertEquals(monday, Timetable.weekStartOf(monday))
        assertEquals(monday, Timetable.weekStartOf(monday.plusDays(3)))   // 周四
        assertEquals(monday, Timetable.weekStartOf(monday.plusDays(6)))   // 本周周日
        // 上一周的周日属于上一周，落到上一个周一。
        assertEquals(monday.minusDays(7), Timetable.weekStartOf(monday.minusDays(1)))
    }

    @Test
    fun courseLandsInItsSessionCell() {
        val g = Timetable.grid(
            courses = listOf(course("高等数学", 3, 1, 2)),
            weekStart = monday,
            weekIndex = null,
        )
        assertEquals("高等数学", g.at(1, 3).first().title)
        // 跨节课在起始那节和后续那节都占位，渲染时只画一次标题。
        assertEquals(1, g.at(2, 3).size)
    }

    @Test
    fun emptyCellIsEmpty() {
        val g = Timetable.grid(
            courses = listOf(course("高等数学", 3, 1, 2)),
            weekStart = monday,
            weekIndex = null,
        )
        assertTrue(g.at(1, 1).isEmpty())
        assertTrue(g.at(5, 7).isEmpty())
    }

    @Test
    fun weekFilterUsesCourseWeeks() {
        // 单周的课：第 1 周出现，第 2 周不出现。
        val odd = listOf(course("体育", 3, 1, 1, weeks = "1-16单"))
        val w1 = Timetable.grid(odd, monday, weekIndex = 1)
        val w2 = Timetable.grid(odd, monday, weekIndex = 2)
        assertEquals(1, w1.at(1, 3).size)
        assertEquals(0, w2.at(1, 3).size)
    }

    @Test
    fun sparseWeeksAreRespected() {
        val c = listOf(course("讲座", 2, 5, 5, weeks = "1,3,5"))
        fun hit(week: Int) = Timetable.grid(c, monday, weekIndex = week).at(5, 2).isNotEmpty()
        assertTrue(hit(1))
        assertTrue(!hit(2))
        assertTrue(hit(3))
        assertTrue(!hit(4))
        assertTrue(hit(5))
        assertTrue(!hit(6))
    }

    @Test
    fun nullWeekIndexShowsEverything() {
        // 没设开学日时不过滤，至少让用户看到课表长什么样。
        val g = Timetable.grid(
            courses = listOf(course("宪法学", 3, 1, 2, weeks = "1-16")),
            weekStart = monday,
            weekIndex = null,
        )
        assertEquals(1, g.courseCount())
        assertTrue(!g.isEmpty())
    }

    @Test
    fun customSessionsChangeAxisLabels() {
        val g = Timetable.grid(
            courses = listOf(course("九点的课", 1, 1, 1)),
            weekStart = monday,
            weekIndex = null,
            sessions = listOf(9 to 0, 10 to 0),
        )
        assertEquals("09:00", Timetable.sessionLabel(1, g.sessions))
        assertEquals("10:00", Timetable.sessionLabel(2, g.sessions))
        assertEquals(1, g.at(1, 1).size)
    }

    @Test
    fun dateOfMapsDayOfWeekToDate() {
        val g = Timetable.grid(emptyList(), monday, weekIndex = 1)
        assertEquals(monday, Timetable.dateOf(g, 1))
        assertEquals(monday.plusDays(2), Timetable.dateOf(g, 3))
        assertEquals(monday.plusDays(6), Timetable.dateOf(g, 7))
    }

    @Test
    fun weekNumberCountsFromTermStart() {
        assertEquals(1, Timetable.weekNumber(monday, monday))
        assertEquals(2, Timetable.weekNumber(monday, monday.plusDays(7)))
        assertEquals(2, Timetable.weekNumber(monday, monday.plusDays(10)))
        assertEquals(3, Timetable.weekNumber(monday, monday.plusDays(14)))
        // 开学前 → 没有「第 0 周」这种说法。
        assertNull(Timetable.weekNumber(monday, monday.minusDays(1)))
        assertNull(Timetable.weekNumber(null, monday))
    }

    @Test
    fun sessionAtFindsNearestWithinWindow() {
        val s = CourseScheduleImporter.DEFAULT_SESSIONS   // 第 1 节 8:00，第 2 节 8:55
        assertEquals(1, Timetable.sessionAt(8 * 60, s))
        assertEquals(2, Timetable.sessionAt(8 * 60 + 55, s))
        assertNull(Timetable.sessionAt(12 * 60, s))
        // 8:30 在第 1 节（差 30 分）与第 2 节（差 25 分）之间 → 更近的第 2 节。
        assertEquals(2, Timetable.sessionAt(8 * 60 + 30, s))
    }

    @Test
    fun todayCoursesOnlyReturnsTodaysClasses() {
        // 2026-11-16 周一（dayOfWeek = 1），第 1 周。
        val courses = listOf(
            course("周一的课", 1, 1, 2),
            course("周二的课", 2, 1, 2),
        )
        val today = Timetable.todayCourses(courses, monday, monday)
        assertEquals(1, today.size)
        assertEquals("周一的课", today.first().title)
    }
}
