package com.neardue.app

/**
 * 把「二维表格形态的课表 PDF」还原成一行行可导入的文本。
 *
 * 为什么需要它：真实教务导出的课表是网格（左侧「1-2节」竖排、顶部「一二三四五」横排、
 * 格子里塞着课程名/班级/教师/教室）。如果只按阅读顺序把同一基线的字拼成一行，
 * 拿到的是「宪法学 1班【李老师】A101」这种跨列碎片，喂给 CourseScheduleImporter
 * 只能认出个位数条目，标题还是垃圾。
 *
 * 做法（不做通用版面分析，只认课表这一种网格）：
 * 1. 找表头行：一整行都是单个「一二三四五六日天」字 -> 用它们的 x 定位列中心。
 * 2. 找节次行：最左侧一竖列（x 很小）拼起来能匹配 `1-2节 / 第3节` -> 用它们的 y 定位行带。
 * 3. 按「列中心 / 行带」把每个文字片段丢进格子，格子内按 y 从上到下、x 从左到右拼。
 * 4. 输出 `周三 1-2节 宪法学 1班 ...`，直接复用 v1.1/v1.5 已有的解析路径。
 *
 * 认不出网格就返回 null，由调用方回退到「按阅读顺序拼」的纯文本，绝不硬猜。
 */
object ScheduleTableParser {

    private const val WEEKDAY_CHARS = "\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u65e5\u5929"

    /** 左侧节次列的最大 x（PDF 用户空间单位）。课表的节次标签总是贴左边。 */
    private const val LEFT_LABEL_MAX_X = 130.0

    private const val MIN_COLUMNS = 3
    private const val MIN_ROWS = 2

    private val SESSION_RANGE = Regex("(\\d{1,2})\\s*[-~\\uFF5E\\u81F3\\u5230]\\s*(\\d{1,2})\\s*\\u8282")
    private val SESSION_SINGLE = Regex("(\\d{1,2})\\s*\\u8282")

    /** 左侧标签直接写时间：17:20（不少教务系统按时间排表，不写节次）。 */
    private val CLOCK = Regex("(\\d{1,2})\\s*[:\\uFF1A]\\s*(\\d{2})")

    /** 英文表头（部分教务系统导出 MON/TUE/WED/THU/FRI）。 */
    private val EN_DAYS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    /** 格子里没写周次时补的标记，由 CourseScheduleImporter 按整学期展开。 */
    private const val WHOLE_TERM = "\u6BCF\u5468"

    /**
     * 格子里可能有周次但没写「周」字：`1-16单` / `1,3,5` / `(双)`。
     * 认出来就别再补「每周」，否则单双周会被当成整学期 —— 这是真实教务导出里
     * 最常见的写法，补错了满屏都是不该上的课。
     */
    private val WEEK_HINT = Regex(
        """(\d{1,2}\s*[-~～至到]\s*\d{1,2})|([（(]\s*[单双]\s*[）)])|(单周|双周|单数周|双数周)"""
    )

    private val WHITESPACE = Regex("\\s+")

    /** 纯时间行（单元格里常摆上课起止时间，不是课程名）。 */
    private val PURE_TIME = Regex("^\\d{1,2}\\s*[:\\uFF1A]\\s*\\d{2}(\\s*[-~\\uFF5E]\\s*\\d{1,2}\\s*[:\\uFF1A]\\s*\\d{2})?$")

    /** 纯班级标记：「1班」「2个班」。 */
    private val BARE_CLASS = Regex("^\\d{1,2}个?班$")

    /** 教室/地点开头的词，用来把「A101」「教 121」这类行判成非课程名。 */
    private val ROOM_WORDS = listOf(
        "\u9986", "\u6559", "\u697c", "\u5BA4", "\u533A", "\u5EA7", "\u573A",
        "\u5B9E\u9A8C", "\u4E2D\u5FC3", "\u5927\u5385", "\u793C\u5802", "\u4F53\u80B2\u9986"
    )

    private class Row(val label: String, val centerY: Double, val half: Double)

    private class Grid(val centers: List<Double>, val rows: List<Row>, val headerY: Double)

