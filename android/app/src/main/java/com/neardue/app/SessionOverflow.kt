package com.neardue.app

/**
 * v1.30：作息节数被改少时，课程的节次可能超出当前作息范围。
 * 课表用 coerceIn 静默夹到边界，会把第 10 节的课挤到第 8 节——这里纯函数算出
 * "越界了哪些课"，供课表页顶部提示，避免静默误导。
 */
object SessionOverflow {
    data class Offender(val title: String, val start: Int, val end: Int)

    /** 节次越界的课程（start/end 任一不在 1..sessionCount 或顺序颠倒）。按课名去重。 */
    fun of(courses: List<Course>, sessionCount: Int): List<Offender> {
        if (sessionCount <= 0) {
            return courses.map { Offender(it.title, it.sessionStart, it.sessionEnd) }
                .distinctBy { it.title }
        }
        return courses.mapNotNull { c ->
            val ok = c.sessionStart in 1..sessionCount &&
                c.sessionEnd in 1..sessionCount &&
                c.sessionStart <= c.sessionEnd
            if (ok) null else Offender(c.title, c.sessionStart, c.sessionEnd)
        }.distinctBy { it.title }
    }

    /** 顶部提示文案；无越界返回 null。 */
    fun message(offenders: List<Offender>, sessionCount: Int): String? {
        if (offenders.isEmpty()) return null
        val names = offenders.take(3).joinToString("、") { "「${it.title}」" }
        val more = if (offenders.size > 3) " 等 ${offenders.size} 门" else ""
        return "$names$more 的节次超出当前 $sessionCount 节作息，课表已夹到末节显示，请改作息或改课"
    }
}
