package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ChineseDateParserTest {

    /** Fixed reference moment so every assertion is deterministic. */
    private val now: Long = LocalDateTime.of(2026, 11, 15, 10, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun at(millis: Long) =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())

    // 1. explicit HH:mm wins, HOMEWORK category, title keeps the subject
    @Test
    fun explicitTimeAndHomeworkCategory() {
        val r = ChineseDateParser.parse("高数作业 11.20 23:59", now, 19, 0)
        assertTrue(r.matched)
        assertTrue(r.title.contains("高数作业"))
        assertEquals(1, r.categoryOrdinal)
        assertEquals(LocalTime.of(23, 59), at(r.dueAt).toLocalTime())
    }

    // 2. no time written -> defaultHour:defaultMinute = 19:00
    @Test
    fun noTimeUsesDefault19() {
        val r = ChineseDateParser.parse("高数作业 11.20", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(19, at(r.dueAt).hour)
        assertEquals(0, at(r.dueAt).minute)
    }

    // 3. 明天 = +1 day, HOMEWORK, default time
    @Test
    fun tomorrowIsPlusOneDay() {
        val r = ChineseDateParser.parse("明天 交论文", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(1, r.categoryOrdinal)
        assertEquals(LocalDate.of(2026, 11, 16), at(r.dueAt).toLocalDate())
        assertEquals(19, at(r.dueAt).hour)
    }

    // 4. 下周三 is a Wednesday and 篮球队训练 maps to EVENT
    @Test
    fun nextWeekWednesdayIsWednesday() {
        val r = ChineseDateParser.parse("下周三 篮球队训练", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(3, r.categoryOrdinal)
        assertEquals(DayOfWeek.WEDNESDAY, at(r.dueAt).dayOfWeek)
    }

    // 5. 3天后 = +3 days, CONTEST wins over HOMEWORK (报名)
    @Test
    fun arabicNumeralDaysAndContestPriority() {
        val r = ChineseDateParser.parse("3天后 数学竞赛报名", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(2, r.categoryOrdinal)
        assertEquals(LocalDate.of(2026, 11, 18), at(r.dueAt).toLocalDate())
    }

    // 6. 六级考试 -> EXAM, 上午9点 -> 9:00, 12月14日 keeps that date
    @Test
    fun examCategoryAndMorningHour() {
        val r = ChineseDateParser.parse("六级考试 12月14日 上午9点", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(0, r.categoryOrdinal)
        assertEquals(9, at(r.dueAt).hour)
        assertEquals(LocalDate.of(2026, 12, 14), at(r.dueAt).toLocalDate())
    }

    // 7. 下午3点 -> 15:00 (with a date present so the result is matched)
    @Test
    fun afternoonPeriodShiftsToPm() {
        val r = ChineseDateParser.parse("明天 下午3点 交论文", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(15, at(r.dueAt).hour)
        assertEquals(0, at(r.dueAt).minute)
    }

    // 8. No date at all -> matched=false; caller must fall back to the date picker
    @Test
    fun noDateReturnsUnmatchedAndUnchangedTitle() {
        val r = ChineseDateParser.parse("随便写点什么", now, 19, 0)
        assertFalse(r.matched)
        assertEquals("随便写点什么", r.title)
    }

    // 9. Blank input -> matched=false, empty title
    @Test
    fun blankInputIsUnmatched() {
        val r = ChineseDateParser.parse("", now, 19, 0)
        assertFalse(r.matched)
        assertEquals("", r.title)
    }

    // 10. Chinese numeral 三天后 = +3 days
    @Test
    fun chineseNumeralDays() {
        val r = ChineseDateParser.parse("三天后 复习", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(LocalDate.of(2026, 11, 18), at(r.dueAt).toLocalDate())
    }

    // 11. 晚上8点 -> 20:00
    @Test
    fun eveningPeriodShiftsToPm() {
        val r = ChineseDateParser.parse("11.20 晚上8点", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(20, at(r.dueAt).hour)
    }

    // 12. Explicit 23:59 is NOT overridden by defaultHour=19
    @Test
    fun explicitTimeNotOverriddenByDefault() {
        val r = ChineseDateParser.parse("11.20 23:59 复习", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(LocalTime.of(23, 59), at(r.dueAt).toLocalTime())
    }

    // 13. Slash form 11/20 works (and explicit time is preserved)
    @Test
    fun slashDateFormWorks() {
        val r = ChineseDateParser.parse("11/20 23:59", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(LocalDate.of(2026, 11, 20), at(r.dueAt).toLocalDate())
        assertEquals(LocalTime.of(23, 59), at(r.dueAt).toLocalTime())
    }

    // extra: past month/day rolls to the following year
    @Test
    fun pastDateRollsToNextYear() {
        val r = ChineseDateParser.parse("1.5 复习", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(LocalDate.of(2027, 1, 5), at(r.dueAt).toLocalDate())
    }

    // extra: 大后天 = +3 days; 本周一 is a Monday strictly after today
    @Test
    fun relativeAndThisWeekForms() {
        val d = ChineseDateParser.parse("大后天 复习", now, 19, 0)
        assertTrue(d.matched)
        assertEquals(LocalDate.of(2026, 11, 18), at(d.dueAt).toLocalDate())
        val w = ChineseDateParser.parse("本周一 训练", now, 19, 0)
        assertTrue(w.matched)
        assertEquals(DayOfWeek.MONDAY, at(w.dueAt).dayOfWeek)
        assertEquals(3, w.categoryOrdinal)
    }

    // extra: category priority - 报名 must map to CONTEST, not HOMEWORK(交)
    @Test
    fun contestPriorityOverHomework() {
        val r = ChineseDateParser.parse("明天 数学竞赛报名", now, 19, 0)
        assertEquals(2, r.categoryOrdinal)
    }

    // extra: 高数作业 -> HOMEWORK, 六级考试 -> EXAM, 篮球队训练 -> EVENT
    @Test
    fun keywordCategoryMapping() {
        assertEquals(1, ChineseDateParser.parse("明天 高数作业", now, 19, 0).categoryOrdinal)
        assertEquals(0, ChineseDateParser.parse("明天 六级考试", now, 19, 0).categoryOrdinal)
        assertEquals(3, ChineseDateParser.parse("明天 篮球队训练", now, 19, 0).categoryOrdinal)
    }

    // extra: 9点 / 9点半 / 中午 forms
    @Test
    fun pointAndHalfAndNoonForms() {
        assertEquals(9, at(ChineseDateParser.parse("明天 9点", now, 19, 0).dueAt).hour)
        assertEquals(30, at(ChineseDateParser.parse("明天 9点半", now, 19, 0).dueAt).minute)
        assertEquals(12, at(ChineseDateParser.parse("明天 中午", now, 19, 0).dueAt).hour)
    }

    // extra: date/time fragments are removed from the title
    @Test
    fun titleStripsDateAndTimeFragments() {
        val r = ChineseDateParser.parse("高数作业 11.20 23:59", now, 19, 0)
        assertEquals("高数作业", r.title)
    }

    // v1.25: pure Chinese numeral month/day
    @Test
    fun chineseNumeralMonthDay() {
        val r = ChineseDateParser.parse("期末考 十二月三十一日", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(LocalDate.of(2026, 12, 31), at(r.dueAt).toLocalDate())
        assertEquals(0, r.categoryOrdinal)
        assertEquals("期末考", r.title)
    }

    // v1.25: past Chinese month/day rolls to next year (九月一日 on 2026-11-15)
    @Test
    fun chineseNumeralMonthDayRollsToNextYear() {
        val r = ChineseDateParser.parse("九月一日 复习", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(LocalDate.of(2027, 9, 1), at(r.dueAt).toLocalDate())
    }

    // v1.25: mixed Chinese/Arabic within one date
    @Test
    fun mixedChineseArabicMonthDay() {
        val a = ChineseDateParser.parse("十二月1日 交作业", now, 19, 0)
        assertTrue(a.matched)
        assertEquals(LocalDate.of(2026, 12, 1), at(a.dueAt).toLocalDate())
        val b = ChineseDateParser.parse("12月一日 交作业", now, 19, 0)
        assertTrue(b.matched)
        assertEquals(LocalDate.of(2026, 12, 1), at(b.dueAt).toLocalDate())
    }

    // v1.25: 十月十号 with 号 suffix
    @Test
    fun chineseNumeralMonthDayHaoSuffix() {
        val r = ChineseDateParser.parse("十月十号 讲座", now, 19, 0)
        assertTrue(r.matched)
        assertEquals(LocalDate.of(2026, 10, 10).withYear(2027), at(r.dueAt).toLocalDate())
    }
}
