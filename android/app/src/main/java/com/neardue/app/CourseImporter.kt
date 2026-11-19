package com.neardue.app

import java.time.Instant
import java.time.ZoneId

/**
 * 课表文本 / Excel 表格 / PDF 抽出文字的导入器。
 *
 * **两类行分开落地**（早期需求「课表不用变成事项提醒」）：
 *
 * 1. **周次课表行**（`周三 1-2节 高等数学 1-16周`）→ 落成 [Course]，只进课表，**不排提醒**。
 *    不再「按学期展开成几十条 Item」—— 课表视图自己按第几周渲染，一门课永远只是一门课。
 * 2. **带日期的行**（`高数期末 12月14日 上午9点`）→ 落成 [Item]，进清单、排提醒。
 *    这类才是考试 / 作业截止，本来就该提醒。
 *
 * **三种输入形态**（v1.11 起）：
 *
 * - 一行一门的纯文本（用户手打 / PDF 抽文字）；
 * - **Excel 整表复制**：带表头、字段之间是制表符。教务系统的「导出数据.xls」就是这个样子，
 *   直接粘进来也必须认，不能把整行当课程名 —— 见 [tableLines]；
 * - 表格里没有表头时按内容猜列（周几 / 节次 / 周次 / 教室 / 课名），猜不出就跳过，不瞎猜。
 *
 * 周次写法必须认全（教务系统的真实写法）：
 * `1-16`、`1-16单`、`1-16双`、`单周`、`双周`、`1,3,5,7`、`1-4,7-8,13`，
 * 还有一段里混写多段的 `3周,6-18周`、`5周,7-9周,11周`、`7-17周(单)`，见 [CourseWeeks]。
 * 认不出来就当「每周」，绝不瞎猜成某一周。
 *
 * **同一个格子里的同一门课只留一门**：教务系统把一门课按周次拆成好几行（走进生命科学
 * 就拆成 3周 / 4周 / 6-7周 / 8-9周 / 10-11周 五行），导入后必须是**一门课、周次并集**，
 * 否则课表里同一格叠着五张卡片。去重键 = 课名 + 周几 + 节次，**不含周次**。
 *
 * 纯 Kotlin + java.time，不依赖 Android，可在 JVM 单测里直接跑。
 */
object CourseImporter {

    data class Result(
        /** 课表行 → 课程。 */
        val courses: List<Course>,
        /** 带日期行 → 事项（会排提醒）。 */
        val items: List<Item>,
        val totalLines: Int,
        val skippedLines: Int,
        val duplicateLines: Int,
    )

    /** 一段周次枚举，不含「周」字：`1-16`、`1,3,5`、`3,6-18`。 */
    private const val WEEK_PART =
        """\d{1,2}(?:\s*[-~～至到]\s*\d{1,2})?(?:\s*[,，、]\s*\d{1,2}(?:\s*[-~～至到]\s*\d{1,2})?)*"""

    /**
     * 一段带「周」的周次写法，可带单双：`3周` / `7-17周(单)` / `1-16周双`。
     * 一次 [Regex.findAll] 就能把 `3周,6-18周` 拆成两段，两段都要从课程名里剔掉。
     */
    private val WEEK_SPEC = Regex("""($WEEK_PART)\s*周\s*(?:[（(]\s*([单双])\s*[）)])?\s*([单双])?""")

    /** 写在数字前面的单双：`单周1-16` / `双周 3-9`（少见但存在）。 */
    private val PARITY_BEFORE = Regex("""(单|双)(?:数)?周""")

    /**
     * 整学期：`每周` / `全周` / `每个星期`。
     * PDF 表格还原出来的行末尾就是「每周」，课程名里不能留这个尾巴。
     */
    private val EVERY_WEEK = Regex("""(每周|每个星期|全周)""")

