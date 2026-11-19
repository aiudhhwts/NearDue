package com.neardue.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WidgetTextTest {
    private val today = LocalDate.of(2026, 11, 16)
    private fun millis(daysFromToday: Long): Long = today.plusDays(daysFromToday)
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun lineShowsDaysUntil() {
        val item = Item(title = "线代作业", dueAt = millis(3), category = Category.HOMEWORK)
        assertEquals("3天  线代作业", WidgetText.line(item, today))
    }

    @Test fun lineShowsOverdue() {
        val item = Item(title = "过期的事", dueAt = millis(-1))
        assertEquals("逾期  过期的事", WidgetText.line(item, today))
    }

    @Test fun examBarCountsExamsAndContestsWithinSevenDays() {
        val now = millis(0)
        val items = listOf(
            Item(title = "高数期中", dueAt = now + 2 * 86_400_000L, category = Category.EXAM),
            Item(title = "ACM", dueAt = now + 5 * 86_400_000L, category = Category.CONTEST),
        )
        assertEquals("未来 7 天 · 2 场考试/比赛", WidgetText.examBar(items, now))
    }

    @Test fun examBarIgnoresBeyondSevenDaysAndOtherCategories() {
        val now = millis(0)
        val items = listOf(
            Item(title = "远期考试", dueAt = now + 8 * 86_400_000L, category = Category.EXAM),
            Item(title = "作业", dueAt = now + 86_400_000L, category = Category.HOMEWORK),
        )
        assertNull(WidgetText.examBar(items, now))
    }

    @Test fun examBarIgnoresDoneItems() {
        val now = millis(0)
        val items = listOf(
            Item(title = "已考完", dueAt = now + 86_400_000L, category = Category.EXAM, done = true)
        )
        assertNull(WidgetText.examBar(items, now))
    }

    @Test fun widgetSpDefaultsToMedium() {
        assertArrayEquals(intArrayOf(14, 12), WidgetText.widgetSp(1))
    }

    @Test fun widgetSpSmallAndLarge() {
        assertArrayEquals(intArrayOf(12, 11), WidgetText.widgetSp(0))
        assertArrayEquals(intArrayOf(16, 14), WidgetText.widgetSp(2))
    }

    @Test fun widgetSpClampsOutOfRange() {
        assertArrayEquals(intArrayOf(12, 11), WidgetText.widgetSp(-1))
        assertArrayEquals(intArrayOf(16, 14), WidgetText.widgetSp(99))
    }

    @Test fun widgetSizeLabels() {
        assertEquals("小", WidgetText.widgetSizeLabel(0))
        assertEquals("中", WidgetText.widgetSizeLabel(1))
        assertEquals("大", WidgetText.widgetSizeLabel(2))
    }
}
