package com.neardue.app

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * 纯 Kotlin + JDK 自带 Inflater 的极简 PDF 文字提取器（零第三方依赖，不引 pdfbox）。
 *
 * 只做一件事：把 PDF 里的文字按**视觉行**抽出来，交给 `CourseScheduleImporter` 复用现有解析。
 * 支持：坏 xref 兜底扫描、对象流 `/ObjStm`、`FlateDecode`、Type0 + Identity-H + `ToUnicode`、
 *      简单字体 + WinAnsi、`Tj` / `TJ` / `'` / `"`、`Td` / `TD` / `Tm` / `T*`、Form XObject 递归、
 *      内联图片跳过、简单加密文档识别。
 *
 * 明确不做：加密文档解密、OCR（扫描件）。
 * 抽出来的文字**一律回填到可编辑输入框**，由用户确认后才导入 —— 绝不静默采信。
 *
 * 除了拼好的整段文字，还输出带坐标的 [Line]/[Cell]（[Result.lines]），
 * 供上层做表格还原（课表就是典型的二维表，按阅读顺序拼会散架）。
 */
object PdfTextExtractor {

    class PdfException(message: String) : Exception(message)

    /** 一段连续落墨：同一次 Tj/TJ 出来的文字，带 x 起点与估算的 x 终点（PDF 用户空间单位）。 */
    data class Cell(val text: String, val x: Double, val endX: Double, val size: Double)

    /** 同一基线上的一行，cells 已按 x 升序。 */
    data class Line(val page: Int, val y: Double, val size: Double, val cells: List<Cell>) {
        val text: String get() = cells.joinToString(" ") { it.text }
    }

    data class Result(
        val text: String,
        val pages: Int,
        val glyphs: Int,
        val lines: List<Line> = emptyList(),
    )

    /** 上层统一入口：抽不出可读文字时抛 [PdfException]，由 UI 提示「换成图片/手动输入」。 */
    fun extract(bytes: ByteArray): Result = Doc(bytes).run()

    // ───────────────────────────── 基础对象 ─────────────────────────────

    private sealed class Obj
    private class Name(val name: String) : Obj()
    private class Str(val bytes: ByteArray) : Obj()
    private class Num(val v: Double) : Obj()
    private class Bool(val v: Boolean) : Obj()
    private class Arr(val items: MutableList<Obj> = mutableListOf()) : Obj()
    private class Dict(val map: MutableMap<String, Obj> = mutableMapOf()) : Obj()
    private class Ref(val num: Int) : Obj()
    private class Stream(val dict: Dict, val raw: ByteArray) : Obj()

    // ───────────────────────────── 字节工具 ─────────────────────────────

