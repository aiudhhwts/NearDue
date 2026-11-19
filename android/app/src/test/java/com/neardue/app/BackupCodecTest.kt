package com.neardue.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class BackupCodecTest {

    private val sample = listOf(
        Item(
            id = 1L, title = "高数作业", dueAt = 1794726000000L,
            category = Category.HOMEWORK, done = false, remindOffsets = "-1440,-120",
        ),
        Item(
            id = 2L, title = "六级考试", dueAt = 1794823200000L,
            category = Category.EXAM, done = true, remindOffsets = "-1440",
        ),
        Item(
            id = 3L, title = "篮球队训练", dueAt = 1794909600000L,
            category = Category.EVENT, done = false,
        ),
    )

    @Test
    fun roundTripKeepsEveryField() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sample))
        assertEquals(sample.size, decoded.size)
        sample.zip(decoded).forEach { (a, b) ->
            assertEquals(a.title, b.title)
            assertEquals(a.dueAt, b.dueAt)
            assertEquals(a.category, b.category)
            assertEquals(a.done, b.done)
            assertEquals(a.remindOffsets, b.remindOffsets)
            assertEquals(0L, b.id) // id 由 SQLite 重新生成，不写进备份
        }
    }

    @Test
    fun emptyListProducesEmptyArray() {
        val json = BackupCodec.encode(emptyList())
        assertTrue(json.contains("["))
        assertTrue(BackupCodec.decode(json).isEmpty())
    }

    @Test
    fun blankBackupIsRejected() {
        try {
            BackupCodec.decode("   ")
            fail("空备份应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("备份文件为空"))
        }
    }

    @Test
    fun malformedJsonIsRejected() {
        try {
            BackupCodec.decode("not json at all")
            fail("非法 JSON 应被拒绝")
        } catch (e: Exception) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val decoded = BackupCodec.decode("""[{"title":"只写了个标题","dueAt":1794726000000}]""")
        assertEquals(1, decoded.size)
        assertEquals("只写了个标题", decoded[0].title)
        assertEquals(1794726000000L, decoded[0].dueAt)
        assertEquals(Category.OTHER, decoded[0].category)
        assertEquals(false, decoded[0].done)
        assertEquals("-1440,-120", decoded[0].remindOffsets)
    }

    // ── v2：事项 + 课表一起备 ─────────────────────────────────────────────

    private val courses = listOf(
        Course(
            title = "走进生命科学", dayOfWeek = 1, sessionStart = 1, sessionEnd = 2,
            weeks = "3,4,6-11", room = "A101", category = Category.HOMEWORK,
        ),
        Course(
            title = "体育Ⅱ", dayOfWeek = 1, sessionStart = 3, sessionEnd = 4,
            weeks = "3,4,6-18", room = "操场", category = Category.EVENT,
        ),
    )

    @Test
    fun roundTripKeepsCoursesToo() {
        val back = BackupCodec.decodeBundle(
            BackupCodec.encode(BackupCodec.Bundle(items = sample, courses = courses)),
        )
        assertEquals(sample.size, back.items.size)
        assertEquals(courses.size, back.courses.size)
        assertTrue("v2 备份必须声明自己有课程表", back.hasCourses)
        courses.zip(back.courses).forEach { (a, b) ->
            assertEquals(a.title, b.title)
            assertEquals(a.dayOfWeek, b.dayOfWeek)
            assertEquals(a.sessionStart, b.sessionStart)
            assertEquals(a.sessionEnd, b.sessionEnd)
            assertEquals(a.weeks, b.weeks)
            assertEquals(a.room, b.room)
            assertEquals(a.category, b.category)
            assertEquals(0L, b.id) // 同上，id 不写进备份
        }
    }

    @Test
    fun newFormatIsAnObjectNotABareArray() {
        val json = BackupCodec.encode(BackupCodec.Bundle(items = sample, courses = courses))
        assertTrue(json.trimStart().startsWith("{"))
        assertTrue(json.contains("\"format\": " + BackupCodec.FORMAT_VERSION))
        assertTrue(json.contains("\"courses\""))
    }

    @Test
    fun teacherAndNoteRoundTripInBackup() {
        val c = Course(title = "线性代数", dayOfWeek = 3, sessionStart = 1, sessionEnd = 2,
            teacher = "李老师", note = "单周交作业")
        val json = BackupCodec.encode(BackupCodec.Bundle(items = emptyList(), courses = listOf(c)))
        val back = BackupCodec.decodeBundle(json)
        assertEquals(1, back.courses.size)
        assertEquals("李老师", back.courses[0].teacher)
        assertEquals("单周交作业", back.courses[0].note)
    }

    @Test
    fun oldBackupWithoutTeacherAndNoteFallsBackToEmpty() {
        val back = BackupCodec.decodeBundle(
            """{"format":4,"items":[],"courses":[{"title":"老课","dayOfWeek":2,"sessionStart":3,"sessionEnd":4}]}"""
        )
        assertEquals(1, back.courses.size)
        assertEquals("", back.courses[0].teacher)
        assertEquals("", back.courses[0].note)
    }

    @Test
    fun oldBareArrayStillImports() {
        // v1 文件：裸数组，只有事项。必须能读，而且**不能声称自己有课程表**（否则会把现课表清空）。
        val back = BackupCodec.decodeBundle("""[{"title":"高数作业","dueAt":1794726000000}]""")
        assertEquals(1, back.items.size)
        assertTrue(back.courses.isEmpty())
        assertEquals(false, back.hasCourses)
    }

    @Test
    fun emptyCourseListInBundleIsStillDeclared() {
        // v2 文件写着 courses: [] —— 这是用户真的没课，导入时应当把课表清空（hasCourses = true）。
        val back = BackupCodec.decodeBundle("""{"format":2,"items":[],"courses":[]}""")
        assertTrue(back.items.isEmpty())
        assertTrue(back.courses.isEmpty())
        assertTrue(back.hasCourses)
    }

    @Test
    fun courseWithoutOptionalFieldsGetsDefaults() {
        val back = BackupCodec.decodeBundle(
            """{"format":2,"items":[],"courses":[{"title":"只有名字","dayOfWeek":3}]}""",
        )
        assertEquals(1, back.courses.size)
        val c = back.courses[0]
        assertEquals("只有名字", c.title)
        assertEquals(3, c.dayOfWeek)
        assertEquals(1, c.sessionStart)
        assertEquals(1, c.sessionEnd)
        assertEquals("", c.weeks)
        assertEquals("", c.room)
        assertEquals(Category.HOMEWORK, c.category)
    }

    // ── v3：单周例外（仅本周改课 / 停课）────────────────────────

    @Test
    fun overridesRoundTrip() {
        val recs = listOf(
            BackupCodec.OverrideRecord(
                courseKey = "高等数学甲I|1|5|6", week = 9, skip = true,
            ),
            BackupCodec.OverrideRecord(
                courseKey = "生物学|2|9|10", week = 11,
                dayOfWeek = 4, sessionStart = 3, sessionEnd = 4, room = "B202",
            ),
        )
        val json = BackupCodec.encode(
            BackupCodec.Bundle(items = sample, courses = courses, hasCourses = true, overrides = recs, hasOverrides = true),
        )
        val back = BackupCodec.decodeBundle(json)
        assertTrue(back.hasOverrides)
        assertEquals(2, back.overrides.size)
        assertEquals(recs[0], back.overrides[0])
        assertEquals(recs[1], back.overrides[1])
    }

    @Test
    fun oldV2FileHasNoOverrides() {
        // v2 文件没有 overrides 键 —— 绝不能把空的例外表当成「用户删光了」去清库。
        val back = BackupCodec.decodeBundle("""{"format":2,"items":[],"courses":[]}""")
        assertTrue(back.hasCourses)
        assertEquals(false, back.hasOverrides)
        assertTrue(back.overrides.isEmpty())
    }

    @Test
    fun nullRoomMeansKeepAndEmptyRoomMeansNone() {
        // room = null：这周不改教室；room = ""：这周没教室。两者不能混。
        val recs = listOf(
            BackupCodec.OverrideRecord(courseKey = "A|1|1|2", week = 3, room = null),
            BackupCodec.OverrideRecord(courseKey = "B|1|1|2", week = 3, room = ""),
        )
        val back = BackupCodec.decodeBundle(
            BackupCodec.encode(BackupCodec.Bundle(overrides = recs, hasOverrides = true)),
        )
        assertEquals(null, back.overrides[0].room)
        assertEquals("", back.overrides[1].room)
    }

    @Test
    fun formatVersionIsFour() {
        assertEquals(4, BackupCodec.FORMAT_VERSION)
    }

    // ── v4：多学期并存 ──────────────────────────────────

    @Test
    fun termsRoundTripWithCurrentIndex() {
        val terms = listOf(
            BackupCodec.TermRecord(index = 0, name = "2026 秋季学期", startMonday = LocalDate.of(2026, 9, 7)),
            BackupCodec.TermRecord(index = 1, name = "2027 春季学期", startMonday = null),
        )
        val json = BackupCodec.encode(
            BackupCodec.Bundle(
                courses = courses, hasCourses = true,
                terms = terms, hasTerms = true, currentTermIndex = 1,
            ),
        )
        val back = BackupCodec.decodeBundle(json)
        assertTrue(back.hasTerms)
        assertEquals(2, back.terms.size)
        assertEquals("2026 秋季学期", back.terms[0].name)
        assertEquals(LocalDate.of(2026, 9, 7), back.terms[0].startMonday)
        assertEquals(null, back.terms[1].startMonday)
        assertEquals(1, back.currentTermIndex)
    }

    @Test
    fun oldV3FileHasNoTerms() {
        // v3 文件没有 terms 键 —— 导入时必须自己造一个学期接课，而不是把课弄丢。
        val back = BackupCodec.decodeBundle(
            """{"format":3,"items":[],"courses":[],"overrides":[]}""",
        )
        assertTrue(back.hasCourses)
        assertEquals(false, back.hasTerms)
        assertTrue(back.terms.isEmpty())
        assertEquals(-1, back.currentTermIndex)
    }

    @Test
    fun coursesCarryTermIndexNotDatabaseId() {
        // 写文件前必须把数据库 id 换成「第几个学期」，否则换手机后课全挂错学期。
        val t1 = Term(id = 7L, name = "A")
        val t2 = Term(id = 9L, name = "B")
        val cs = listOf(
            Course(title = "课一", dayOfWeek = 1, sessionStart = 1, sessionEnd = 2, termId = 9L),
            Course(title = "课二", dayOfWeek = 2, sessionStart = 3, sessionEnd = 4, termId = 7L),
        )
        val tagged = BackupCodec.coursesWithTermIndex(cs, listOf(t1, t2))
        assertEquals(2L, tagged[0].termId) // 数据库 id 9 → 序号 2
        assertEquals(1L, tagged[1].termId) // 数据库 id 7 → 序号 1
        // 反方向要能换回真的学期 id：序号从 1 起，0 是「没挂学期」的哨兵。
        val fresh = listOf(Term(id = 41L, name = "A"), Term(id = 43L, name = "B"))
        assertEquals(43L, BackupCodec.termIdForIndex(2L, fresh, 41L))
        assertEquals(41L, BackupCodec.termIdForIndex(1L, fresh, 41L))
        assertEquals(41L, BackupCodec.termIdForIndex(0L, fresh, 41L))
    }
    @Test
    fun archivedTermAndLeadMinutesRoundTrip() {
        val back = BackupCodec.encode(
            BackupCodec.Bundle(
                items = emptyList(),
                courses = listOf(
                    Course(
                        id = 1L, title = "跨楼课", dayOfWeek = 1, sessionStart = 1, sessionEnd = 2,
                        termId = 1L, leadMinutes = 20,
                    ),
                ),
                hasCourses = true,
                terms = listOf(
                    BackupCodec.TermRecord(index = 0, name = "旧学期", archived = true),
                    BackupCodec.TermRecord(index = 1, name = "本学期"),
                ),
                hasTerms = true,
                currentTermIndex = 1,
            ),
        )
        val parsed = BackupCodec.decodeBundle(back)
        assertTrue(parsed.terms[0].archived)
        assertEquals(false, parsed.terms[1].archived)
        assertEquals(20, parsed.courses[0].leadMinutes)
    }

    @Test
    fun oldBackupWithoutNewKeysFallsBack() {
        val parsed = BackupCodec.decodeBundle(
            """{"format":4,"items":[],"courses":[{"title":"课","dayOfWeek":1,"sessionStart":1,"sessionEnd":2}],"terms":[{"name":"老学期"}],"hasTerms":true,"currentTermIndex":0}""",
        )
        assertEquals(false, parsed.terms[0].archived)
        assertEquals(CourseLead.USE_GLOBAL, parsed.courses[0].leadMinutes)
    }

}