    /**
     * @return 还原出来的每行文本（形如 `周三 1-2节 宪法学`）；认不出课表网格时返回 null。
     */
    fun toRows(lines: List<PdfTextExtractor.Line>): String? {
        if (lines.size < MIN_ROWS * MIN_COLUMNS) return null
        val out = ArrayList<String>()
        var anyPage = false
        var junk = 0

        val byPage = lines.groupBy { it.page }
        for (pageIdx in byPage.keys.sorted()) {
            val pageLines = byPage[pageIdx] ?: continue
            val grid = detectGrid(pageLines) ?: continue
            val cells = fillCells(pageLines, grid)
            if (cells.isEmpty()) continue
            anyPage = true
            for (rowIdx in grid.rows.indices) {
                val row = grid.rows[rowIdx]
                for (colIdx in grid.centers.indices) {
                    val block = cells[rowIdx * 100 + colIdx] ?: continue
                    val wd = WEEKDAY_CHARS[colIdx]
                    // 一个格子里常堆着好几门课（分周上），每门课单独一行，否则会被拼成一串垃圾标题。
                    for (name in block) {
                        if (name.isBlank()) continue
                        // 那些「一格塞两门课」的复杂版面，勉强拆出来全是垃圾标题，整页弃用。
                        if (looksLikeJunk(name)) { junk++; continue }
                        val hasWeeks = name.contains("\u5468") || WEEK_HINT.containsMatchIn(name)
                        val tail = if (hasWeeks) "" else " " + WHOLE_TERM
                        out.add("\u5468" + wd + " " + row.label + " " + name + tail)
                    }
                }
            }
        }
        // 垃圾条目占比过高就不算认出来，交给调用方回退纯文本；宁可不用，也不给用户一堆错标题。
        if (junk > out.size) return null
        return if (anyPage && out.isNotEmpty()) out.joinToString("\n") else null
    }

    // -- 1. 定位表头（周几）与节次行 --

    private fun detectGrid(pageLines: List<PdfTextExtractor.Line>): Grid? {
        val header = findWeekdayHeader(pageLines) ?: return null
        val centers = header.first
        if (centers.size < MIN_COLUMNS) return null

        val hits = ArrayList<Pair<Double, String>>()
        for (ln in pageLines) {
            val labelText = leftLabel(ln)
            if (labelText.isEmpty()) continue
            var a = -1
            var b = -1
            val range = SESSION_RANGE.find(labelText)
            if (range != null) {
                a = range.groupValues[1].toIntOrNull() ?: -1
                b = range.groupValues[2].toIntOrNull() ?: -1
            } else {
                val single = SESSION_SINGLE.find(labelText)
                if (single != null) {
                    a = single.groupValues[1].toIntOrNull() ?: -1
                    b = a
                } else {
                    // 没有节次就试时间标签（17:20），很多教务系统按时间排表。
                    val clock = CLOCK.find(labelText) ?: continue
                    val hh = clock.groupValues[1].toIntOrNull() ?: -1
                    val mm = clock.groupValues[2].toIntOrNull() ?: -1
                    if (hh in 0..23 && mm in 0..59) hits.add(ln.y to ("%02d:%02d".format(hh, mm)))
                    continue
                }
            }
            if (a < 1 || a > 20 || b < a || b > 20) continue
            hits.add(ln.y to (a.toString() + "-" + b + "\u8282"))
        }
        if (hits.size < MIN_ROWS) return null
        hits.sortByDescending { it.first }

        val rows = ArrayList<Row>()
        for (i in hits.indices) {
            val y = hits[i].first
            val prev = if (i > 0) hits[i - 1].first else null
            val next = if (i < hits.size - 1) hits[i + 1].first else null
            // 行带只能占两行中点的一半，否则相邻两行会重叠、上一行的课粘到下一行。
            val above = if (prev != null) (prev - y) / 2.0 else if (next != null) (y - next) / 2.0 else 40.0
            val below = if (next != null) (y - next) / 2.0 else above
            val half = ((above + below) / 2.0).coerceIn(6.0, 60.0)
            rows.add(Row(hits[i].second, y, half))
        }
        return Grid(centers, rows, header.second)
    }

