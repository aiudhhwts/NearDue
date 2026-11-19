package com.neardue.app

import org.json.JSONObject

/**
 * 一门课（课表里的一格）。
 *
 * 和 [Item] 的区别：Item 是「到点该干什么」的截止事项，会进清单、会排提醒；
 * Course 是「每周几第几节上什么」，只活在课表里，**不变成事项、不响铃**。
 * 早期需求明确要求这两件事分开。
 *
 * 纯 Kotlin + org.json，不依赖 Android，可在 JVM 单测里直接跑。
 */
data class Course(
    val id: Long = 0L,
    val title: String,
    /** 1 = 周一 … 7 = 周日。 */
    val dayOfWeek: Int,
    /** 第几节开始，1 起。 */
    val sessionStart: Int,
    /** 第几节结束（含）。等于 [sessionStart] 时就是一节课。 */
    val sessionEnd: Int,
    /** 周次，见 [CourseWeeks]。空串 = 每周。 */
    val weeks: String = "",
    val room: String = "",
    val category: Category = Category.HOMEWORK,
    /**
     * 属于哪个学期（[Term.id]）。0 = 还没归属（老库升级上来的，会被收进第一个学期）。
     * **不参与 [CourseImporter.courseKey]** —— 判重是学期内的同一门课，跟学期编号无关。
     */
    val termId: Long = 0L,
    /** 任课教师（v1.23，选填，最多 20 字）。默认不显示，长按复制可带走。 */
    val teacher: String = "",
    /** 备注（v1.23，选填，最多 40 字）。默认不显示，长按复制可带走。 */
    val note: String = "",
    /**
     * 这门课自己的课前提醒提前量（分钟，v1.31）。-1 = 跟全局；0/5/10/20 为可选档。
     * 老备份/老库缺这个字段会回落 -1，行为与 v1.30 完全一致。
     */
    val leadMinutes: Int = CourseLead.USE_GLOBAL,
) {
    /** 几节课，给格子的高度用。 */
    val sessionCount: Int get() = (sessionEnd - sessionStart + 1).coerceAtLeast(1)

    /** 「1-2 节」这种文案。 */
    fun sessionText(): String =
        if (sessionEnd <= sessionStart) "$sessionStart 节" else "$sessionStart-$sessionEnd 节"

    fun weeksText(): String = CourseWeeks.label(weeks)

    /**
     * 长按课程块复制到剪贴板的文案（v1.20）。
     * 只描述这一门课的固定排布，不写「这一周改过」——那是例外，不是课本身。
     */
    fun copyText(): String = buildString {
        append(title)
        append(' ')
        append(CourseWeeks.dayName(dayOfWeek))
        append(" 第 ")
        append(sessionStart)
        if (sessionEnd > sessionStart) append('-').append(sessionEnd)
        append(" 节")
        if (room.isNotBlank()) append(" · ").append(room.trim())
        if (teacher.isNotBlank()) append(" · ").append(teacher.trim())
        if (note.isNotBlank()) append(" · ").append(note.trim())
        val w = CourseWeeks.label(weeks)
        if (w != "每周") append(" · ").append(w)
    }
}

fun Course.toJson(): JSONObject = JSONObject().apply {
    put("title", title)
    put("dayOfWeek", dayOfWeek)
    put("sessionStart", sessionStart)
    put("sessionEnd", sessionEnd)
    put("weeks", weeks)
    put("room", room)
    put("category", category.ordinal)
    // 只在真有归属时写，免得把备份文件弄得全是 termId:0 的噪声。
    if (termId > 0L) put("termId", termId)
    // v1.23：只在真有内容时写，老备份缺这两键会自动回落空串。
    if (teacher.isNotBlank()) put("teacher", teacher)
    if (note.isNotBlank()) put("note", note)
    // v1.31：只在真设了单课提前量时写，老备份缺键回落 -1（跟全局）。
    if (leadMinutes != CourseLead.USE_GLOBAL) put("leadMinutes", leadMinutes)
}

