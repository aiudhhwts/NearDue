package com.neardue.app

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 课程表 / 教学日历批量导入。
 *
 * 两条路径并存（v1.5 起）：
 *
 * 1. **带日期的行**（`高数期末 12月14日 上午9点`）—— 复用 [ChineseDateParser]，每行一个倒计时。
 *    这是 v1.1 就有的能力，保持不变。
 * 2. **周次课表行**（`周三 1-2节 高等数学 1-16周`）—— 参考 WakeUp 课程表的思路，用「第几周 +
 *    周几 + 节次」在学期日历上展开成每次课一个条目。它必须先知道**第 1 周的周一**，所以只有
 *    设置了开学日才会展开；没设置就计入 [Result.needTermStartLines] 提示用户去设，**绝不瞎猜**。
 *
 * 其余原则不变：没写日期也没写周次的行跳过并计数；同批与已有条目按「标题 + 时刻」去重；
 * 结果按时间升序。纯 Kotlin + java.time，不依赖 Android，可在 JVM 单测里直接跑。
 */
object CourseScheduleImporter {

    data class Result(
        /** 识别成功、已去重、按时间升序排序的条目。 */
        val items: List<Item>,
        /** 去掉空行后的总行数。 */
        val totalLines: Int,
        /** 没识别出任何时间信息（或标题被剥空）而跳过的行数。 */
        val skippedLines: Int,
        /** 与本批或已有条目重复而跳过的行数。 */
        val duplicateLines: Int,
        /** 「第几周 + 周几」展开出来的条目数（不是行数，一门课会展开成多条）。 */
        val expandedFromWeeks: Int = 0,
        /** 是周次课表行、但因为没设开学日期而跳过的行数。 */
        val needTermStartLines: Int = 0,
    )

    /**
     * 学期上下文。`startMonday` = 第 1 周的周一；传 null 表示用户还没设开学日。
     */
    data class Term(
        val startMonday: LocalDate,
        /** 第 n 节（1 起）的上课时刻。默认用常见高校作息。 */
        val sessionStarts: List<Pair<Int, Int>> = DEFAULT_SESSIONS,
        /** 展开上限，防御性：坏数据里出现「第 99 周」也不会炸出几百条。 */
        val maxWeek: Int = 25,
    )

    /** 「每周」这种没写具体周次的行只展开未来几周，避免一次倒进几百条把列表淹了。 */
    private const val OPEN_ENDED_WEEKS = 4

    /** 常见高校作息：第 n 节开始时刻（小时, 分钟），1 起。 */
    val DEFAULT_SESSIONS: List<Pair<Int, Int>> = listOf(
        8 to 0, 8 to 55, 10 to 0, 10 to 55,
        14 to 0, 14 to 55, 16 to 0, 16 to 55,
        19 to 0, 19 to 55, 20 to 50, 21 to 45,
    )

    /** 周次区间：1-16周 / 第1~16周 / 第 3 至 9 周。 */
    private val WEEK_RANGE = Regex("""(?:第)?\s*(\d{1,2})\s*[-~～至到]\s*(\d{1,2})\s*周""")

    /** 单个周次：第9周 / 9周。 */
    private val WEEK_SINGLE = Regex("""(?:第)?\s*(\d{1,2})\s*周""")

    /** 单双周：单周 / 双周 / 单数周 / 双数周。 */
    private val WEEK_PARITY = Regex("""(单数周|双数周|单周|双周)""")

    /** 整学期：每周 / 每周都上 / 全周。 */
    private val WEEK_EVERY = Regex("""(每周|每个星期|全周)""")

    /** 节次区间：1-2节 / 第1~2节。 */
    private val SESSION_RANGE = Regex("""(?:第)?\s*(\d{1,2})\s*[-~～至到]\s*(\d{1,2})\s*节""")

    /** 单个节次：第3节 / 3节。 */
    private val SESSION_SINGLE = Regex("""(?:第)?\s*(\d{1,2})\s*节""")

    /** 周几：周一 / 星期三 / 礼拜天。 */
    private val WEEKDAY = Regex("""(?:星期|周|礼拜)([一二三四五六日天])""")

    // 「.」必须后跟空白，否则会误吃掉「11.20」这类日期的「11.」；「、」「)」则无需空白。
    private val LEADING_MARK = Regex("""^\s*(?:[（(]\d+[）)]\s*|\d+[、)]\s*|\d+\.\s+|[-•*·]\s+)""")

