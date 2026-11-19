package com.neardue.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 本地 JSON 备份的编解码。只序列化数据，不导出数据库结构；导入时 id 由 SQLite 重新生成。
 * 纯 Kotlin + org.json，可在 JVM 单测里直接跑（testImplementation 引入真实 org.json）。
 *
 * **格式版本**
 *
 * - v1（旧）：裸数组 `[{...}]`，只有事项，**不含课程表** —— 换手机 / 重置后课表全丢。
 * - v2：`{"format":2,"items":[...],"courses":[...]}`，事项 + 课程表一起走。
 * - v3：再加 `"overrides":[...]`，连「仅本周改课 / 停课」的例外也带上。
 * - v4（现在）：再加 `"terms":[...]` 与 `"currentTermId"`，多个学期一起备份、一起还原。
 *
 * 四种都认（`[` 开头走 v1 裸数组，缺哪个键就当没有），所以老备份文件照样能导入。
 * v1.23 起课程多了 `teacher` / `note` 两个**可选字段**（写在 course 对象里，有内容才写键）。
 * 这不改文件结构：老备份缺键 → 回落空串；新备份多键 → 老版本读到也忽略，所以格式号仍是 4。
 *
 * **id 全都不作数**：课程与学期在导入时 id 会重新生成，所以这里
 * - 单周例外用「课的身份」(`courseKey`) 找课；
 * - 课程用「学期在备份里的序号」找学期（`termIndex`），而不是旧 id。
 */
object BackupCodec {

    /** 当前写出的格式版本。 */
    const val FORMAT_VERSION = 4

    /**
     * 备份里的单周例外。
     *
     * **不能用自增 id 关联课程** —— 导入时 id 是 SQLite 重新生成的，旧 id 全部失效。
     * 所以这里存的是「课的身份」（[CourseImporter.courseKey]：课名 + 周几 + 起止节次），
     * 导入后再用同一个 key 把课找回来。
     */
    data class OverrideRecord(
        val courseKey: String,
        val week: Int,
        val skip: Boolean = false,
        val dayOfWeek: Int = 0,
        val sessionStart: Int = 0,
        val sessionEnd: Int = 0,
        val room: String? = null,
    )

    /**
     * 备份里的学期。用 [index] 而不是数据库 id 做身份 —— 导入时 id 会变，
     * 但「第几个学期」是稳定的，课程靠它挂回去。
     */
    data class TermRecord(
        val index: Int,
        val name: String,
        val startMonday: LocalDate? = null,
        /** v1.31：归档的学期导出来也要是归档的。老备份缺键回落 false。 */
        val archived: Boolean = false,
    )

    /** 一次备份的全部内容。 */
    data class Bundle(
        val items: List<Item> = emptyList(),
        val courses: List<Course> = emptyList(),
        /** 备份文件里到底有没有课程表。v1 裸数组没有；v2 起有（哪怕空表也不是 null）。 */
        val hasCourses: Boolean = false,
        val overrides: List<OverrideRecord> = emptyList(),
        /** v3 才有。没有它时绝不能拿空表去清用户本周的例外。 */
        val hasOverrides: Boolean = false,
        val terms: List<TermRecord> = emptyList(),
        /** v4 才有。没有它就说明是老文件，得自己造一个学期来接课。 */
        val hasTerms: Boolean = false,
        /** 备份里「当前学期」是第几个（下标）。-1 = 没记。 */
        val currentTermIndex: Int = -1,
    ) {
        val isEmpty: Boolean get() = items.isEmpty() && courses.isEmpty()
    }

    fun encode(bundle: Bundle): String = JSONObject().apply {
        put("format", FORMAT_VERSION)
        put("items", JSONArray().apply { bundle.items.forEach { put(it.toJson()) } })
        put("terms", JSONArray().apply { bundle.terms.forEach { put(termToJson(it)) } })
        put("currentTermIndex", bundle.currentTermIndex)
        put("courses", JSONArray().apply { bundle.courses.forEach { put(it.toJson()) } })
        put("overrides", JSONArray().apply { bundle.overrides.forEach { put(overrideToJson(it)) } })
    }.toString(2)

    /** 只有事项的旧接口，保留给旧调用点与旧测试。 */
    fun encode(items: List<Item>): String = encode(Bundle(items = items))

    /**
     * {@link Bundle} 里课程的 `termId` 是「学期序号 + 1」。写文件前用它把课程跟学期对齐。
     *
     * ★为什么 +1：`termId = 0` 已经是「没挂学期」的哨兵值，直接用下标 0 会被当成「没挂」，
     * 第一个学期的课就会静默丢掉（v1.18 真机验证时就是这么发现的）。
     */
    fun coursesWithTermIndex(courses: List<Course>, terms: List<Term>): List<Course> {
        val indexOf = terms.mapIndexed { i, t -> t.id to (i + 1).toLong() }.toMap()
        return courses.map { c -> c.copy(termId = indexOf[c.termId] ?: 0L) }
    }

