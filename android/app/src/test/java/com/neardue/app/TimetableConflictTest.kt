package com.neardue.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.19「撞课提示」的投影测试。
 *
 * 冲突只标出来，不改数据：两门课都还留在格子里，用户自己决定上哪一门。
 */
class TimetableConflictTest {

    private val monday = LocalDate.of(2026, 11, 16)

    private fun course(title: String, day: Int, from: Int, to: Int = from) = Course(
        title = title,
        dayOfWeek = day,
        sessionStart = from,
        sessionEnd = to,
    )

    @Test
    fun noConflictWhenCoursesDoNotOverlap() {
        val g = Timetable.grid(
            courses = listOf(course("第一节", 1, 1), course("第三节", 1, 3)),
            weekStart = monday, weekIndex = 1,
        )
        assertEquals(0, g.conflictCount())
        assertTrue(g.conflicts.isEmpty())
    }

    @Test
    fun twoCoursesInSameCellAreOneConflict() {
        val a = course("高等数学", 1, 1, 2)
        val b = course("线性代数", 1, 1, 2)
        val g = Timetable.grid(
            courses = listOf(a, b),
            weekStart = monday, weekIndex = 1,
        )
        assertEquals(1, g.conflictCount())
        val clash = g.conflicts.first()
        assertEquals(1, clash.dayOfWeek)
        assertEquals(1, clash.session)
        assertEquals(2, clash.courses.size)
        assertTrue(g.isConflict(a.id))
        assertTrue(g.isConflict(b.id))
    }

    @Test
    fun partiallyOverlappingPairIsOneConflictAcrossSharedCells() {
        val a = course("从第一节到第四节", 3, 1, 4)
        val b = course("从第三节到第六节", 3, 3, 6)
        val g = Timetable.grid(
            courses = listOf(a, b),
            weekStart = monday, weekIndex = 1,
        )
        // 同一对课在第 3、4 节都撞了，但用户眼里就是「一处」冲突。
        assertEquals(1, g.conflictCount())
        assertEquals(listOf(3, 4), g.conflicts.first().sessions)
    }

    @Test
    fun stoppedCourseDoesNotCreateConflict() {
        val a = course("停掉的课", 1, 1, 2).copy(id = 1L)
        val b = course("照常上的课", 1, 1, 2).copy(id = 2L)
        val g = Timetable.grid(
            courses = listOf(a, b),
            weekStart = monday, weekIndex = 1,
            overrides = mapOf(1L to CourseOverride(courseId = 1L, week = 1, skip = true)),
        )
        assertEquals(0, g.conflictCount())
        assertEquals(1, g.at(1, 1).size)
    }

    @Test
    fun conflictsAreSortedByDayThenSession() {
        val g = Timetable.grid(
            courses = listOf(
                course("周三第5节A", 3, 5), course("周三第5节B", 3, 5),
                course("周一第7节A", 1, 7), course("周一第7节B", 1, 7),
            ),
            weekStart = monday, weekIndex = 1,
        )
        assertEquals(2, g.conflictCount())
        assertEquals(1, g.conflicts[0].dayOfWeek)
        assertEquals(listOf(7), g.conflicts[0].sessions)
        assertEquals(3, g.conflicts[1].dayOfWeek)
        assertEquals(listOf(5), g.conflicts[1].sessions)
    }
}