fun courseFromJson(o: JSONObject): Course = Course(
    title = o.optString("title", ""),
    dayOfWeek = o.optInt("dayOfWeek", 1).coerceIn(1, 7),
    sessionStart = o.optInt("sessionStart", 1).coerceAtLeast(1),
    sessionEnd = o.optInt("sessionEnd", o.optInt("sessionStart", 1)).coerceAtLeast(1),
    weeks = o.optString("weeks", ""),
    room = o.optString("room", ""),
    category = Category.of(o.optInt("category", Category.HOMEWORK.ordinal)),
    termId = o.optLong("termId", 0L),
    teacher = o.optString("teacher", ""),
    note = o.optString("note", ""),
    leadMinutes = CourseLead.normalize(o.optInt("leadMinutes", CourseLead.USE_GLOBAL)),
)

/**
 * 周次写法解析。
 *
 * 教务系统导出的课表里，同一个格子经常不是「每周」这么简单，常见写法有：
 *
 * - `1-16`              连续周
 * - `1-16单` / `1-16双` 单周 / 双周
 * - `1,3,5,7`           零散周
 * - `1-4,7-8,13`        区间与零散混写（最常见的排课写法）
 * - `单` / `双`          整学期单周 / 双周
 * - `每周` / 空          每周
 *
 * 这些都必须认，否则一门 1-8 周的课、一门 9-16 周的课会被当成同一门挂满整学期。
 */
object CourseWeeks {

    /** 展开上限。坏数据写「第 99 周」也不会炸出几百条。 */
    const val MAX_WEEK = 25

    private val RANGE = Regex("""(\d{1,2})\s*[-~～至到]\s*(\d{1,2})""")
    private val SINGLE = Regex("""(\d{1,2})""")

    /**
     * 把规格文本规范成存储用的紧凑形式：`1-16`、`1-16单`、`1,3,5`、`单`、`双`、``（=每周）。
     * 认不出来就当每周，不猜。
     */
    fun normalize(spec: String): String {
        var s = spec.trim()
        if (s.isEmpty()) return ""
        if (s.contains("每") || s.contains("全周")) return ""
        s = s.replace("第", "")
            .replace("周", "")
            .replace("（", "").replace("）", "")
            .replace("(", "").replace(")", "")
            .replace(" ", "")
            .replace("整", "")
            .replace("学期", "")
        var parity = ""
        if (s.endsWith("单数")) {
            s = s.dropLast(2)
            parity = "单"
        }
        if (s.endsWith("双数")) {
            s = s.dropLast(2)
            parity = "双"
        }
        if (s.endsWith("单")) {
            s = s.dropLast(1)
            parity = "单"
        }
        if (s.endsWith("双")) {
            s = s.dropLast(1)
            parity = "双"
        }
        if (s.isEmpty()) return parity
        return s + parity
    }

    /** 展开成具体周次。空规格 = 1..maxWeek。 */
    fun parse(spec: String, maxWeek: Int = MAX_WEEK): List<Int> {
        val n = normalize(spec)
        if (n.isEmpty()) return (1..maxWeek).toList()
        var body = n
        var parity = 0
        if (body.endsWith("单")) {
            parity = 1
            body = body.dropLast(1)
        }
        if (body.endsWith("双")) {
            parity = 2
            body = body.dropLast(1)
        }
        if (body.isEmpty()) {
            return (1..maxWeek).filter { w -> if (parity == 1) w % 2 == 1 else w % 2 == 0 }
        }
        val out = sortedSetOf<Int>()
        for (part in body.split(',', '，', '、', '；', ';')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val r = RANGE.find(p)
            if (r != null) {
                val a = r.groupValues[1].toIntOrNull() ?: continue
                val b = r.groupValues[2].toIntOrNull() ?: continue
                for (w in minOf(a, b)..maxOf(a, b)) if (w in 1..maxWeek) out += w
            } else {
                val one = SINGLE.find(p)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (one in 1..maxWeek) out += one
            }
        }
        if (out.isEmpty()) return (1..maxWeek).toList()
        return out.filter { w ->
            when (parity) {
                1 -> w % 2 == 1
                2 -> w % 2 == 0
                else -> true
            }
        }
    }