    /** 反方向：把文件里的「序号 + 1」换回真的学期 id。没匹配上就返回 [fallback]。 */
    fun termIdForIndex(indexOrZero: Long, fresh: List<Term>, fallback: Long): Long {
        if (indexOrZero <= 0L) return fallback
        return fresh.getOrNull((indexOrZero - 1).toInt())?.id ?: fallback
    }

    /** 读回来：四种格式都认。 */
    fun decodeBundle(text: String): Bundle {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "备份文件为空" }
        if (trimmed.startsWith("[")) {
            // v1 裸数组：只有事项。**不能拿它去清课程表**。
            return Bundle(items = decodeItemArray(JSONArray(trimmed)), hasCourses = false)
        }
        val obj = JSONObject(trimmed)
        val items = obj.optJSONArray("items")?.let { decodeItemArray(it) } ?: emptyList()
        val courseArr = obj.optJSONArray("courses")
        val overrideArr = obj.optJSONArray("overrides")
        val termArr = obj.optJSONArray("terms")
        return Bundle(
            items = items,
            courses = courseArr?.let { decodeCourseArray(it) } ?: emptyList(),
            hasCourses = courseArr != null,
            overrides = overrideArr?.let { decodeOverrideArray(it) } ?: emptyList(),
            hasOverrides = overrideArr != null,
            terms = termArr?.let { decodeTermArray(it) } ?: emptyList(),
            hasTerms = termArr != null,
            currentTermIndex = if (obj.has("currentTermIndex")) obj.optInt("currentTermIndex", -1) else -1,
        )
    }

    /** 只有事项的旧接口。给了 v2 文件时也只取事项。 */
    fun decode(text: String): List<Item> = decodeBundle(text).items

    private fun decodeItemArray(arr: JSONArray): List<Item> =
        (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }

    private fun decodeCourseArray(arr: JSONArray): List<Course> =
        (0 until arr.length()).map { courseFromJson(arr.getJSONObject(it)) }

    private fun decodeTermArray(arr: JSONArray): List<TermRecord> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TermRecord(
                index = i,
                name = o.optString("name", "未命名学期"),
                startMonday = if (o.has("startMonday") && !o.isNull("startMonday")) {
                    LocalDate.ofEpochDay(o.optLong("startMonday"))
                } else {
                    null
                },
                archived = o.optBoolean("archived", false),
            )
        }

    private fun decodeOverrideArray(arr: JSONArray): List<OverrideRecord> =
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            OverrideRecord(
                courseKey = o.optString("key", ""),
                week = o.optInt("week", 0),
                skip = o.optBoolean("skip", false),
                dayOfWeek = o.optInt("dayOfWeek", 0),
                sessionStart = o.optInt("sessionStart", 0),
                sessionEnd = o.optInt("sessionEnd", 0),
                // optString 拿不到 null，用 has() + isNull 区分「这周没教室（空串）」与「照旧（没有这个键）」。
                room = if (o.has("room") && !o.isNull("room")) o.optString("room", "") else null,
            )
        }

    private fun termToJson(t: TermRecord): JSONObject = JSONObject().apply {
        put("name", t.name)
        put("startMonday", t.startMonday?.toEpochDay() ?: JSONObject.NULL)
        if (t.archived) put("archived", true)
    }

    private fun overrideToJson(o: OverrideRecord): JSONObject = JSONObject().apply {
        put("key", o.courseKey)
        put("week", o.week)
        put("skip", o.skip)
        put("dayOfWeek", o.dayOfWeek)
        put("sessionStart", o.sessionStart)
        put("sessionEnd", o.sessionEnd)
        if (o.room == null) put("room", JSONObject.NULL) else put("room", o.room)
    }

    private fun toJson(item: Item): JSONObject = JSONObject().apply {
        put("title", item.title)
        put("dueAt", item.dueAt)
        put("category", item.category.ordinal)
        put("done", item.done)
        put("remindOffsets", item.remindOffsets)
    }

    private fun fromJson(o: JSONObject): Item = Item(
        title = o.optString("title", ""),
        dueAt = o.optLong("dueAt", 0L),
        category = Category.of(o.optInt("category", Category.OTHER.ordinal)),
        done = o.optBoolean("done", false),
        remindOffsets = o.optString("remindOffsets", "-1440,-120"),
    )
}
