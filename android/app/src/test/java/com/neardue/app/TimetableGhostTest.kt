package com.neardue.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「不在这一周的课灰着显示」的投影测试（v1.14）。
 *
 * 参考 WakeUp 课表的思路：一屏能看出整学期的排布，本周的课是实的、其余是虚的。
 * 关键是**灰块不能混进本周的格子**，也不能算进「这周几门课」，否则点到灰块会编辑错课。
 */
class TimetableGhostTest {

    // 2026-11-16 是周一。
    private val monday = LocalDate.of(2026, 11, 16)

    private fun course(
        title: String,
        day: Int,
        from: Int,
        to: Int = from,
        weeks: String = "",
    ) = Course(
        title = title,
        dayOfWeek = day,
        sessionStart = from,
        sessionEnd = to,
        weeks = weeks,
    )

    @Test
    fun otherWeeksAreSkippedByDefault() {
        val g = Timetable.grid(
            courses = listOf(course("只在第 5 周上的课", 3, 1, 2, weeks = "5")),
            weekStart = monday,
            weekIndex = 1,
        )
        assertTrue(g.at(1, 3).isEmpty())
        assertEquals(0, g.courseCount())
        assertEquals(0, g.otherCount())
        assertTrue(g.ghosts.isEmpty())
    }

    @Test
    fun showOtherWeeksCollectsThemAsGhosts() {
        val g = Timetable.grid(
            courses = listOf(
                course("这周的课", 3, 1, 2, weeks = "1-16"),
                course("第 5 周的课", 3, 5, 5, weeks = "5"),
            ),
            weekStart = monday,
            weekIndex = 1,
            showOtherWeeks = true,
        )
        // 本周的格子只有本周的课。
        assertEquals(listOf("这周的课"), g.at(1, 3).map { it.title })
        assertEquals(1, g.courseCount())
        // 第 5 周那门进灰显层，位置对得上。
        assertEquals(listOf("第 5 周的课"), g.ghostsAt(5, 3).map { it.title })
        assertEquals(1, g.otherCount())
    }

    @Test
    fun ghostsForDayDedupesAcrossSessions() {
        // 跨两节的课在 ghosts 里占两个格子，但按天取只应出现一次。
        val g = Timetable.grid(
            courses = listOf(course("讲座", 2, 5, 6, weeks = "9")),
            weekStart = monday,
            weekIndex = 1,
            showOtherWeeks = true,
        )
        assertEquals(2, g.ghostsAt(5, 2).size + g.ghostsAt(6, 2).size)
        assertEquals(1, g.ghostsForDay(2).size)
        assertTrue(g.ghostsForDay(3).isEmpty())
    }

    @Test
    fun ghostedCoursesNeverOutnumberTheirOwnCells() {
        // 灰显层不能凭空造课：2 门不在本周的课，otherCount 就是 2。
        val g = Timetable.grid(
            courses = listOf(
                course("A", 1, 1, 1, weeks = "2"),
                course("B", 7, 9, 9, weeks = "8"),
            ),
            weekStart = monday,
            weekIndex = 3,
            showOtherWeeks = true,
        )
        assertEquals(0, g.courseCount())
        assertEquals(2, g.otherCount())
    }

    @Test
    fun noTermStartPutsEverythingInCells() {
        // 没设开学日（weekIndex = null）时不做周次过滤，所有课都是实的，灰显层为空。
        val g = Timetable.grid(
            courses = listOf(course("每周都有", 4, 3, 4), course("只在前八周", 4, 3, 4, weeks = "1-8")),
            weekStart = monday,
            weekIndex = null,
            showOtherWeeks = true,
        )
        assertEquals(2, g.at(3, 4).size)
        assertEquals(0, g.otherCount())
    }

    @Test
    fun emptySpecCountsAsEveryWeek() {
        // weeks 为空串 = 每周都有 → 任何时候都不该是灰块。
        val g = Timetable.grid(
            courses = listOf(course("体育", 5, 1, 2, weeks = "")),
            weekStart = monday,
            weekIndex = 20,
            showOtherWeeks = true,
        )
        assertEquals(1, g.courseCount())
        assertEquals(0, g.otherCount())
    }
}
