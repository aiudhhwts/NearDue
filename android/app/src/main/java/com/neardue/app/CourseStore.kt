package com.neardue.app

import android.content.ContentValues
import android.content.Context

/**
 * 课表存储。与 [ItemStore] 完全分开 —— 课表里的课不进清单、不排闹钟。
 *
 * v1.18 起课挂学期（`term_id`）：传 `termId = 0` 是「不分学期，全部课」，
 * 传具体 id 就只拿那个学期的。界面上一律传当前学期的 id。
 */
class CourseStore(context: Context) {

    private val dbHelper = DbHelper(context.applicationContext)

    /** 某一学期的课；[termId] 传 0 表示不筛学期（迁移、去重统计时用）。 */
    fun list(termId: Long = 0L): List<Course> {
        val db = dbHelper.readableDatabase
        val where = if (termId > 0L) "${DbHelper.C_TERM_ID} = ?" else null
        val args = if (termId > 0L) arrayOf(termId.toString()) else null
        val cursor = db.query(
            DbHelper.TABLE_COURSES, null, where, args, null, null,
            "${DbHelper.C_DAY} ASC, ${DbHelper.C_SESSION_START} ASC, ${DbHelper.C_ID} ASC",
        )
        return cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DbHelper.C_ID)
            val titleIdx = c.getColumnIndexOrThrow(DbHelper.C_TITLE)
            val dayIdx = c.getColumnIndexOrThrow(DbHelper.C_DAY)
            val sIdx = c.getColumnIndexOrThrow(DbHelper.C_SESSION_START)
            val eIdx = c.getColumnIndexOrThrow(DbHelper.C_SESSION_END)
            val wIdx = c.getColumnIndexOrThrow(DbHelper.C_WEEKS)
            val rIdx = c.getColumnIndexOrThrow(DbHelper.C_ROOM)
            val catIdx = c.getColumnIndexOrThrow(DbHelper.C_CATEGORY)
            val termIdx = c.getColumnIndexOrThrow(DbHelper.C_TERM_ID)
            // v1.23：老库没升上来时这两列会缺，不能因此 crash。
            val teacherIdx = c.getColumnIndex(DbHelper.C_TEACHER)
            val noteIdx = c.getColumnIndex(DbHelper.C_NOTE)
            val leadIdx = c.getColumnIndex(DbHelper.C_LEAD_MINUTES)
            val out = mutableListOf<Course>()
            while (c.moveToNext()) {
                out += Course(
                    id = c.getLong(idIdx),
                    title = c.getString(titleIdx),
                    dayOfWeek = c.getInt(dayIdx),
                    sessionStart = c.getInt(sIdx),
                    sessionEnd = c.getInt(eIdx),
                    weeks = c.getString(wIdx),
                    room = c.getString(rIdx),
                    category = Category.of(c.getInt(catIdx)),
                    termId = c.getLong(termIdx),
                    teacher = if (teacherIdx >= 0) c.getString(teacherIdx) else "",
                    note = if (noteIdx >= 0) c.getString(noteIdx) else "",
                    leadMinutes = if (leadIdx >= 0)
                        CourseLead.normalize(c.getInt(leadIdx)) else CourseLead.USE_GLOBAL,
                )
            }
            out
        }
    }

    fun get(id: Long): Course? = list().firstOrNull { it.id == id }

    fun add(course: Course): Long {
        val db = dbHelper.writableDatabase
        return db.insert(DbHelper.TABLE_COURSES, null, course.toValues())
    }

    fun addAll(courses: List<Course>): Int {
        val db = dbHelper.writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            for (c in courses) {
                if (db.insert(DbHelper.TABLE_COURSES, null, c.toValues()) > 0) n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    fun save(course: Course) {
        if (course.id <= 0L) { add(course); return }
        dbHelper.writableDatabase.update(
            DbHelper.TABLE_COURSES, course.toValues(),
            "${DbHelper.C_ID} = ?", arrayOf(course.id.toString()),
        )
    }

    fun remove(id: Long) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // 课没了，它的单周例外也没意义 —— 一起清，不留垃圾行。
            db.delete(DbHelper.TABLE_OVERRIDES, "${DbHelper.O_COURSE_ID} = ?", arrayOf(id.toString()))
            db.delete(DbHelper.TABLE_COURSES, "${DbHelper.C_ID} = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * **整表替换**：只清掉这一学期的课，别的学期一根毫毛都不动。
     *
     * 这是 v1.18「整表替换开关」的底层 —— 用户导入新课表时选「替换」，
     * 换的是当前学期，不是把手机上所有学期都推平。
     */
    fun replaceTerm(termId: Long, courses: List<Course>): Int {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        var n = 0
        try {
            db.delete(
                DbHelper.TABLE_OVERRIDES,
                "${DbHelper.O_COURSE_ID} IN (SELECT ${DbHelper.C_ID} FROM ${DbHelper.TABLE_COURSES} WHERE ${DbHelper.C_TERM_ID} = ?)",
                arrayOf(termId.toString()),
            )
            db.delete(DbHelper.TABLE_COURSES, "${DbHelper.C_TERM_ID} = ?", arrayOf(termId.toString()))
            for (c in courses) {
                val values = c.toValues()
                values.put(DbHelper.C_TERM_ID, termId)
                if (db.insert(DbHelper.TABLE_COURSES, null, values) > 0L) n++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    /** 整库替换（导备份用）：所有学期一起换。 */
    fun replaceAll(courses: List<Course>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(DbHelper.TABLE_OVERRIDES, null, null)
            db.delete(DbHelper.TABLE_COURSES, null, null)
            for (c in courses) db.insert(DbHelper.TABLE_COURSES, null, c.toValues())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 删掉一个学期的全部课与例外（删学期时调用）。 */
    fun clearTerm(termId: Long): Int {
        val db = dbHelper.writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            db.delete(
                DbHelper.TABLE_OVERRIDES,
                "${DbHelper.O_COURSE_ID} IN (SELECT ${DbHelper.C_ID} FROM ${DbHelper.TABLE_COURSES} WHERE ${DbHelper.C_TERM_ID} = ?)",
                arrayOf(termId.toString()),
            )
            n = db.delete(DbHelper.TABLE_COURSES, "${DbHelper.C_TERM_ID} = ?", arrayOf(termId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    /** 把一个学期的课整体搬到另一个学期（合并学期用）。 */
    fun moveTermCourses(fromTermId: Long, toTermId: Long): Int {
        val values = ContentValues().apply { put(DbHelper.C_TERM_ID, toTermId) }
        return dbHelper.writableDatabase.update(
            DbHelper.TABLE_COURSES, values,
            "${DbHelper.C_TERM_ID} = ?", arrayOf(fromTermId.toString()),
        )
    }

    /**
     * 同一门课的同天同节次 → 去重用。
     *
     * **不含周次**，与 [CourseImporter.courseKey] 必须是同一把尺子：教务系统会把一门课按周次
     * 拆成多行，导进来会并成一门（周次取并集）；再导一次时也得认出是同一门，不能重复加。
     */
    fun keyOf(c: Course): String = CourseImporter.courseKey(c)

    /** 该学期已有的课的 key（导入时判重用）。 */
    fun existingKeys(termId: Long = 0L): Set<String> = list(termId).map { keyOf(it) }.toSet()

    // ── 单周例外（仅本周改课 / 停课）──────────────────────────────

    /** 全部例外，按 (课, 周次) 排序。界面上按「第几周」查，一次读进来就够。 */
    fun listOverrides(): List<CourseOverride> {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            DbHelper.TABLE_OVERRIDES, null, null, null, null, null,
            "${DbHelper.O_COURSE_ID} ASC, ${DbHelper.O_WEEK} ASC",
        )
        return cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DbHelper.O_COURSE_ID)
            val wIdx = c.getColumnIndexOrThrow(DbHelper.O_WEEK)
            val skipIdx = c.getColumnIndexOrThrow(DbHelper.O_SKIP)
            val dIdx = c.getColumnIndexOrThrow(DbHelper.O_DAY)
            val sIdx = c.getColumnIndexOrThrow(DbHelper.O_SESSION_START)
            val eIdx = c.getColumnIndexOrThrow(DbHelper.O_SESSION_END)
            val rIdx = c.getColumnIndexOrThrow(DbHelper.O_ROOM)
            val out = mutableListOf<CourseOverride>()
            while (c.moveToNext()) {
                val room = if (c.isNull(rIdx)) null else c.getString(rIdx)
                CourseOverride.of(
                    courseId = c.getLong(idIdx),
                    week = c.getInt(wIdx),
                    skip = c.getInt(skipIdx) != 0,
                    dayOfWeek = c.getInt(dIdx),
                    sessionStart = c.getInt(sIdx),
                    sessionEnd = c.getInt(eIdx),
                    room = room,
                )?.let { out += it }
            }
            out
        }
    }

    /** 只读某一学期的例外：先拿该学期的课 id，再过滤。 */
    fun overridesForTerm(termId: Long): List<CourseOverride> {
        val ids = list(termId).map { it.id }.toSet()
        if (ids.isEmpty()) return emptyList()
        return listOverrides().filter { it.courseId in ids }
    }

    /** 只读某一周的例外，课表渲染用。 */
    fun overridesForWeek(week: Int): Map<Long, CourseOverride> =
        listOverrides().filter { it.week == week }.associateBy { it.courseId }

    /**
     * 写一条例外。同一门课同一周只留一条 —— 改两次就是改第二次的意思，不叠加。
     */
    fun saveOverride(o: CourseOverride) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                DbHelper.TABLE_OVERRIDES,
                "${DbHelper.O_COURSE_ID} = ? AND ${DbHelper.O_WEEK} = ?",
                arrayOf(o.courseId.toString(), o.week.toString()),
            )
            db.insert(DbHelper.TABLE_OVERRIDES, null, o.toValues())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 删掉某一周的例外（「恢复这周原样」）。 */
    fun removeOverride(courseId: Long, week: Int) {
        dbHelper.writableDatabase.delete(
            DbHelper.TABLE_OVERRIDES,
            "${DbHelper.O_COURSE_ID} = ? AND ${DbHelper.O_WEEK} = ?",
            arrayOf(courseId.toString(), week.toString()),
        )
    }

    /** 清孤岛行：指向已不存在的课的例外。导备份、清库后都可能留下这种。 */
    fun purgeOrphanOverrides(): Int {
        val db = dbHelper.writableDatabase
        return db.delete(
            DbHelper.TABLE_OVERRIDES,
            "${DbHelper.O_COURSE_ID} NOT IN (SELECT ${DbHelper.C_ID} FROM ${DbHelper.TABLE_COURSES})",
            null,
        )
    }

    private fun CourseOverride.toValues(): ContentValues = ContentValues().apply {
        put(DbHelper.O_COURSE_ID, courseId)
        put(DbHelper.O_WEEK, week)
        put(DbHelper.O_SKIP, if (skip) 1 else 0)
        put(DbHelper.O_DAY, dayOfWeek)
        put(DbHelper.O_SESSION_START, sessionStart)
        put(DbHelper.O_SESSION_END, sessionEnd)
        if (room == null) putNull(DbHelper.O_ROOM) else put(DbHelper.O_ROOM, room)
    }

    private fun Course.toValues(): ContentValues = ContentValues().apply {
        put(DbHelper.C_TITLE, title)
        put(DbHelper.C_DAY, dayOfWeek)
        put(DbHelper.C_SESSION_START, sessionStart)
        put(DbHelper.C_SESSION_END, sessionEnd)
        put(DbHelper.C_WEEKS, CourseWeeks.normalize(weeks))
        put(DbHelper.C_ROOM, room)
        put(DbHelper.C_CATEGORY, category.ordinal)
        put(DbHelper.C_TERM_ID, termId)
        put(DbHelper.C_TEACHER, teacher)
        put(DbHelper.C_NOTE, note)
        put(DbHelper.C_LEAD_MINUTES, leadMinutes)
    }
}
