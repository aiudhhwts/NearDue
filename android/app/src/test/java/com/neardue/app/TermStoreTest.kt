package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 学期命名的纯逻辑（不碰 Android Context，所以能在 JVM 里跑）。
 *
 * 数据库那一层（TermStore 的增删改）要 Context，交给真机验证；
 * 这里只钉住「名字怎么起」，因为它是用户第一眼看到的东西。
 */
class TermStoreTest {

    @Test
    fun springForFebruaryToJuly() {
        for (month in 2..7) {
            val d = LocalDate.of(2027, month, 1)
            assertEquals("2027 春季学期", TermStore.defaultNameFor(d))
        }
    }

    @Test
    fun autumnForAugustToJanuary() {
        for (month in listOf(8, 9, 10, 11, 12, 1)) {
            val d = LocalDate.of(2026, month, 1)
            assertEquals("2026 秋季学期", TermStore.defaultNameFor(d))
        }
    }

    @Test
    fun noStartDateFallsBackToGenericName() {
        assertEquals("新课表", TermStore.defaultNameFor(null))
    }

    @Test
    fun subtitleSaysWhatIsMissing() {
        val bare = Term(name = "2026 秋季学期")
        assertEquals("还没设开学日 · 课表不显示周次", bare.subtitle())
    }

    @Test
    fun subtitleShowsFirstWeekDate() {
        val t = Term(name = "X", startMonday = LocalDate.of(2026, 9, 7))
        assertEquals("第 1 周从 9月7日 起", t.subtitle())
    }

    @Test
    fun termJsonRoundTrip() {
        val t = Term(name = "2027 春季学期", startMonday = LocalDate.of(2027, 2, 22), createdAt = 123L)
        val back = termFromJson(t.toJson())
        assertEquals(t.name, back.name)
        assertEquals(t.startMonday, back.startMonday)
    }

    @Test
    fun termJsonKeepsNullStart() {
        val back = termFromJson(Term(name = "新课表").toJson())
        assertEquals(null, back.startMonday)
    }
}
