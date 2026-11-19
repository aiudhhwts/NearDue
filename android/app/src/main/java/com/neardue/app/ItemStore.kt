package com.neardue.app

import android.content.ContentValues
import android.content.Context

class ItemStore(context: Context) {

    private val dbHelper = DbHelper(context.applicationContext)

    fun list(): List<Item> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DbHelper.TABLE,
            null,
            null,
            null,
            null,
            null,
            "${DbHelper.COL_DUE_AT} ASC, ${DbHelper.COL_ID} ASC"
        )
        return cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DbHelper.COL_ID)
            val titleIdx = c.getColumnIndexOrThrow(DbHelper.COL_TITLE)
            val dueIdx = c.getColumnIndexOrThrow(DbHelper.COL_DUE_AT)
            val categoryIdx = c.getColumnIndexOrThrow(DbHelper.COL_CATEGORY)
            val doneIdx = c.getColumnIndexOrThrow(DbHelper.COL_DONE)
            val remindIdx = c.getColumnIndexOrThrow(DbHelper.COL_REMIND_OFFSETS)
            val items = mutableListOf<Item>()
            while (c.moveToNext()) {
                items.add(
                    Item(
                        id = c.getLong(idIdx),
                        title = c.getString(titleIdx),
                        dueAt = c.getLong(dueIdx),
                        category = Category.of(c.getInt(categoryIdx)),
                        done = c.getInt(doneIdx) != 0,
                        remindOffsets = c.getString(remindIdx),
                    )
                )
            }
            items
        }
    }

    fun get(id: Long): Item? {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DbHelper.TABLE,
            null,
            "${DbHelper.COL_ID} = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1"
        )
        return cursor.use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                Item(
                    id = c.getLong(c.getColumnIndexOrThrow(DbHelper.COL_ID)),
                    title = c.getString(c.getColumnIndexOrThrow(DbHelper.COL_TITLE)),
                    dueAt = c.getLong(c.getColumnIndexOrThrow(DbHelper.COL_DUE_AT)),
                    category = Category.of(c.getInt(c.getColumnIndexOrThrow(DbHelper.COL_CATEGORY))),
                    done = c.getInt(c.getColumnIndexOrThrow(DbHelper.COL_DONE)) != 0,
                    remindOffsets = c.getString(c.getColumnIndexOrThrow(DbHelper.COL_REMIND_OFFSETS)),
                )
            }
        }
    }

    fun add(item: Item): Long {
        val db = dbHelper.writableDatabase
        return db.insert(DbHelper.TABLE, null, item.toContentValues())
    }

    fun save(item: Item) {
        val db = dbHelper.writableDatabase
        db.update(
            DbHelper.TABLE,
            item.toContentValues(),
            "${DbHelper.COL_ID} = ?",
            arrayOf(item.id.toString())
        )
    }

    fun setDone(id: Long, done: Boolean) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(DbHelper.COL_DONE, if (done) 1 else 0)
        }
        db.update(
            DbHelper.TABLE,
            values,
            "${DbHelper.COL_ID} = ?",
            arrayOf(id.toString())
        )
    }

    fun remove(id: Long) {
        val db = dbHelper.writableDatabase
        db.delete(
            DbHelper.TABLE,
            "${DbHelper.COL_ID} = ?",
            arrayOf(id.toString())
        )
    }

    // 本地 JSON 备份恢复：整表替换，id 由 SQLite 重新生成。
    fun replaceAll(items: List<Item>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(DbHelper.TABLE, null, null)
            items.forEach { db.insert(DbHelper.TABLE, null, it.toContentValues()) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Item.toContentValues(): ContentValues = ContentValues().apply {
        put(DbHelper.COL_TITLE, title)
        put(DbHelper.COL_DUE_AT, dueAt)
        put(DbHelper.COL_CATEGORY, category.ordinal)
        put(DbHelper.COL_DONE, if (done) 1 else 0)
        put(DbHelper.COL_REMIND_OFFSETS, remindOffsets)
    }
}
