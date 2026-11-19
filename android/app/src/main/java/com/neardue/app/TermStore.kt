package com.neardue.app

import android.content.ContentValues
import android.content.Context
import java.time.LocalDate

/**
 * 学期存储。跟 [CourseStore] 分开：学期是"容器"，课是"内容"。
 *
 * 设计要点（v1.18）：
 * - **至少有一个学期**。首个学期由 [ensureAtLeastOne] 自动建（名字按开学日推）。
 * - 删学期会连带删掉它下面的课与单周例外（[CourseStore.removeTerm]），但**不允许删最后一个**。
 * - 老库升级上来的课 `term_id = 0`，由 [adoptOrphanCourses] 收进第一个学期。
 */
class TermStore(context: Context) {

    private val dbHelper = DbHelper(context.applicationContext)
    private val appContext: Context = context.applicationContext

    fun list(): List<Term> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DbHelper.TABLE_TERMS, null, null, null, null, null,
            "${DbHelper.T_ID} ASC",
        )
        return cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DbHelper.T_ID)
            val nameIdx = c.getColumnIndexOrThrow(DbHelper.T_NAME)
            val startIdx = c.getColumnIndexOrThrow(DbHelper.T_START)
            val createdIdx = c.getColumnIndexOrThrow(DbHelper.T_CREATED_AT)
            val archivedIdx = c.getColumnIndex(DbHelper.T_ARCHIVED)
            val out = mutableListOf<Term>()
            while (c.moveToNext()) {
                val raw = c.getLong(startIdx)
                out += Term(
                    id = c.getLong(idIdx),
                    name = c.getString(nameIdx),
                    startMonday = if (raw == DbHelper.NO_TERM_START) null else LocalDate.ofEpochDay(raw),
                    createdAt = c.getLong(createdIdx),
                    archived = archivedIdx >= 0 && c.getInt(archivedIdx) != 0,
                )
            }
            out
        }
    }

    fun get(id: Long): Term? = list().firstOrNull { it.id == id }

    fun add(term: Term): Long {
        val db = dbHelper.writableDatabase
        return db.insert(DbHelper.TABLE_TERMS, null, term.toValues())
    }

    fun save(term: Term) {
        if (term.id <= 0L) {
            add(term)
            return
        }
        dbHelper.writableDatabase.update(
            DbHelper.TABLE_TERMS, term.toValues(),
            "${DbHelper.T_ID} = ?", arrayOf(term.id.toString()),
        )
    }

    /**
     * 没有学期时建一个空的，保证界面永远有「当前学期」可用。
     *
     * 名字按开学日推：想省事就先用默认名，用户随时能改。
     */
    fun ensureAtLeastOne(context: Context): Term {
        val existing = list()
        if (existing.isNotEmpty()) return existing.first()
        val start = Prefs.termStartMonday(context)
        val created = Term(
            name = defaultNameFor(start),
            startMonday = start,
            createdAt = System.currentTimeMillis(),
        )
        val id = add(created)
        return created.copy(id = id)
    }

    /**
     * v3 → v4 升级后，老课全在 `term_id = 0`。把它们收进第一个学期，
     * 并把 Prefs 里的老开学日搬进该学期（搬完清掉，免得两处真相打架）。
     */
    fun adoptOrphanCourses(context: Context): Int {
        val term = ensureAtLeastOne(context)
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply { put(DbHelper.C_TERM_ID, term.id) }
        val n = db.update(
            DbHelper.TABLE_COURSES, values,
            "${DbHelper.C_TERM_ID} = 0", null,
        )
        // 老开学日在 Prefs 里；搬进学期后清掉，只留一个真相。
        val legacyStart = Prefs.termStartMonday(context)
        if (legacyStart != null && term.startMonday == null) {
            save(term.copy(startMonday = legacyStart))
        }
        if (legacyStart != null) Prefs.setTermStartMonday(context, null)
        return n
    }

    /** 新学期的默认名：`2026 秋季学期` / `2027 春季学期`，按开学日所在月份推。 */
    companion object {
        fun defaultNameFor(start: LocalDate?): String {
            if (start == null) return "新课表"
            val season = if (start.monthValue in 2..7) "春季学期" else "秋季学期"
            return "${start.year} $season"
        }
    }

    /** 复制一个学期的壳（不含课）。给「新学期沿用上一学期作息」用。 */
    fun duplicateShell(source: Term, name: String = source.name + " 副本"): Long =
        add(Term(name = name, startMonday = source.startMonday, createdAt = System.currentTimeMillis()))

    /** 删一个学期（连带它的课与例外）。不允许删最后一个，见 [canDelete]。 */
    fun remove(term: Term) {
        if (!canDelete(term)) return
        CourseStore(appContext).clearTerm(term.id)
        dbHelper.writableDatabase.delete(
            DbHelper.TABLE_TERMS, "${DbHelper.T_ID} = ?", arrayOf(term.id.toString()),
        )
    }

    /** 至少留一个学期，否则界面没有「当前学期」可用。 */
    fun canDelete(term: Term): Boolean = list().size > 1 && term.id > 0L

    /**
     * 整表替换：只替换学期表（导备份用）。返回带新 id 的学期，下标与传入顺序一致 ——
     * 备份里的课程就是按下标挂学期的。
     */
    fun replaceAll(terms: List<Term>): List<Term> {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(DbHelper.TABLE_TERMS, null, null)
            for (t in terms) db.insert(DbHelper.TABLE_TERMS, null, t.toValues())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return list()
    }

    private fun Term.toValues(): ContentValues = ContentValues().apply {
        put(DbHelper.T_NAME, name)
        put(DbHelper.T_START, startMonday?.toEpochDay() ?: DbHelper.NO_TERM_START)
        put(DbHelper.T_CREATED_AT, if (createdAt > 0L) createdAt else System.currentTimeMillis())
        put(DbHelper.T_ARCHIVED, if (archived) 1 else 0)
    }
}