    private val SESSION_RANGE = Regex("""(?:第)?\s*(\d{1,2})\s*[-~～至到]\s*(\d{1,2})\s*节""")
    private val SESSION_SINGLE = Regex("""(?:第)?\s*(\d{1,2})\s*节""")
    private val WEEKDAY = Regex("""(?:星期|周|礼拜)([一二三四五六日天])""")
    private val LEADING_MARK = Regex("""^\s*(?:[（(]\d+[）)]\s*|\d+[、)]\s*|\d+\.\s+|[-•*·]\s+)""")
    private val LINE_BREAK = Regex("""\r\n|\n|\r""")
    private val WHITESPACE = Regex("""\s+""")

    /** 课程名前面的培养层次标记：`【本】` / `[研]` / `（专）`。教务系统的分类前缀，不是课名。 */
    private val NAME_MARK = Regex("""^\s*[【\[（(]\s*(?:本|研|专|辅|双)\s*[】\]）)]\s*""")

    /** 表格里没写节次时的默认节。 */
    private const val DEFAULT_SESSION = 1

    fun parse(
        text: String,
        nowMillis: Long = System.currentTimeMillis(),
        defaultHour: Int = 19,
        defaultMinute: Int = 0,
        existingCourseKeys: Set<String> = emptySet(),
        /** 导进来的课挂到哪个学期。0 = 不挂（单测与旧调用点）。 */
        termId: Long = 0L,
    ): Result {
        val table = tableLines(text)
        val original = table?.first
            ?: text.split(LINE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }

        // 去重键 → 课。用 LinkedHashMap 保持「第一次出现的顺序」，合并时原地更新。
        val courses = LinkedHashMap<String, Course>()
        val seenItems = mutableSetOf<String>()
        val items = mutableListOf<Item>()
        var skipped = table?.second ?: 0
        var duplicates = 0

        for (raw in original) {
            val line = LEADING_MARK.replace(raw, "").trim()
            if (line.isEmpty()) { skipped++; continue }

            // ① 周次课表行 → Course
            val weekdayHit = WEEKDAY.find(line)
            if (weekdayHit != null) {
                val dow = weekdayOf(weekdayHit.groupValues[1])
                val session = takeSession(line)
                val weeks = takeWeeks(line)
                // 一次性把所有元信息（周几 / 节次 / 周次）从原行里剔掉。
                // 不能用「逐个 removeRange」—— 每删一段，后面的下标就会前移，
                // 按原串算好的区间就错了（曾把「高等数学」砍成「高等数学周」）。
                val cut = listOfNotNull(weekdayHit.range, session.range) + weeks.ranges
                val keep = StringBuilder()
                for (i in line.indices) {
                    if (cut.none { i in it }) keep.append(line[i])
                }
                var name = WHITESPACE.replace(keep.toString(), " ").trim()
                name = NAME_MARK.replace(name, "").trim()

                if (dow != null && name.isNotEmpty()) {
                    // 把尾部教室（`A101` / `B201` / `A101` / `理科楼C223` / `操场`）拆出去。
                    val roomed = splitRoom(name)
                    name = roomed.first.trim(' ', ',', '，', '、', '-', '·')
                    if (name.isNotEmpty()) {
                        val course = Course(
                            title = name,
                            dayOfWeek = dow,
                            sessionStart = session.start,
                            sessionEnd = maxOf(session.start, session.end ?: session.start),
                            weeks = weeks.spec,
                            room = roomed.second,
                            category = categoryFor(name),
                            termId = termId,
                        )
                        val key = courseKey(course)
                        val prev = courses[key]
                        when {
                            existingCourseKeys.contains(key) -> duplicates++
                            prev == null -> courses[key] = course
                            else -> {
                                // 同一格同一门课的多行（不同周次 / 不同教室）→ 并成一门。
                                duplicates++
                                courses[key] = prev.copy(
                                    weeks = CourseWeeks.mergeSpecs(listOf(prev.weeks, course.weeks)),
                                    room = mergeRoom(prev.room, course.room),
                                )
                            }
                        }
                        continue
                    }
                }
            }

            // ② 带日期的行 → Item（考试 / 作业截止，该提醒）
            val parsed = ChineseDateParser.parse(line, nowMillis, defaultHour, defaultMinute)
            if (parsed.matched && parsed.title.isNotBlank()) {
                val key = "${parsed.title.trim()}|${parsed.dueAt}"
                if (!seenItems.add(key)) { duplicates++; continue }
                items += Item(
                    title = parsed.title,
                    dueAt = parsed.dueAt,
                    category = Category.of(parsed.categoryOrdinal),
                )
                continue
            }

            skipped++
        }

        return Result(
            courses = courses.values.sortedWith(compareBy({ it.dayOfWeek }, { it.sessionStart })),
            items = items.sortedBy { it.dueAt },
            totalLines = original.size,
            skippedLines = skipped,
            duplicateLines = duplicates,
        )
    }

