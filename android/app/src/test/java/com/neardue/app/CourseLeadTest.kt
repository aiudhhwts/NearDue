package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseLeadTest {

    @Test
    fun normalizeAcceptsOnlyTheFourOptions() {
        assertEquals(CourseLead.USE_GLOBAL, CourseLead.normalize(-1))
        assertEquals(0, CourseLead.normalize(0))
        assertEquals(5, CourseLead.normalize(5))
        assertEquals(10, CourseLead.normalize(10))
        assertEquals(20, CourseLead.normalize(20))
        assertEquals(CourseLead.USE_GLOBAL, CourseLead.normalize(3))
        assertEquals(CourseLead.USE_GLOBAL, CourseLead.normalize(999))
    }

    @Test
    fun courseOwnLeadOverridesGlobal() {
        assertEquals(20, CourseLead.effective(20, 10))
        assertEquals(0, CourseLead.effective(0, 10))
        assertEquals(5, CourseLead.effective(5, 20))
    }

    @Test
    fun unspecifiedLeadFollowsGlobal() {
        assertEquals(10, CourseLead.effective(-1, 10))
        assertEquals(20, CourseLead.effective(-1, 20))
        assertEquals(0, CourseLead.effective(-1, 0))
    }

    @Test
    fun badGlobalFallsBackToDefault() {
        assertEquals(CourseReminder.DEFAULT_LEAD, CourseLead.effective(-1, 7))
        assertEquals(CourseReminder.DEFAULT_LEAD, CourseLead.effective(-1, -5))
    }

    @Test
    fun optionsIncludeGlobalAndFourChoices() {
        assertEquals(listOf(-1, 0, 5, 10, 20), CourseLead.options())
        assertTrue(CourseLead.options().contains(CourseLead.USE_GLOBAL))
    }

    @Test
    fun labelsReadNaturally() {
        assertEquals("跟全局（提前 10 分钟）", CourseLead.label(-1, 10))
        assertEquals("准时", CourseLead.label(0, 10))
        assertEquals("提前 20 分钟", CourseLead.label(20, 10))
    }
}