    /** 裸「周几」没有周次信息时无法定位到具体某一天 → 不猜，剥掉让该行跳过。 */
    private val BARE_WEEKDAY = Regex("""(?<![下本上这])(?:星期|周|礼拜)[一二三四五六日天]""")

    private val LINE_BREAK = Regex("""\r\n|\n|\r""")
    private val WHITESPACE = Regex("""\s+""")

    fun parse(
        text: String,
        nowMillis: Long = System.currentTimeMillis(),
        defaultHour: Int = 19,
        defaultMinute: Int = 0,
        existingKeys: Set<String> = emptySet(),
        term: Term? = null,
    ): Result {
        val original = text.split(LINE_BREAK)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val today = Instant.ofEpochMilli(nowMillis)
            .atZone(ZoneId.systemDefault()).toLocalDate()

        val seen = existingKeys.toMutableSet()
        val items = mutableListOf<Item>()
        var skipped = 0
        var duplicates = 0
        var expanded = 0
        var needTermStart = 0

        for (raw in original) {
            val line = LEADING_MARK.replace(raw, "").trim()
            if (line.isEmpty()) { skipped++; continue }

            // ① 写了明确日期就走老路（「12月14日」比「第1-16周 周三」更确切，优先）。
            val cleaned = stripMetadata(line)
            if (cleaned.isNotEmpty()) {
                val parsed = ChineseDateParser.parse(cleaned, nowMillis, defaultHour, defaultMinute)
                if (parsed.matched && parsed.title.isNotBlank()) {
                    if (!seen.add(keyOf(parsed.title, parsed.dueAt))) { duplicates++; continue }
                    items += Item(
                        title = parsed.title,
                        dueAt = parsed.dueAt,
                        category = Category.of(parsed.categoryOrdinal),
                    )
                    continue
                }
            }

            // ② 没有日期：看看是不是「第几周 + 周几」的周次课表行，能展开就展开。
            val weekHit = takeWeeks(line)
            val weekdayHit = WEEKDAY.find(weekHit.rest)
            if (weekHit.spec != null && weekdayHit != null) {
                val session = takeSession(weekHit.rest)
                // 两个区间都相对 weekHit.rest；必须从后往前删，否则先删周几会让节次区间错位。
                val ranges = listOfNotNull(weekdayHit.range, session.range).sortedByDescending { it.first }
                var name = weekHit.rest
                for (r in ranges) name = name.removeRange(r)
                name = WHITESPACE.replace(name, " ").trim()
                if (name.isEmpty()) { skipped++; continue }

                if (term == null) {
                    needTermStart++
                    continue
                }
                val dow = weekdayOf(weekdayHit.groupValues[1])
                if (dow == null) { skipped++; continue }
                val (hour, minute) = sessionStart(session.firstIndex, term, defaultHour, defaultMinute)
                // 课表行一定是「学业」类；只有识别出考试/竞赛/活动时才覆盖（高数、大学物理这类课名靠关键词猜不出来）。
                val detected = ChineseDateParser.categoryOf(name)
                val category = Category.of(if (detected == 4) 1 else detected)
                var made = 0
                // 「每周」这种没写具体周次的行，不能按 25 周展开 —— 13 门课 × 25 周 = 325 条，
                // 会把真正的考试 / 作业截止挤到列表底部，「将至」就废了。只看未来 4 周。
                val horizon = if (weekHit.spec.openEnded) today.plusWeeks(OPEN_ENDED_WEEKS.toLong()) else null
                for (w in weekHit.spec.select(term.maxWeek)) {
                    val date = term.startMonday
                        .plusWeeks((w - 1).toLong())
                        .plusDays((dow - 1).toLong())
                    if (date.isBefore(today)) continue      // 已经上过的课不再生成
                    if (horizon != null && date.isAfter(horizon)) continue
                    val dueAt = epochMillis(date, hour, minute)
                    if (!seen.add(keyOf(name, dueAt))) { duplicates++; continue }
                    items += Item(title = name, dueAt = dueAt, category = category)
                    made++
                }
                if (made == 0) skipped++ else expanded += made
                continue
            }

            skipped++
        }

        return Result(
            items = items.sortedBy { it.dueAt },
            totalLines = original.size,
            skippedLines = skipped,
            duplicateLines = duplicates,
            expandedFromWeeks = expanded,
            needTermStartLines = needTermStart,
        )
    }

    fun keyOf(item: Item): String = keyOf(item.title, item.dueAt)