    /**
     * 课程去重键：课名 + 周几 + 节次。
     *
     * **不含周次** —— 一门课被教务系统按周次拆成多行是常态，周次是「这门课在哪些周上」，
     * 不是「另一门课」。把周次放进键里，走进生命科学一门课就会在课表里变成五门。
     * 库里的 `CourseStore.keyOf` 必须用同一个键，否则重复导入拦不住。
     */
    fun courseKey(c: Course): String =
        "${c.title.trim()}|${c.dayOfWeek}|${c.sessionStart}|${c.sessionEnd}"

    /** 同一格换教室（生物学 周二 9-10 节 B202 / B203）→ 两个教室都留着，别丢信息。 */
    private fun mergeRoom(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        a == b -> a
        a.contains(b) || b.contains(a) -> if (a.length >= b.length) a else b
        else -> "$a / $b"
    }

    /** 课表行一定是「学业」类；只有识别出考试/竞赛/活动时才覆盖。 */
    fun categoryFor(name: String): Category {
        val detected = ChineseDateParser.categoryOf(name)
        return Category.of(if (detected == 4) 1 else detected)
    }

    // ── 周次 ────────────────────────────────────────────────────────────────

    private data class TakeWeeks(val spec: String, val ranges: List<IntRange>)

    private fun takeWeeks(line: String): TakeWeeks {
        val hits = WEEK_SPEC.findAll(line).toList()
        if (hits.isNotEmpty()) {
            val specs = hits.map { m ->
                val body = WHITESPACE.replace(m.groupValues[1], "")
                var parity = m.groupValues[2].ifEmpty { m.groupValues[3] }
                if (parity.isEmpty()) parity = parityNearby(line)
                CourseWeeks.normalize(body + parity)
            }
            // 单段：保持原文写法（`1-16单` 原样存，别被压成 `1-15单`）。
            // 多段：各段展开取并集，再压回紧凑写法（`3周,6-18周` → `3-4,6-18`）。
            val spec = if (specs.size == 1) specs.first() else CourseWeeks.mergeSpecs(specs)
            // 区间要连着段间分隔符一起剔。`3周,6-18周` 里逗号不属于任何一个段，
            // 只剔段本身会留下一个孤零零的 `,`，它随后会粘到课名/教室上（曾产出 `[ 高等数学甲I ,B201]`）。
            val cuts = hits.mapIndexed { i, m ->
                if (i < hits.lastIndex) m.range.first..(hits[i + 1].range.first - 1) else m.range
            }
            return TakeWeeks(spec, cuts)
        }
        val parity = parityNearby(line)
        if (parity.isNotEmpty()) {
            val r = PARITY_BEFORE.find(line)?.range
            return TakeWeeks(CourseWeeks.normalize(parity), listOfNotNull(r))
        }
        // 「每周」= 没写周次 = 整学期。spec 留空，range 只用来把它从课程名里摘掉。
        EVERY_WEEK.find(line)?.let { return TakeWeeks("", listOf(it.range)) }
        return TakeWeeks("", emptyList())
    }

