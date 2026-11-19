package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleExtractTest {
    private val raw = "物理期末考试 11.20 晚点名 带资料"
    private val clean = "物理期末考试 晚点名 带资料"

    @Test fun externalTextKeepsRawOnSave() {
        assertEquals(raw, TitleExtract.finalTitle(clean, raw, true, fromExternal = true, manual = false))
    }

    @Test fun extractButtonSuggestsCleanName() {
        assertEquals(clean, TitleExtract.suggestion(clean, raw))
    }

    @Test fun noSuggestionWhenNothingToClean() {
        assertNull(TitleExtract.suggestion(raw, raw))
        assertNull(TitleExtract.suggestion("", raw))
    }

    @Test fun afterExplicitExtractSaveUsesCleanName() {
        assertEquals(clean, TitleExtract.finalTitle(clean, clean, true, fromExternal = false, manual = false))
    }

    @Test fun handTypedOneLinerStillAutoCleans() {
        assertEquals(clean, TitleExtract.finalTitle(clean, raw, true, fromExternal = false, manual = false))
    }

    @Test fun manualEditAlwaysKeepsWhatUserWrote() {
        assertEquals(raw, TitleExtract.finalTitle(clean, raw, true, fromExternal = false, manual = true))
    }

    @Test fun unmatchedExternalTextNeverGetsInventedName() {
        assertEquals(raw, TitleExtract.finalTitle(raw, raw, false, fromExternal = true, manual = false))
    }
}
