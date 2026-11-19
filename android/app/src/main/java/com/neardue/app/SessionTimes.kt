package com.neardue.app

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 课表作息：一节课 = **一段时间** `[start, end)`（分钟），不是「8:00 这一个点」。
 *
 * WakeUp 的做法（官方课表设置）：每节单独设上课/下课时刻，勾上「每节课时长相同」
 * 就按统一时长算，取消勾选就能逐节改。将至沿用这套模型，但维持「不进备份、
 * 不绑学期」的原则——作息是设备/用户习惯，不是课程数据。
 *
 * 存储兼容：老格式是「每节开始时刻」`8:0,8:55,10:0,...`，读取时按默认时长
 * （下一节开始−本节开始，末节 45 分钟）补出结束时刻，用户无感。
 */
object SessionTimes {

    /** 一节课的起止时刻（分钟，0..1440）。 */
    data class Slot(val start: Int, val end: Int) {
        val duration: Int get() = end - start
        fun startText(): String = fmt(start)
        fun endText(): String = fmt(end)
        fun rangeText(): String = "${startText()}-${endText()}"
        companion object {
            fun fmt(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
        }
    }

    /** 默认统一时长 45 分钟。 */
    const val DEFAULT_DURATION = 45

    /** 把老的「开始时刻列表」升成起止段：下一节开始−本节开始，末节用默认时长。 */
    fun fromStarts(starts: List<Pair<Int, Int>>, defaultDuration: Int = DEFAULT_DURATION): List<Slot> {
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { i, (h, m) ->
            val start = (h * 60 + m).coerceIn(0, 1439)
            val end = if (i + 1 < starts.size) {
                val next = starts[i + 1].first * 60 + starts[i + 1].second
                if (next > start) next else (start + defaultDuration).coerceAtMost(1440)
            } else {
                (start + defaultDuration).coerceAtMost(1440)
            }
            Slot(start, end)
        }
    }

    /** 从存储文本读：`8:0-8:45,10:0-10:45`（新）或 `8:0,8:55`（老）。 */
    fun parse(text: String?): List<Slot>? {
        if (text.isNullOrBlank()) return null
        val parts = text.split(',')
        val hasRanges = text.contains('-')
        val parsed = parts.mapNotNull { part ->
            val p = part.trim()
            if (hasRanges) {
                val seg = p.split('-', limit = 2)
                if (seg.size != 2) return@mapNotNull null
                val s = hm(seg[0]) ?: return@mapNotNull null
                val e = hm(seg[1]) ?: return@mapNotNull null
                if (e <= s) return@mapNotNull null
                Slot(s, e)
            } else {
                hm(p)?.let { Slot(it, (it + DEFAULT_DURATION).coerceAtMost(1440)) }
            }
        }
        if (parsed.isEmpty() || parsed.size != parts.size) return null
        return if (hasRanges) parsed else fromStarts(parsed.map { it.start / 60 to it.start % 60 })
    }

    /** 统一时长：把每节都改成 [duration] 分钟（从原开始时刻起算）。 */
    fun uniform(slots: List<Slot>, duration: Int): List<Slot> {
        val d = duration.coerceIn(5, 180)
        return slots.map { Slot(it.start, (it.start + d).coerceAtMost(1440)) }
    }

    /** 单独改某一节的结束时刻；开始不动，结束必须晚于开始。 */
    fun withEnd(slots: List<Slot>, index: Int, end: Int): List<Slot> {
        if (index !in slots.indices) return slots
        val s = slots[index]
        if (end <= s.start || end > 1440) return slots
        return slots.toMutableList().also { it[index] = Slot(s.start, end) }
    }

    /** 存成文本。新格式 `8:0-8:45,10:0-10:45`。 */
    fun format(slots: List<Slot>): String = slots.joinToString(",") {
        "${hmText(it.start)}-${hmText(it.end)}"
    }