    private fun ByteArray.idxOf(needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        val last = size - needle.size
        var i = from
        while (i <= last) {
            var j = 0
            while (j < needle.size && this[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }

    private fun ByteArray.idxOf(text: String, from: Int): Int = idxOf(text.toByteArray(Charsets.ISO_8859_1), from)

    private fun ByteArray.idxOfBack(needle: ByteArray, from: Int): Int {
        var i = minOf(from, size - needle.size)
        while (i >= 0) {
            var j = 0
            while (j < needle.size && this[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i--
        }
        return -1
    }

    private fun hexVal(c: Int): Int = when {
        c in 0x30..0x39 -> c - 0x30
        c in 0x41..0x46 -> c - 0x41 + 10
        c in 0x61..0x66 -> c - 0x61 + 10
        else -> -1
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater()
        try {
            // 有的 PDF 首字节是 zlib 头，有的直接是 raw deflate；先试标准，失败再试 raw。
            fun attempt(nowrap: Boolean): ByteArray? {
                val i = Inflater(nowrap)
                try {
                    i.setInput(data)
                    val out = ByteArrayOutputStream(maxOf(1024, data.size * 4))
                    val buf = ByteArray(16384)
                    var guard = 0
                    while (!i.finished() && guard < 4_000_000) {
                        val n = try {
                            i.inflate(buf)
                        } catch (e: DataFormatException) {
                            return null
                        }
                        if (n == 0) {
                            if (i.needsInput() || i.needsDictionary()) break
                        } else {
                            out.write(buf, 0, n)
                        }
                        guard += n
                    }
                    return out.toByteArray()
                } finally {
                    i.end()
                }
            }
            if (data.size >= 2) {
                val b0 = data[0].toInt() and 0xFF
                val b1 = data[1].toInt() and 0xFF
                val zlibHeader = (b0 and 0x0F) == 8 && ((b0 shl 8) or b1) % 31 == 0
                val first = attempt(!zlibHeader) ?: attempt(zlibHeader)
                if (first != null) return first
            }
            return attempt(false) ?: attempt(true) ?: ByteArray(0)
        } finally {
            inf.end()
        }
    }

    // ───────────────────────────── 语法解析 ─────────────────────────────

    private class Parser(val buf: ByteArray, var pos: Int = 0) {

        fun atEnd() = pos >= buf.size

        fun skipWs() {
            while (pos < buf.size) {
                val c = buf[pos].toInt() and 0xFF
                if (c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 || c == 0x0C || c == 0x00) {
                    pos++
                } else if (c == 0x25) { // % 注释
                    while (pos < buf.size) {
                        val d = buf[pos].toInt() and 0xFF
                        pos++
                        if (d == 0x0A || d == 0x0D) break
                    }
                } else {
                    break
                }
            }
        }

        private fun isDelim(c: Int): Boolean =
            c < 0 || c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 || c == 0x0C || c == 0x00 ||
                c == 0x28 || c == 0x29 || c == 0x3C || c == 0x3E || c == 0x5B || c == 0x5D ||
                c == 0x7B || c == 0x7D || c == 0x2F || c == 0x25

        private fun readKeyword(): String {
            skipWs()
            val start = pos
            while (!atEnd() && !isDelim(buf[pos].toInt() and 0xFF)) pos++
            return String(buf, start, pos - start, Charsets.ISO_8859_1)
        }

        fun parse(): Obj? {
            skipWs()
            if (atEnd()) return null
            val c = buf[pos].toInt() and 0xFF
            return when {
                c == 0x2F -> parseName()
                c == 0x28 -> parseLiteral()
                c == 0x3C -> if (pos + 1 < buf.size && (buf[pos + 1].toInt() and 0xFF) == 0x3C) parseDict() else parseHex()
                c == 0x5B -> parseArray()
                c in 0x30..0x39 || c == 0x2B || c == 0x2D || c == 0x2E -> parseNumberOrRef()
                else -> {
                    val kw = readKeyword()
                    when (kw) {
                        "true" -> Bool(true)
                        "false" -> Bool(false)
                        "null" -> null
                        else -> null
                    }
                }
            }
        }

        fun parseName(): Name {
            pos++ // '/'
            val sb = StringBuilder()
            while (!atEnd()) {
                val c = buf[pos].toInt() and 0xFF
                if (isDelim(c)) break
                if (c == 0x23 && pos + 2 < buf.size) { // #XX
                    val h1 = hexVal(buf[pos + 1].toInt() and 0xFF)
                    val h2 = hexVal(buf[pos + 2].toInt() and 0xFF)
                    if (h1 >= 0 && h2 >= 0) {
                        sb.append(((h1 shl 4) or h2).toChar())
                        pos += 3
                        continue
                    }
                }
                sb.append(c.toChar())
                pos++
            }
            return Name(sb.toString())
        }

        fun parseLiteral(): Str {
            pos++ // '('
            val out = ByteArrayOutputStream()
            var depth = 1
            while (!atEnd()) {
                val c = buf[pos].toInt() and 0xFF
                pos++
                when (c) {
                    0x5C -> { // 反斜杠转义
                        if (atEnd()) break
                        val e = buf[pos].toInt() and 0xFF
                        pos++
                        when (e) {
                            0x6E -> out.write(0x0A)
                            0x72 -> out.write(0x0D)
                            0x74 -> out.write(0x09)
                            0x62 -> out.write(0x08)
                            0x66 -> out.write(0x0C)
                            0x28 -> out.write(0x28)
                            0x29 -> out.write(0x29)
                            0x5C -> out.write(0x5C)
                            0x0A -> { /* 续行 */ }
                            0x0D -> if (pos < buf.size && (buf[pos].toInt() and 0xFF) == 0x0A) pos++
                            in 0x30..0x37 -> {
                                var v = e - 0x30
                                var k = 0
                                while (k < 2 && !atEnd()) {
                                    val d = buf[pos].toInt() and 0xFF
                                    if (d in 0x30..0x37) {
                                        v = v * 8 + (d - 0x30)
                                        pos++
                                        k++
                                    } else break
                                }
                                out.write(v and 0xFF)
                            }
                            else -> out.write(e)
                        }
                    }
                    0x28 -> { depth++; out.write(0x28) }
                    0x29 -> {
                        depth--
                        if (depth == 0) return Str(out.toByteArray()) else out.write(0x29)
                    }
                    else -> out.write(c)
                }
            }
            return Str(out.toByteArray())
        }

        fun parseHex(): Str {
            pos++ // '<'
            val out = ByteArrayOutputStream()
            var hi = -1
            while (!atEnd()) {
                val c = buf[pos].toInt() and 0xFF
                pos++
                if (c == 0x3E) break
                val v = hexVal(c)
                if (v < 0) continue
                if (hi < 0) hi = v else { out.write((hi shl 4) or v); hi = -1 }
            }
            if (hi >= 0) out.write(hi shl 4)
            return Str(out.toByteArray())
        }

        fun parseArray(): Arr {
            pos++ // '['
            val a = Arr()
            while (true) {
                skipWs()
                if (atEnd()) break
                if ((buf[pos].toInt() and 0xFF) == 0x5D) { pos++; break }
                val o = parse() ?: break
                a.items.add(o)
            }
            return a
        }

        fun parseDict(): Dict {
            pos += 2 // '<<'
            val d = Dict()
            while (true) {
                skipWs()
                if (atEnd()) break
                if ((buf[pos].toInt() and 0xFF) == 0x3E && pos + 1 < buf.size &&
                    (buf[pos + 1].toInt() and 0xFF) == 0x3E
                ) { pos += 2; break }
                val k = parse() ?: break
                if (k !is Name) continue
                val v = parse()
                if (v != null) d.map[k.name] = v else d.map[k.name] = Bool(false)
            }
            return d
        }

        private fun parseNumberOrRef(): Obj {
            val start = pos
            while (!atEnd() && !isDelim(buf[pos].toInt() and 0xFF)) pos++
            val raw = String(buf, start, pos - start, Charsets.ISO_8859_1)
            val n = raw.toDoubleOrNull() ?: return Num(0.0)
            // 可能是 "12 0 R" 引用
            if (raw.matches(Regex("^\\d+$"))) {
                val save = pos
                skipWs()
                val g0 = pos
                while (!atEnd() && !isDelim(buf[pos].toInt() and 0xFF)) pos++
                val gen = String(buf, g0, pos - g0, Charsets.ISO_8859_1)
                skipWs()
                val k0 = pos
                while (!atEnd() && !isDelim(buf[pos].toInt() and 0xFF)) pos++
                val kw = String(buf, k0, pos - k0, Charsets.ISO_8859_1)
                if (gen.toIntOrNull() != null && kw == "R") {
                    return Ref(n.toInt())
                }
                pos = save
            }
            return Num(n)
        }
    }

    // ───────────────────────────── 文档 ─────────────────────────────

    private class Doc(private val buf: ByteArray) {

        private val objects = HashMap<Int, Obj>()

        // ── 静态字典取值 ──
        private fun dget(o: Obj?, key: String): Obj? = when (o) {
            is Dict -> o.map[key]
            is Stream -> o.dict.map[key]
            else -> null
        }

        private fun resolve(o: Obj?): Obj? {
            var cur = o
            var hops = 0
            while (cur is Ref && hops++ < 32) cur = objects[cur.num]
            return cur
        }

        private fun numOf(o: Obj?): Double? = (resolve(o) as? Num)?.v

        private fun nameOf(o: Obj?): String? = (resolve(o) as? Name)?.name

        private fun dictOf(o: Obj?): Dict? = when (val r = resolve(o)) {
            is Dict -> r
            is Stream -> r.dict
            else -> null
        }

        private fun arrayOf(o: Obj?): List<Obj>? = (resolve(o) as? Arr)?.items

        // 从 Resources 里的 XObject 字典反查它对应的 Stream 对象（字典是 Stream 的成员，按引用相等对比）。
        private fun formStreamOf(d: Dict): Stream? {
            val direct = objects.values.firstOrNull { it is Stream && it.dict === d }
            if (direct is Stream) return direct
            for ((_, v) in objects) {
                if (v is Stream && v.dict === d) return v
            }
            return null
        }
        fun scan() {
            val p = Parser(buf, 0)
            var guard = 0
            while (p.pos < buf.size && guard++ < 100_000) {
                p.skipWs()
                if (p.pos >= buf.size) break
                val head = p.parse()
                // 期望 "num gen obj"
                if (head is Num && head.v == head.v.toInt().toDouble()) {
                    val num = head.v.toInt()
                    p.skipWs()
                    val genObj = p.parse()
                    if (genObj is Num) {
                        p.skipWs()
                        val kwStart = p.pos
                        while (p.pos < buf.size && !isDelimByte(buf[p.pos].toInt() and 0xFF)) p.pos++
                        val kw = String(buf, kwStart, p.pos - kwStart, Charsets.ISO_8859_1)
                        if (kw == "obj") {
                            p.skipWs()
                            val body = p.parse()
                            if (body != null) {
                                // 看看是不是 stream
                                p.skipWs()
                                val after = p.pos
                                while (p.pos < buf.size && !isDelimByte(buf[p.pos].toInt() and 0xFF)) p.pos++
                                val kw2 = String(buf, after, p.pos - after, Charsets.ISO_8859_1)
                                if (kw2 == "stream" && body is Dict) {
                                    // 跳过 EOL
                                    if (p.pos < buf.size && (buf[p.pos].toInt() and 0xFF) == 0x0D) p.pos++
                                    if (p.pos < buf.size && (buf[p.pos].toInt() and 0xFF) == 0x0A) p.pos++
                                    val dataStart = p.pos
                                    var dataEnd = -1
                                    val declared = (body.map["Length"] as? Num)?.v?.toInt()
                                    if (declared != null) {
                                        val probe = dataStart + declared
                                        if (probe <= buf.size) {
                                            var q = probe
                                            while (q < buf.size && isWsByte(buf[q].toInt() and 0xFF)) q++
                                            if (buf.idxOf("endstream", q).let { it >= 0 && it - q < 4 }) dataEnd = probe
                                        }
                                    }
                                    if (dataEnd < 0) {
                                        val e = buf.idxOf("endstream", dataStart)
                                        if (e < 0) {
                                            p.pos = buf.size
                                            break
                                        }
                                        dataEnd = e
                                        while (dataEnd > dataStart && isWsByte(buf[dataEnd - 1].toInt() and 0xFF)) dataEnd--
                                    }
                                    val raw = buf.copyOfRange(dataStart, dataEnd)
                                    objects[num] = Stream(body, raw)
                                    val e2 = buf.idxOf("endstream", dataEnd)
                                    p.pos = if (e2 >= 0) e2 + 9 else buf.size
                                } else {
                                    objects[num] = body
                                }
                            }
                        }
                    }
                }
                // 前进一点，避免死循环
                if (p.pos < buf.size) p.pos++
            }
            expandObjectStreams()
        }

        private fun isWsByte(c: Int) = c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 || c == 0x0C || c == 0x00

        private fun isDelimByte(c: Int): Boolean =
            c < 0 || isWsByte(c) || c == 0x28 || c == 0x29 || c == 0x3C || c == 0x3E ||
                c == 0x5B || c == 0x5D || c == 0x7B || c == 0x7D || c == 0x2F || c == 0x25

        /** 展开 /Type /ObjStm 里压缩存放的对象（现代 PDF 的字体字典常在里面）。 */
        private fun expandObjectStreams() {
            val stms = objects.entries.filter { it.value is Stream && nameOf(dget(it.value, "Type")) == "ObjStm" }
            for ((_, s) in stms) {
                val stream = s as Stream
                val data = decoded(stream) ?: continue
                val n = numOf(dget(stream, "N"))?.toInt() ?: continue
                val first = numOf(dget(stream, "First"))?.toInt() ?: continue
                val header = String(data, 0, minOf(first, data.size), Charsets.ISO_8859_1)
                val toks = header.split(Regex("\\s+")).filter { it.isNotEmpty() }
                var i = 0
                val entries = ArrayList<Pair<Int, Int>>()
                while (i + 1 < toks.size && entries.size < n) {
                    val on = toks[i].toIntOrNull() ?: break
                    val off = toks[i + 1].toIntOrNull() ?: break
                    entries.add(on to off)
                    i += 2
                }
                for ((on, off) in entries) {
                    if (objects.containsKey(on)) continue
                    val p = Parser(data, first + off)
                    val o = try { p.parse() } catch (_: Exception) { null }
                    if (o != null) objects[on] = o
                }
            }
        }

        private fun decoded(s: Stream): ByteArray? {
            val filter = nameOf(dget(s, "Filter"))
            if (filter == null) return s.raw
            return when (filter) {
                "FlateDecode", "Fl" -> inflate(s.raw)
                else -> null
            }
        }

        // ── 页树 ──
        private class Page(val dict: Dict, val resources: Dict?)

        private fun pages(): List<Page> {
            val out = ArrayList<Page>()
            // 找 Catalog
            var root: Dict? = null
            for ((_, o) in objects) {
                if (nameOf(dget(o, "Type")) == "Catalog") { root = dictOf(o); break }
            }
            if (root != null) {
                walk(root, null, out, 0)
            }
            if (out.isEmpty()) {
                // 兜底：所有 /Type /Page
                for ((num, o) in objects.entries.sortedBy { it.key }) {
                    if (nameOf(dget(o, "Type")) == "Page") {
                        out.add(Page(dictOf(o) ?: continue, dictOf(dget(o, "Resources"))))
                    }
                }
            }
            return out
        }

        private fun walk(node: Dict?, inherited: Dict?, out: MutableList<Page>, depth: Int) {
            if (node == null || depth > 32) return
            val res = dictOf(node.map["Resources"]) ?: inherited
            when (nameOf(dget(node, "Type"))) {
                "Page" -> out.add(Page(node, res))
                "Pages" -> {
                    val kids = arrayOf(node.map["Kids"]) ?: return
                    for (k in kids) {
                        val kd = dictOf(k) ?: continue
                        walk(kd, res, out, depth + 1)
                    }
                }
                else -> {
                    val kids = arrayOf(node.map["Kids"])
                    if (kids != null) for (k in kids) walk(dictOf(k), res, out, depth + 1)
                }
            }
        }

        // ── 字体解码 ──
        private class FontDecoder(
            val twoByte: Boolean,
            val toUni: HashMap<Int, String>?,
            val simpleMap: IntArray?,
        )

        private val fontCache = HashMap<Int, FontDecoder>()

        private fun fontDecoder(fontDict: Dict?): FontDecoder? {
            if (fontDict == null) return null
            val subtype = nameOf(fontDict.map["Subtype"])
            if (subtype == "Type0") {
                val enc = nameOf(fontDict.map["Encoding"]) ?: ""
                val twoByte = enc.contains("Identity") || enc.contains("-H") || enc.contains("-V")
                val toUni = parseToUnicode(fontDict)
                return FontDecoder(twoByte, toUni, null)
            }
            // 简单字体：WinAnsi / Standard / MacRoman，可带 Differences
            val encObj = resolve(fontDict.map["Encoding"])
            val base = when (encObj) {
                is Name -> encObj.name
                is Dict -> nameOf(encObj.map["BaseEncoding"]) ?: "StandardEncoding"
                else -> "StandardEncoding"
            }
            val map = IntArray(256) { it } // 默认用 latin1 直通
            if (base.contains("WinAnsi")) for (i in 0x80..0x9F) map[i] = WIN_ANSI_80_9F[i - 0x80]
            if (encObj is Dict) {
                val diffs = arrayOf(encObj.map["Differences"]) ?: emptyList()
                var code = 0
                for (d in diffs) {
                    when (val r = resolve(d)) {
                        is Num -> code = r.v.toInt()
                        is Name -> {
                            val u = GLYPH_NAMES[r.name]
                            if (u != null && code in 0..255) map[code] = u
                            code++
                        }
                        else -> {}
                    }
                }
            }
            return FontDecoder(false, parseToUnicode(fontDict), map)
        }

        private fun parseToUnicode(fontDict: Dict): HashMap<Int, String>? {
            val s = resolve(fontDict.map["ToUnicode"]) as? Stream ?: return null
            val data = decoded(s) ?: return null
            val text = String(data, Charsets.ISO_8859_1)
            val map = HashMap<Int, String>()

            // ToUnicode 的目标串是 UTF-16BE（可能带代理对），绝不能当一个字节一个字符读。
            fun hexToStr(hex: String): String {
                val clean = hex.filter { !it.isWhitespace() }
                if (clean.length < 2) return ""
                val n = clean.length / 2
                val bytes = ByteArray(n) { i ->
                    val hi = hexVal(clean[i * 2].code)
                    val lo = hexVal(clean[i * 2 + 1].code)
                    (((hi shl 4) or lo) and 0xFF).toByte()
                }
                return String(bytes, Charsets.UTF_16BE)
            }

            val tokenRe = Regex("<([0-9A-Fa-f\\s]+)>|\\[|\\]|(beginbfchar|endbfchar|beginbfrange|endbfrange)")

            // bfchar
            run {
                var idx = text.indexOf("beginbfchar")
                while (idx >= 0) {
                    val end = text.indexOf("endbfchar", idx)
                    if (end < 0) break
                    val body = text.substring(idx + 11, end)
                    val toks = Regex("<([0-9A-Fa-f\\s]*)>").findAll(body).map { it.groupValues[1] }.toList()
                    var i = 0
                    while (i + 1 < toks.size) {
                        val src = toks[i].filter { !it.isWhitespace() }
                        val dst = toks[i + 1].filter { !it.isWhitespace() }
                        if (src.isNotEmpty()) {
                            val code = src.toIntOrNull(16) ?: 0
                            map[code] = hexToStr(dst)
                        }
                        i += 2
                    }
                    idx = text.indexOf("beginbfchar", end)
                }
            }

            // bfrange
            run {
                var idx = text.indexOf("beginbfrange")
                while (idx >= 0) {
                    val end = text.indexOf("endbfrange", idx)
                    if (end < 0) break
                    val body = text.substring(idx + 12, end)
                    // 逐个三元组
                    val re = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(\\[|<[0-9A-Fa-f]*>|\\[[^\\]]*\\])")
                    for (m in re.findAll(body)) {
                        val lo = m.groupValues[1].toIntOrNull(16) ?: continue
                        val hi = m.groupValues[2].toIntOrNull(16) ?: continue
                        val dst = m.groupValues[3]
                        if (hi < lo) continue
                        if (dst.startsWith("[")) {
                            val items = Regex("<([0-9A-Fa-f]*)>").findAll(dst).map { it.groupValues[1] }.toList()
                            for ((k, h) in items.withIndex()) {
                                if (lo + k > hi) break
                                map[lo + k] = hexToStr(h)
                            }
                        } else {
                            val baseHex = dst.trim('<', '>').filter { !it.isWhitespace() }
                            if (baseHex.length < 4) continue
                            val n = baseHex.length
                            // 前面的 UTF-16 码元原样保留（一般是空）
                            val prefix = ByteArray(n / 2 - 2) { i ->
                                val h = hexVal(baseHex[i * 2].code)
                                val l = hexVal(baseHex[i * 2 + 1].code)
                                (((h shl 4) or l) and 0xFF).toByte()
                            }
                            // 递增的只是最后一个 UTF-16 码元（4 个 hex 字符 = 2 字节）
                            val lastUnit = (hexVal(baseHex[n - 4].code) shl 12) or
                                (hexVal(baseHex[n - 3].code) shl 8) or
                                (hexVal(baseHex[n - 2].code) shl 4) or
                                hexVal(baseHex[n - 1].code)
                            for (c in lo..minOf(hi, lo + 65535)) {
                                val v = lastUnit + (c - lo)
                                val unit = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
                                map[c] = String(prefix + unit, Charsets.UTF_16BE)
                            }
                        }
                    }
                    idx = text.indexOf("beginbfrange", end)
                }
            }
            return if (map.isEmpty()) null else map
        }

        // ── 内容流解析 ──
        private class GfxState {
            var font: FontDecoder? = null
            var fontSize = 0.0
            // Tm 矩阵的缩放（WPS 导出的表格常写 Tm 0.017 0 0 -0.017 …，真实字号要乘上它）
            var tmScale = 1.0
            var leading = 0.0
            var charSpace = 0.0
            var wordSpace = 0.0
            var hScale = 1.0
            // 文本矩阵
            var a = 1.0; var b = 0.0; var c = 0.0; var d = 1.0; var e = 0.0; var f = 0.0
            var la = 1.0; var lb = 0.0; var lc = 0.0; var ld = 1.0; var le = 0.0; var lf = 0.0

            /** 真实字号：Tf 写的值 × Tm 缩放。 */
            fun eff(): Double = (if (fontSize > 0) fontSize else 10.0) * tmScale
        }

        private class Run(val text: String, val x: Double, val y: Double, val size: Double)

        private fun extractPageText(page: Page, pageIndex: Int, outLines: MutableList<Line>): Pair<String, Int> {
            val runs = ArrayList<Run>()
            val res = page.resources
            val contents = page.dict.map["Contents"]
            val pieces = ArrayList<ByteArray>()
            when (val c = resolve(contents)) {
                is Stream -> decoded(c)?.let { pieces.add(it) }
                is Arr -> for (o in c.items) {
                    val s = resolve(o) as? Stream ?: continue
                    decoded(s)?.let { pieces.add(it) }
                }
                else -> {}
            }
            if (pieces.isEmpty()) return "" to 0
            val data = if (pieces.size == 1) pieces[0] else {
                val out = ByteArrayOutputStream()
                for (p in pieces) { out.write(p); out.write('\n'.code) }
                out.toByteArray()
            }
            val st = GfxState()
            runContent(data, res, st, runs, 0)
            return assemble(runs, pageIndex, outLines)
        }

        private fun runContent(
            data: ByteArray,
            res: Dict?,
            st: GfxState,
            runs: MutableList<Run>,
            depth: Int,
        ) {
            if (depth > 8 || runs.size > 60_000) return
            val fonts = dictOf(res?.map?.get("Font"))
            val xobjs = dictOf(res?.map?.get("XObject"))
            val p = Parser(data, 0)
            val ops = ArrayList<Obj>()

            fun emit(strBytes: ByteArray, isTwoByteFromFont: Boolean?) {
                val font = st.font ?: return
                val sb = StringBuilder()
                val two = isTwoByteFromFont ?: font.twoByte
                if (two) {
                    var i = 0
                    while (i + 1 < strBytes.size) {
                        val code = ((strBytes[i].toInt() and 0xFF) shl 8) or (strBytes[i + 1].toInt() and 0xFF)
                        val u = font.toUni?.get(code)
                        if (u != null) sb.append(u) else if (code in 0x20..0x7E) sb.append(code.toChar())
                        i += 2
                    }
                    if (strBytes.size % 2 == 1) {
                        val code = strBytes.last().toInt() and 0xFF
                        font.toUni?.get(code)?.let { sb.append(it) }
                    }
                } else {
                    for (b in strBytes) {
                        val code = b.toInt() and 0xFF
                        val mapped = font.simpleMap?.get(code) ?: code
                        val u = font.toUni?.get(code)
                        if (u != null) sb.append(u)
                        else if (mapped in 0x20..0x7E || mapped in 0xA0..0xFF) sb.append(mapped.toChar())
                        else if (mapped > 0xFF) sb.append(mapped.toChar())
                    }
                }
                if (sb.isEmpty()) return
                val text = sb.toString()
                runs.add(Run(text, st.e, st.f, st.eff()))
                // 估算前进量
                val units = if (two) strBytes.size / 2.0 else strBytes.size.toDouble()
                val adv = units * st.eff() * 0.55 * st.hScale +
                    strBytes.size * st.charSpace
                st.e += adv
            }

            while (true) {
                p.skipWs()
                if (p.pos >= data.size) break
                val save = p.pos
                val tok = try { p.parse() } catch (_: Exception) { null }
                if (tok != null) {
                    ops.add(tok)
                    continue
                }
                // 不是对象 → 是操作符
                p.pos = save
                val kwStart = p.pos
                while (p.pos < data.size && !isDelimByte(data[p.pos].toInt() and 0xFF)) p.pos++
                if (p.pos == kwStart) { p.pos++; continue }
                val op = String(data, kwStart, p.pos - kwStart, Charsets.ISO_8859_1)

                when (op) {
                    "BT" -> { st.a = 1.0; st.b = 0.0; st.c = 0.0; st.d = 1.0; st.e = 0.0; st.f = 0.0
                        st.la = 1.0; st.lb = 0.0; st.lc = 0.0; st.ld = 1.0; st.le = 0.0; st.lf = 0.0 }
                    "Tf" -> {
                        val nm = ops.getOrNull(ops.size - 2) as? Name
                        val sz = (ops.lastOrNull() as? Num)?.v ?: 0.0
                        st.fontSize = sz
                        val fd = fonts?.map?.get(nm?.name ?: "")
                        st.font = fontDecoder(dictOf(fd))
                    }
                    "TL" -> st.leading = (ops.lastOrNull() as? Num)?.v ?: 0.0
                    "Tc" -> st.charSpace = (ops.lastOrNull() as? Num)?.v ?: 0.0
                    "Tw" -> st.wordSpace = (ops.lastOrNull() as? Num)?.v ?: 0.0
                    "Tz" -> st.hScale = ((ops.lastOrNull() as? Num)?.v ?: 100.0) / 100.0
                    "Tm" -> {
                        if (ops.size >= 6) {
                            val n = ops.takeLast(6).map { (it as? Num)?.v ?: 0.0 }
                            st.a = n[0]; st.b = n[1]; st.c = n[2]; st.d = n[3]; st.e = n[4]; st.f = n[5]
                            val det = kotlin.math.abs(n[0] * n[3] - n[1] * n[2])
                            st.tmScale = if (det > 1e-9) kotlin.math.sqrt(det) else 1.0
                        }
                    }
                    "Td" -> {
                        val tx = (ops.getOrNull(ops.size - 2) as? Num)?.v ?: 0.0
                        val ty = (ops.lastOrNull() as? Num)?.v ?: 0.0
                        st.le += tx * st.la + ty * st.lc
                        st.lf += tx * st.lb + ty * st.ld
                        st.e = st.le; st.f = st.lf
                    }
                    "TD" -> {
                        val tx = (ops.getOrNull(ops.size - 2) as? Num)?.v ?: 0.0
                        val ty = (ops.lastOrNull() as? Num)?.v ?: 0.0
                        st.leading = -ty
                        st.le += tx * st.la + ty * st.lc
                        st.lf += tx * st.lb + ty * st.ld
                        st.e = st.le; st.f = st.lf
                    }
                    "T*" -> {
                        st.lf -= st.leading * st.ld
                        st.le -= st.leading * st.lc
                        st.e = st.le; st.f = st.lf
                    }
                    "Tj" -> (ops.lastOrNull() as? Str)?.let { emit(it.bytes, null) }
                    "TJ" -> {
                        val arr = ops.lastOrNull() as? Arr
                        if (arr != null) for (item in arr.items) {
                            when (val r = item) {
                                is Str -> emit(r.bytes, null)
                                is Num -> {
                                    val gap = -r.v / 1000.0 * st.eff() * st.hScale
                                    st.e += gap
                                    if (gap > 0.18 * st.eff()) {
                                        runs.add(Run(" ", st.e, st.f, st.eff()))
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                    "'" -> {
                        st.lf -= st.leading * st.ld
                        st.le -= st.leading * st.lc
                        st.e = st.le; st.f = st.lf
                        (ops.lastOrNull() as? Str)?.let { emit(it.bytes, null) }
                    }
                    "\"" -> {
                        st.wordSpace = (ops.getOrNull(ops.size - 3) as? Num)?.v ?: st.wordSpace
                        st.charSpace = (ops.getOrNull(ops.size - 2) as? Num)?.v ?: st.charSpace
                        st.lf -= st.leading * st.ld
                        st.le -= st.leading * st.lc
                        st.e = st.le; st.f = st.lf
                        (ops.lastOrNull() as? Str)?.let { emit(it.bytes, null) }
                    }
                    "Do" -> {
                        val nm = ops.lastOrNull() as? Name
                        val xo = dictOf(xobjs?.map?.get(nm?.name ?: ""))
                        if (xo == null || nameOf(xo.map["Subtype"]) != "Form") {
                            // 不是 Form XObject（图片等）→ 跳过
                        } else {
                        val s = formStreamOf(xo)
                        val payload = s?.let { decoded(it) }
                        if (payload != null) {
                            val formRes = dictOf(xo.map["Resources"]) ?: res
                            val saved = GfxState().also {
                                it.font = st.font; it.fontSize = st.fontSize; it.leading = st.leading
                                it.tmScale = st.tmScale
                                it.charSpace = st.charSpace; it.wordSpace = st.wordSpace; it.hScale = st.hScale
                                it.a = st.a; it.b = st.b; it.c = st.c; it.d = st.d
                                it.e = 0.0; it.f = 0.0
                                it.la = st.la; it.lb = st.lb; it.lc = st.lc; it.ld = st.ld
                                it.le = 0.0; it.lf = 0.0
                            }
                            runContent(payload, formRes, saved, runs, depth + 1)
                        }
                        }
                    }
                    "BI" -> {
                        // 内联图片：跳到 ID 之后、EI 之前
                        val idPos = data.idxOf("ID", p.pos)
                        if (idPos >= 0) {
                            val eiPos = data.idxOf("EI", idPos + 3)
                            p.pos = if (eiPos >= 0) eiPos + 2 else data.size
                        } else p.pos = data.size
                    }
                    else -> {}
                }
                ops.clear()
            }
        }

        private fun assemble(runs: List<Run>, pageIndex: Int, outLines: MutableList<Line>): Pair<String, Int> {
            if (runs.isEmpty()) return "" to 0
            val lines = ArrayList<MutableList<Run>>()
            var cur = ArrayList<Run>()
            var curY = Double.NaN
            var curSize = 10.0
            for (r in runs) {
                if (r.text.isBlank() && r.text != " ") continue
                if (cur.isEmpty() || curY.isNaN()) {
                    cur.add(r); curY = r.y; curSize = r.size
                    continue
                }
                val tol = maxOf(2.5, maxOf(curSize, r.size) * 0.45)
                if (kotlin.math.abs(r.y - curY) <= tol) {
                    cur.add(r)
                } else {
                    lines.add(cur)
                    cur = ArrayList<Run>().also { it.add(r) }
                    curY = r.y; curSize = r.size
                }
            }
            if (cur.isNotEmpty()) lines.add(cur)

            val sb = StringBuilder()
            for (line in lines) {
                line.sortBy { it.x }
                val lb = StringBuilder()
                var prevEnd = Double.NaN
                var prevSize = 10.0
                val cells = ArrayList<Cell>()
                var maxSize = 0.0
                var baselineY = line.first().y
                for (r in line) {
                    if (r.text == " ") { if (lb.isNotEmpty() && lb.last() != ' ') lb.append(' '); continue }
                    val units = r.text.length.toDouble()
                    val endX = r.x + units * r.size * 0.5
                    cells.add(Cell(r.text, r.x, endX, r.size))
                    if (r.size > maxSize) maxSize = r.size
                    if (!prevEnd.isNaN()) {
                        val gap = r.x - prevEnd
                        val sz = maxOf(prevSize, r.size)
                        when {
                            gap > 2.2 * sz -> lb.append("   ")
                            gap > 0.32 * sz -> if (lb.isNotEmpty() && lb.last() != ' ') lb.append(' ')
                        }
                    }
                    lb.append(r.text)
                    prevEnd = endX
                    prevSize = r.size
                }
                val t = lb.toString().trimEnd()
                if (t.isNotEmpty()) {
                    sb.append(t); sb.append('\n')
                    outLines.add(Line(pageIndex, baselineY, if (maxSize > 0) maxSize else 10.0, cells))
                }
            }
            val text = sb.toString().trimEnd('\n')
            return text to runs.sumOf { it.text.count { ch -> !ch.isWhitespace() } }
        }

        // ── 主流程 ──
        fun run(): Result {
            if (buf.size < 8) throw PdfException("文件太小，不像 PDF")
            val head = String(buf, 0, minOf(1024, buf.size), Charsets.ISO_8859_1)
            if (!head.contains("%PDF-")) throw PdfException("这不是 PDF 文件")

            scan()

            if (objects.isEmpty()) throw PdfException("PDF 结构读不出来（可能已加密或被特殊工具加工过）")

            // 加密文档直接告知
            if (buf.idxOf("/Encrypt", 0) >= 0) {
                throw PdfException("这个 PDF 有密码保护，抽不出文字")
            }

            val pg = pages()
            if (pg.isEmpty()) throw PdfException("PDF 里没找到页面")

            val sb = StringBuilder()
            var totalGlyphs = 0
            val allLines = ArrayList<Line>()
            pg.forEachIndexed { idx, p ->
                val (t, g) = extractPageText(p, idx, allLines)
                totalGlyphs += g
                if (t.isNotBlank()) { sb.append(t); sb.append('\n') }
            }
            val text = sb.toString().trim()

            if (text.isEmpty()) {
                throw PdfException("这个 PDF 里没有文字层（可能是扫描件 / 图片转的 PDF），抽不出来")
            }
            // 可读性校验：正常中文课表应该是汉字/字母/数字为主
            val meaningful = text.count { ch ->
                (ch in 'A'..'Z') || (ch in 'a'..'z') || (ch in '0'..'9') ||
                    (ch.code in 0x4E00..0x9FFF) || (ch.code in 0x3000..0x303F) ||
                    (ch.code in 0xFF00..0xFFEF) || " .,:;-_/()（）·、".contains(ch)
            }
            if (text.isEmpty() || meaningful * 100 / text.length < 55) {
                throw PdfException("PDF 的文字编码认不出来（少见的中文字体子集），抽出来是乱码")
            }
            return Result(text, pg.size, totalGlyphs, allLines)
        }
    }

    // ───────────────────────────── 码表 ─────────────────────────────

    private val WIN_ANSI_80_9F = intArrayOf(
        0x20AC, 0x81, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x8D, 0x017D, 0x8F,
        0x90, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x9D, 0x017E, 0x0178,
    )

    /** 常见字形名 → Unicode（`/Differences` 用）。只收 ASCII 常见符号 + 少量。 */
    private val GLYPH_NAMES: Map<String, Int> = buildMap {
        put("space", 0x20); put("exclam", 0x21); put("quotedbl", 0x22); put("numbersign", 0x23)
        put("dollar", 0x24); put("percent", 0x25); put("ampersand", 0x26); put("quotesingle", 0x27)
        put("parenleft", 0x28); put("parenright", 0x29); put("asterisk", 0x2A); put("plus", 0x2B)
        put("comma", 0x2C); put("hyphen", 0x2D); put("period", 0x2E); put("slash", 0x2F)
        for (i in 0..9) put(listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")[i], 0x30 + i)
        put("colon", 0x3A); put("semicolon", 0x3B); put("less", 0x3C); put("equal", 0x3D)
        put("greater", 0x3E); put("question", 0x3F); put("at", 0x40)
        for (i in 0..25) put(('A' + i).toString(), 0x41 + i)
        put("bracketleft", 0x5B); put("backslash", 0x5C); put("bracketright", 0x5D)
        put("asciicircum", 0x5E); put("underscore", 0x5F); put("grave", 0x60)
        for (i in 0..25) put(('a' + i).toString(), 0x61 + i)
        put("braceleft", 0x7B); put("bar", 0x7C); put("braceright", 0x7D); put("asciitilde", 0x7E)
        put("bullet", 0x2022); put("endash", 0x2013); put("emdash", 0x2014)
        put("quoteleft", 0x2018); put("quoteright", 0x2019)
        put("quotedblleft", 0x201C); put("quotedblright", 0x201D)
        put("ellipsis", 0x2026); put("minus", 0x2212); put("degree", 0x00B0)
    }
}
