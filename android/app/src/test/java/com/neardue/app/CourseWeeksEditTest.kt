package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 编辑课程对话框的周次取值（v1.13）。
 *
 * 回归自真机：导入「走进生命科学」拿到的周次是 `3,4,6-11`（8 次），
 * 打开编辑对话框再原样保存，w 变成 `3-11`（9 次）——第 5 周被凭空补上。
 * 对话框只有起止两个旋钮，所以「没碰旋钮 = 保留原文」是必须的。
 */
class CourseWeeksEditTest {

    @Test
    fun untouchedKeepsOriginalSparseSpec() {
        assertEquals(
            "3,4,6-11",
            CourseWeeks.editSpec("3,4,6-11", 3, 11, "", touched = false),
        )
    }

    @Test
    fun untouchedKeepsParitySpec() {
        assertEquals(
            "6-18双",
            CourseWeeks.editSpec("6-18双", 6, 18, "", touched = false),
        )
    }

    @Test
    fun untouchedKeepsEmptySpecAsEveryWeek() {
        assertEquals("", CourseWeeks.editSpec("", 1, 16, "", touched = false))
    }

    @Test
    fun trimmedWeekRangeIsKeptEvenWhenItEqualsParsedBounds() {
        // 原文 `3,4,6-11` 的边界就是 3..11；旋钮显示 3–11，
        // 但只要没动过就必须还回原文，不能顺手压成 3-11。
        val parsed = CourseWeeks.parse("3,4,6-11")
        assertEquals(3, parsed.minOrNull())
        assertEquals(11, parsed.maxOrNull())
        assertEquals("3,4,6-11", CourseWeeks.editSpec("3,4,6-11", 3, 11, "", touched = false))
        // 一旦动过（哪怕只是把 11 按回 11 又调回 11 也会置 touched），才按范围重算。
        assertEquals("3-11", CourseWeeks.editSpec("3,4,6-11", 3, 11, "", touched = true))
    }

    @Test
    fun touchedRebuildsFromKnobs() {
        assertEquals("1-16", CourseWeeks.editSpec("3,4,6-11", 1, 16, "", touched = true))
        assertEquals("2-8单", CourseWeeks.editSpec("", 2, 8, "单", touched = true))
        assertEquals("6-18双", CourseWeeks.editSpec("1-5", 6, 18, "双", touched = true))
    }

    @Test
    fun reversedKnobsAreNormalised() {
        assertEquals("5-9", CourseWeeks.editSpec(null, 9, 5, "", touched = true))
    }

    @Test
    fun newCourseAlwaysUsesKnobs() {
        assertEquals("1-16", CourseWeeks.editSpec(null, 1, 16, "", touched = false))
    }

    @Test
    fun timesCountFollowsSpec() {
        // 对话框底部「共 N 次」按展开周数算，不能让合并后的假区间多算一周。
        assertEquals(8, CourseWeeks.parse(CourseWeeks.editSpec("3,4,6-11", 3, 11, "", false)).size)
        assertEquals(9, CourseWeeks.parse(CourseWeeks.editSpec("3,4,6-11", 3, 11, "", true)).size)
    }
}
