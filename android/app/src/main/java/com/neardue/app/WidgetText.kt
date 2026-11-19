package com.neardue.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// 小组件的纯文本逻辑：不碰 Android，单测能直接跑。
object WidgetText {
    /** 一行事项：「N天  标题」/「逾期  标题」。今天 = 「今天  标题」。 */
    fun line(item: Item, today: LocalDate): String {
        val d = ChronoUnit.DAYS.between(
            today,
            Instant.ofEpochMilli(item.dueAt).atZone(ZoneId.systemDefault()).toLocalDate()
        )
        return if (d < 0) "逾期  ${item.title}" else "${d}天  ${item.title}"
    }

    /**
     * 聚合条：未来 7 天内的考试 + 比赛场数。
     * 没有就是 null（视图直接隐藏），口径写进文案，不叫含糊的「考试周」。
     */
    fun examBar(pending: List<Item>, nowMillis: Long): String? {
        val end = nowMillis + 7L * 24 * 60 * 60 * 1000
        val n = pending.count { item ->
            !item.done &&
                (item.category == Category.EXAM || item.category == Category.CONTEST) &&
                item.dueAt in nowMillis..end
        }
        return if (n == 0) null else "未来 7 天 · $n 场考试/比赛"
    }

    /** 小组件字号档位：0 小 / 1 中（默认）/ 2 大。单位 sp，[主行, 辅行]。 */
    fun widgetSp(level: Int): IntArray = when (level.coerceIn(0, 2)) {
        0 -> intArrayOf(12, 11)
        2 -> intArrayOf(16, 14)
        else -> intArrayOf(14, 12)
    }

    /** 档位显示文案：设置页用。 */
    fun widgetSizeLabel(level: Int): String = when (level.coerceIn(0, 2)) {
        0 -> "小"
        2 -> "大"
        else -> "中"
    }
}