    private fun findWeekdayHeader(pageLines: List<PdfTextExtractor.Line>): Pair<List<Double>, Double>? {
        var best: Pair<List<Double>, Double>? = null
        for (ln in pageLines) {
            val words = mergeLetterCells(ln)
            if (words.size < MIN_COLUMNS) continue
            val isChinese = words.all { it.first.length == 1 && WEEKDAY_CHARS.indexOf(it.first[0]) >= 0 }
            val isEnglish = words.all { w -> EN_DAYS.any { w.first.uppercase().startsWith(it) } }
            if (!isChinese && !isEnglish) continue
            val centers = words.map { it.second }
            var increasing = true
            for (i in 1 until centers.size) if (centers[i] <= centers[i - 1] + 1.0) { increasing = false; break }
            if (!increasing) continue
            if (best == null || centers.size > best.first.size) best = centers to ln.y
        }
        return best
    }

    /**
     * 把逐字母摆位的表头（M|O|N|T|U|E）按间距合并成单词。
     * 很多教务系统导出时每个字母一个 Tj，不合并就认不出 MON/TUE 表头。
     */
    private fun mergeLetterCells(ln: PdfTextExtractor.Line): List<Pair<String, Double>> {
        val cells = ln.cells.sortedBy { it.x }
        val out = ArrayList<Pair<String, Double>>()
        var i = 0
        while (i < cells.size) {
            val c = cells[i]
            val t = c.text.trim()
            if (t.length == 1 && t[0].isUpperCase()) {
                val sb = StringBuilder(t)
                var endX = c.endX
                var j = i + 1
                while (j < cells.size) {
                    val nx = cells[j]
                    val nt = nx.text.trim()
                    if (nt.length != 1 || !nt[0].isUpperCase()) break
                    if (nx.x - endX > maxOf(1.5, c.size * 0.45)) break
                    sb.append(nt)
                    endX = nx.endX
                    j++
                }
                out.add(sb.toString() to ((c.x + endX) / 2.0))
                i = j
            } else {
                out.add(t to ((c.x + c.endX) / 2.0))
                i++
            }
        }
        return out
    }

    private fun leftLabel(ln: PdfTextExtractor.Line): String {
        val sb = StringBuilder()
        for (c in ln.cells.sortedBy { it.x }) {
            if (c.endX > LEFT_LABEL_MAX_X) continue
            sb.append(c.text.trim())
        }
        return sb.toString()
    }

    // -- 2. 把片段填进格子 --

    private fun fillCells(pageLines: List<PdfTextExtractor.Line>, grid: Grid): Map<Int, List<String>> {
        val centers = grid.centers
        val leftEdges = ArrayList<Double>(centers.size)
        val rightEdges = ArrayList<Double>(centers.size)
        for (i in centers.indices) {
            val left = if (i == 0) centers[0] - (centers[1] - centers[0]) / 2.0 else (centers[i - 1] + centers[i]) / 2.0
            val right = if (i == centers.size - 1) centers[i] + (centers[i] - centers[i - 1]) / 2.0 else (centers[i] + centers[i + 1]) / 2.0
            leftEdges.add(left)
            rightEdges.add(right)
        }

        val buckets = HashMap<Int, MutableList<Pair<Double, PdfTextExtractor.Cell>>>()

        for (ln in pageLines) {
            val headerTol = maxOf(2.5, ln.size * 0.45)
            if (kotlin.math.abs(ln.y - grid.headerY) <= headerTol) continue

            var rowIdx = -1
            for (i in grid.rows.indices) {
                if (kotlin.math.abs(ln.y - grid.rows[i].centerY) <= grid.rows[i].half) { rowIdx = i; break }
            }
            if (rowIdx < 0) continue

            for (c in ln.cells) {
                if (c.endX <= LEFT_LABEL_MAX_X) continue
                if (c.text.isBlank()) continue
                val mid = (c.x + c.endX) / 2.0
                var colIdx = -1
                for (i in centers.indices) {
                    if (mid >= leftEdges[i] && mid < rightEdges[i]) { colIdx = i; break }
                }
                if (colIdx < 0) continue
                buckets.getOrPut(rowIdx * 100 + colIdx) { ArrayList() }.add(ln.y to c)
            }
        }

        val result = HashMap<Int, List<String>>()
        for ((key, list) in buckets) {
            val names = joinCell(list)
            if (names.isNotEmpty()) result[key] = names
        }
        return result
    }

