package com.neardue.app

/**
 * 每门课独立的「课前多久提醒」。
 *
 * v1.17 起将至有一个全局提前量（设置页一档，所有课共用），参考 WakeUp 的
 * 「全局一套参数」。但学生真实课表不是一刀切：有的课在隔壁楼（提前 5 分钟够），
 * 有的课要走 10 分钟跨校区（得提前 20 分钟）。所以 v1.31 让单门课可以覆盖全局。
 *
 * 口径钉死在这里，纯 Kotlin 无 Android 依赖，JVM 单测直接跑：
 * - 课程不设（-1）= 跟全局；老备份/老库没有这个字段，回落 -1，行为完全不变。
 * - 只有 0/5/10/20 四档合法（跟设置页一致），坏数据回落 -1，不瞎猜。
 * - 提前量不改变闹钟的 requestCode（同一节课还是同一个闹钟，改档位直接覆盖）。
 */
object CourseLead {

    /** 课程没设自己的提前量，跟随全局。 */
    const val USE_GLOBAL = -1

    /** 与设置页 CourseReminder.LEAD_OPTIONS 一致。 */
    val OPTIONS = listOf(0, 5, 10, 20)

    /** 坏数据/老数据统一回落到「跟全局」。 */
    fun normalize(value: Int): Int = if (value in OPTIONS) value else USE_GLOBAL

    /** 课程实际生效的提前量：设了就用课的，没设就用全局（全局坏数据回落默认 10）。 */
    fun effective(courseLead: Int, globalLead: Int): Int {
        val own = normalize(courseLead)
        if (own != USE_GLOBAL) return own
        return if (globalLead in OPTIONS) globalLead else CourseReminder.DEFAULT_LEAD
    }

    /** 编辑课程对话框里那一排选项：跟全局 + 四档。 */
    fun options(): List<Int> = listOf(USE_GLOBAL) + OPTIONS

    /** 每个选项给用户看的短文案。 */
    fun label(value: Int, globalLead: Int): String {
        val v = normalize(value)
        if (v == USE_GLOBAL) return "跟全局（${CourseReminder.leadLabel(effective(USE_GLOBAL, globalLead))}）"
        return CourseReminder.leadLabel(v)
    }
}
