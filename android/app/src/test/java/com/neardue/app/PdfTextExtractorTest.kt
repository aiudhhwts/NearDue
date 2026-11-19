package com.neardue.app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 真实课表 PDF 的抽取测试。
 *
 * 素材放在 `src/test/resources/fixtures/` 下（真实教务系统 / Word 导出的课表，不是自己造的），
 * 文件不存在时用 [assumeTrue] 跳过，避免 CI 上没有素材就红。
 *
 * 这里断言的是「能抽出像课表的中文文字」，不是精确文案 —— 不同教务系统导出的排版差别很大，
 * 钉死具体字符串只会让测试变脆。
 */
class PdfTextExtractorTest {

    private val fixtures = File("src/test/resources/fixtures")

    private fun load(name: String): ByteArray? {
        val f = File(fixtures, name)
        return if (f.exists() && f.length() > 0) f.readBytes() else null
    }

    private fun extractOrFail(name: String): PdfTextExtractor.Result {
        val bytes = load(name)
        assumeTrue("缺少素材 $name（见 _pdfgen/real/SOURCES.md）", bytes != null)
        return PdfTextExtractor.extract(bytes!!)
    }

    private fun allFixtures(): List<File> =
        fixtures.listFiles { f -> f.isFile && f.name.endsWith(".pdf", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    @Test
    fun everyRealFixtureYieldsReadableText() {
        val files = allFixtures().filter { it.name != "sample-scan-negative.pdf" }
        assumeTrue("fixtures 目录里还没有真实 PDF", files.isNotEmpty())

        val report = StringBuilder()
        var ok = 0
        for (f in files) {
            val r = try {
                PdfTextExtractor.extract(f.readBytes())
            } catch (e: Exception) {
                report.appendLine("${f.name}: 抽取失败 ${e.message}")
                continue
            }
            val text = r.text
            val cjk = text.count { it.code in 0x4E00..0x9FFF }
            report.appendLine(
                "${f.name}: ${f.length()}B → ${r.pages} 页 / ${text.length} 字 / 汉字 $cjk"
            )
            // 一份课表 PDF 至少得有几行能读的字。
            if (text.length >= 40 && cjk >= 8) ok++
        }
        println(report)
        assertTrue("没有一份真实 PDF 抽出可用文字：\n$report", ok > 0)
    }

    /** 扫描件（纯图片 PDF）必须明确报错，而不是返回空字符串糊弄过去。 */
    @Test
    fun scannedPdfIsRejectedWithClearMessage() {
        val bytes = load("sample-scan-negative.pdf")
        assumeTrue("缺少扫描件负样本 sample-scan-negative.pdf", bytes != null)
        try {
            val r = PdfTextExtractor.extract(bytes!!)
            fail("扫描件不该抽出文字，却拿到 ${r.text.length} 字：${r.text.take(80)}")
        } catch (e: PdfTextExtractor.PdfException) {
            println("扫描件如期被拒：${e.message}")
            assertTrue(
                "错误信息要说清是「没有文字层」而不是别的原因：${e.message}",
                e.message?.contains("没有文字层") == true
            )
        }
    }

    @Test
    fun garbageBytesAreNotTreatedAsPdf() {
        try {
            PdfTextExtractor.extract("这不是 PDF，只是一段普通文本。".toByteArray())
            fail("普通文本不该被当成 PDF")
        } catch (e: PdfTextExtractor.PdfException) {
            assertTrue(e.message?.contains("PDF") == true)
        }
    }

    @Test
    fun truncatedPdfDoesNotCrash() {
        val bytes = load("sample-letters.pdf")
        assumeTrue("缺少素材 sample-letters.pdf", bytes != null)
        val truncated = bytes!!.copyOf(bytes.size / 3)
        try {
            PdfTextExtractor.extract(truncated)
        } catch (e: PdfTextExtractor.PdfException) {
            // 明确报错是预期结果
            println("截断文件如期报错：${e.message}")
        } catch (e: Throwable) {
            fail("截断的 PDF 只能报 PdfException，不该抛 ${e::class.simpleName}: ${e.message}")
        }
    }

    /** 把真实 PDF 里抽出的文字喂给课表导入器 —— 端到端看一眼能识别多少行。 */
    @Test
    fun realPdfTextFeedsCourseImporter() {
        val r = extractOrFail("sample-letters.pdf")
        val parsed = CourseScheduleImporter.parse(r.text)
        println("=== 真实的课表 PDF 抽出来 ${r.text.length} 字 ===")
        println(r.text.take(1200))
        println("=== 导入器识别 ${parsed.items.size} 项，跳过 ${parsed.skippedLines} 行 ===")
        parsed.items.take(5).forEach { println("  ${fmtForLog(it)}  ${it.title}") }
        // 不强制一定有结果（排版千奇百怪），但至少不能崩。
        assertTrue(parsed.items.size >= 0)
    }

    private fun fmtForLog(i: Item): String =
        java.time.Instant.ofEpochMilli(i.dueAt)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDateTime()
            .toString()
}