    /**
     * 格子内：先按 y 从上往下分小组（PDF 的 y 越大越靠上），再逐行按 x 拼。
     *
     * 真实教务课表的格子里常把「课程名 / 班级 / 【教师】 / 教室」分好几行摆；
     * 对「将至」只要有课程名就够，所以遇到【教师】或教室行就停，不要括进标题。
     */
    private fun joinCell(items: List<Pair<Double, PdfTextExtractor.Cell>>): List<String> {
        if (items.isEmpty()) return emptyList()
        var sizeSum = 0.0
        for (p in items) sizeSum += p.second.size
        val size = maxOf(4.0, sizeSum / items.size)
        val tol = maxOf(1.6, size * 0.45)

        val rows = ArrayList<MutableList<Pair<Double, PdfTextExtractor.Cell>>>()
        for (p in items.sortedByDescending { it.first }) {
            val last = if (rows.isEmpty()) null else rows[rows.size - 1]
            if (last != null && kotlin.math.abs(last[0].first - p.first) <= tol) last.add(p)
            else rows.add(mutableListOf(p))
        }

        val names = ArrayList<String>()
        var parts = ArrayList<String>()
        var head = ""
        fun flush() {
            if (parts.isEmpty()) return
            val name = cleanCourseName(parts.joinToString(" "))
            if (name.isNotBlank() && names.none { it == name }) names.add(name)
            parts = ArrayList()
        }
        for (row in rows) {
            row.sortBy { it.second.x }
            val lb = StringBuilder()
            var prevEnd = Double.NaN
            for (p in row) {
                val c = p.second
                val t = c.text.trim()
                if (t.isEmpty()) continue
                if (!prevEnd.isNaN()) {
                    val gap = c.x - prevEnd
                    if (gap > 0.7 * size && lb.isNotEmpty() && lb.last() != ' ') lb.append(' ')
                }
                lb.append(t)
                prevEnd = c.endX
            }
            val line = dedupeTokens(lb.toString().trim())
            if (line.isEmpty()) continue
            // 「【教师】」「A101」「教 121」「1班」这类是元数据：本门课到此结束。
            if (isMetaLine(line)) { flush(); head = ""; continue }
            // 同一个格子里推了好几门课：又出现课程名的开头词，就开新的一门。
            if (head.isNotEmpty() && line == head) flush()
            if (parts.isEmpty()) head = line
            parts.add(line)
        }
        flush()
        // 只剩「（总论一）」这类续行碎片就别当课程名。
        val kept = names.filter { it.isNotBlank() && it.length >= 2 && !it.startsWith("\uFF08") && !it.startsWith("(") }
        return if (kept.isNotEmpty()) listOf(kept.first()) else emptyList()
    }

