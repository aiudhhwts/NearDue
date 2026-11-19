package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class BackupStampTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 19)

    private fun at(y: Int, m: Int, d: Int, h: Int = 10): Long =
        LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun neverBackedUpSaysSo() {
        assertEquals("本地 JSON，还没备份过", BackupStamp.desc(0L, today, zone))
    }

    @Test
    fun sameDayIsToday() {
        assertEquals("本地 JSON · 上次备份 今天", BackupStamp.desc(at(2026, 11, 19), today, zone))
    }

    @Test
    fun yesterdayIsYesterday() {
        assertEquals("本地 JSON · 上次备份 昨天", BackupStamp.desc(at(2026, 11, 18), today, zone))
    }

    @Test
    fun fewDaysAgoCountsDays() {
        assertEquals("本地 JSON · 上次备份 3 天前", BackupStamp.desc(at(2026, 11, 16), today, zone))
        assertEquals("本地 JSON · 上次备份 29 天前", BackupStamp.desc(at(2026, 10, 21), today, zone))
    }

    @Test
    fun monthOrOlderShowsDate() {
        // 30 天前 = 10 月 20 日，改成写具体日期，比「30 天前」好读。
        assertEquals("本地 JSON · 上次备份 10 月 20 日", BackupStamp.desc(at(2026, 10, 20), today, zone))
        // 跨年级的老备份也只写日期。
        assertEquals("本地 JSON · 上次备份 3 月 5 日", BackupStamp.desc(at(2026, 3, 5), today, zone))
    }

    @Test
    fun futureTimestampFallsBackToToday() {
        // 用户改过系统时间，备份时间落在「明天」——不能显示成负天数。
        assertEquals("本地 JSON · 上次备份 今天", BackupStamp.desc(at(2026, 11, 25), today, zone))
    }
}