    private fun keyOf(title: String, dueAt: Long): String = "${title.trim()}|$dueAt"

    /** 剥掉节次 / 周次 / 裸周几这类无法定位到某一天的元数据。 */
    private fun stripMetadata(line: String): String {
        var s = SESSION_RANGE.replace(line, " ")
        s = SESSION_SINGLE.replace(s, " ")
        s = WEEK_RANGE.replace(s, " ")
        s = WEEK_SINGLE.replace(s, " ")
        s = WEEK_PARITY.replace(s, " ")
        s = WEEK_EVERY.replace(s, " ")
        s = BARE_WEEKDAY.replace(s, " ")
        return WHITESPACE.replace(s, " ").trim()
    }

    // ── 周次 ────────────────────────────────────────────────────────────────

    /** 周次筛选条件：区间 + 单双周奇偶。parity: 0 每周 / 1 只单周 / 2 只双周。 */
    private data class WeekSpec(
        val from: Int,
        val to: Int,
        val parity: Int,
        /** 没写具体周次（「每周」「单周」）= 整学期都上，展开时要收窄范围。 */
        val openEnded: Boolean = false,
    ) {
        fun select(maxWeek: Int): List<Int> {
            val lo = from.coerceAtLeast(1)
            val hi = to.coerceAtMost(maxWeek)
            if (lo > hi) return emptyList()
            return (lo..hi).filter { w ->
                when (parity) {
                    1 -> w % 2 == 1
                    2 -> w % 2 == 0
                    else -> true
                }
            }
        }
    }

    private data class TakeWeeks(val spec: WeekSpec?, val rest: String)

    /** 抽出周次标记并从行里删掉；没有周次就返回 null 与原行。 */
    private fun takeWeeks(line: String): TakeWeeks {
        var s = line
        var parity = 0
        WEEK_PARITY.find(s)?.let {
            parity = if (it.value.startsWith("单")) 1 else 2
            s = s.removeRange(it.range)
        }
        WEEK_RANGE.find(s)?.let {
            val a = it.groupValues[1].toIntOrNull() ?: return TakeWeeks(null, line)
            val b = it.groupValues[2].toIntOrNull() ?: return TakeWeeks(null, line)
            return TakeWeeks(WeekSpec(minOf(a, b), maxOf(a, b), parity), s.removeRange(it.range))
        }
        WEEK_SINGLE.find(s)?.let {
            val w = it.groupValues[1].toIntOrNull() ?: return TakeWeeks(null, line)
            return TakeWeeks(WeekSpec(w, w, parity), s.removeRange(it.range))
        }
        // 只写了「单周 / 双周」没有区间：按整学期处理。
        if (parity != 0) return TakeWeeks(WeekSpec(1, 25, parity, openEnded = true), s)
        // 写了「每周」：整学期每周都上（课表还原器对没写周次的格子会补这个标记）。
        WEEK_EVERY.find(s)?.let {
            return TakeWeeks(WeekSpec(1, 25, 0, openEnded = true), s.removeRange(it.range))
        }
        return TakeWeeks(null, line)
    }

    // ── 节次 ────────────────────────────────────────────────────────────────

    private data class TakeSession(val firstIndex: Int?, val range: IntRange?, val rest: String)

    private fun takeSession(line: String): TakeSession {
        SESSION_RANGE.find(line)?.let {
            val a = it.groupValues[1].toIntOrNull() ?: return TakeSession(null, null, line)
            return TakeSession(a, it.range, line.removeRange(it.range))
        }
        SESSION_SINGLE.find(line)?.let {
            val a = it.groupValues[1].toIntOrNull() ?: return TakeSession(null, null, line)
            return TakeSession(a, it.range, line.removeRange(it.range))
        }
        return TakeSession(null, null, line)
    }

    /** 节次 → 上课时刻；没写节次就用默认截止时刻。 */
    private fun sessionStart(
        index: Int?,
        term: Term,
        defaultHour: Int,
        defaultMinute: Int,
    ): Pair<Int, Int> {
        if (index == null) return defaultHour to defaultMinute
        return term.sessionStarts.getOrNull(index - 1) ?: (defaultHour to defaultMinute)
    }

    private fun weekdayOf(c: String): Int? = when (c) {
        "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4
        "五" -> 5; "六" -> 6; "日", "天" -> 7
        else -> null
    }

    private fun epochMillis(date: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(date, LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59)))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