    /** 没有跟在「周」后面的单双时，去数字前面找 `单周` / `双周`。 */
    private fun parityNearby(line: String): String =
        PARITY_BEFORE.find(line)?.groupValues?.get(1).orEmpty()

    // ── 节次 ────────────────────────────────────────────────────────────────

    private data class TakeSession(val start: Int, val end: Int?, val range: IntRange?)

    private fun takeSession(line: String): TakeSession {
        SESSION_RANGE.find(line)?.let {
            val a = it.groupValues[1].toIntOrNull() ?: DEFAULT_SESSION
            val b = it.groupValues[2].toIntOrNull() ?: a
            return TakeSession(minOf(a, b), maxOf(a, b), it.range)
        }
        SESSION_SINGLE.find(line)?.let {
            val a = it.groupValues[1].toIntOrNull() ?: DEFAULT_SESSION
            return TakeSession(a, a, it.range)
        }
        return TakeSession(DEFAULT_SESSION, DEFAULT_SESSION, null)
    }

    // ── 教室 ────────────────────────────────────────────────────────────────

    /** 把尾部的教室（`A101` / `B201` / `A101` / `理科楼C223` / `操场`）从课程名里拆出去。 */
    private fun splitRoom(raw: String): Pair<String, String> {
        val tokens = raw.split(WHITESPACE).filter { it.isNotBlank() }
        if (tokens.size < 2) return raw to ""
        val last = tokens.last()
        if (!looksLikeRoom(last)) return raw to ""
        return tokens.dropLast(1).joinToString(" ") to tokenRoom(last)
    }

    /** 教室：`B201` / `A101` / `A101` / `A-202` / `理科楼C223` / `操场`。带「周」「节」的一律不是。 */
    private fun looksLikeRoom(token: String): Boolean {
        if (token.any { it == '周' || it == '节' || it == '班' }) return false
        if (token.length > 14) return false
        if (token.any { it.isDigit() }) return token.matches(ROOM_SHAPE)
        // 没数字但带地点字（操场 / 体育馆 / 报告厅）：也当教室，否则会粘在课名里。
        return PLACE_WORDS.any { token.endsWith(it) } && token.length <= 8
    }

    private fun tokenRoom(token: String): String = WHITESPACE.replace(token, "")

    /**
     * 教室形状：字母/汉字前缀 + 数字，数字中间可以夹字母（`A101` 这种东/西教学楼编号；
     * v1.10 的旧形状不允许字母夹在数字中间，导致 A101 留在课名里、教室为空）。
     */
    private val ROOM_SHAPE = Regex("""[A-Za-z一-龥]{0,4}[A-Za-z0-9][A-Za-z0-9\-－]{0,9}""")

    private val PLACE_WORDS = listOf(
        "楼", "室", "馆", "场", "中心", "厅", "园", "座", "苑", "堂",
    )

    private fun weekdayOf(c: String): Int? = when (c) {
        "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4
        "五" -> 5; "六" -> 6; "日", "天" -> 7
        else -> null
    }

    // ── Excel 整表（制表符分隔）────────────────────────────────────────────

    /**
     * 表头里会出现的字段名。只用**强关键词**，不能用「节」「周」这种单字：
     * 数据行里的 `第1节` / `10-11周` 也含这两个字，会把数据行误当成表头整行丢掉。
     */
    private val HEADER_HINTS = listOf(
        "课程名", "教学内容", "课名", "星期", "周几", "上课日",
        "节次", "周次", "起止周", "教室", "上课地点", "开课单位",
    )

    private class Columns(
        val title: Int,
        val day: Int,
        val sessionStart: Int,
        val sessionEnd: Int,
        val weeks: Int,
        val room: Int,
    )

