package com.neardue.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.time.LocalDate

/**
 * v1.24 日期模式：先选开始日期、再选结束日期，两个都选完回调。
 * 用系统 DatePickerDialog，不引新依赖、不占对话框版面（二级入口）。
 */
private fun pickDateRange(
    context: Context,
    base: LocalDate,
    onDone: (LocalDate, LocalDate) -> Unit,
) {
    android.app.DatePickerDialog(context, { _, sy, sm, sd ->
        val start = LocalDate.of(sy, sm + 1, sd)
        android.app.DatePickerDialog(context, { _, ey, em, ed ->
            onDone(start, LocalDate.of(ey, em + 1, ed))
        }, start.year, start.monthValue - 1, start.dayOfMonth).show()
    }, base.year, base.monthValue - 1, base.dayOfMonth).show()
}

/**
 * 表格式课表。
 *
 * 课表**独立于事项清单**（早期需求「课表不用变成事项提醒」）：这里读的是 [Course]，
 * 不是 [Item]。一门课只存一条（周几 + 起止节 + 周次规格），换周显示时按「第几周」过滤，
 * 单双周 / 零散周也用 [CourseWeeks] 现算，不会把一门课展开成几十条。
 *
 * 刻意做得很窄：左侧一列「第几节 + 开始时刻」，右侧周一到周日七格。
 * 不做 WakeUp 那种周次滑竿 / 分享口令 / 登录 —— 「将至」的主角始终是「还剩几天」，
 * 课表只是一眼看明白「这周要上什么」。
 */

/** 左侧节次列宽度。要比 7 个日期列都窄，把宽度留给课程名。 */
private val AxisWidth = 44.dp

/**
 * 给同一天里的课分「车道」：时间上重叠的课并排平分宽度，不重叠的各自占满。
 *
 * 返回 `(课, 车道号)` 列表，车道号从 0 起；同时返回总车道数放在第二项以后调用方自己算。
 * 真实的课表里撞课不罕见（同一节有两次课、或者手动加重了），不能直接盖住。
 */
private fun assignLanes(courses: List<Course>): List<Pair<Course, Int>> {
    val out = ArrayList<Pair<Course, Int>>()
    // 每条车道当前占到的最大节次；新的一课找个空着的车道。
    val laneEnds = ArrayList<Int>()
    for (c in courses.sortedBy { it.sessionStart }) {
        val from = c.sessionStart
        val to = maxOf(c.sessionStart, c.sessionEnd)
        var lane = laneEnds.indexOfFirst { it < from }
        if (lane < 0) {
            laneEnds.add(to)
            lane = laneEnds.size - 1
        } else {
            laneEnds[lane] = to
        }
        out.add(c to lane)
    }
    return out
}

/** 一格的高度。10sp 的字能放两行课程名，同时 8 节也还装得下。 */
private val CellHeight = 62.dp

/**
 * 找一个离当前周最近的「这门课真的会上」的一周。
 *
 * 灰显的课点一下就跳到它那一周，比让用户自己一周一周翻十几下顺手。
 * `weeks` 为空表示每周都有，此时它不会是灰块，返回 null。
 */
private fun nearestWeek(c: Course, current: Int?): Int? {
    if (current == null) return null
    val weeks = CourseWeeks.parse(c.weeks)
    if (weeks.isEmpty()) return null
    return weeks.minByOrNull { kotlin.math.abs(it - current) }
}

private val DayNames = listOf("一", "二", "三", "四", "五", "六", "日")

/** 长按课程块：把这门课的排布写进剪贴板（v1.20，零视觉元素）。 */
private fun copyCourse(context: Context, c: Course) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("将至课程", c.copyText()))
}

