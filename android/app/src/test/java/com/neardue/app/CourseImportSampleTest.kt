package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实教务系统导出表的导入回归测试（v1.11）。
 *
 * 样例来自用户真机的 `导出数据.xls`（本机 Excel 能开，adb 侧读不了 OLE2），
 * 用 Excel 另存成 UTF-8 TSV 后放进 `fixtures/sample_timetable.tsv`：
 * 1 行表头 + 42 条上课记录，课程名带 `【本】` 前缀，周次写成 `10-11周`、`3周,6-18周`、
 * `7-17周(单)`、`5周,7-9周,11周`，节次写成 `第1节` / `第8节`。
 *
 * v1.10 在这张表上错得很离谱（实测）：
 * - 42 行 → 42 门课，同一门课的 5 段周次各算一门，课表格子叠成一摞；
 * - 用户直接复制整表粘贴时，整行被当成课名（`[2 9000001 【本】走进生命科学 01 …]`），节次只剩 1 节；
 * - `3周,6-18周` 只吃掉第一段，剩下 `,6-18周` 粘在课名里；
 * - `A101` 这种字母夹数字的教室认不出，留在课名里、教室为空。
 *
 * 这些断言就是钉住这四个坑的。
 */
class CourseImportSampleTest {

    private val now = 1_700_000_000_000L

    private fun sample(): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/sample_timetable.tsv")!!
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun wholeTablePasteYieldsOneCoursePerGrid() {
        val r = CourseImporter.parse(sample(), now)

        // 42 条记录 → 24 个格子。多出来的 18 行是同一门课的不同周次/教室，必须并掉。
        assertEquals(42, r.totalLines)
        assertEquals(24, r.courses.size)
        assertEquals(0, r.items.size)
        assertEquals(0, r.skippedLines)
        assertEquals(18, r.duplicateLines)
    }

    @Test
    fun courseNameHasNoLevelPrefixNorWeekTail() {
        val r = CourseImporter.parse(sample(), now)
        for (c in r.courses) {
            assertFalse("课名里不该有培养层次前缀: ${c.title}", c.title.contains("【本】"))
            assertFalse("课名里不该留周次尾巴: ${c.title}", c.title.contains("周"))
            assertFalse("课名里不该留教室: ${c.title}", c.title.contains("N8") || c.title.contains("N3"))
            assertFalse("课名不该带首尾空格/逗号: [${c.title}]", c.title != c.title.trim() || c.title.contains(","))
        }
        assertTrue(r.courses.any { it.title == "走进生命科学" })
        assertTrue(r.courses.any { it.title == "高等数学甲I" })
        assertTrue(r.courses.any { it.title == "大学语文（Ⅰ）" })
    }

    @Test
    fun splitWeekRowsMergeIntoUnionNotManyCourses() {
        val r = CourseImporter.parse(sample(), now)

        // 走进生命科学：3周 / 4周 / 6-7周 / 8-9周 / 10-11周 五行 → 一门，周次并集 3,4,6-11。
        val near = r.courses.single { it.title == "走进生命科学" }
        assertEquals(1, near.dayOfWeek)
        assertEquals(1, near.sessionStart)
        assertEquals(2, near.sessionEnd)
        assertEquals("3,4,6-11", near.weeks)

        // 高等数学甲I 周一 5-6：`4周` + `3周,6-18周` → 3,4,6-18。
        val math = r.courses.single { it.title == "高等数学甲I" && it.dayOfWeek == 1 }
        assertEquals("3,4,6-18", math.weeks)
    }

    @Test
    fun paritySpecStaysParityAfterMerge() {
        val r = CourseImporter.parse(sample(), now)

        // 大学语文（Ⅰ）周二 3-4：3周 + 4周 + 7-17周(单) + 6-18周(双) → 3,4,6-18（全周覆盖）。
        val english = r.courses.single { it.title == "大学语文（Ⅰ）" && it.dayOfWeek == 2 }
        assertEquals("3,4,6-18", english.weeks)

        // 心理健康 周五 5-6：6周 + 5周,7-9周,11周 → 5-9,11（连续段压成区间）。
        val mind = r.courses.single { it.title == "大学生心理健康与成长" && it.dayOfWeek == 5 }
        assertEquals("5-9,11", mind.weeks)
    }

