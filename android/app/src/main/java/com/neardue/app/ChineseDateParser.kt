package com.neardue.app

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * A reminder parsed from a free-form Chinese string.
 *
 * @property title text with every recognised date/time fragment removed; falls back to the
 *   raw trimmed input when empty.
 * @property dueAt epoch millis of LocalDateTime.of(date, time) in ZoneId.systemDefault().
 * @property categoryOrdinal ordinal of the app's category enum:
 *   EXAM=0, HOMEWORK=1, CONTEST=2, EVENT=3, OTHER=4.
 * @property matched true only when an explicit DATE was recognised.
 */
data class ParsedReminder(
    val title: String,
    val dueAt: Long,
    val categoryOrdinal: Int,
    val matched: Boolean
)

/**
 * Offline, deterministic, rule-based parser for Chinese deadline phrases. Pure Kotlin +
 * java.time; no Android, JSON, or project-class dependencies, so it compiles in a plain JVM
 * unit test.
 *
 * Caller contract: when no date is recognised, parse() returns matched=false together with a
 * placeholder dueAt (today at the default time). The caller (MainActivity) uses that flag to
 * fall back to a manual date picker and MUST NOT silently save that guessed moment - a note
 * with no date must never be persisted with an invented time. For matched results an explicit
 * time always wins over defaultHour/defaultMinute.
 */
object ChineseDateParser {

    /** 11月20日 / 12月14号 / 九月一日 / 十二月三十一日 / 10月十号 / 九月1日. */
    private val MONTH_DAY_CN = Regex("""([0-9零一二三四五六七八九十两廿]{1,3})月([0-9零一二三四五六七八九十两廿]{1,3})[日号]""")

    /** 11.20 / 11-20 / 11/20. */
    private val MONTH_DAY_NUM = Regex("""([0-9]{1,2})[./-]([0-9]{1,2})""")

    /** 今天 明日 明天 后天 大后天 - longest forms first. */
    private val RELATIVE_DAY = Regex("""大后天|今天|明日|明天|后天""")

    /** 3天后 / 三天后 / 廿天后 (Arabic or Chinese numeral). */
    private val N_DAYS = Regex("""([0-9]+|[零一二三四五六七八九十两廿]+)天后""")

    /** 下周三 本周日 星期一 周日 礼拜天 ... */
    private val WEEKDAY = Regex("""(下星期|本星期|下周|本周|星期|周|礼拜)([一二三四五六日天])""")

    // 1 = optional period word, 2/3 = HH:mm, 4 = H点, 5 = 半
    private val CLOCK = Regex(
        """(上午|下午|晚上|中午|早上|凌晨)?(?:([0-9]{1,2}):([0-9]{2})|([0-9]{1,2})点(半)?)"""
    )

    /** 中午 standing alone (no number) means 12:00. */
    private val BARE_NOON = Regex("""中午""")

    private val TITLE_SEPARATORS = charArrayOf(
        ' ', '·', '-', '–', '—', ',', '，', '、', '：', ':', '.', '/', ';', '；', '~', '～'
    )

    /** Keyword tables in strict priority order: EXAM, CONTEST, HOMEWORK, EVENT. */
    private val CATEGORY_RULES: List<Pair<Int, List<String>>> = listOf(
        0 to listOf("考试", "期中", "期末", "四级", "六级", "考研", "雅思", "托福", "测验", "模考"),
        2 to listOf("竞赛", "比赛", "大赛", "报名", "挑战杯", "建模", "答辩"),
        1 to listOf("作业", "论文", "报告", "习题", "提交", "交", "ddl", "homework", "hw", "实验"),
        3 to listOf("训练", "活动", "聚会", "讲座", "会议", "运动会", "面试", "排练")
    )

    fun parse(
        input: String,
        nowMillis: Long = System.currentTimeMillis(),
        defaultHour: Int = 19,
        defaultMinute: Int = 0
    ): ParsedReminder {
        val trimmed = input.trim()
        val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val category = detectCategory(trimmed)

        // Blank input: nothing to guess, nothing to save.
        if (trimmed.isEmpty()) {
            return ParsedReminder("", epochMillis(today, defaultHour, defaultMinute), category, false)
        }

        val occupied = mutableListOf<IntRange>()
        val dateHits = mutableListOf<Pair<IntRange, LocalDate>>()
        val timeHits = mutableListOf<Pair<IntRange, Pair<Int, Int>>>()

        // Time first, so 下午3点 is claimed as one span (not 下午 + 3点).
        scan(CLOCK, trimmed, occupied, timeHits) { extractClock(it) }
        scan(BARE_NOON, trimmed, occupied, timeHits) { 12 to 0 }

        scan(MONTH_DAY_CN, trimmed, occupied, dateHits) {
            resolveMonthDay(today, it.groupValues[1], it.groupValues[2])
        }
        scan(MONTH_DAY_NUM, trimmed, occupied, dateHits) {
            resolveMonthDay(today, it.groupValues[1], it.groupValues[2])
        }
        scan(RELATIVE_DAY, trimmed, occupied, dateHits) {
            today.plusDays(relativeOffset(it.value).toLong())
        }
        scan(N_DAYS, trimmed, occupied, dateHits) {
            chineseNumeral(it.groupValues[1])?.let { n -> today.plusDays(n.toLong()) }
        }
        scan(WEEKDAY, trimmed, occupied, dateHits) {
            resolveWeekday(today, it.groupValues[1], it.groupValues[2])
        }

        // No date -> placeholder only; caller MUST fall back to a manual date picker.
        if (dateHits.isEmpty()) {
            return ParsedReminder(
                title = trimmed,
                dueAt = epochMillis(today, defaultHour, defaultMinute),
                categoryOrdinal = category,
                matched = false
            )
        }

        val date = dateHits.minByOrNull { it.first.first }!!.second
        val (hour, minute) = timeHits.minByOrNull { it.first.first }?.second
            ?: (defaultHour to defaultMinute)
        val stripped = buildTitle(trimmed, dateHits.map { it.first } + timeHits.map { it.first })

        return ParsedReminder(
            title = stripped,
            dueAt = epochMillis(date, hour, minute),
            categoryOrdinal = category,
            matched = true
        )
    }

