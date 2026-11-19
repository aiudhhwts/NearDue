package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.29：作息按学期隔离的纯逻辑回归。 */
class TermScheduleTest {

    @Test
    fun keysAreDistinctPerTerm() {
        assertEquals("session_times", TermSchedule.slotsKey(0L))
        assertEquals("session_times_7", TermSchedule.slotsKey(7L))
        assertEquals("session_uniform_7", TermSchedule.uniformKey(7L))
    }

    @Test
    fun resolveFallsBackToGlobal() {
        assertEquals("G", TermSchedule.resolve(null, "G"))
        assertEquals("O", TermSchedule.resolve("O", "G"))
        assertNull(TermSchedule.resolve(null, null))
    }

    @Test
    fun resolveUniformDefaultsTrue() {
        assertEquals(true, TermSchedule.resolveUniform(null, null))
        assertEquals(false, TermSchedule.resolveUniform(false, true))
        assertEquals(true, TermSchedule.resolveUniform(true, false))
    }
}