    @Test
    fun roomWithLetterInsideDigitsIsRecognised() {
        val r = CourseImporter.parse(sample(), now)

        val near = r.courses.single { it.title == "走进生命科学" }
        assertEquals("A101", near.room)

        val military = r.courses.single { it.title == "军事常识" && it.dayOfWeek == 1 }
        assertEquals("A104", military.room)

        val contest = r.courses.single { it.title == "趣味数学竞赛选讲" }
        assertEquals("A103", contest.room)
    }

    @Test
    fun placeWithoutDigitsCountsAsRoom() {
        val r = CourseImporter.parse(sample(), now)

        // 体育Ⅱ 教室写的是「操场」，没有数字，也得认成教室而不是粘在课名后面。
        val pe = r.courses.single { it.title == "体育Ⅱ" }
        assertEquals("操场", pe.room)
    }

    @Test
    fun differentRoomsOnSameGridAreBothKept() {
        val r = CourseImporter.parse(sample(), now)

        // 生物学 周二 9-10 节换过教室（B202 / B203），信息不能丢。
        val zoo = r.courses.single { it.title == "生物学" && it.dayOfWeek == 2 }
        assertEquals("B202 / B203", zoo.room)
    }

    @Test
    fun contestIsCategorisedAsContest() {
        val r = CourseImporter.parse(sample(), now)
        assertEquals(Category.CONTEST, r.courses.single { it.title == "趣味数学竞赛选讲" }.category)
        assertEquals(Category.HOMEWORK, r.courses.single { it.title == "走进生命科学" }.category)
    }

    @Test
    fun gridIsSortedByDayThenSession() {
        val r = CourseImporter.parse(sample(), now)
        val keys = r.courses.map { it.dayOfWeek to it.sessionStart }
        assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second })), keys)
    }

    @Test
    fun reimportingSameTableIsRejectedByKeys() {
        val first = CourseImporter.parse(sample(), now)
        val keys = first.courses.map { CourseImporter.courseKey(it) }.toSet()
        val second = CourseImporter.parse(sample(), now, existingCourseKeys = keys)

        assertEquals(0, second.courses.size)
        // 24 个格子对应的 42 行全部命中已有键（同一门课的不同周次行会被拆开比对）。
        assertEquals(42, second.duplicateLines)
    }

    // ── 纯文本形态（用户手打 / PDF 抽文字）也要认 ──────────────────────────

    @Test
    fun commaSeparatedWeekListIsFullyStripped() {
        val r = CourseImporter.parse("周一 5-6节 高等数学甲I 3周,6-18周 B201", now)
        val c = r.courses.single()
        assertEquals("高等数学甲I", c.title)
        assertEquals("3,6-18", c.weeks)
        assertEquals("B201", c.room)
    }

    @Test
    fun threeWeekSegmentsAreStrippedAndMerged() {
        val r = CourseImporter.parse("周五 5-6节 心理健康 5周,7-9周,11周 B209", now)
        val c = r.courses.single()
        assertEquals("心理健康", c.title)
        // 5 / 7,8,9 / 11：7-9 连续三段压成区间，5 和 11 零散留下。
        assertEquals("5,7-9,11", c.weeks)
    }

    @Test
    fun singleSegmentSpecKeepsOriginalParityWriting() {
        // 单段时保留原文写法，别把 `1-16单` 压成 `1-15单` 这种假区间。
        val c = CourseImporter.parse("周二 1-2节 物理 7-17周(单)", now).courses.single()
        assertEquals("7-17单", CourseWeeks.normalize(c.weeks))
    }

    @Test
    fun tableWithoutHeaderFallsBackToContentInference() {
        val text = "9000001\t【本】走进生命科学\t星期一\t第1节\t第2节\t10-11周\tA101"
        val c = CourseImporter.parse(text, now).courses.single()
        assertEquals("走进生命科学", c.title)
        assertEquals(1, c.dayOfWeek)
        assertEquals(1, c.sessionStart)
        assertEquals(2, c.sessionEnd)
        assertEquals("10-11", c.weeks)
        assertEquals("A101", c.room)
    }

    @Test
    fun tableRowWithoutWeekdayIsSkippedNotGuessed() {
        // 认不出周几就跳过，绝不瞎猜成周一。
        val text = "课程名\t星期\t节次\n走进生命科学\t\t第1节"
        val r = CourseImporter.parse(text, now)
        assertEquals(0, r.courses.size)
        assertEquals(1, r.skippedLines)
    }
}
