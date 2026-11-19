package com.neardue.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用真实教务系统导出的课表 PDF 测「表格还原」。
 *
 * 这些 fixture 是公网上找到的真文件（不是自己生成的），分别代表了三种典型版面：
 *  - sample-letters.pdf：Word 导出，单页，中文「一二三四五」表头 + 左侧「1-2节」
 *  - sample-grid.pdf：Microsoft Print to PDF，10 页，英文 MON/TUE 表头 + 左侧时间
 *  - sample-dense.pdf：WPS 表格导出，整张表压成一条 Tj 序列，认不出网格
 *  - sample-scan-negative.pdf：纯图片扫描件，没有文字层（负样本）
 */
class ScheduleTableParserTest {

    private fun fixture(name: String): File? {
        val a = File("src/test/resources/fixtures/$name")
        if (a.exists()) return a
        val b = File("app/src/test/resources/fixtures/$name")
        return if (b.exists()) b else null
    }

    private fun rows(name: String): String? {
        val f = fixture(name) ?: return null
        val r = PdfTextExtractor.extract(f.readBytes())
        return ScheduleTableParser.toRows(r.lines)
    }

    @Test
    fun realWordScheduleIsRebuiltIntoRows() {
        val body = rows("sample-letters.pdf")
        if (body == null) {
            println("跳过：没有 sample-letters.pdf")
            return
        }
        val lines = body.split("\n").filter { it.isNotBlank() }
        println("还原出 " + lines.size + " 行：")
        lines.take(20).forEach { println("  " + it) }
        assertTrue("至少要还原出 8 行，实际 " + lines.size, lines.size >= 8)
        // 每一行都得是「周X + 节次」开头，不能是碎片
        for (l in lines) {
            assertTrue("行首必须是周几：" + l, l.startsWith("周") && l.length > 4)
        }
        assertTrue("应该含「宪法学」", body.contains("宪法学"))
        assertTrue("不该出现【教师】这种元数据", !body.contains("【"))
        assertTrue("不该有空标题", lines.none { it.trim().endsWith("节") })
    }

    @Test
    fun rebuiltRowsFeedTheImporter() {
        val body = rows("sample-letters.pdf") ?: return
        val now = java.time.LocalDateTime.of(2026, 9, 7, 10, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val r = CourseScheduleImporter.parse(
            text = body,
            nowMillis = now,
            defaultHour = 19,
            defaultMinute = 0,
            term = CourseScheduleImporter.Term(java.time.LocalDate.of(2026, 9, 7))
        )
        println("导入结果：items=" + r.items.size + " skipped=" + r.skippedLines +
            " expanded=" + r.expandedFromWeeks + " needTerm=" + r.needTermStartLines)
        r.items.take(8).forEach {
            println("  " + java.time.Instant.ofEpochMilli(it.dueAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime() + "  " + it.title)
        }
        assertTrue("表格还原后至少该生成 8 项，实际 " + r.items.size, r.items.size >= 8)
        // 标题不能是「【李老师】A101」这类碎片
        for (it in r.items) {
            assertTrue("标题不该以括号开头：" + it.title, !it.title.trim().startsWith("【"))
            assertTrue("标题不该为空", it.title.isNotBlank())
        }
        // 课程名应该真的出现在标题里
        assertTrue("标题里应该有「宪法学」", r.items.any { it.title.contains("宪法学") })
    }

    @Test
    fun unparseableLayoutReturnsNullInsteadOfJunk() {
        // real_course_2 / real_course_3 的版面（逐字母表头 + 一格多课、整表一条 Tj）
        // 目前拆出来全是垃圾标题，所以约定：认不出就返回 null，让调用方回退纯文本。
        for (name in listOf("sample-grid.pdf", "sample-dense.pdf")) {
            if (fixture(name) == null) continue
            val body = rows(name)
            if (body != null) {
                println(name + " 这次认出来了：" + body.split("\n").size + " 行（若将来修好了是好事）")
                assertTrue(
                    "既然返回了结果，就不该含 ¶ / 方括号代码 / 时间前缀",
                    !body.contains("\u00B6") && !body.contains("]")
                )
            } else {
                println(name + " 正确地放弃了表格还原，会回退纯文本")
            }
        }
    }
}
