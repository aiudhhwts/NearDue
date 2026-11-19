package com.neardue.app

/**
 * 只改某一周的例外（WakeUp 课表里叫「单周改课」）。
 *
 * 教务系统给的课表是死的，现实里总有例外：这周调课换教室、这周老师出差停一次、
 * 这周被运动会占用。**这些都不该改整学期的课** —— 改了整学期，后面十几周全错。
 *
 * 所以例外单独存一张表（`course_overrides`），按 (course_id, week) 唯一：
 * 平时的课仍在 `courses` 里，某一周有例外就叠一条覆盖上去。
 *
 * 字段用 0 / null 表示「这项不改」，这样一条记录可以只改教室、也可以只挪节次。
 */
data class CourseOverride(
    val courseId: Long,
    val week: Int,
    /** 这周停课（调休、老师出差）。停课时不看其它字段。 */
    val skip: Boolean = false,
    /** 改到这周几；0 = 不改。 */
    val dayOfWeek: Int = 0,
    /** 改到第几节开始；0 = 不改。 */
    val sessionStart: Int = 0,
    /** 改到第几节结束；0 = 不改。 */
    val sessionEnd: Int = 0,
    /** 改到这间教室；null = 不改，空串 = 这周没教室。 */
    val room: String? = null,
) {
    /** 把例外叠到课表原记录上。停课的调用方应先看 [skip]，这里不返回 null。 */
    fun applyTo(c: Course): Course = c.copy(
        dayOfWeek = if (dayOfWeek > 0) dayOfWeek else c.dayOfWeek,
        sessionStart = if (sessionStart > 0) sessionStart else c.sessionStart,
        sessionEnd = if (sessionEnd > 0) sessionEnd else c.sessionEnd,
        room = room ?: c.room,
    )

    /** 这条例外改了点什么（停课不算，停课看 [skip]）。 */
    val changesAnything: Boolean
        get() = !skip && (dayOfWeek > 0 || sessionStart > 0 || sessionEnd > 0 || room != null)

    companion object {
        /** 从磁盘/备份读出来的脏数据要挡一道：周次越界、指向不存在的课，一律不收。 */
        fun of(
            courseId: Long,
            week: Int,
            skip: Boolean,
            dayOfWeek: Int,
            sessionStart: Int,
            sessionEnd: Int,
            room: String?,
        ): CourseOverride? {
            if (courseId <= 0L) return null
            if (week !in 1..CourseWeeks.MAX_WEEK) return null
            val d = dayOfWeek.coerceIn(0, 7)
            val s = sessionStart.coerceIn(0, 20)
            val e = sessionEnd.coerceIn(0, 20)
            return CourseOverride(
                courseId = courseId,
                week = week,
                skip = skip,
                dayOfWeek = d,
                sessionStart = s,
                sessionEnd = if (e in 1..20 && e < s) s else e,
                room = room,
            )
        }
    }
}

/** 改课的范围：整学期，还是只改哪一周。 */
enum class EditScope { ALL_WEEKS, THIS_WEEK }

/**
 * 编辑对话框的产出。
 *
 * [week] 只在 [EditScope.THIS_WEEK] 时有意义；[skipWeek] 表示「这一周停课」。
 */
data class CourseEdit(
    val course: Course,
    val scope: EditScope,
    val week: Int = 0,
    val skipWeek: Boolean = false,
)