    /**
     * 把「从 Excel 复制出来的整张表」还原成一行行课表文本。
     *
     * 教务系统的导出表长这样（`导出数据.xls`，42 行 11 列）：
     * `课程号 课程名 课序号 开课单位 上课周次 上课星期 开始节次 结束节次 学时 教室名称 上课班级`。
     * 用户把整片粘进来时，v1.10 会把整行当成一个课程名（探针实测：标题变成
     * `[2 9000001 【本】走进生命科学 01 理学院 第2节 16 N8T...]`，节次只剩 1 节）。
     *
     * 这里只在「确实有制表符」时才启用，且认不出列就跳过该行 —— 绝不把垃圾塞进课表。
     * 还原出的行会走上面同一条解析路径，不另写第二份解析逻辑。
     *
     * @return 第一项是还原出的课表行，第二项是被丢掉的表格行数；不是表格时返回 null。
     */
    private fun tableLines(text: String): Pair<List<String>, Int>? {
        val raw = text.split(LINE_BREAK).map { it.trimEnd() }.filter { it.isNotBlank() }
        if (raw.none { it.contains('\t') }) return null

        var headerAt = -1
        var columns: Columns? = null
        for ((i, row) in raw.withIndex()) {
            if (!row.contains('\t')) continue
            val cells = row.split('\t').map { it.trim() }
            val hits = cells.count { cell -> HEADER_HINTS.any { cell.contains(it) } }
            if (hits >= 2) {
                columns = mapColumns(cells)
                headerAt = i
                break
            }
        }

        val out = ArrayList<String>()
        var dropped = 0
        for (i in raw.indices) {
            if (i <= headerAt) continue
            val row = raw[i]
            if (!row.contains('\t')) {
                // 表格里混进来的手写行（带日期的那种）照常交给后面的解析。
                out += row.trim()
                continue
            }
            val cells = row.split('\t').map { it.trim() }
            val line = columns?.let { tableRowToLine(cells, it) } ?: inferRow(cells)
            if (line != null) out += line else dropped++
        }
        return out to dropped
    }

    /** 按表头找列。各校表头写法不同，只要求命中关键词，不要求列序。 */
    private fun mapColumns(cells: List<String>): Columns? {
        fun find(vararg keys: String): Int =
            cells.indexOfFirst { cell -> keys.any { cell.contains(it) } }

        val title = find("课程名称", "课程名", "教学内容", "课名")
        val day = find("上课星期", "星期", "周几", "上课日")
        val start = find("开始节次", "起始节", "开始节", "起节")
        val end = find("结束节次", "终止节", "结束节", "止节")
        val session = find("节次", "节")
        val weeks = find("上课周次", "周次", "起止周")
        val room = find("教室名称", "教室", "地点", "上课地点")
        if (title < 0 || day < 0) return null
        return Columns(
            title = title,
            day = day,
            sessionStart = if (start >= 0) start else session,
            sessionEnd = if (end >= 0) end else -1,
            weeks = weeks,
            room = room,
        )
    }

    private fun tableRowToLine(cells: List<String>, c: Columns): String? {
        fun cell(i: Int): String = if (i in cells.indices) cells[i].trim() else ""

        val title = cleanTitle(cell(c.title))
        if (title.isEmpty()) return null
        val dow = weekdayIndexOf(cell(c.day)) ?: return null
        val startText = cell(c.sessionStart)
        val endText = cell(c.sessionEnd)
        val span = sessionPair(startText) ?: sessionPair(endText) ?: return null
        val end = intOf(endText) ?: span.second
        val weeks = weeksText(cell(c.weeks))
        val room = tokenRoom(cell(c.room))
        return buildLine(dow, span.first, maxOf(span.first, end), title, weeks, room)
    }