    /** 去掉标题里的括号碎片与多余空格，并剔除纯班级标记。 */
    private fun cleanCourseName(raw: String): String {
        var s = raw
        s = WHITESPACE.replace(s, " ").trim()
        s = s.replace("(", "\uFF08").replace(")", "\uFF09")
        // 末尾的「1 班」「2 班」保留但不重复
        s = s.replace("\uFF08 \uFF09", "").replace("\uFF08\uFF09", "")
        // 教室经常紧跟在课程名后面（「心理学概论（上） 新教 -207」），标题里不要它。
        val toks = s.split(' ').filter { it.isNotEmpty() }.toMutableList()
        // 「（总论一 ）」这种被空格拆开的闭括号，先粘回去，否则下面会把它当单字扔了。
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t in BRACKET_TOKENS && i > 0) {
                toks[i - 1] = toks[i - 1] + t
                toks.removeAt(i)
            } else if (t.startsWith("\uFF08") && !t.contains("\uFF09") && i + 1 < toks.size && toks[i + 1] == "\uFF09") {
                toks[i] = t + "\uFF09"
                toks.removeAt(i + 1)
                i++
            } else {
                i++
            }
        }
        while (toks.size > 1 && isRoomToken(toks.last())) toks.removeAt(toks.size - 1)
        if (toks.size > 1 && toks.last().matches(Regex("^-?\\d{2,4}$"))) {
            toks.removeAt(toks.size - 1)
            if (toks.size > 1 && isRoomToken(toks.last())) toks.removeAt(toks.size - 1)
        }
        // 末尾孤零零一个汉字（「数据思维 费」中的教师姓）不是课程名的一部分。
        while (toks.size > 1 && toks.last().length == 1 && !BRACKET_TOKENS.contains(toks.last())) {
            toks.removeAt(toks.size - 1)
        }
        return toks.joinToString(" ").trim()
    }

    /** 「楼-106」「新教-207」「A-202」「1-106」「某教学楼403」这类是教室标记。 */
    private fun isRoomToken(tok: String): Boolean {
        val t = WHITESPACE.replace(tok, "")
        if (t.isEmpty() || t.none { it.isDigit() }) return false
        if (t.length > 10) return false
        if (ROOM_WORDS.any { t.contains(it) }) return true
        // 纯数字/字母 + 数字编号，如 A-202、1-106、403
        if (t.matches(Regex("^[A-Za-z]?-?\\d{1,4}(-\\d{1,4})?$"))) return true
        return false
    }

    /** 「（总论一 ）」这种被空格拆开的括号碎片。 */
    private val BRACKET_TOKENS = setOf("\uFF09", ")", "\uFF08", "(")

    /** “【李老师】”“A101”“教 121”“某教学楼 403”“1 班” 这类是教室/教师/班级，不算课程名。 */
    private fun isMetaLine(line: String): Boolean {
        val compact = WHITESPACE.replace(line, "")
        if (compact.isEmpty()) return true
        if (compact.contains("\u3010") || compact.contains("\u3011")) return true
        if (compact.contains("\u00B6")) return true
        if (PURE_TIME.matches(compact)) return true
        if (BARE_CLASS.matches(compact)) return true
        if (ROOM_WORDS.any { compact.startsWith(it) } && compact.any { it.isDigit() }) return true
        // 只有教室号（「1-106」「A-202」）的行也不是课程名。
        if (isRoomToken(compact)) return true
        return false
    }

    /**
     * 标题是否看着就不像课程名。
     *
     * 只看字符构成：真课名几乎全是汉字（「宪法学」「大学英语 听说」「民法学（总则）」），
     * 而拆坏的碎片会带满 `◇ ¶ ] , -` 和数字串（「智峰◇1-4,7-13周第7节◇信工」）。
     * 用来做整页兜底：那些「一格塞两门课」的复杂版面，勉强拆出来全是垃圾，
     * 不如整页弃用、回退到纯文本，让用户自己看。
     */
    private fun looksLikeJunk(title: String): Boolean {
        if (title.contains("\u00B6") || title.contains("\u25C7") || title.contains("]") || title.contains("[")) return true
        var cjk = 0
        var bad = 0
        for (ch in title) {
            if (ch.isWhitespace()) continue
            when {
                ch.code in 0x4E00..0x9FFF -> cjk++
                ch.isDigit() || ch in "◇¶,，、-~」【】[]()" -> bad++
            }
        }
        val solid = cjk + bad
        if (solid == 0) return true
        // 汉字占比低于六成，基本是版式碎片而不是课名。
        return cjk * 10 < solid * 6
    }

    /** 合并相邻重复词（合班课常把同一个课程名摆两遍：「民法学 民法学」）。 */
    private fun dedupeTokens(text: String): String {
        var t = text
        // 「刑法学 （总论一 ） （总论一 ）」：整段括号重复，先抺掉第二份。
        val paren = Regex("(\uFF08[^\uFF08\uFF09]{1,20}\uFF09)\\s*\\1")
        while (true) {
            val next = paren.replace(t, "$1")
            if (next == t) break
            t = next
        }
        val parts = t.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return t
        val kept = ArrayList<String>()
        for (p in parts) if (kept.isEmpty() || kept[kept.size - 1] != p) kept.add(p)
        return kept.joinToString(" ")
    }
}
