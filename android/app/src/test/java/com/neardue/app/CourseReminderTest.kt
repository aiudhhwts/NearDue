package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 上课提醒的纯计算单测。
 *
 * 全部用固定的日期与时刻，不依赖「现在几点」，所以任何时区任何时间跑结果都一样。
 */
class CourseReminderTest {

    // 2026-09-07 是周一，当作学期第 1 周星期一。
    private val termStart = LocalDate.of(2026, 9, 7)
    private val sessions = CourseScheduleImporter.DEFAULT_SESSIONS   // 8:00, 8:55, 10:00, ...

    private fun course(
        id: Long = 1L,
        title: String = "高等数学甲I",
        day: Int = 1,
        from: Int = 1,
        to: Int = 2,
        weeks: String = "",
        room: String = "B201",
    ) = Course(id, title, day, from, to, weeks, room, Category.HOMEWORK)

    @Test
    fun oneCourseOnItsDayBecomesOneSlot() {
        // 第 1 周周一 8:00 的课。从周日晚上开始看，未来 2 天应该只看到这一节。
        val slots = CourseReminder.upcoming(
            courses = listOf(course()),
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 6, 20, 0),
            days = 2,
            leadMinutes = 10,
        )
        assertEquals(1, slots.size)
        assertEquals("高等数学甲I", slots[0].title)
        assertEquals("B201", slots[0].room)
        assertEquals(1, slots[0].week)
        // 8:00 上课 - 10 分钟 = 7:50 提醒
        val trigger = java.time.Instant.ofEpochMilli(slots[0].triggerAt)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(LocalDateTime.of(2026, 9, 7, 7, 50), trigger)
    }

    @Test
    fun leadMinutesShiftTheTrigger() {
        val base = CourseReminder.upcoming(
            listOf(course()), sessions, termStart,
            LocalDateTime.of(2026, 9, 6, 20, 0), 2, leadMinutes = 10,
        ).single()
        val early = CourseReminder.upcoming(
            listOf(course()), sessions, termStart,
            LocalDateTime.of(2026, 9, 6, 20, 0), 2, leadMinutes = 20,
        ).single()
        assertEquals(10 * 60_000L, base.triggerAt - early.triggerAt)
        assertEquals(base.startAt, early.startAt)   // 上课时刻本身不受提前量影响
    }

    @Test
    fun coursesOutsideTheirWeeksAreSkipped() {
        // 只在第 3 周上：第 1 周的窗口里不该出现。
        val slots = CourseReminder.upcoming(
            courses = listOf(course(weeks = "3")),
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 6, 20, 0),
            days = 2,
        )
        assertTrue(slots.isEmpty())
    }

    @Test
    fun courseAppearsInItsOwnWeek() {
        // 第 3 周 = 9/7 起第 3 个周一 = 9/21。
        val slots = CourseReminder.upcoming(
            courses = listOf(course(weeks = "3")),
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 20, 20, 0),
            days = 2,
        )
        assertEquals(1, slots.size)
        assertEquals(3, slots[0].week)
    }

    @Test
    fun emptyWeeksMeansEveryWeek() {
        val slots = CourseReminder.upcoming(
            courses = listOf(course(weeks = "")),
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 6, 20, 0),
            days = 9,   // 跨一整周，下周一还会再响一次
        )
        assertEquals(2, slots.size)
    }

    @Test
    fun noTermStartStillReminds() {
        // 没设开学日 = 不知道第几周。宁可多提醒，不要漏。
        val slots = CourseReminder.upcoming(
            courses = listOf(course(weeks = "5")),
            sessions = sessions,
            termStart = null,
            from = LocalDateTime.of(2026, 9, 6, 20, 0),
            days = 2,
        )
        assertEquals(1, slots.size)
        assertNull(slots[0].week)
    }

    @Test
    fun stoppedWeekIsNotReminded() {
        val override = CourseOverride(courseId = 1L, week = 1, skip = true)
        val slots = CourseReminder.upcoming(
            courses = listOf(course()),
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 6, 20, 0),
            days = 2,
            overrides = listOf(override),
        )
        assertTrue(slots.isEmpty())
    }

    @Test
    fun movedCourseUsesTheMovedSession() {
        // 第 1 周把这门课挪到第 3-4 节（10:00 上课）。
        val override = CourseOverride(courseId = 1L, week = 1, sessionStart = 3, sessionEnd = 4)
        val slot = CourseReminder.upcoming(
            courses = listOf(course()),
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 6, 20, 0),
            days = 2,
            overrides = listOf(override),
        ).single()
        assertEquals(3, slot.sessionStart)
        val trigger = java.time.Instant.ofEpochMilli(slot.triggerAt)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        assertEquals(LocalDateTime.of(2026, 9, 7, 9, 50), trigger)
    }

    @Test
    fun overrideForAnotherWeekIsIgnored() {
        val override = CourseOverride(courseId = 1L, week = 2, skip = true)
        val slots = CourseReminder.upcoming(
            courses = listOf(course()),
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 6, 20, 0),
            days = 2,
            overrides = listOf(override),
        )
        assertEquals(1, slots.size)   // 第 1 周不受第 2 周的例外影响
    }

    @Test
    fun pastSlotsAreNotReturned() {
        // 周三中午看周一早上的课：已经过去了，不该排。
        val slots = CourseReminder.upcoming(
            courses = listOf(course()),
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 9, 12, 0),
            days = 2,
        )
        assertTrue(slots.isEmpty())
    }

    @Test
    fun slotsAreSortedByTrigger() {
        val a = course(id = 1L, title = "早课", day = 2, from = 1)
        val b = course(id = 2L, title = "午课", day = 1, from = 5)   // 周一 14:00
        val slots = CourseReminder.upcoming(
            courses = listOf(a, b),
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 6, 20, 0),
            days = 3,
        )
        assertEquals(2, slots.size)
        assertEquals("午课", slots[0].title)      // 周一 14:00 在周二 8:00 之前
        assertEquals("早课", slots[1].title)
    }

    @Test
    fun maxCapsTheList() {
        val many = (1..40).map { course(id = it.toLong(), title = "课$it", day = 1) }
        val slots = CourseReminder.upcoming(
            courses = many,
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 6, 20, 0),
            days = 14,
            max = 10,
        )
        assertEquals(10, slots.size)
    }

    @Test
    fun weekIndexOfCountsFromTermStart() {
        assertEquals(1, CourseReminder.weekIndexOf(termStart, LocalDate.of(2026, 9, 7)))
        assertEquals(1, CourseReminder.weekIndexOf(termStart, LocalDate.of(2026, 9, 13)))   // 周日仍第 1 周
        assertEquals(2, CourseReminder.weekIndexOf(termStart, LocalDate.of(2026, 9, 14)))
        assertEquals(11, CourseReminder.weekIndexOf(termStart, LocalDate.of(2026, 11, 16)))
        assertNull(CourseReminder.weekIndexOf(termStart, LocalDate.of(2026, 8, 1)))        // 开学前
        assertNull(CourseReminder.weekIndexOf(null, LocalDate.of(2026, 9, 7)))
    }

    @Test
    fun sessionTextHandlesSingleAndRange() {
        val s = CourseReminder.Slot(1L, "课", "N1", 5, 6, 1, 1, 0L, 0L)
        assertEquals("第 5-6 节", s.sessionText())
        val one = CourseReminder.Slot(1L, "课", "N1", 5, 5, 1, 1, 0L, 0L)
        assertEquals("第 5 节", one.sessionText())
    }

    @Test
    fun whenTextReadsLikeAStudentWouldSayIt() {
        val slot = CourseReminder.upcoming(
            listOf(course()), sessions, termStart,
            LocalDateTime.of(2026, 9, 6, 20, 0), 2,
        ).single()
        assertEquals("周一 8:00", CourseReminder.whenText(slot))
    }

    @Test
    fun emptyCourseListReturnsNothing() {
        assertTrue(
            CourseReminder.upcoming(
                emptyList(), sessions, termStart, LocalDateTime.of(2026, 9, 6, 20, 0), 14,
            ).isEmpty()
        )
    }

    @Test
    fun zeroDaysReturnsNothing() {
        assertTrue(
            CourseReminder.upcoming(
                listOf(course()), sessions, termStart, LocalDateTime.of(2026, 9, 6, 20, 0), 0,
            ).isEmpty()
        )
    }

    @Test
    fun sessionIndexOutOfRangeIsSkipped() {
        // 作息表只有 12 节，但课写的是第 15 节：算不出上课时刻就跳过，不能崩。
        val slots = CourseReminder.upcoming(
            courses = listOf(course(from = 15, to = 16)),
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 6, 20, 0),
            days = 2,
        )
        assertTrue(slots.isEmpty())
    }

    @Test
    fun leadOptionsIncludeZeroAndLabelsReadNaturally() {
        assertEquals(listOf(0, 5, 10, 20), CourseReminder.LEAD_OPTIONS)
        assertEquals("准时", CourseReminder.leadLabel(0))
        assertEquals("提前 5 分钟", CourseReminder.leadLabel(5))
        assertEquals("提前 20 分钟", CourseReminder.leadLabel(20))
    }

    @Test
    fun zeroLeadTriggersAtClassTimeExactly() {
        val slots = CourseReminder.upcoming(
            courses = listOf(course(from = 1, to = 2)),
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 7, 0, 0),
            days = 1,
            leadMinutes = 0,
        )
        assertEquals(1, slots.size)
        assertEquals(slots[0].startAt, slots[0].triggerAt)
    }
    @Test
    fun perCourseLeadOverridesGlobalTriggerTime() {
        // 同一门课设了提前 20 分钟：触发时刻应该比全局 10 分钟早 10 分钟。
        val own = course(id = 2L).copy(leadMinutes = 20)
        val globalOnly = course(id = 2L).copy(leadMinutes = CourseLead.USE_GLOBAL)
        val ownSlots = CourseReminder.upcoming(
            courses = listOf(own),
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 6, 20, 0),
            days = 2,
            leadMinutes = 10,
        )
        val globalSlots = CourseReminder.upcoming(
            courses = listOf(globalOnly),
            sessions = sessions,
            termStart = termStart,
            from = LocalDateTime.of(2026, 9, 6, 20, 0),
            days = 2,
            leadMinutes = 10,
        )
        assertEquals(1, ownSlots.size)
        assertEquals(20, ownSlots[0].leadMinutes)
        assertEquals(10, globalSlots[0].leadMinutes)
        assertEquals(10 * 60_000L, globalSlots[0].triggerAt - ownSlots[0].triggerAt)
    }

}
