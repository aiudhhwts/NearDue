package com.neardue.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * v1.35「上次备份」的文案计算。
 *
 * 纯函数，不碰 Context / 时钟 / 时区 —— 今天和时区都由调用方传进来，
 * 这样单测可以钉死任意一天，不用去 mock 系统时间。
 */
object BackupStamp {

    /** 设置页「导出 / 导入」那行的副标题。 */
    fun desc(lastAt: Long, today: LocalDate, zone: ZoneId): String {
        if (lastAt <= 0L) return "本地 JSON，还没备份过"
        val day = Instant.ofEpochMilli(lastAt).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(day, today)
        val whenText = when {
            // 时钟回拨 / 未来时间（换机改时间常见）也算「今天」，不显示「−3 天前」这种鬼东西。
            days <= 0L -> "今天"
            days == 1L -> "昨天"
            days < 30L -> "$days 天前"
            else -> "${day.monthValue} 月 ${day.dayOfMonth} 日"
        }
        return "本地 JSON · 上次备份 $whenText"
    }
}
