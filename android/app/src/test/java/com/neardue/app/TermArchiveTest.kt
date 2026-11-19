package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermArchiveTest {

    private fun term(id: Long, archived: Boolean = false) =
        Term(id = id, name = "学期$id", archived = archived)

    @Test
    fun activeAndArchivedSplit() {
        val terms = listOf(term(1), term(2, archived = true), term(3))
        assertEquals(listOf(1L, 3L), TermArchive.active(terms).map { it.id })
        assertEquals(listOf(2L), TermArchive.archived(terms).map { it.id })
    }

    @Test
    fun cannotArchiveTheOnlyActiveTerm() {
        val only = listOf(term(1))
        assertFalse(TermArchive.canArchive(only[0], only))
    }

    @Test
    fun canArchiveWhenAnotherActiveRemains() {
        val terms = listOf(term(1), term(2))
        assertTrue(TermArchive.canArchive(terms[0], terms))
        assertTrue(TermArchive.canArchive(terms[1], terms))
    }

    @Test
    fun cannotArchiveAnAlreadyArchivedTerm() {
        val terms = listOf(term(1), term(2, archived = true))
        assertFalse(TermArchive.canArchive(terms[1], terms))
    }

    @Test
    fun effectiveCurrentFallsBackWhenCurrentArchived() {
        val terms = listOf(term(1), term(2, archived = true), term(3))
        assertEquals(1L, TermArchive.effectiveCurrent(terms, 2L))
        assertEquals(3L, TermArchive.effectiveCurrent(terms, 3L))
        assertEquals(1L, TermArchive.effectiveCurrent(terms, 99L))
    }

    @Test
    fun effectiveCurrentIsNullWhenAllArchived() {
        assertNull(TermArchive.effectiveCurrent(listOf(term(1, archived = true)), 1L))
    }

    @Test
    fun hintCountsArchived() {
        assertEquals("已归档 2 个学期（课都在，随时可取出）", TermArchive.archiveHint(2))
    }
}
