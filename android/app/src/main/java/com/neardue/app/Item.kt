package com.neardue.app

import org.json.JSONObject

enum class Category(val label: String, val argb: Long) {
    EXAM("考", 0xFFC4382F),
    HOMEWORK("业", 0xFF3E63DD),
    CONTEST("赛", 0xFFA8530C),
    EVENT("活", 0xFF1F7A4E),
    OTHER("其他", 0xFF6C6A72);
    companion object {
        fun of(v: Int): Category = entries.getOrElse(v) { OTHER }
    }
}

data class Item(
    val id: Long = 0L,
    val title: String,
    val dueAt: Long,                       // epoch millis
    val category: Category = Category.OTHER,
    val done: Boolean = false,
    val remindOffsets: String = "-1440,-120",
)

// 本地 JSON 备份：只导出数据，不导出数据库结构；导入时 id 由 SQLite 重新生成。
fun Item.toJson(): JSONObject = JSONObject().apply {
    put("title", title)
    put("dueAt", dueAt)
    put("category", category.ordinal)
    put("done", done)
    put("remindOffsets", remindOffsets)
}

fun itemFromJson(o: JSONObject): Item = Item(
    title = o.optString("title", ""),
    dueAt = o.optLong("dueAt", 0L),
    category = Category.of(o.optInt("category", Category.OTHER.ordinal)),
    done = o.optBoolean("done", false),
    remindOffsets = o.optString("remindOffsets", "-1440,-120"),
)