    /**
     * 没有表头时按内容猜列：周几 / 节次 / 周次 / 教室 / 第一个像课名的中文串。
     * 猜不出周几就整行跳过。
     */
    private fun inferRow(cells: List<String>): String? {
        var dow = -1
        var start = -1
        var end = -1
        var weeks = ""
        var room = ""
        var title = ""
        for (rawCell in cells) {
            val cell = rawCell.trim()
            if (cell.isEmpty()) continue
            // 纯数字长串（课程号 `9000001`、课序号）不是周几/节次/教室，跳过。
            if (cell.length >= 5 && cell.all { it.isDigit() }) continue
            if (dow < 0) {
                val d = weekdayIndexOf(cell)
                if (d != null) { dow = d; continue }
            }
            if (start < 0) {
                val span = sessionPair(cell)
                if (span != null) { start = span.first; end = span.second; continue }
            } else if (end <= start) {
                // 表格把节次拆成「开始节次 / 结束节次」两列时，第二列就是结束节。
                val span = sessionPair(cell)
                if (span != null && span.second >= start) { end = span.second; continue }
            }
            if (weeks.isEmpty() && cell.contains('周') && WEEK_SPEC.containsMatchIn(cell)) {
                weeks = weeksText(cell); continue
            }
            if (room.isEmpty() && looksLikeRoom(cell)) { room = tokenRoom(cell); continue }
            if (title.isEmpty() && cell.length >= 2 && cell.any { it in '一'..'龥' }) {
                title = cleanTitle(cell)
            }
        }
        if (dow < 0 || title.isEmpty()) return null
        if (start < 0) start = DEFAULT_SESSION
        if (end < start) end = start
        return buildLine(dow, start, end, title, weeks, room)
    }

    /** 拼成导入器自己的行格式（只有一套解析路径，不写第二份）。 */
    private fun buildLine(dow: Int, start: Int, end: Int, title: String, weeks: String, room: String): String {
        val sb = StringBuilder()
        sb.append("周").append("一二三四五六日"[dow - 1]).append(' ')
        sb.append(start)
        if (end > start) sb.append('-').append(end)
        sb.append("节 ").append(title)
        if (weeks.isNotEmpty()) sb.append(' ').append(weeks)
        if (room.isNotEmpty()) sb.append(' ').append(room)
        return sb.toString()
    }

    /** 表格里的周次列：`10-11周` 原样留，`10-11` 补上「周」。 */
    private fun weeksText(raw: String): String {
        val s = WHITESPACE.replace(raw.trim(), "")
        if (s.isEmpty()) return ""
        if (s.contains('周')) return s
        return if (s.any { it.isDigit() }) s + "周" else ""
    }

    /** `星期一` / `周一` / `礼拜一` / `一` / `1` → 1..7。 */
    private fun weekdayIndexOf(raw: String): Int? {
        val s = WHITESPACE.replace(raw.trim(), "")
        if (s.isEmpty()) return null
        if (s.length == 1) {
            weekdayOf(s)?.let { return it }
            return s.toIntOrNull()?.takeIf { it in 1..7 }
        }
        WEEKDAY.find(s)?.let { return weekdayOf(it.groupValues[1]) }
        s.toIntOrNull()?.let { if (it in 1..7) return it }
        return null
    }

    /** `第1节` / `1-2节` / `1` → (起始, 结束)。 */
    private fun sessionPair(raw: String): Pair<Int, Int>? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        SESSION_RANGE.find(s)?.let {
            val a = it.groupValues[1].toIntOrNull() ?: return null
            val b = it.groupValues[2].toIntOrNull() ?: a
            return minOf(a, b) to maxOf(a, b)
        }
        SESSION_SINGLE.find(s)?.let {
            val a = it.groupValues[1].toIntOrNull() ?: return null
            return a to a
        }
        val n = s.toIntOrNull() ?: return null
        // 纯数字列只在 1..20 内当节次；`9000001`（课程号）这种长数字不能当节次。
        if (n !in 1..20) return null
        return n to n
    }

    private fun intOf(raw: String): Int? = Regex("""\d{1,2}""").find(raw)?.value?.toIntOrNull()

    private fun cleanTitle(raw: String): String {
        var s = raw.trim()
        s = NAME_MARK.replace(s, "").trim()
        s = s.trim(' ', ',', '，', '、')
        return s
    }
}
