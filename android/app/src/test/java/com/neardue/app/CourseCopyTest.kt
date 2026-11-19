package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.20：长按课程块复制的文案 + 单双周快捷后缀。
 * 纯 JVM 测试，复制的是「一门课的固定排布」，不含单周例外。
 */
class CourseCopyTest {

    private val sample = Course(
        id = 7L,
        title = "高等数学甲I",
        dayOfWeek = 1,
        sessionStart = 5,
        sessionEnd = 6,
        weeks = "3,4,6-11",
        room = "B201",
    )

    @Test
    fun copyTextIncludesTitleDaySessionRoomAndWeeks() {
        assertEquals(
            "高等数学甲I 周一 第 5-6 节 · B201 · 3,4,6-11 周",
            sample.copyText(),
        )
    }

    @Test
    fun copyTextOmitsRoomWhenBlank() {
        val c = sample.copy(room = "")
        assertEquals("高等数学甲I 周一 第 5-6 节 · 3,4,6-11 周", c.copyText())
    }

    @Test
    fun copyTextOmitsWeeksWhenEveryWeek() {
        val c = sample.copy(weeks = "", room = "B201")
        assertEquals("高等数学甲I 周一 第 5-6 节 · B201", c.copyText())
    }

    @Test
    fun copyTextHandlesSingleSession() {
        val c = sample.copy(sessionStart = 3, sessionEnd = 3, weeks = "1-16单")
        assertEquals("高等数学甲I 周一 第 3 节 · B201 · 1-16 周（单周）", c.copyText())
    }

    @Test
    fun copyTextIncludesTeacherAndNoteWhenPresent() {
        val c = sample.copy(teacher = "王老师", note = "实验室上课")
        assertEquals(
            "高等数学甲I 周一 第 5-6 节 · B201 · 王老师 · 实验室上课 · 3,4,6-11 周",
            c.copyText(),
        )
    }

    @Test
    fun parityShortcutAddsAndRemovesSuffixWithoutTouchingRange() {
        assertEquals("3,4,6-11单", CourseWeeks.withParity("3,4,6-11", "单"))
        assertEquals("3,4,6-11双", CourseWeeks.withParity("3,4,6-11", "双"))
        assertEquals("3,4,6-11双", CourseWeeks.withParity("3,4,6-11单", "双"))
        assertEquals("3,4,6-11", CourseWeeks.withParity("3,4,6-11双", ""))
        assertEquals("单", CourseWeeks.withParity("", "单")) // 空 = 整学期，加后缀 = 整学期单周
    }
}