    /** Finds non-overlapping matches of regex, extracting a value for each occupied span. */
    private fun <T> scan(
        regex: Regex,
        text: String,
        occupied: MutableList<IntRange>,
        out: MutableList<Pair<IntRange, T>>,
        extract: (MatchResult) -> T?
    ) {
        for (m in regex.findAll(text)) {
            if (occupied.any { it.first <= m.range.last && m.range.first <= it.last }) continue
            val value = extract(m) ?: continue
            occupied.add(m.range)
            out.add(m.range to value)
        }
    }

    /** M月d日 / M.d / M-d / M/d / 中文数字月日; rolls to next year when already past. */
    private fun resolveMonthDay(today: LocalDate, mStr: String, dStr: String): LocalDate? {
        val month = numeral(mStr) ?: return null
        val day = numeral(dStr) ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        val base = try {
            LocalDate.of(today.year, month, day)
        } catch (e: DateTimeException) {
            return null   // e.g. 2月30日
        }
        return if (base.isBefore(today)) base.withYear(today.year + 1) else base
    }

    private fun relativeOffset(word: String): Int = when (word) {
        "今天" -> 0
        "明日", "明天" -> 1
        "后天" -> 2
        "大后天" -> 3
        else -> 0
    }

    /** Bare weekday = strictly after today; 本周X = coming X; 下周X = next week's X. */
    private fun resolveWeekday(today: LocalDate, prefix: String, dayChar: String): LocalDate? {
        val target = when (dayChar) {
            "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6
            "日", "天" -> 7
            else -> return null
        }
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return when (prefix) {
            "下周", "下星期" -> monday.plusWeeks(1L).plusDays((target - 1).toLong())
            "本周", "本星期" -> {
                val d = monday.plusDays((target - 1).toLong())
                if (d.isBefore(today)) d.plusWeeks(1L) else d
            }
            else -> {
                val delta = (target - today.dayOfWeek.value + 7) % 7
                today.plusDays((if (delta == 0) 7 else delta).toLong())
            }
        }
    }

    /** Arabic digits or a small Chinese numeral (一..十, 两, 廿, 十X, X十Y, 廿X). */
    private fun chineseNumeral(s: String): Int? {
        if (s.isEmpty()) return null
        if (s.all { it in '0'..'9' }) return s.toIntOrNull()
        val digit = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
            '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        var total = 0
        var cur = 0
        var any = false
        for (c in s) {
            when {
                c in digit -> { cur = digit.getValue(c); any = true }
                c == '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0; any = true }
                c == '廿' -> { total += 20; cur = 0; any = true }
                else -> return null
            }
        }
        return if (any) total + cur else null
    }

    /** Arabic digits first, then Chinese numeral (reuses the same table as N天后). */
    private fun numeral(s: String): Int? = s.toIntOrNull() ?: chineseNumeral(s)

    private fun extractClock(m: MatchResult): Pair<Int, Int>? {
        val hour: Int
        val minute: Int
        if (m.groupValues[2].isNotEmpty()) {
            hour = m.groupValues[2].toInt()
            minute = m.groupValues[3].toInt()
        } else {
            hour = m.groupValues[4].toInt()
            minute = if (m.groupValues[5].isNotEmpty()) 30 else 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return applyPeriod(m.groupValues[1].takeIf { it.isNotEmpty() }, hour) to minute
    }

    /** 下午/晚上 shift into PM; 中午 is 12:00; others (上午/早上/凌晨/none) keep the hour. */
    private fun applyPeriod(period: String?, hour: Int): Int = when (period) {
        "下午", "晚上" -> if (hour < 12) hour + 12 else hour
        "中午" -> 12
        "凌晨" -> if (hour == 12) 0 else hour
        else -> hour
    }

    /** Removes matched spans, collapses whitespace, trims decorative separators. */
    private fun buildTitle(text: String, ranges: List<IntRange>): String {
        val sb = StringBuilder(text)
        for (r in ranges.sortedByDescending { it.first }) {
            sb.replace(r.first, r.last + 1, " ")
        }
        var t = sb.toString().replace(Regex("""\s+"""), " ").trim()
        t = t.trim(*TITLE_SEPARATORS)
        return t.ifEmpty { text.trim() }
    }

    private fun detectCategory(text: String): Int {
        val lower = text.lowercase()
        for ((ordinal, words) in CATEGORY_RULES) {
            if (words.any { lower.contains(it) }) return ordinal
        }
        return 4
    }

    /** 只做分类识别，供课程表按课程名归类（不碰日期）。 */
    fun categoryOf(text: String): Int = detectCategory(text)

    private fun epochMillis(date: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(date, LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
