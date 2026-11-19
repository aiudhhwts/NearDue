package com.neardue.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 三张表，各管各的：
 *
 * - `items`   —— 截止事项，进清单、排提醒（v0.1 起）
 * - `courses` —— 课表里的课，只在课表视图里出现，**不变成事项、不响铃**（v1.9 起）
 * - `terms`   —— 学期（v1.18 起）。一份学期 = 一套课 + 开学日，切换学期就是换一整套课。
 *
 * 早期需求明确要求事项与课表分开，所以课表有自己的表；早期需求要求「多学期并存」，
 * 所以课程再挂一个 `term_id`。
 */
class DbHelper(context: Context) : SQLiteOpenHelper(context, "neardue.db", null, DB_VERSION) {

    companion object {
        const val DB_VERSION = 6

        const val TABLE = "items"
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_DUE_AT = "due_at"
        const val COL_CATEGORY = "category"
        const val COL_DONE = "done"
        const val COL_REMIND_OFFSETS = "remind_offsets"

        const val TABLE_COURSES = "courses"
        const val C_ID = "id"
        const val C_TITLE = "title"
        const val C_DAY = "day_of_week"
        const val C_SESSION_START = "session_start"
        const val C_SESSION_END = "session_end"
        const val C_WEEKS = "weeks"
        const val C_ROOM = "room"
        const val C_CATEGORY = "category"
        /** 属于哪个学期。v1.18 之前的老库没有这列，升级时补上并归到第一个学期。 */
        const val C_TERM_ID = "term_id"
        /** 任课教师 / 备注（v1.23 起，选填）。 */
        const val C_TEACHER = "teacher"
        const val C_NOTE = "note"
        /** 这门课自己的提前量分钟数（v1.31），-1 = 跟全局。 */
        const val C_LEAD_MINUTES = "lead_minutes"

        /** 某一周对某门课的例外（停课 / 换教室 / 挪节次），见 [CourseOverride]。 */
        const val TABLE_OVERRIDES = "course_overrides"
        const val O_COURSE_ID = "course_id"
        const val O_WEEK = "week"
        const val O_SKIP = "skip"
        const val O_DAY = "day_of_week"
        const val O_SESSION_START = "session_start"
        const val O_SESSION_END = "session_end"
        const val O_ROOM = "room"

        /** 学期表。一个学期 = 名字 + 第 1 周周一，课都挂在它下面。 */
        const val TABLE_TERMS = "terms"
        const val T_ID = "id"
        const val T_NAME = "name"
        /** 第 1 周周一的 epochDay；0 = 还没设（课表不展开周次，绝不瞎猜日期）。 */
        const val T_START = "start_monday"
        const val T_CREATED_AT = "created_at"
        /** 是否归档（v1.31），0 = 活动，1 = 归档。 */
        const val T_ARCHIVED = "archived"

        /** `start_monday` 的哨兵值：用户还没选开学日。 */
        const val NO_TERM_START = 0L
    }

