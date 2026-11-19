package com.neardue.app

import org.json.JSONObject
import java.time.LocalDate

/**
 * 一个学期。一份学期 = 一个名字 + 一个「第 1 周周一」，课都挂在它下面。
 *
 * 早期需求 要「多学期并存」：上完的学期不删，换学期只是切换，不丢数据。
 * 参考 WakeUp 课程表的「多课表」：每个课表配置独立，切换即用。
 *
 * [startMonday] 为 null 表示还没设开学日 —— 这时课表照常显示，只是不显示「第几周」，
 * 绝不替用户瞎猜一个日期。
 */
data class Term(
    val id: Long = 0L,
    val name: String,
    val startMonday: LocalDate? = null,
    val createdAt: Long = 0L,
    /** 归档后不占课表顶部标签，课都还在，随时可取出（v1.31）。 */
    val archived: Boolean = false,
) {
    /** 界面上的一行字，例如「2026 秋季学期 · 第 1 周从 9月7日 起」。 */
    fun subtitle(): String = if (startMonday == null) {
        "还没设开学日 · 课表不显示周次"
    } else {
        "第 1 周从 ${startMonday.monthValue}月${startMonday.dayOfMonth}日 起"
    }
}

fun Term.toJson(): JSONObject = JSONObject().apply {
    put("name", name)
    put("startMonday", startMonday?.toEpochDay() ?: JSONObject.NULL)
    // v1.31：只在归档时写，老备份缺键回落 false。
    if (archived) put("archived", true)
}

fun termFromJson(o: JSONObject): Term = Term(
    name = o.optString("name", "未命名学期"),
    startMonday = if (o.has("startMonday") && !o.isNull("startMonday")) {
        LocalDate.ofEpochDay(o.optLong("startMonday"))
    } else {
        null
    },
    archived = o.optBoolean("archived", false),
)
