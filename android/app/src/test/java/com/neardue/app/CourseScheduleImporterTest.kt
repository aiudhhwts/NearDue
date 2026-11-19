package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class CourseScheduleImporterTest {

    private val now = LocalDateTime.of(LocalDate.of(2026, 11, 15), LocalTime.of(10, 0))
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun at(date: LocalDate, h: Int, m: Int): Long =
        LocalDateTime.of(date, LocalTime.of(h, m))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun parsesDatedLinesAndSkipsUndatedOnes() {
        val text = """
            高等数学期末考试 12月14日 上午9点
            周一 线性代数 1-2节
            数据结构实验报告 11.20
        """.trimIndent()
        val r = CourseScheduleImporter.parse(text, now)
        assertEquals(3, r.totalLines)
        assertEquals(1, r.skippedLines)
        assertEquals(0, r.duplicateLines)
        assertEquals(2, r.items.size)
        // 按截止时间升序
        assertEquals("数据结构实验报告", r.items[0].title)
        assertEquals(at(LocalDate.of(2026, 11, 20), 19, 0), r.items[0].dueAt)
        assertEquals(Category.HOMEWORK, r.items[0].category)
        assertEquals("高等数学期末考试", r.items[1].title)
        assertEquals(at(LocalDate.of(2026, 12, 14), 9, 0), r.items[1].dueAt)
        assertEquals(Category.EXAM, r.items[1].category)
    }

    @Test
    fun defaultTimeIsUsedWhenOnlyDateIsPresent() {
        val r = CourseScheduleImporter.parse("六级考试 12月14日", now)
        assertEquals(1, r.items.size)
        assertEquals(at(LocalDate.of(2026, 12, 14), 19, 0), r.items[0].dueAt)
        assertEquals(Category.EXAM, r.items[0].category)
    }

    @Test
    fun defaultTimeIsOverridable() {
        val r = CourseScheduleImporter.parse("六级考试 12月14日", now, defaultHour = 21, defaultMinute = 30)
        assertEquals(at(LocalDate.of(2026, 12, 14), 21, 30), r.items[0].dueAt)
    }

    @Test
    fun stripsWeekSessionAndLeadingMarkers() {
        val text = "1. 第1-16周 周三 1-2节 高等数学 12月14日"
        val r = CourseScheduleImporter.parse(text, now)
        assertEquals(1, r.items.size)
        assertEquals("高等数学", r.items[0].title)
        assertEquals(at(LocalDate.of(2026, 12, 14), 19, 0), r.items[0].dueAt)
    }

    @Test
    fun bareWeekdayWithoutDateIsNotGuessed() {
        val r = CourseScheduleImporter.parse("周三 篮球队训练", now)
        assertEquals(1, r.totalLines)
        assertEquals(1, r.skippedLines)
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun explicitRelativeWeekdayIsAccepted() {
        val r = CourseScheduleImporter.parse("下周三 篮球队训练 19:00", now)
        assertEquals(1, r.items.size)
        // 2026-11-15 是周日 → 下周三是 2026-11-18
        assertEquals(at(LocalDate.of(2026, 11, 18), 19, 0), r.items[0].dueAt)
        assertEquals(Category.EVENT, r.items[0].category)
    }

    @Test
    fun deduplicatesWithinTheSameBatch() {
        val text = """
            高数作业 11.20
            高数作业 11.20
        """.trimIndent()
        val r = CourseScheduleImporter.parse(text, now)
        assertEquals(2, r.totalLines)
        assertEquals(1, r.items.size)
        assertEquals(1, r.duplicateLines)
    }

    @Test
    fun deduplicatesAgainstExistingItems() {
        val existing = Item(title = "高数作业", dueAt = at(LocalDate.of(2026, 11, 20), 19, 0))
        val keys = setOf(CourseScheduleImporter.keyOf(existing))
        val r = CourseScheduleImporter.parse("高数作业 11.20", now, existingKeys = keys)
        assertTrue(r.items.isEmpty())
        assertEquals(1, r.duplicateLines)
    }

    @Test
    fun itemsAreSortedByDueAt() {
        val text = """
            期末考试 12月20日
            明天 交论文
            大作业 11.18
        """.trimIndent()
        val r = CourseScheduleImporter.parse(text, now)
        assertEquals(3, r.items.size)
        assertTrue(r.items[0].dueAt <= r.items[1].dueAt)
        assertTrue(r.items[1].dueAt <= r.items[2].dueAt)
        assertEquals(at(LocalDate.of(2026, 11, 16), 19, 0), r.items[0].dueAt)
    }

    @Test
    fun blankInputReturnsEmptyResult() {
        val r = CourseScheduleImporter.parse("   \n\n  ", now)
        assertEquals(0, r.totalLines)
        assertEquals(0, r.skippedLines)
        assertTrue(r.items.isEmpty())
    }

    @Test
    fun neverInventsTimeForUndatedLines() {
        val r = CourseScheduleImporter.parse("线性代数\n概率论", now)
        assertEquals(2, r.totalLines)
        assertEquals(2, r.skippedLines)
        assertTrue(r.items.isEmpty())
    }

    // ── v1.5 周次课表展开 ───────────────────────────────────────────────

    private val term = CourseScheduleImporter.Term(LocalDate.of(2026, 11, 16))

    @Test
    fun weekLinesExpandIntoOneItemPerWeek() {
        val r = CourseScheduleImporter.parse("周三 1-2节 高等数学 1-4周", now, term = term)
        assertEquals(1, r.totalLines)
        assertEquals(0, r.skippedLines)
        assertEquals(4, r.items.size)
        assertEquals(4, r.expandedFromWeeks)
        assertEquals("高等数学", r.items[0].title)
        // 第 1 周周三是 2026-11-18，第 1 节 08:00
        assertEquals(at(LocalDate.of(2026, 11, 18), 8, 0), r.items[0].dueAt)
        assertEquals(at(LocalDate.of(2026, 12, 9), 8, 0), r.items[3].dueAt)
        // 课表行默认就是「学业」类，课程名里没有考试/竞赛/活动关键词也不该落到「其他」
        assertEquals(Category.HOMEWORK, r.items[0].category)
    }

    @Test
    fun sessionIndexMapsToItsStartTime() {
        val r = CourseScheduleImporter.parse("周五 第3节 大学物理 1周", now, term = term)
        assertEquals(1, r.items.size)
        // 第 3 节 = 10:00，第 1 周周五 = 2026-11-20
        assertEquals(at(LocalDate.of(2026, 11, 20), 10, 0), r.items[0].dueAt)
    }

    @Test
    fun missingSessionFallsBackToDefaultTime() {
        val r = CourseScheduleImporter.parse("周二 英语听说 1周", now, term = term)
        assertEquals(1, r.items.size)
        assertEquals(at(LocalDate.of(2026, 11, 17), 19, 0), r.items[0].dueAt)
    }

    @Test
    fun parityWeeksAreFiltered() {
        val odd = CourseScheduleImporter.parse("周三 1-2节 体育 1-6周 单周", now, term = term)
        assertEquals(3, odd.items.size)
        val even = CourseScheduleImporter.parse("周三 1-2节 音乐 1-6周 双周", now, term = term)
        assertEquals(3, even.items.size)
        // 单周 = 1/3/5 周 → 11-18, 12-02, 12-16
        assertEquals(at(LocalDate.of(2026, 11, 18), 8, 0), odd.items[0].dueAt)
        assertEquals(at(LocalDate.of(2026, 12, 16), 8, 0), odd.items[2].dueAt)
        // 双周 = 2/4/6 周 → 11-25, 12-09, 12-23
        assertEquals(at(LocalDate.of(2026, 11, 25), 8, 0), even.items[0].dueAt)
    }

    @Test
    fun weekLineWithoutTermStartIsCountedNotGuessed() {
        val r = CourseScheduleImporter.parse("周三 1-2节 高等数学 1-16周", now, term = null)
        assertTrue(r.items.isEmpty())
        assertEquals(1, r.needTermStartLines)
        assertEquals(0, r.skippedLines)
    }

    @Test
    fun pastClassesAreNotGenerated() {
        // 开学日已过一个月，1-2 周已经上完了
        val past = CourseScheduleImporter.Term(LocalDate.of(2026, 10, 26))
        val r = CourseScheduleImporter.parse("周三 1-2节 高等数学 1-2周", now, term = past)
        assertTrue(r.items.isEmpty())
        assertEquals(1, r.skippedLines)
    }

    @Test
    fun datedLineWinsOverWeekLine() {
        val r = CourseScheduleImporter.parse("周三 1-2节 高等数学 1-4周 12月14日 补课", now, term = term)
        assertEquals(1, r.items.size)
        assertEquals(at(LocalDate.of(2026, 12, 14), 19, 0), r.items[0].dueAt)
        assertTrue(r.items[0].title.contains("高等数学"))
    }

    @Test
    fun courseNameDrivesCategory() {
        val r = CourseScheduleImporter.parse("周四 1-2节 期末考试 1周", now, term = term)
        assertEquals(Category.EXAM, r.items[0].category)
    }

    @Test
    fun weekExpansionDeduplicatesAgainstExisting() {
        val first = CourseScheduleImporter.parse("周三 1-2节 高等数学 1-2周", now, term = term)
        val keys = first.items.map { CourseScheduleImporter.keyOf(it) }.toSet()
        val second = CourseScheduleImporter.parse(
            "周三 1-2节 高等数学 1-2周", now, existingKeys = keys, term = term,
        )
        assertTrue(second.items.isEmpty())
        assertEquals(2, second.duplicateLines)
    }

    @Test
    fun invalidWeekRangeFallsBackWithoutThrowing() {
        val r = CourseScheduleImporter.parse("周三 1-2节 高等数学 0-0周", now, term = term)
        assertTrue(r.items.isEmpty())
        assertEquals(1, r.skippedLines)
    }

    @Test
    fun everyWeekExpandsOnlyTheNextFewWeeks() {
        // 课表还原器对没写周次的格子会补「每周」；绝不能按 25 周展开把列表淹掉。
        val r = CourseScheduleImporter.parse("周三 1-2节 宪法学 每周", now, term = term)
        assertTrue("应该展开出若干次课", r.items.isNotEmpty())
        assertTrue("每周最多展开 " + 4 + " 周，实际 " + r.items.size, r.items.size <= 4)
        assertEquals(0, r.skippedLines)
        // 每次课都在未来 4 周内
        val horizon = java.time.LocalDate.of(2026, 11, 15).plusWeeks(4)
        for (it in r.items) {
            val d = java.time.Instant.ofEpochMilli(it.dueAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            assertTrue("展开的日期不该超过 4 周：" + d, !d.isAfter(horizon))
        }
    }

    @Test
    fun explicitWeekRangeIsNotCapped() {
        // 明确写了 1-16 周的，还是老老实实展开 16 周（用户自己写的，不能偷偷截断）。
        val r = CourseScheduleImporter.parse("周三 1-2节 宪法学 1-16周", now, term = term)
        assertEquals(16, r.items.size)
    }
}
