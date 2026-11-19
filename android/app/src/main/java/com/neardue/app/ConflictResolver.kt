package com.neardue.app

/**
 * 撞课时的「显示这门课」（WakeUp 5.0.06，v1.28）。
 *
 * v1.19 的冲突提示只把撞课摆出来，不替人决定。真要处理时，用户得自己去给
 * 落选的课加一条「这周停课」例外，步骤很长。这里给一个一键入口：
 * 选中想上的那门 → 本周把其余几门停掉（写 CourseOverride(skip=true)）。
 *
 * 刻意只作用「本周」：下周这两门还撞不撞，是另一回事；不偷偷改整学期。
 * 也不删课、不改课表数据 —— 例外随时可以「恢复这周」撤销。
 */
object ConflictResolver {
    /**
     * 本周只保留 [keepId]，其余各写一条停课例外。
     * 没设开学日（week<1）或课程没有 id 时不生成，避免脏数据。
     */
    fun skipOthers(courses: List<Course>, keepId: Long, week: Int): List<CourseOverride> {
        if (week !in 1..CourseWeeks.MAX_WEEK) return emptyList()
        if (courses.none { it.id == keepId }) return emptyList()
        return courses
            .filter { it.id != keepId && it.id > 0L }
            .distinctBy { it.id }
            .map { CourseOverride(courseId = it.id, week = week, skip = true) }
    }

    /** 确认对话框文案：把「会停掉谁」说清楚，不玩文字游戏。 */
    fun summary(keep: Course, others: List<Course>, week: Int): String {
        val names = others.distinctBy { it.id }.joinToString("、") { it.title }
        return "第 $week 周只上「${keep.title}」，本周停掉：$names。\n只是这一周，其它周不变，随时能恢复。"
    }
}