    /** 某分钟在第几节（1 起），不在任何节内返回 null。 */
    fun slotAt(minute: Int, slots: List<Slot>): Int? =
        slots.indexOfFirst { minute in it.start until it.end }.takeIf { it >= 0 }?.plus(1)

    private fun hm(s: String): Int? {
        val p = s.trim().split(':')
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    private fun hmText(min: Int): String = "${min / 60}:${min % 60}"
}

/**
 * 删除课程的范围（WakeUp「删除时询问时间范围」）：整学期 / 仅本周 / 本周起以后。
 *
 * 「以后」对应的是**周次边界**：删掉第 N 周起的课，等价于把周次规格收窄到 `< N`。
 * 这是纯规格运算，不动数据库——调用方拿到新规格后 save 原课程即可。
 */
object CourseDeletion {

    enum class Scope { ALL, THIS_WEEK, FROM_THIS_WEEK }

    /**
     * 按 [scope] 算出删除后剩下的周次规格（空串 = 全删）。
     * @param original 原周次规格；空 = 每周（整学期）。
     * @param week 当前周（THIS_WEEK / FROM_THIS_WEEK 时用）。
     */
    fun remainingSpec(original: String, scope: Scope, week: Int): String {
        if (scope == Scope.ALL) return ""
        if (week < 1) return CourseWeeks.normalize(original)
        val weeks = CourseWeeks.parse(original).toSet()
        val kept = when (scope) {
            Scope.THIS_WEEK -> weeks - week
            Scope.FROM_THIS_WEEK -> weeks.filter { it < week }.toSet()
            Scope.ALL -> emptySet()
        }
        if (kept.isEmpty()) return ""
        if (kept == weeks) return CourseWeeks.normalize(original)
        val sorted = kept.sorted()
        val parts = mutableListOf<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            parts += if (j - i >= 2) "${sorted[i]}-${sorted[j]}" else
                (i..j).joinToString(",") { sorted[it].toString() }
            i = j + 1
        }
        return CourseWeeks.normalize(parts.joinToString(","))
    }

    /** 提示文案：让用户知道删掉的是哪一周、还剩几周。 */
    fun summary(scope: Scope, week: Int, remaining: String): String = when (scope) {
        Scope.ALL -> "整学期删除，这门课的所有周次都会移除"
        Scope.THIS_WEEK -> if (remaining.isEmpty()) "只删第 $week 周，这门课没有别的周次了"
        else "只删第 $week 周，其余周次保留"
        Scope.FROM_THIS_WEEK -> if (remaining.isEmpty()) "删掉第 $week 周及以后，前面没有课了"
        else "删掉第 $week 周及以后，前面的周次保留"
    }
}

/**
 * 学期状态提示：开学日设错、翻到学期外、还没设开学日，都给用户一句明白话。
 */
object TermStatus {

    enum class Kind { NONE, TOO_EARLY, TOO_LATE, OK }

    data class Status(val kind: Kind, val message: String)

    /**
     * @param termStart 第 1 周周一；null = 没设开学日。
     * @param lastWeek 有课的最后一期；null = 不知道，按 16 周算。
     * @param viewingWeek 当前翻到第几周。
     */
    fun of(termStart: LocalDate?, lastWeek: Int?, viewingWeek: Int, today: LocalDate): Status {
        if (termStart == null) return Status(Kind.NONE, "还没设开学日 · 不显示第几周，去设置里填一下")
        if (viewingWeek < 1) {
            val days = ChronoUnit.DAYS.between(termStart, today)
            return Status(
                Kind.TOO_EARLY,
                if (days < 0) "学期还没开始 · 还有 ${-days} 天开学" else "这一周在开学前",
            )
        }
        val end = lastWeek ?: 16
        if (viewingWeek > end) {
            return Status(Kind.TOO_LATE, "学期已结束 · 当前是第 $viewingWeek 周，课程只排到第 $end 周")
        }
        return Status(Kind.OK, "")
    }
}
