package com.neardue.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表导入的测试（v1.9）。
 *
 * 核心边界：**周次课表行只进课表（Course），不生成提醒（Item）**；
 * 带日期的行才进清单、才提醒。单/双周与零散周是真实教务系统最常见的写法，
 * 这里逐种覆盖，免得开学第一天就把 16 周的课全塞进提醒。
 */
class CourseImporterTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private val now: Long = at(LocalDate.of(2026, 11, 16), 10, 0)

    // ── 周次课表行 ──────────────────────────────────────────────────────────

    @Test
    fun weekLineBecomesCourseNotReminder() {
        val r = CourseImporter.parse("周三 1-2节 高等数学 1-16周", now)

        assertEquals(1, r.courses.size)
        assertEquals(0, r.items.size)          // 关键：不变成提醒
        val c = r.courses.first()
        assertEquals("高等数学", c.title)
        assertEquals(3, c.dayOfWeek)
        assertEquals(1, c.sessionStart)
        assertEquals(2, c.sessionEnd)
        assertEquals("1-16", c.weeks)
    }

    @Test
    fun oddWeeksAreParsed() {
        val c = CourseImporter.parse("周三 1-2节 体育 1-16周（单）", now).courses.single()
        assertEquals("1-16单", c.weeks)

        // 展开后只包含奇数周
        val weeks = CourseWeeks.parse(c.weeks)
        assertEquals(listOf(1, 3, 5, 7, 9, 11, 13, 15), weeks)
    }

    @Test
    fun evenWeeksAreParsed() {
        val r = CourseImporter.parse("周四 3-4节 音乐 1-16周双", now)
        val c = r.courses.single()
        assertEquals("1-16双", c.weeks)
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 14, 16), CourseWeeks.parse(c.weeks))
    }

    @Test
    fun sparseWeeksAreParsed() {
        val c = CourseImporter.parse("周五 5-6节 讲座 1,3,5周", now).courses.single()
        assertEquals("1,3,5", c.weeks)
        assertEquals(listOf(1, 3, 5), CourseWeeks.parse(c.weeks))
    }

    @Test
    fun mixedRangesAreParsed() {
        val c = CourseImporter.parse("周二 1-2节 实验 1-4,7-8,13周", now).courses.single()
        assertEquals("1-4,7-8,13", c.weeks)
        assertEquals(
            listOf(1, 2, 3, 4, 7, 8, 13),
            CourseWeeks.parse(c.weeks),
        )
    }

    @Test
    fun wholeTermSingleWeekSpecIsSupported() {
        // 「单周」不带数字：整学期单周。
        val c = CourseImporter.parse("周一 1-2节 军训 单周", now).courses.single()
        assertEquals("单", c.weeks)
        val weeks = CourseWeeks.parse(c.weeks)
        assertTrue(weeks.isNotEmpty())
        assertTrue(weeks.all { it % 2 == 1 })
    }

    @Test
    fun courseWithNoWeekSpecCoversWholeTerm() {
        // 常见情况：PDF 抽出来只有「周三 1-2节 宪法学」。
        val c = CourseImporter.parse("周三 1-2节 宪法学", now).courses.single()
        assertEquals("", c.weeks)
        assertEquals(CourseWeeks.MAX_WEEK, CourseWeeks.parse(c.weeks).size)
    }

    @Test
    fun roomIsSplitOutOfTitle() {
        val c = CourseImporter.parse("周三 1-2节 宪法学 A101 1-16周", now).courses.single()
        assertEquals("宪法学", c.title)
        assertEquals("A101", c.room)
    }

    @Test
    fun englishRoomIsSplitOut() {
        val c = CourseImporter.parse("周四 3-4节 大学物理 B201 1-8周", now).courses.single()
        assertEquals("大学物理", c.title)
        assertEquals("B201", c.room)
    }

    @Test
    fun courseCategoryIsHomeworkByDefault() {
        val c = CourseImporter.parse("周三 1-2节 高等数学 1-16周", now).courses.single()
        assertEquals(Category.HOMEWORK, c.category)
    }

    @Test
    fun courseNamedExamIsExamCategory() {
        // 课表里真的有「期末考试」这种行，它该是红色。
        val c = CourseImporter.parse("周四 1-2节 期末考试 1周", now).courses.single()
        assertEquals(Category.EXAM, c.category)
    }

    @Test
    fun duplicateCourseIsCounted() {
        val text = "周三 1-2节 高等数学 1-16周\n周三 1-2节 高等数学 1-16周"
        val r = CourseImporter.parse(text, now)
        assertEquals(1, r.courses.size)
        assertEquals(1, r.duplicateLines)
    }

    @Test
    fun existingKeysPreventReimport() {
        val first = CourseImporter.parse("周三 1-2节 高等数学 1-16周", now)
        val keys = first.courses.map { CourseImporter.courseKey(it) }.toSet()
        val second = CourseImporter.parse("周三 1-2节 高等数学 1-16周", now, existingCourseKeys = keys)
        assertEquals(0, second.courses.size)
        assertEquals(1, second.duplicateLines)
    }

    // ── 带日期的行 ──────────────────────────────────────────────────────────

    @Test
    fun datedLineBecomesReminderNotCourse() {
        val r = CourseImporter.parse("高数期末 12月14日 9:00", now)

        assertEquals(0, r.courses.size)
        assertEquals(1, r.items.size)
        assertEquals("高数期末", r.items.first().title)
        assertEquals(at(LocalDate.of(2026, 12, 14), 9, 0), r.items.first().dueAt)
    }

    @Test
    fun mixedInputSplitsIntoBothBuckets() {
        val text = """
            周三 1-2节 高等数学 1-16周
            高数期末 12月14日 9:00
            周五 3-4节 大学物理 1-8周
        """.trimIndent()
        val r = CourseImporter.parse(text, now)

        assertEquals(2, r.courses.size)
        assertEquals(1, r.items.size)
        assertEquals(0, r.skippedLines)
    }

    @Test
    fun lineWithNeitherDateNorWeekIsSkipped() {
        val r = CourseImporter.parse("学分 3", now)
        assertEquals(0, r.courses.size)
        assertEquals(0, r.items.size)
        assertEquals(1, r.skippedLines)
    }

    @Test
    fun blankInputYieldsNothing() {
        val r = CourseImporter.parse("   \n\n  ", now)
        assertEquals(0, r.courses.size)
        assertEquals(0, r.items.size)
        assertEquals(0, r.totalLines)
    }

    @Test
    fun leadingNumberingIsStripped() {
        val r = CourseImporter.parse("1、周三 1-2节 高等数学 1-16周", now)
        assertEquals("高等数学", r.courses.single().title)
    }

    @Test
    fun everyWeekIsWholeTermAndNotPartOfTheTitle() {
        // PDF 表格还原行末尾就是「每周」：既是整学期，也不能留在课程名里。
        val c = CourseImporter.parse("周六 1-2节 体育 每周", now).courses.single()
        assertEquals("体育", c.title)
        assertEquals("", c.weeks)
        assertEquals(CourseWeeks.MAX_WEEK, CourseWeeks.parse(c.weeks).size)
    }
}