    override fun onCreate(db: SQLiteDatabase) {
        createItems(db)
        createCourses(db)
        createOverrides(db)
        createTerms(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 只加表加列，绝不 DROP —— v0.1 的骨架版是删表重建，正式版必须保住用户数据。
        if (oldVersion < 1) createItems(db)
        if (oldVersion < 2) createCourses(db)
        if (oldVersion < 3) createOverrides(db)
        if (oldVersion < 4) migrateToTerms(db)
        // v1.23：教师 / 备注两列。从老库一路升上来的可能已由 createCourses 建好，
        // 所以这里逐列 try/catch，重复列直接忽略（只加不删，绝不 DROP）。
        if (oldVersion < 5) addCourseExtraFields(db)
        // v1.31：学期归档标记 + 单课提前量，逐列 try/catch 幂等（只加不删）。
        if (oldVersion < 6) addV131Fields(db)
    }

    private fun addV131Fields(db: SQLiteDatabase) {
        for (col in listOf(
            "ALTER TABLE ${TABLE_TERMS} ADD COLUMN ${T_ARCHIVED} INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE ${TABLE_COURSES} ADD COLUMN ${C_LEAD_MINUTES} INTEGER NOT NULL DEFAULT -1",
        )) {
            try {
                db.execSQL(col)
            } catch (_: android.database.SQLException) {
                // 列已存在，忽略。
            }
        }
    }

    private fun addCourseExtraFields(db: SQLiteDatabase) {
        for (col in listOf(C_TEACHER to "TEXT NOT NULL DEFAULT ''", C_NOTE to "TEXT NOT NULL DEFAULT ''")) {
            try {
                db.execSQL("ALTER TABLE ${TABLE_COURSES} ADD COLUMN ${col.first} ${col.second}")
            } catch (_: android.database.SQLException) {
                // 列已存在（例如从 v1 一路 createCourses 建出来的），忽略。
            }
        }
    }

    private fun createItems(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, " +
                "due_at INTEGER NOT NULL, " +
                "category INTEGER NOT NULL DEFAULT 4, " +
                "done INTEGER NOT NULL DEFAULT 0, " +
                "remind_offsets TEXT NOT NULL DEFAULT '-1440,-120'" +
                ")"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_items_due ON items(due_at)")
    }

    private fun createCourses(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS courses (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "title TEXT NOT NULL, " +
                "day_of_week INTEGER NOT NULL, " +
                "session_start INTEGER NOT NULL, " +
                "session_end INTEGER NOT NULL, " +
                "weeks TEXT NOT NULL DEFAULT '', " +
                "room TEXT NOT NULL DEFAULT '', " +
                "category INTEGER NOT NULL DEFAULT 1, " +
                "term_id INTEGER NOT NULL DEFAULT 0, " +
                "teacher TEXT NOT NULL DEFAULT '', " +
                "note TEXT NOT NULL DEFAULT '', " +
                "lead_minutes INTEGER NOT NULL DEFAULT -1" +
                ")"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_courses_day ON courses(day_of_week, session_start)")
    }

    /**
     * 单周改课的例外表。
     *
     * `room` 允许 NULL —— 空串是「这周没教室」，NULL 是「这周教室照旧」，两者必须分得开。
     * 不加外键约束：课程被删掉时留下的孤儿行由 [CourseStore.purgeOrphanOverrides] 清。
     * 例外按 `course_id` 关联，课本身就带 `term_id`，所以这里不用再存学期。
     */
    private fun createOverrides(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS course_overrides (" +
                "course_id INTEGER NOT NULL, " +
                "week INTEGER NOT NULL, " +
                "skip INTEGER NOT NULL DEFAULT 0, " +
                "day_of_week INTEGER NOT NULL DEFAULT 0, " +
                "session_start INTEGER NOT NULL DEFAULT 0, " +
                "session_end INTEGER NOT NULL DEFAULT 0, " +
                "room TEXT" +
                ")"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_overrides_key " +
                "ON course_overrides(course_id, week)"
        )
    }

    private fun createTerms(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS terms (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "start_monday INTEGER NOT NULL DEFAULT 0, " +
                "created_at INTEGER NOT NULL DEFAULT 0, " +
                "archived INTEGER NOT NULL DEFAULT 0" +
                ")"
        )
    }

    /**
     * v3 → v4：建学期表 + 给 `courses` 补 `term_id` 列。
     *
     * 老用户的课全都在，一条不删；先归到 `term_id = 0`，随后由
     * [TermStore.adoptOrphanCourses] 收进第一个学期（学期名沿用原来的开学日）。
     * 这样升级完立刻能正常显示，而不是让用户重导一遍课表。
     */
    private fun migrateToTerms(db: SQLiteDatabase) {
        createTerms(db)
        val hasTermId = try {
            db.rawQuery("PRAGMA table_info(courses)", null).use { c ->
                var found = false
                val nameIdx = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == C_TERM_ID) {
                        found = true
                        break
                    }
                }
                found
            }
        } catch (_: Exception) {
            false
        }
        if (!hasTermId) {
            db.execSQL(
                "ALTER TABLE courses ADD COLUMN term_id INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
}