    /**
     * 多段周次的并集，压回紧凑写法。
     *
     * 教务系统把一门课按周次拆成多行（`3周` + `6-18周`），合并成一门课时要把两边都算上。
     * 任一段是「每周」（空串）→ 整学期，返回空串，别拼出一个假的 1-25。
     * 连续周压成 `a-b`，零散周逐个列出（`3-4,6-18`）。
     */
    fun mergeSpecs(specs: List<String>): String {
        if (specs.isEmpty()) return ""
        if (specs.any { normalize(it).isEmpty() }) return ""
        val all = sortedSetOf<Int>()
        for (s in specs) all.addAll(parse(s))
        if (all.isEmpty()) return ""
        if (all.size >= MAX_WEEK) return ""
        val parts = mutableListOf<String>()
        val runStart = { i: Int -> all.elementAt(i) }
        var i = 0
        val weeks = all.toList()
        while (i < weeks.size) {
            var j = i
            while (j + 1 < weeks.size && weeks[j + 1] == weeks[j] + 1) j++
            parts += if (j - i >= 2) "${runStart(i)}-${runStart(j)}" else
                (i..j).joinToString(",") { weeks[it].toString() }
            i = j + 1
        }
        return parts.joinToString(",")
    }

    /**
     * 编辑对话框里的周次取值。
     *
     * 控件只有「起 / 止 / 单双」三个旋钮，装不下 `3,4,6-11` 这种零散写法。
     * 所以用 [touched] 区分：用户没动过旋钮就原样保留原文，
     * 一旦动过才按范围重算 —— 否则打开又保存会悄悄把第 5 周补上。
     *
     * @param original 原来的写法，null 表示新增的课。
     * @param touched 用户是否碰过起止周 / 单双周。
     */
    fun editSpec(original: String?, weekFrom: Int, weekTo: Int, parity: String, touched: Boolean): String {
        if (!touched && original != null) return original
        return normalize("${minOf(weekFrom, weekTo)}-${maxOf(weekFrom, weekTo)}$parity")
    }

    /**
     * 给周次文本加/去单双后缀（v1.20）。周次输入框旁的「单周 / 双周 / 都要」快捷按钮用。
     * 单双周只是便捷写法，不是硬约束——点按钮只在原文后加个字，不改区间。
     */
    fun withParity(spec: String, parity: String): String {
        val body = normalize(spec).removeSuffix("单").removeSuffix("双")
        return normalize(body + parity)
    }

    /** 回显文案。`1-16单` → `1-16 周（单周）`。 */
    fun label(spec: String): String {
        val n = normalize(spec)
        if (n.isEmpty()) return "每周"
        val single = n.endsWith("单")
        val double = n.endsWith("双")
        val body = if (single || double) n.dropLast(1) else n
        val base = when {
            body.isEmpty() && single -> "整学期单周"
            body.isEmpty() && double -> "整学期双周"
            body.contains('-') -> "$body 周"
            else -> {
                val weeks = body.split(',', '，')
                if (weeks.size > 6) "$body 周" else "第 $body 周"
            }
        }
        return when {
            single -> "$base（单周）"
            double -> "$base（双周）"
            else -> base
        }
    }

    private val DAY_CHARS = listOf("一", "二", "三", "四", "五", "六", "日")

    fun dayName(dayOfWeek: Int): String =
        "周" + DAY_CHARS.getOrElse(dayOfWeek - 1) { "一" }

    /** 这门课在第 [week] 周上不上。 */
    fun includes(spec: String, week: Int, maxWeek: Int = MAX_WEEK): Boolean =
        parse(spec, maxWeek).contains(week)
}