@Composable
fun TimetableScreen(
    paper: Color,
    ink: Color,
    muted: Color,
    hair: Color,
    courses: List<Course>,
    sessions: List<Pair<Int, Int>>,
    termStart: LocalDate?,
    weekShift: Int,
    today: LocalDate,
    onShift: (Int) -> Unit,
    onToday: () -> Unit,
    onEditCourse: (Course) -> Unit,
    onAddAt: (LocalDate?, Int) -> Unit,
    onAdd: () -> Unit,
    onSessions: () -> Unit,
    onBack: () -> Unit,
    /** 这一周的单周例外（停课 / 改课），key = 课程 id。 */
    overrides: Map<Long, CourseOverride> = emptyMap(),
    /** 上课提醒是否开启；开了就在底部标一行，不占额外界面。 */
    courseRemindOn: Boolean = false,
    courseLead: Int = CourseReminder.DEFAULT_LEAD,
    /** 所有学期（多学期并存），课表顶部一排小标签切换。 */
    terms: List<Term> = emptyList(),
    currentTermId: Long = 0L,
    onSwitchTerm: (Long) -> Unit = {},
    onNewTerm: () -> Unit = {},
    onManageTerms: () -> Unit = {},
    /** v1.20 显示做减法：时间轴只显示节次 / 课程块不显示教室。 */
    hideTimes: Boolean = false,
    hideRoom: Boolean = false,
    /** v1.27 每节起止时刻文案（两行 `08:00
-08:45`），时间轴显示；空则只显示开始时刻。 */
    slotLabels: List<String> = emptyList(),
    /** v1.27 翻到别的周时显示「非本周 · 第 N 周」。 */
    nonCurrentWeekLabel: String = "",
    /** v1.27 学期状态提示（没设开学日 / 开学前 / 学期结束），空则不显示。 */
    termStatusMessage: String = "",
    /** v1.30 课程节次超出当前作息节数的提示（越界会被夹到末节显示）。 */
    overflowMessage: String = "",
    /** v1.28 Start week on Sunday (WakeUp toggle): display order only, course data untouched. */
    startOnSunday: Boolean = false,
    /** v1.28 Resolve conflict: keep this course, skip the others this week. */
    onKeepThisCourse: ((Course, List<Course>) -> Unit)? = null,
    /** v1.36 列头事项条：待办按日期摆到当前显示的七天；不响铃、不改课表数据。 */
    items: List<Item> = emptyList(),
    onEditItem: (Item) -> Unit = {},
) {
    val context = LocalContext.current
    val thisWeek = Timetable.weekStartOf(today)
    // v1.28 Sunday-first: only the displayed order/dates change; course dayOfWeek stays ISO.
    val displayedDays = WeekDisplay.orderedDays(startOnSunday)
    val columnOfDay = displayedDays.mapIndexed { col, d -> d to col }.toMap()
    val weekStart = thisWeek.plusWeeks(weekShift.toLong())
    val firstDate = WeekDisplay.firstDate(weekStart, startOnSunday)
    val weekIndex = Timetable.weekNumber(termStart, weekStart)
    // v1.36 列头事项条：只把未完成的 Item 按截止日期分到当前七天，最多两条/天。
    // 不碰 grid / 停课 / 冲突车道 —— 回归风险集中在这一行，不动课表本体。
    val agenda = remember(items, weekStart, startOnSunday) {
        TimetableAgenda.group(
            items,
            displayedDays.map { WeekDisplay.dateAt(weekStart, startOnSunday, columnOfDay[it] ?: 0) },
        )
    }
    // 撞课提示：只摆出来让人自己看，不替用户决定上哪一门。
    var showConflicts by remember { mutableStateOf(false) }
    // 不在这一周的课也要收进来，灰着显示（WakeUp 课表的做法：一屏看出整学期排布）。
    val grid = remember(courses, weekStart, weekIndex, sessions, overrides) {
        Timetable.grid(
            courses, weekStart, weekIndex, sessions,
            showOtherWeeks = true,
            overrides = overrides,
        )
    }
    // 标题里的「第 N 周」要跟当前翻到的那一周（weekIndex），
    // 不是今天所在的周 —— 否则往后翻页时日期变了、周次不变（v1.9 修）。

    Column(Modifier.fillMaxSize().background(paper)) {
        // ── 顶栏：返回 / 标题 / 时段设置 ──────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 18.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                fontSize = 26.sp,
                color = muted,
                modifier = Modifier
                    .clickable { onBack() }
                    .semantics { contentDescription = "返回清单" }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("课表", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (weekIndex != null) append("第 $weekIndex 周 · ")
                        append("${firstDate.monthValue}月${firstDate.dayOfMonth}日 – ")
                        append("${firstDate.plusDays(6).monthValue}月${firstDate.plusDays(6).dayOfMonth}日")
                    },
                    fontSize = 12.sp,
                    color = muted,
                )
            }
            Text(
                "时段",
                fontSize = 13.sp,
                color = muted,
                modifier = Modifier
                    .clickable { onSessions() }
                    .semantics { contentDescription = "设置上下课时间" }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            )
        }

        // ── 学期切换：多个学期并存时，一排小标签点一下就换（WakeUp 的多课表）──
        if (terms.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                terms.filter { !it.archived }.forEach { t ->
                    val on = t.id == currentTermId
                    Box(
                        Modifier
                            .background(
                                if (on) ink else Color.Transparent,
                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            )
                            .border(
                                1.dp,
                                if (on) ink else muted.copy(alpha = 0.35f),
                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            )
                            .clickable { onSwitchTerm(t.id) }
                            .semantics { contentDescription = "切换到${t.name}" }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(t.name, fontSize = 12.sp, color = if (on) paper else muted, maxLines = 1)
                    }
                }
                Text(
                    "＋", fontSize = 14.sp, color = muted,
                    modifier = Modifier
                        .clickable { onNewTerm() }
                        .semantics { contentDescription = "新建学期" }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Text(
                    "管理", fontSize = 12.sp, color = muted,
                    modifier = Modifier
                        .clickable { onManageTerms() }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
        }

        // ── 周切换 ───────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                fontSize = 20.sp,
                color = muted,
                modifier = Modifier
                    .clickable { onShift(-1) }
                    .semantics { contentDescription = "上一周" }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text(
                when {
                    weekShift == 0 -> "本周"
                    weekShift == 1 -> "下周"
                    weekShift == -1 -> "上周"
                    weekShift > 0 -> "$weekShift 周后"
                    else -> "${-weekShift} 周前"
                },
                fontSize = 12.sp,
                color = if (weekShift == 0) ink else muted,
                modifier = Modifier
                    .clickable { onToday() }
                    .semantics { contentDescription = "回到本周" }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
            Text(
                "›",
                fontSize = 20.sp,
                color = muted,
                modifier = Modifier
                    .clickable { onShift(1) }
                    .semantics { contentDescription = "下一周" }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            val n = grid.courseCount()
            val other = grid.otherCount()
            val stopped = grid.skippedCount()
            val clash = grid.conflictCount()
            Text(
                buildString {
                    append(if (n == 0) "这周没课" else "$n 门课")
                    // 灰显层里有几门、停了几门，明说出来，否则用户会以为课丢了。
                    if (other > 0) append(" · 另有 $other 门")
                    if (stopped > 0) append(" · 停 $stopped 门")
                    if (clash > 0) append(" · 冲突 $clash 处")
                },
                fontSize = 11.sp,
                color = muted,
                modifier = if (clash > 0) Modifier.clickable { showConflicts = true }
                    .semantics { contentDescription = "查看撞课详情" } else Modifier,
            )
        }

        // ── v1.27 状态提示：学期状态 + 非本周大字 ──────────────────
        if (termStatusMessage.isNotBlank()) {
            Text(
                termStatusMessage,
                fontSize = 11.sp, color = muted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
        if (overflowMessage.isNotBlank()) {
            Text(
                overflowMessage,
                fontSize = 11.sp, color = muted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
        if (nonCurrentWeekLabel.isNotBlank()) {
            Text(
                nonCurrentWeekLabel,
                fontSize = 13.sp, color = ink, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }

        // ── 周次条：一学期十几二十周，一次点到位（不必一周一周翻）──────
        if (weekIndex != null) {
            // 只排到「最后一门课所在的周」，没课的空白周不占地方；
            // 整行等分，每格都是整块热区（不是那两个字），手指点偏一点也命中。
            val lastWeek = remember(courses, weekIndex) {
                // 只看写了周次的课：周次空的（= 每周都上）会把 parse 拉成 1..25，没意义。
                val fromCourses = courses
                    .filter { CourseWeeks.normalize(it.weeks).isNotEmpty() }
                    .mapNotNull { CourseWeeks.parse(it.weeks).maxOrNull() }
                    .maxOrNull()
                (fromCourses ?: 16).coerceIn(weekIndex, CourseWeeks.MAX_WEEK)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
                // 单行横滑，但每格 44dp 宽——12sp 的小字只有 20dp，手指根本压不准；
                // 全局看学期靠上面的「另有 N 门」和灰显层，这里只管「精准跳到某一周」。
                val density = LocalDensity.current
                val scroll = rememberScrollState()
                // 切周后把当前周滚到视口中间，否则第 15 周在屏幕外，看不到自己在哪。
                LaunchedEffect(weekIndex) {
                    val cellPx = with(density) { 45.dp.toPx() }
                    val target = cellPx * (weekIndex - 1) - scroll.viewportSize / 2f + cellPx / 2f
                    scroll.scrollTo(target.toInt().coerceAtLeast(0))
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(scroll),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    for (w in 1..lastWeek) {
                        val on = w == weekIndex
                        Box(
                            Modifier
                                .width(44.dp)
                                .height(44.dp)
                                .clickable { onShift(w - weekIndex) }
                                .semantics { contentDescription = "第 $w 周" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$w",
                                fontSize = 12.sp,
                                maxLines = 1,
                                color = if (on) ink else muted,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        // ── 日期表头 ─────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Spacer(Modifier.width(AxisWidth))
            displayedDays.forEach { isoDay ->
                val d = WeekDisplay.dateAt(weekStart, startOnSunday, columnOfDay[isoDay] ?: 0)
                val name = DayNames[isoDay - 1]
                val isToday = d == today
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        name,
                        fontSize = 11.sp,
                        color = if (isToday) ink else muted,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        "${d.dayOfMonth}",
                        fontSize = 13.sp,
                        color = if (isToday) ink else muted,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        // ── 列头事项条（v1.36）───────────────────────────────────────
        // 每天最多两条 + 「还有 N 件」；整列可点，打开截止时间最早的那条。
        // 不用分类色点：课程块已经用了分类色，再来一套会花。
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            Spacer(Modifier.width(AxisWidth))
            displayedDays.forEach { isoDay ->
                val d = WeekDisplay.dateAt(weekStart, startOnSunday, columnOfDay[isoDay] ?: 0)
                val dayItems = agenda[d]
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .then(
                            if (dayItems != null && dayItems.shown.isNotEmpty()) {
                                Modifier.clickable { onEditItem(dayItems.shown.first()) }
                            } else Modifier
                        )
                        .then(
                            if (dayItems != null && dayItems.shown.isNotEmpty()) {
                                Modifier.semantics {
                                    contentDescription = "${d.monthValue}月${d.dayOfMonth}日，" +
                                        "${dayItems.shown.size + dayItems.hidden} 件待办，" +
                                        "点按编辑「${dayItems.shown.first().title}」"
                                }
                            } else Modifier
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    dayItems?.shown?.forEach { it ->
                        Text(
                            it.title,
                            fontSize = 10.sp,
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (dayItems != null && dayItems.hidden > 0) {
                        Text("还有 ${dayItems.hidden} 件", fontSize = 10.sp, color = muted, maxLines = 1)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(hair))

        // ── 网格 ─────────────────────────────────────────────────────
        // 跨节的课画成一整块（高度 = 节数 × 行高），像 WakeUp 那样一眼看出「占几节」。
        // 同一时段撞课的按「车道」平分宽度，不互相盖住。
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            val gridHeight = CellHeight * sessions.size
            Row(Modifier.fillMaxWidth().height(gridHeight)) {
                // 左侧节次轴
                Column(Modifier.width(AxisWidth).fillMaxSize()) {
                    sessions.forEachIndexed { idx, hm ->
                        Column(
                            Modifier.fillMaxWidth().height(CellHeight).padding(top = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "${idx + 1}",
                                fontSize = 12.sp,
                                color = ink,
                                fontWeight = FontWeight.Bold,
                            )
                            if (!hideTimes) {
                                Text(
                                    slotLabels.getOrNull(idx) ?: Timetable.label(hm),
                                    fontSize = 8.sp, color = muted,
                                    lineHeight = 9.sp, maxLines = 2, textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                for (day in displayedDays) {
                    val dayCourses = grid.forDay(day)
                    val ghostCourses = grid.ghostsForDay(day)
                    val stoppedCourses = grid.skippedForDay(day)
                    // v1.33: 停课块与保留课同格，必须一起分车道，否则「停」和「N节」叠字。
                    val lanes = assignLanes(dayCourses + stoppedCourses)
                    val laneTotal = (lanes.maxOfOrNull { it.second } ?: 0) + 1
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        // 底板：每节一个可点区域（空课就在这一天的这一节加课）
                        Column(Modifier.fillMaxSize()) {
                            for (s in 1..sessions.size) {
                                val occupant = grid.at(s, day)
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(CellHeight)
                                        .clickable {
                                            if (occupant.isEmpty()) {
                                                onAddAt(WeekDisplay.dateAt(weekStart, startOnSunday, columnOfDay[day] ?: 0), s)
                                            } else {
                                                onEditCourse(occupant.first())
                                            }
                                        }
                                        .semantics {
                                            contentDescription =
                                                "周${DayNames[day - 1]}第 $s 节，" +
                                                    if (occupant.isEmpty()) {
                                                        "空，点按添加课程"
                                                    } else {
                                                        occupant.map { it.title }.distinct()
                                                            .joinToString("、") + "，点按编辑"
                                                    }
                                        },
                                ) {
                                    // 节次细线（跨节块会盖在上面，所以只在空白处看得到）
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .height(1.dp)
                                            .background(hair.copy(alpha = 0.6f))
                                    )
                                }
                            }
                        }
                        // 不在这一周的课：画在最下面一层，只有轮廓。点它跳到有这课的那一周。
                        for (c in ghostCourses) {
                            val from = c.sessionStart.coerceIn(1, sessions.size)
                            val to = c.sessionEnd.coerceIn(from, sessions.size)
                            val target = nearestWeek(c, weekIndex)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(CellHeight * (to - from + 1))
                                    .offset(y = CellHeight * (from - 1))
                                    .padding(1.dp)
                                    .background(
                                        Color(c.category.argb).copy(alpha = 0.05f),
                                        RoundedCornerShape(4.dp),
                                    )
                                    .border(
                                        1.dp,
                                        Color(c.category.argb).copy(alpha = 0.20f),
                                        RoundedCornerShape(4.dp),
                                    )
                                    .clickable {
                                        if (target != null && weekIndex != null) onShift(target - weekIndex)
                                    }
                                    .semantics {
                                        contentDescription = "周${DayNames[day - 1]}第 $from-$to 节，" +
                                            "${c.title}，不在本周，点按跳到有这课的一周"
                                    }
                                    .padding(horizontal = 3.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    c.title,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    color = muted.copy(alpha = 0.8f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // 这周停的课：按原位置画虚线框，写「停」。不画就变成「课莫名消失」。
                        // v1.33：和保留课共享车道，各占一格宽，不再叠字。
                        for (c in stoppedCourses) {
                            val from = c.sessionStart.coerceIn(1, sessions.size)
                            val to = c.sessionEnd.coerceIn(from, sessions.size)
                            val myLane = lanes.indexOfFirst { it.first === c }.let { if (it < 0) 0 else lanes[it].second }
                            Row(Modifier.fillMaxWidth().height(CellHeight * (to - from + 1))
                                .offset(y = CellHeight * (from - 1)).padding(1.dp)) {
                            if (myLane > 0) Spacer(Modifier.weight(myLane.toFloat()))
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(1.dp)
                                    .border(
                                        1.dp,
                                        muted.copy(alpha = 0.35f),
                                        RoundedCornerShape(4.dp),
                                    )
                                    .clickable { onEditCourse(c) }
                                    .semantics {
                                        contentDescription = "周${DayNames[day - 1]}第 $from-$to 节，" +
                                            "${c.title}，这周停课，点按查看"
                                    }
                                    .padding(horizontal = 3.dp, vertical = 2.dp),
                            ) {
                                Column(Modifier.fillMaxSize()) {
                                    Text(
                                        c.title,
                                        fontSize = 10.sp,
                                        lineHeight = 12.sp,
                                        color = muted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textDecoration = TextDecoration.LineThrough,
                                    )
                                    Text("停", fontSize = 9.sp, color = muted, maxLines = 1)
                                }
                            }
                            if (myLane < laneTotal - 1) Spacer(Modifier.weight((laneTotal - 1 - myLane).toFloat()))
                            }
                        }
                        // 课程块：画在底板之上，点到的是课。v1.33：与停课块共享车道。
                        for (c in dayCourses) {
                            val from = c.sessionStart.coerceIn(1, sessions.size)
                            val to = c.sessionEnd.coerceIn(from, sessions.size)
                            val myLane = lanes.indexOfFirst { it.first === c }.let { if (it < 0) 0 else lanes[it].second }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(CellHeight * (to - from + 1))
                                    .offset(y = CellHeight * (from - 1))
                                    .padding(1.dp),
                            ) {
                                if (myLane > 0) Spacer(Modifier.weight(myLane.toFloat()))
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .background(
                                            Color(c.category.argb).copy(alpha = 0.14f),
                                            RoundedCornerShape(4.dp),
                                        )
                                        .border(
                                            1.dp,
                                            Color(c.category.argb).copy(alpha = 0.45f),
                                            RoundedCornerShape(4.dp),
                                        )
                                        .pointerInput(c) {
                                            detectTapGestures(
                                                onTap = { onEditCourse(c) },
                                                onLongPress = {
                                                    copyCourse(context, c)
                                                    Toast.makeText(
                                                        context, "已复制：${c.copyText()}", Toast.LENGTH_SHORT,
                                                    ).show()
                                                },
                                            )
                                        }
                                        .semantics {
                                            contentDescription =
                                                "周${DayNames[day - 1]}第 $from-$to 节，${c.title}，点按编辑，长按复制"
                                        }
                                        .padding(horizontal = 3.dp, vertical = 2.dp),
                                ) {
                                    Column(Modifier.fillMaxSize()) {
                                        Text(
                                            c.title,
                                            fontSize = 10.sp,
                                            color = ink,
                                            lineHeight = 12.sp,
                                            maxLines = if (to > from) 4 else 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (to > from) {
                                            Text(
                                                "${to - from + 1}节",
                                                fontSize = 9.sp,
                                                color = muted,
                                                maxLines = 1,
                                            )
                                        }
                                        if (!hideRoom && c.room.isNotBlank()) {
                                            Text(
                                                c.room,
                                                fontSize = 9.sp,
                                                color = muted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        // 这周被单周改课动过：标一个「改」，免得用户下周发现又变回去以为是 bug。
                                        if (grid.isChanged(c.id)) {
                                            Text("改", fontSize = 9.sp, color = ink, maxLines = 1)
                                        }
                                        // 和别的课撞了：角标一个「!」，详情点顶部统计看。
                                        if (grid.isConflict(c.id)) {
                                            Text("冲突", fontSize = 9.sp, color = ink, maxLines = 1)
                                        }
                                    }
                                }
                                if (myLane < laneTotal - 1) {
                                    Spacer(Modifier.weight((laneTotal - 1 - myLane).toFloat()))
                                }
                            }
                        }
                        // 右侧分隔线
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(hair.copy(alpha = 0.55f))
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // ── 底部：添加课程 ────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "＋ 添加课程",
                fontSize = 14.sp,
                color = ink,
                modifier = Modifier
                    .clickable { onAdd() }
                    .semantics { contentDescription = "添加课程" }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 4.dp, vertical = 14.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (termStart == null) "没设开学日，不能按周过滤" else "开学 ${termStart.monthValue}/${termStart.dayOfMonth}",
                fontSize = 11.sp,
                color = muted,
            )
            Spacer(Modifier.width(8.dp))
            // 只发一行状态：开着就写提前多少，关着就写“只看不响”。
            // 这里不放开关：开关属于一次性设置，放设置页；课表页要能一眼看出状态。
            Text(
                if (courseRemindOn) CourseReminder.leadLabel(courseLead) else "只看不响",
                fontSize = 11.sp,
                color = if (courseRemindOn) ink.copy(alpha = 0.7f) else muted.copy(alpha = 0.8f),
            )
        }
    }

    if (showConflicts && grid.conflicts.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showConflicts = false },
            containerColor = paper,
            title = { Text("撞课 · ${grid.conflictCount()} 处", color = ink) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "同一节排了两门课，将至只把冲突摆出来，不替你决定上哪一门。",
                        fontSize = 12.sp, color = muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    grid.conflicts.forEach { clash ->
                        Text(
                            "周${DayNames[clash.dayOfWeek - 1]} ${clash.sessionText()}",
                            fontSize = 12.sp, color = ink, fontWeight = FontWeight.Bold,
                        )
                        clash.courses.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "· ${c.title}${if (c.room.isBlank()) "" else " · ${c.room}"}",
                                    fontSize = 12.sp, color = muted,
                                    modifier = Modifier.clickable {
                                        showConflicts = false
                                        onEditCourse(c)
                                    }.padding(vertical = 2.dp).weight(1f),
                                )
                                if (onKeepThisCourse != null && weekIndex != null) {
                                    TextButton(onClick = {
                                        val others = clash.courses.filter { it.id != c.id }
                                        showConflicts = false
                                        onKeepThisCourse!!(c, others)
                                    }) { Text("显示这门", fontSize = 11.sp, color = ink) }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showConflicts = false }) { Text("知道了", color = ink) } },
        )
    }
}

/**
 * 时段设置：一天几节、每节几点上课 / 下课。
 *
 * v1.27：一节课 = 一段时间 [start, end)，不再是「8:00 这一个点」。
 * 勾上「每节时长相同」只填一个时长；取消勾选就能逐节改下课时刻——
 * 与 WakeUp 课表设置一致，也顺带实现了「课间 10 分钟」这类不规则作息。
 */
@Composable
fun SessionTimesDialog(
    paper: Color,
    ink: Color,
    muted: Color,
    initialSlots: List<SessionTimes.Slot>,
    initialUniform: Boolean,
    onCancel: () -> Unit,
    onConfirm: (List<SessionTimes.Slot>, Boolean) -> Unit,
) {
    var slots by remember { mutableStateOf(initialSlots) }
    var uniform by remember { mutableStateOf(initialUniform) }
    // 正在改哪一节的哪个时刻：first = 下标，second = true 改下课。
    var editing by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    val duration = slots.firstOrNull()?.duration ?: SessionTimes.DEFAULT_DURATION

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = paper,
        title = { Text("上下课时间", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "一节课是一段时间。勾选「每节时长相同」只填一个时长；取消勾选就能逐节改下课时刻（比如课间 10 分钟）。",
                    fontSize = 12.sp, color = muted,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("一天 ${slots.size} 节", fontSize = 13.sp, color = ink, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { if (slots.size > 1) slots = slots.dropLast(1) },
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) { Text("−", fontSize = 18.sp, color = ink) }
                    TextButton(
                        onClick = {
                            if (slots.size < 14) {
                                val last = slots.last().start
                                val next = (last + 55).coerceAtMost(1400)
                                slots = slots + SessionTimes.Slot(next, (next + SessionTimes.DEFAULT_DURATION).coerceAtMost(1440))
                            }
                        },
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) { Text("＋", fontSize = 18.sp, color = ink) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "每节时长相同", fontSize = 13.sp, color = ink,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { uniform = !uniform }
                            .heightIn(min = 44.dp),
                    )
                    TextButton(onClick = { uniform = !uniform }, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(if (uniform) "✓ 开" else "关", fontSize = 12.sp, color = ink)
                    }
                }
                if (uniform) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("统一时长 $duration 分钟", fontSize = 12.sp, color = muted, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { slots = SessionTimes.uniform(slots, duration - 5) },
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) { Text("−5", fontSize = 12.sp, color = ink) }
                        TextButton(
                            onClick = { slots = SessionTimes.uniform(slots, duration + 5) },
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) { Text("＋5", fontSize = 12.sp, color = ink) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                slots.forEachIndexed { i, slot ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("第 ${i + 1} 节", fontSize = 12.sp, color = muted, modifier = Modifier.width(52.dp))
                        TextButton(
                            onClick = { editing = i to false },
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) { Text(slot.startText(), fontSize = 13.sp, color = ink) }
                        Text("–", fontSize = 12.sp, color = muted)
                        TextButton(
                            onClick = { uniform = false; editing = i to true },
                            modifier = Modifier.heightIn(min = 44.dp),
                        ) { Text(slot.endText(), fontSize = 13.sp, color = ink) }
                        Spacer(Modifier.weight(1f))
                        Text("${slot.duration} 分", fontSize = 10.sp, color = muted)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(slots, uniform) },
                colors = ButtonDefaults.buttonColors(containerColor = ink, contentColor = paper),
                shape = RoundedCornerShape(10.dp),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消", color = muted) } },
    )

    val pick = editing
    if (pick != null) {
        val (i, isEnd) = pick
        val slot = slots[i]
        TimePickerHost(
            initial = if (isEnd) slot.end / 60 to slot.end % 60 else slot.start / 60 to slot.start % 60,
            onPicked = { h, m ->
                val v = h * 60 + m
                slots = if (isEnd) {
                    SessionTimes.withEnd(slots, i, v)
                } else {
                    slots.toMutableList().also { it[i] = SessionTimes.Slot(v, maxOf(v + 5, slot.end)) }
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** 用系统的选择器改某一节的开始时刻。状态驱动弹出，回调只触发一次。 */
@Composable
fun TimePickerHost(
    initial: Pair<Int, Int>,
    onPicked: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        var done = false
        val dialog = android.app.TimePickerDialog(
            context,
            { _, h, m ->
                if (!done) {
                    done = true
                    onPicked(h, m)
                }
            },
            initial.first,
            initial.second,
            true,
        )
        dialog.setOnCancelListener { if (!done) { done = true; onDismiss() } }
        dialog.setOnDismissListener { if (!done) { done = true; onDismiss() } }
        dialog.show()
    }
}

/**
 * 添加 / 编辑一门课。
 *
 * 直接产出 [Course]（不再绕 `CourseScheduleImporter` 展开成几十条 Item）——
 * 一门课就是一门课，周次存成 `1-16单` 这样的规格，显示时现算。
 *
 * v1.16 起编辑已有课时可以选择改动范围：**整学期**（改 `courses` 表）或
 * **仅第 N 周**（写一条 [CourseOverride]，不动整学期的课）。后者的布局与 WakeUp
 * 课表的「单周改课」一致：现实里总有调课、停课，但下周又恢复正常。
 */
@Composable
fun AddCourseDialog(
    paper: Color,
    ink: Color,
    muted: Color,
    sessionCount: Int,
    initialCourse: Course?,
    initialDay: Int?,
    initialSession: Int?,
    onCancel: () -> Unit,
    onDelete: ((CourseDeletion.Scope) -> Unit)?,
    onConfirm: (CourseEdit) -> Unit,
    /** 当前正在看的是第几周（1 起）。null = 没设开学日，此时不提供「仅本周」。 */
    weekIndex: Int? = null,
    /** 这一周已有的例外（编辑已有课的某一周时传入）。 */
    initialOverride: CourseOverride? = null,
    /** 「恢复第 N 周原样」：只删这周的例外。 */
    onRestoreWeek: (() -> Unit)? = null,
    /** 学期开学日（第 1 周周一）；v1.24 日期模式换算周次要用。 */
    termStart: LocalDate? = null,
    /** v1.31：全局课前提前量，给「这门课用什么」那一行显示用。 */
    globalLead: Int = CourseReminder.DEFAULT_LEAD,
    /** v1.31：改这门课单独的提前量（null = 跟全局）。 */
    onPickLead: ((Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    // 只有「编辑一门已存的课」且「知道当前是第几周」时，才谈得上只改这一周。
    // 设了值就等于 weekIndex 不为 null，所以下面直接用 weekIndexInt 不再判空。
    val canScopeWeek = initialCourse != null && initialCourse.id > 0L && weekIndex != null
    val weekIndexInt = weekIndex ?: 0
    var scope by remember {
        mutableStateOf(if (initialOverride != null) EditScope.THIS_WEEK else EditScope.ALL_WEEKS)
    }
    var skipWeek by remember { mutableStateOf(initialOverride?.skip == true) }
    // v1.27 删除范围对话框：点「删除」先问范围，不直接删整门课。
    var showDeleteScope by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(initialCourse?.title.orEmpty()) }
    var room by remember { mutableStateOf(initialCourse?.room.orEmpty()) }
    // v1.23 教师 / 备注：默认折叠，有内容时自动展开；教师 ≤20 字，备注 ≤40 字。
    var extraOpen by remember {
        mutableStateOf(initialCourse?.teacher?.isNotBlank() == true || initialCourse?.note?.isNotBlank() == true)
    }
    var teacher by remember { mutableStateOf(initialCourse?.teacher.orEmpty()) }
    var note by remember { mutableStateOf(initialCourse?.note.orEmpty()) }
    // v1.31：单课提前量，-1 = 跟全局。
    var leadMinutes by remember {
        mutableStateOf(initialCourse?.leadMinutes ?: CourseLead.USE_GLOBAL)
    }
    var days by remember {
        mutableStateOf(
            initialCourse?.let { setOf(it.dayOfWeek) } ?: setOf(initialDay ?: 1)
        )
    }
    var from by remember { mutableStateOf(initialCourse?.sessionStart ?: (initialSession ?: 1)) }
    var to by remember {
        mutableStateOf(initialCourse?.sessionEnd ?: ((initialSession ?: 1) + 1))
    }
    // v1.20：周次改成直接输入任意文本（`1,3,5-9`、`3,4,6-11` 都行），
    // 单双周降级成快捷后缀按钮——只是便捷写法，不是硬约束。空文本 = 每周。
    var weeksText by remember { mutableStateOf(initialCourse?.weeks.orEmpty()) }
    // v1.24 日期模式：二级入口里选起止日期，换算结果回填周次框；不新增存储。
    var dateModeHint by remember { mutableStateOf<String?>(null) }
    val spec = remember(weeksText) { CourseWeeks.normalize(weeksText) }
    val curParity = when {
        spec.endsWith("单") -> "单"
        spec.endsWith("双") -> "双"
        else -> ""
    }
    // 这门课一学期会上几次（按周次规格和勾选的周几天数算）。
    val timesCount = remember(spec, days) { CourseWeeks.parse(spec).size * days.size }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = paper,
        title = {
            Text(
                if (initialCourse == null) "添加课程" else "编辑课程",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = ink,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    singleLine = true,
                    label = { Text("课程名") },
                    placeholder = { Text("高等数学") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it.take(16) },
                    singleLine = true,
                    label = { Text("教室（选填）") },
                    placeholder = { Text("B201") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (extraOpen) "收起 教师 / 备注" else "＋ 教师 / 备注（选填）",
                    fontSize = 12.sp, color = muted,
                    modifier = Modifier.clickable { extraOpen = !extraOpen },
                )
                if (extraOpen) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = teacher,
                        onValueChange = { teacher = it.take(20) },
                        singleLine = true,
                        label = { Text("教师") },
                        placeholder = { Text("王老师") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(40) },
                        label = { Text("备注") },
                        placeholder = { Text("实验室上课，带教材") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("这门课提前多久提醒", fontSize = 11.sp, color = muted)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CourseLead.options().forEach { opt ->
                        val selected = leadMinutes == opt
                        Button(
                            onClick = { leadMinutes = opt; onPickLead?.invoke(opt) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) ink else Color.Transparent,
                                contentColor = if (selected) paper else ink,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(CourseLead.label(opt, globalLead), fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                // ── 改动范围：整学期 / 仅第 N 周 ────────────────────────
                // 现实里调课、停课都是「就这一周」，改整学期会把后面十几周全弄错。
                if (canScopeWeek) {
                    Text("改动范围", fontSize = 11.sp, color = muted)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            EditScope.ALL_WEEKS to "整学期",
                            EditScope.THIS_WEEK to "仅第 $weekIndex 周",
                        ).forEach { pair ->
                            val v = pair.first
                            val label = pair.second
                            val on = scope == v
                            Box(
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 44.dp)
                                    .background(if (on) ink else paper, RoundedCornerShape(6.dp))
                                    .clickable { scope = v; if (v == EditScope.ALL_WEEKS) skipWeek = false }
                                    .semantics { contentDescription = label + if (on) "，已选" else "" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    fontSize = 13.sp,
                                    color = if (on) paper else ink,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (scope == EditScope.THIS_WEEK) {
                    // 只改这一周：能改的是「停课 / 周几 / 节次 / 教室」，周次规格本身不动。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("这一周", fontSize = 11.sp, color = muted, modifier = Modifier.width(48.dp))
                        listOf(false to "照常上", true to "停课").forEach { pair ->
                            val v = pair.first
                            val label = pair.second
                            val on = skipWeek == v
                            Box(
                                Modifier
                                    .padding(end = 6.dp)
                                    .heightIn(min = 44.dp)
                                    .background(if (on) ink else paper, RoundedCornerShape(6.dp))
                                    .clickable { skipWeek = v }
                                    .semantics { contentDescription = label + if (on) "，已选" else "" }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, fontSize = 13.sp, color = if (on) paper else ink)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Text(
                    "周几（可多选）",
                    fontSize = 11.sp,
                    color = if (scope == EditScope.THIS_WEEK && skipWeek) muted.copy(alpha = 0.4f) else muted,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayNames.forEachIndexed { i, label ->
                        val d = i + 1
                        val on = d in days
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp)
                                .background(if (on) ink else paper, RoundedCornerShape(6.dp))
                                .clickable { days = if (on) days - d else days + d }
                                .semantics {
                                    contentDescription = "周$label" + if (on) "，已选" else ""
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize = 14.sp,
                                color = if (on) paper else ink,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("节次", fontSize = 11.sp, color = muted, modifier = Modifier.width(40.dp))
                    Stepper(from, 1, to, ink, muted) { from = it }
                    Text(" – ", fontSize = 13.sp, color = muted)
                    Stepper(to, from, sessionCount, ink, muted) { to = it }
                    Text(" 节", fontSize = 12.sp, color = muted)
                }
                Spacer(Modifier.height(8.dp))
                // 周次 / 单双周只对「整学期」有意义；只改一周时周次规格不动。
                if (scope == EditScope.ALL_WEEKS) {
                    OutlinedTextField(
                        value = weeksText,
                        onValueChange = { weeksText = it.take(80) },
                        singleLine = true,
                        label = { Text("周次（空 = 每周）") },
                        placeholder = { Text("1,3,5-9") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "单 / 双周只是快捷写法，可直接改上面的文字",
                        fontSize = 11.sp, color = muted,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        if (termStart == null) {
                            dateModeHint = "请先在设置里设学期开学日"
                        } else {
                            val monday = DateModeWeeks.mondayOf(termStart)
                            pickDateRange(context, LocalDate.now()) { start, end ->
                                val s = DateModeWeeks.fromDates(monday, start, end)
                                dateModeHint = if (s == null) {
                                    "日期要落在开学日之后"
                                } else {
                                    weeksText = s
                                    "已换算成 $s · ${CourseWeeks.label(s)}"
                                }
                            }
                        }
                    }) {
                        Text("按日期选周 ›", color = ink)
                    }
                    if (dateModeHint != null) {
                        Text(dateModeHint!!, fontSize = 11.sp, color = muted)
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("快捷", fontSize = 11.sp, color = muted, modifier = Modifier.width(40.dp))
                        listOf("" to "都要", "单" to "单周", "双" to "双周").forEach { pair ->
                            val v = pair.first
                            val label = pair.second
                            val on = curParity == v
                            Box(
                                Modifier
                                    .padding(end = 6.dp)
                                    .heightIn(min = 44.dp)
                                    .background(if (on) ink else paper, RoundedCornerShape(6.dp))
                                    .clickable { weeksText = CourseWeeks.withParity(weeksText, v) }
                                    .semantics { contentDescription = label + if (on) "，已选" else "" }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(label, fontSize = 13.sp, color = if (on) paper else ink)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))

                Text(
                    when {
                        name.isBlank() -> "填写课程名后显示这学期会上几次"
                        days.isEmpty() -> "至少选一天"
                        scope == EditScope.THIS_WEEK && skipWeek ->
                            "第 $weekIndex 周停课 · 其余周按 " + CourseWeeks.label(spec) + " 照常上"
                        scope == EditScope.THIS_WEEK ->
                            "只改第 $weekIndex 周 · " +
                                days.sorted().joinToString("、") { "周" + DayNames[it - 1] } +
                                " 第 " + minOf(from, to) + "-" + maxOf(from, to) + " 节" +
                                (if (room.isBlank()) "" else " · " + room.trim()) +
                                "；其余周不变"
                        else -> CourseWeeks.label(spec) + " · " +
                            days.sorted().joinToString("、") { "周" + DayNames[it - 1] } +
                            " 第 " + minOf(from, to) + "-" + maxOf(from, to) + " 节 · 共 " + timesCount + " 次"
                    },
                    fontSize = 12.sp,
                    color = ink,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val course = Course(
                        id = initialCourse?.id ?: 0L,
                        title = name.trim(),
                        dayOfWeek = days.minOrNull() ?: 1,
                        sessionStart = minOf(from, to),
                        sessionEnd = maxOf(from, to),
                        weeks = spec,
                        room = room.trim(),
                        category = initialCourse?.category ?: CourseImporter.categoryFor(name.trim()),
                        teacher = teacher.trim(),
                        note = note.trim(),
                        leadMinutes = CourseLead.normalize(leadMinutes),
                    )
                    onConfirm(
                        CourseEdit(
                            course = course,
                            scope = scope,
                            week = weekIndexInt,
                            skipWeek = scope == EditScope.THIS_WEEK && skipWeek,
                        )
                    )
                },
                enabled = name.isNotBlank() && days.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = ink, contentColor = paper),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    when {
                        initialCourse == null -> "添加"
                        scope == EditScope.THIS_WEEK -> "只改这周"
                        else -> "保存"
                    }
                )
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { showDeleteScope = true }) { Text("删除", color = muted) }
                }
                if (onRestoreWeek != null) {
                    TextButton(onClick = onRestoreWeek) { Text("恢复这周", color = muted) }
                }
                TextButton(onClick = onCancel) { Text("取消", color = muted) }
            }
        },
    )

    // v1.27 删除范围：整门 / 仅本周 / 本周起以后。复用 CourseDeletion 的规格运算。
    if (showDeleteScope && initialCourse != null) {
        val scopes = listOf(
            CourseDeletion.Scope.ALL to "删整门课",
            CourseDeletion.Scope.THIS_WEEK to "只删第 $weekIndexInt 周",
            CourseDeletion.Scope.FROM_THIS_WEEK to "删第 $weekIndexInt 周及以后",
        )
        AlertDialog(
            onDismissRequest = { showDeleteScope = false },
            containerColor = paper,
            title = { Text("删哪一周？", fontSize = 18.sp, color = ink, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "同一门课常常每周都有，删之前选一下范围。",
                        fontSize = 12.sp, color = muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    scopes.forEach { (scope, label) ->
                        TextButton(
                            onClick = {
                                showDeleteScope = false
                                onDelete?.invoke(scope)
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        ) {
                            Text(
                                label + " · " + CourseDeletion.summary(
                                    scope, weekIndexInt,
                                    CourseDeletion.remainingSpec(spec, scope, weekIndexInt),
                                ),
                                fontSize = 12.sp, color = ink,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDeleteScope = false }) { Text("取消", color = muted) } },
        )
    }
}

@Composable
private fun Stepper(
    value: Int,
    min: Int,
    max: Int,
    ink: Color,
    muted: Color,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "−",
            fontSize = 16.sp,
            color = if (value > min) ink else muted.copy(alpha = 0.4f),
            modifier = Modifier
                .clickable(enabled = value > min) { onChange(value - 1) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
        Text(
            "$value",
            fontSize = 14.sp,
            color = ink,
            modifier = Modifier.width(20.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            "＋",
            fontSize = 16.sp,
            color = if (value < max) ink else muted.copy(alpha = 0.4f),
            modifier = Modifier
                .clickable(enabled = value < max) { onChange(value + 1) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}
