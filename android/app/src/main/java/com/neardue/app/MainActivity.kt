package com.neardue.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// 纸底 + 墨字 + 一条细线。唯一允许的彩色是 5 类标记点。
// v1.19：去掉暖米调，换成中性冷灰——更接近纸张与石墨的本色，读起来更安静。
// 这些色按 WCAG AA（≥4.5:1）对纸底调深过；暗底上要换回亮色，否则反而掉到 3.4:1。
// 全是等长十六进制字面量，DEX 体积不变。
private val PaperLight = Color(0xFFFAFAFB)
private val InkLight = Color(0xFF1A1B1E)
private val MutedLight = Color(0xFF6B6D75)
private val HairLight = Color(0xFFE6E6EA)
private val PaperDark = Color(0xFF151619)
private val InkDark = Color(0xFFF2F2F5)
private val MutedDark = Color(0xFF999BA4)
private val HairDark = Color(0xFF32343B)

// v1.34：状态色。此前全 App 只有「纸 / 墨 / 次墨 / 细线」四色，成功与未识别共用一条
// 硬编码绿条，视觉重量一模一样，用户看不出「这句是让你动手」。这两个色按 AA 调过：
// 铁锈红对纸底 6.20:1、对暗底 7.62:1；绿 5.09:1 / 8.41:1。
private val OkLight = Color(0xFF1F7A4E)
private val WarnLight = Color(0xFFA63A1E)
private val OkDark = Color(0xFF5CC492)
private val WarnDark = Color(0xFFEE8F79)

private val WhenFmt = DateTimeFormatter.ofPattern("M月d日 E HH:mm", Locale.CHINA)

private enum class Group(val label: String) {
    OVERDUE("已逾期"), TODAY("今天"), TOMORROW("明天"), WEEK("本周"), LATER("更远"), DONE("已完成"),
}

private enum class Screen { LIST, SETTINGS, TIMETABLE }

// 「分享 / 选中文本 → 将至」带进来的文本；读到即清空，只消费一次。
private var incomingText by mutableStateOf<String?>(null)

// 「点开 .json 备份文件 → 将至」带进来的 URI；读到即清空，只消费一次。
private var pendingImportUri by mutableStateOf<String?>(null)

// 「点开课表 PDF → 将至」带进来的 URI；读到即清空，只消费一次。
private var pendingPdfUri by mutableStateOf<String?>(null)

// 「小组件某一行 → 直达这件事」带进来的 id；读到即清空，只消费一次。
private var pendingOpenItemId by mutableStateOf<Long?>(null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIncoming(intent)
        setContent { NearDueRoot() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIncoming(intent)
    }

    // 只认纯文本：群通知 / 课程群里的截止时间，通常就是一段文字。
    // 例外：点开 .json 备份文件 → 直接恢复，不弹文件选择器。
    private fun readIncoming(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_VIEW) {
            val data = intent.data ?: return
            // 小组件逐行点击：neardue://item/<id> → 打开那条事项的编辑框。
            // ★必须排在 .pdf/.json 判定之前，否则会被当成备份文件 URI。
            if (data.scheme == "neardue" && data.host == "item") {
                pendingOpenItemId = data.lastPathSegment?.toLongOrNull()
                    ?: intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, 0L).takeIf { it != 0L }
                return
            }
            // 课表 PDF 与 .json 备份走不同处理：PDF 要抽文字，JSON 要恢复数据。
            val looksPdf = intent.type == "application/pdf" ||
                data.path?.endsWith(".pdf", ignoreCase = true) == true
            if (looksPdf) pendingPdfUri = data.toString() else pendingImportUri = data.toString()
            return
        }
        val text = when (intent.action) {
            Intent.ACTION_SEND ->
                if (intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT) else null
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        val t = text?.trim().orEmpty()
        if (t.length in 4..200) incomingText = t
    }
}

@Composable
private fun NearDueRoot() {
    val context = LocalContext.current
    var darkPref by remember { mutableStateOf(Prefs.dark(context)) }
    val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val dark = if (darkPref == 1) systemDark else darkPref == 2
    val (paper, ink, muted, hair) = if (dark) {
        listOf(PaperDark, InkDark, MutedDark, HairDark)
    } else {
        listOf(PaperLight, InkLight, MutedLight, HairLight)
    }
    val ok = if (dark) OkDark else OkLight
    val warn = if (dark) WarnDark else WarnLight
    val scheme = if (dark) {
        darkColorScheme(
            background = paper, surface = paper, onBackground = ink, onSurface = ink,
            primary = ink, onPrimary = paper, surfaceVariant = paper,
            onSurfaceVariant = muted, outline = hair,
        )
    } else {
        lightColorScheme(
            background = paper, surface = paper, onBackground = ink, onSurface = ink,
            primary = ink, onPrimary = paper, surfaceVariant = paper,
            onSurfaceVariant = muted, outline = hair,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        NearDueApp(
            paper = paper, ink = ink, muted = muted, hair = hair,
            ok = ok, warn = warn,
            onDarkChanged = { darkPref = it },
        )
    }
}

// 数据变动后统一刷新列表与小组件。
private fun refreshWidgets(context: Context) {
    try { NearDueWidget.refresh(context) } catch (_: Throwable) { }
}

private fun LocalDate.toMillis(hour: Int, minute: Int): Long =
    LocalDateTime.of(this, LocalTime.of(hour, minute))
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun dateOf(dueAt: Long): LocalDate =
    Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()).toLocalDate()

private fun daysLeft(dueAt: Long, today: LocalDate): Long =
    ChronoUnit.DAYS.between(today, dateOf(dueAt))

private fun groupOf(item: Item, today: LocalDate): Group {
    if (item.done) return Group.DONE
    return when (val d = daysLeft(item.dueAt, today)) {
        in Long.MIN_VALUE..-1L -> Group.OVERDUE
        0L -> Group.TODAY
        1L -> Group.TOMORROW
        in 2L..7L -> Group.WEEK     // v0.1 近似「本周」：未来 2–7 天
        else -> Group.LATER
    }
}

private fun fmt(dueAt: Long): String =
    Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()).format(WhenFmt)

private fun fmtDate(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

/** v1.35「导出 / 导入」的副标题：把上次备份时间直接写在设置页上，不弹窗不推通知。 */
private fun backupDesc(lastAt: Long): String =
    BackupStamp.desc(lastAt, LocalDate.now(), ZoneId.systemDefault())

@Composable
private fun NearDueApp(
    paper: Color,
    ink: Color,
    muted: Color,
    hair: Color,
    ok: Color,
    warn: Color,
    onDarkChanged: (Int) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ItemStore(context) }
    // 课表是**独立**的一张表（早期需求「课表不用变成事项提醒」）。
    val courseStore = remember { CourseStore(context) }
    // 学期是「容器」（v1.18）：多份课表各管各的，互不干扰（WakeUp 课表的多课表）。
    val termStore = remember { TermStore(context) }
    // 老库升级：`term_id = 0` 的课收进第一个学期，老的单一开学日搬进学期表。
    // 放在 remember 里只跑一次，且失败也不能挡 App 启动（大不了当没有学期）。
    val terms0 = remember { runCatching { termStore.adoptOrphanCourses(context); termStore.list() }.getOrDefault(emptyList()) }
    val firstTermId = remember { terms0.firstOrNull()?.id ?: Prefs.currentTermId(context) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf(store.list()) }
    var editing by remember { mutableStateOf<Item?>(null) }
    var adding by remember { mutableStateOf(false) }
    var doneExpanded by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(Screen.LIST) }
    var darkPref by remember { mutableStateOf(Prefs.dark(context)) }
    var clipboardOn by remember { mutableStateOf(Prefs.clipboard(context)) }
    // v1.20 显示做减法：只影响绘制，不动数据（开关状态本地保存）。
    var hideTimes by remember { mutableStateOf(Prefs.hideTimes(context)) }
    var hideRoom by remember { mutableStateOf(Prefs.hideRoom(context)) }
    // v1.22 小组件字号档位（0 小 / 1 中 / 2 大）。
    var widgetSize by remember { mutableStateOf(Prefs.widgetTextSize(context)) }
    // v1.35 上次备份时刻：导出成功后立刻刷新，设置页那行的副标题跟着变。
    var lastBackupAt by remember { mutableStateOf(Prefs.lastBackupAt(context)) }
    var startMonday by remember { mutableStateOf(Prefs.weekStartMonday(context)) }
    // v1.28 周日为每周第一天：只影响课表展示，课程数据仍是 ISO 周几。
    val startOnSunday = !startMonday
    var conflictKeep by remember { mutableStateOf<Course?>(null) }
    var conflictOthers by remember { mutableStateOf<List<Course>>(emptyList()) }
    var hour by remember { mutableStateOf(Prefs.hour(context)) }
    var minute by remember { mutableStateOf(Prefs.minute(context)) }
    var timeDialog by remember { mutableStateOf(false) }
    var pendingClip by remember { mutableStateOf<String?>(null) }
    var quietOn by remember { mutableStateOf(Prefs.quietEnabled(context)) }
    var offsets by remember { mutableStateOf(Prefs.remindOffsets(context)) }
    var offsetsDialog by remember { mutableStateOf(false) }
    var courseDialog by remember { mutableStateOf(false) }
    var termsDialog by remember { mutableStateOf(false) }
    var termPick by remember { mutableStateOf(false) }
    var termStart by remember {
        mutableStateOf(terms0.firstOrNull { it.id == firstTermId }?.startMonday ?: Prefs.termStartMonday(context))
    }
    var terms by remember { mutableStateOf(terms0) }
    var currentTermId by remember { mutableStateOf(firstTermId) }
    // 选 PDF 抽出的文字：塞进课程表对话框，用户可改可删，绝不静默导入。
    var pdfText by remember { mutableStateOf<String?>(null) }
    var pdfBusy by remember { mutableStateOf(false) }
    // 课表视图：第几周（0 = 本周）、左侧时间段、手动添加课程。
    var weekShift by remember { mutableStateOf(0) }
    var sessionSlots by remember { mutableStateOf(Prefs.sessionSlots(context, firstTermId)) }
    var sessionUniform by remember { mutableStateOf(Prefs.sessionUniform(context, firstTermId)) }
    var sessionsDialog by remember { mutableStateOf(false) }
    var addCourse by remember { mutableStateOf(false) }
    var addDay by remember { mutableStateOf<Int?>(null) }
    var addSession by remember { mutableStateOf<Int?>(null) }
    var editingCourse by remember { mutableStateOf<Course?>(null) }
    var courses by remember { mutableStateOf(courseStore.list(firstTermId)) }
    // 单周例外（仅本周改课 / 停课）。一次全读进来，按周筛是纯内存操作。
    var overrides by remember { mutableStateOf(courseStore.listOverrides()) }
    // 上课提醒：默认关（课表只看不响）。开了才排闹钟。
    var courseRemindOn by remember { mutableStateOf(Prefs.courseRemindEnabled(context)) }
    var courseLead by remember { mutableStateOf(Prefs.courseLeadMinutes(context)) }
    var courseLeadDialog by remember { mutableStateOf(false) }
    var permissionCheckDialog by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 打开 App 时补一次通知权限，并重排所有闹钟（开机由 BootReceiver 负责）。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AlarmScheduler.scheduleAll(context)
        // 上课提醒：窗口只有两周，所以每次打开 App 都把窗口补满。
        CourseAlarmScheduler.rescheduleAll(context)
    }

    // ── 学期（v1.18）──────────────────────────────────────────────
    // 一门课属于一个学期；单周例外、开学日、周次条都跟着学期走。

    /** 换学期：课 / 例外 / 开学日全跟着换，闹钟重排，周次回到本周。 */
    fun switchTerm(id: Long) {
        val target = termStore.get(id) ?: return
        Prefs.setCurrentTermId(context, id)
        currentTermId = id
        termStart = target.startMonday
        Prefs.setTermStartMonday(context, target.startMonday)
        sessionSlots = Prefs.sessionSlots(context, id)
        sessionUniform = Prefs.sessionUniform(context, id)
        courses = courseStore.list(id)
        overrides = courseStore.listOverrides()
        weekShift = 0
        try { CourseAlarmScheduler.rescheduleAll(context) } catch (_: Throwable) { }
    }

    fun uniqueTermName(base: String): String {
        val used = termStore.list().map { it.name }.toSet()
        if (base !in used) return base
        var i = 2
        while ("$base $i" in used) i++
        return "$base $i"
    }

    /** 新建一个空学期并切过去。课表得自己导或手动加——不偷偷复制上一学期的课。 */
    fun newTerm() {
        val id = termStore.add(
            Term(name = uniqueTermName("新课表"), createdAt = System.currentTimeMillis()),
        )
        terms = termStore.list()
        switchTerm(id)
    }

    fun deleteTerm(t: Term) {
        if (!termStore.canDelete(t)) {
            Toast.makeText(context, "至少要留一个学期", Toast.LENGTH_SHORT).show()
            return
        }
        val wasCurrent = t.id == currentTermId
        termStore.remove(t)
        Prefs.clearTermSchedule(context, t.id)
        terms = termStore.list()
        if (wasCurrent) {
            val first = terms.firstOrNull()
            if (first != null) switchTerm(first.id)
        }
        Toast.makeText(context, "已删除「${t.name}」", Toast.LENGTH_SHORT).show()
    }

    /**
     * 课表一变（导入 / 加课 / 改课 / 停课 / 改开学日 / 改作息），上课提醒就得重算。
     * 集中成一个函数，避免每个改课的地方各写一遍、总有地方漏。
     *
     * ★ v1.18 起只读**当前学期**的课 —— 别的学期没在上的课不该把你叫醒。
     */
    fun reloadCourses() {
        courses = courseStore.list(currentTermId)
        overrides = courseStore.listOverrides()
        try { CourseAlarmScheduler.rescheduleAll(context) } catch (_: Throwable) { }
    }

    /** 导备份：事项 → 学期 → 课程 → 例外，顺序不能变。 */
    fun importBackup(text: String) {
        val parsed = BackupCodec.decodeBundle(text)
        store.replaceAll(parsed.items)
        var termNote = ""
        if (parsed.hasCourses) {
            if (parsed.hasTerms && parsed.terms.isNotEmpty()) {
                // 带学期的备份：整表重建学期，课程按备份里的下标挂回去。
                // 作息不进备份；学期 id 会重生成，先清掉旧 id 的作息避免串味。
                termStore.list().forEach { Prefs.clearTermSchedule(context, it.id) }
                val fresh = termStore.replaceAll(
                    parsed.terms.map {
                        Term(name = it.name, startMonday = it.startMonday, createdAt = System.currentTimeMillis(), archived = it.archived)
                    },
                )
                terms = fresh
                val fallbackId = fresh.first().id
                courseStore.replaceAll(
                    parsed.courses.map {
                        it.copy(termId = BackupCodec.termIdForIndex(it.termId, fresh, fallbackId))
                    },
                )
                val ci = if (parsed.currentTermIndex in fresh.indices) parsed.currentTermIndex else 0
                // v1.31：归档中的学期不能当当前学期，回落到第一个活动学期。
                switchTerm(TermArchive.effectiveCurrent(fresh, fresh[ci].id) ?: fresh[ci].id)
                if (fresh.size > 1) termNote = " · ${fresh.size} 个学期"
            } else {
                // 老备份（v1~v3）：课全放进当前学期，一个学期都不删。
                val target = currentTermId.takeIf { it > 0L }
                    ?: termStore.ensureAtLeastOne(context).id
                courseStore.replaceTerm(target, parsed.courses.map { it.copy(termId = target) })
                switchTerm(target)
            }
        }
        val restored = restoreOverrides(courseStore, parsed)
        items = store.list()
        AlarmScheduler.scheduleAll(context)
        refreshWidgets(context)
        Toast.makeText(
            context,
            "已导入 ${parsed.items.size} 项 · ${parsed.courses.size} 门课$termNote" +
                if (restored > 0) " · $restored 处单周改动" else "",
            Toast.LENGTH_SHORT,
        ).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val list = store.list()
                // 备份是**全部学期**一起走：只导当前学期，换手机就等于丢半本课表。
                val termList = termStore.list()
                val allCourses = courseStore.list()
                val tagged = BackupCodec.coursesWithTermIndex(allCourses, termList)
                val overrideRecs = overridesToRecords(allCourses, courseStore.listOverrides())
                val bundle = BackupCodec.Bundle(
                    items = list,
                    courses = tagged,
                    hasCourses = true,
                    overrides = overrideRecs,
                    hasOverrides = true,
                    terms = termList.mapIndexed { i, t ->
                        BackupCodec.TermRecord(index = i, name = t.name, startMonday = t.startMonday, archived = t.archived)
                    },
                    hasTerms = true,
                    currentTermIndex = termList.indexOfFirst { it.id == currentTermId },
                )
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(BackupCodec.encode(bundle).toByteArray(Charsets.UTF_8))
                }
                val nowAt = System.currentTimeMillis()
                Prefs.setLastBackupAt(context, nowAt)
                lastBackupAt = nowAt
                Toast.makeText(
                    context,
                    "已导出 ${list.size} 项 · ${allCourses.size} 门课 · ${termList.size} 个学期" +
                        " · ${overrideRecs.size} 处单周改动",
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }.orEmpty()
                importBackup(text)
            } catch (e: Exception) {
                Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun reload() {
        items = store.list()
        try { AlarmScheduler.scheduleAll(context) } catch (_: Throwable) { }
    }

    // 选一个 PDF 课表 → 抽文字 → 填进课程表对话框。抽不出来就明说原因，不给假数据。
    fun loadPdf(uri: Uri) {
        pdfBusy = true
        kotlin.concurrent.thread {
            val outcome = try {
                val bytes = try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (_: Exception) {
                    // file:// 的 URI 有些 ROM 的 MediaProvider 不肯开，直接按路径读。
                    val p = uri.path
                    if (p != null) java.io.File(p).readBytes() else null
                } ?: throw PdfTextExtractor.PdfException("读不到这个文件")
                val r = PdfTextExtractor.extract(bytes)
                // 课表是二维表格，按阅读顺序拼会散架；先试按 x/y 还原网格，认不出才回退纯文本。
                val rows = try { ScheduleTableParser.toRows(r.lines) } catch (_: Exception) { null }
                val body = if (!rows.isNullOrBlank()) rows else r.text
                if (body.isBlank()) "__ERR__PDF 里没抽出文字" else body
            } catch (e: Exception) {
                "__ERR__${e.message ?: "解析失败"}"
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                pdfBusy = false
                if (outcome.startsWith("__ERR__")) {
                    Toast.makeText(context, "读不了这个 PDF：${outcome.removePrefix("__ERR__")}", Toast.LENGTH_LONG).show()
                } else {
                    pdfText = outcome
                    courseDialog = true
                }
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) loadPdf(uri) }

    fun delete(item: Item) {
        store.remove(item.id)
        reload()
        refreshWidgets(context)
        scope.launch {
            val result = snackbar.showSnackbar(
                message = "已删除",
                actionLabel = "撤销",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                store.add(item)      // 撤销：把原 Item 放回去
                reload()
                refreshWidgets(context)
            }
        }
    }

    // 剪贴板识别：每次窗口拿到焦点且开关打开时读一次，绝不后台监听。
    // 原来只在 LaunchedEffect(Unit)（进程启动）读一次——从别的 App 复制完切回来进程还在，
    // 永远不会重读。改成 ON_RESUME 后真机又暴露 Android 11 的另一道限制：ClipboardService
    // 只允许「当前有窗口焦点」的 App 读剪贴板，ON_RESUME 时窗口还没 focus，系统会直接
    // Denying clipboard access（logcat 可见）。所以监听 OnWindowFocusChangeListener：
    // 焦点回来 = 用户真的切回了这个 App，这时读既能读得到，时机也正好。
    // 条件放宽：能不能解析交给录入框的实时预览判断，这里只挡空文本和超长文本。
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view, clipboardOn) {
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && clipboardOn) {
                try {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                    if (text.isNotBlank() && text.length <= 200) pendingClip = text
                } catch (_: Exception) { /* 焦点切换瞬间偶发，忽略即可 */ }
            }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }

    // 「分享到将至」/「选中文本 → 将至」：直接带进录入框，让实时预览先跑一遍。
    val shared = incomingText
    androidx.compose.runtime.LaunchedEffect(shared) {
        if (shared != null) {
            incomingText = null
            clipPrefill = shared
            adding = true
        }
    }

    // 「小组件某一行 → 直达这件事」：按 id 找出来，直接打开编辑框。
    val openItemId = pendingOpenItemId
    androidx.compose.runtime.LaunchedEffect(openItemId) {
        if (openItemId == null) return@LaunchedEffect
        pendingOpenItemId = null
        store.get(openItemId)?.let {
            screen = Screen.LIST
            adding = false
            editing = it
        }
    }

    // 「点开 .json 备份文件 → 将至」：直接恢复，不弹选择器、不猜时间。
    val importUri = pendingImportUri
    val pdfUri = pendingPdfUri
    androidx.compose.runtime.LaunchedEffect(pdfUri) {
        if (pdfUri == null) return@LaunchedEffect
        pendingPdfUri = null
        loadPdf(Uri.parse(pdfUri))
    }
    androidx.compose.runtime.LaunchedEffect(importUri) {
        if (importUri == null) return@LaunchedEffect
        pendingImportUri = null
        try {
            val uri = Uri.parse(importUri)
            val text = try {
                context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }.orEmpty()
            } catch (_: Exception) {
                val p = uri.path
                if (p != null) java.io.File(p).readText(Charsets.UTF_8) else ""
            }
            // 与设置页「导入备份」走同一条路：学期、课程、例外一起还原。
            importBackup(text)
        } catch (e: Exception) {
            Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val today = LocalDate.now()
    val now = System.currentTimeMillis()
    // 当前翻到的是第几周（与 TimetableScreen 里同一算法）。
    // 只有知道第几周时才能「只改这周」—— 没设开学日就没有周次概念。
    val viewingWeek = Timetable.weekNumber(
        termStart,
        Timetable.weekStartOf(today).plusWeeks(weekShift.toLong()),
    )
    val weekOverrides = remember(overrides, viewingWeek) {
        val w = viewingWeek ?: return@remember emptyMap<Long, CourseOverride>()
        overrides.filter { it.week == w }.associateBy { it.courseId }
    }
    val active = items.count { !it.done }
    val soon = items.count { !it.done && daysLeft(it.dueAt, today) in 0L..3L }
    val next = items.filter { !it.done && it.dueAt >= now }.minByOrNull { it.dueAt }
        ?: items.filter { !it.done }.minByOrNull { it.dueAt }

    Scaffold(
        containerColor = paper,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (screen == Screen.LIST) {
                FloatingActionButton(
                    onClick = { adding = true },
                    containerColor = ink,
                    contentColor = paper,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .size(56.dp)
                        .semantics { contentDescription = "记一件事" },
                ) { Text("+", fontSize = 26.sp, color = paper) }
            }
        },
    ) { inner ->
        if (screen == Screen.LIST) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 22.dp),
                contentPadding = PaddingValues(bottom = 110.dp),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("将至", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ink)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$active 件事 · 最近 3 天 $soon 项",
                                fontSize = 13.sp,
                                color = muted,
                            )
                        }
                        // 主界面两个入口都用图形：日历 = 课表，齿轮 = 设置。
                        // 纯文字「课表」在标题旁边很容易被当成小标题略过（早期需求）。
                        Icon(
                            painter = painterResource(R.drawable.ic_calendar),
                            contentDescription = null,
                            tint = muted,
                            modifier = Modifier
                                .clickable { weekShift = 0; screen = Screen.TIMETABLE }
                                .semantics { contentDescription = "打开课表" }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                                .size(24.dp),
                        )
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = null,
                            tint = muted,
                            modifier = Modifier
                                .clickable { screen = Screen.SETTINGS }
                                .semantics { contentDescription = "打开设置" }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                                .size(24.dp),
                        )
                    }
                }

                if (pendingClip != null) {
                    item(key = "clip") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp)
                                .clickable {
                                    val t = pendingClip.orEmpty()
                                    pendingClip = null
                                    clipPrefill = t
                                    adding = true
                                },
                        ) {
                            Text("刚复制了一段通知？", fontSize = 12.sp, color = muted)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                pendingClip.orEmpty(),
                                fontSize = 14.sp,
                                color = ink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("点一下，记一件事 ›", fontSize = 12.sp, color = muted)
                            Spacer(Modifier.height(10.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
                        }
                    }
                }

                if (next != null) {
                    item { Hero(next, today, ink, muted, hair, onEdit = { editing = next }) }
                } else {
                    item { EmptyState(paper, ink, muted, hair, onAdd = { adding = true }) }
                }

                val grouped = items.groupBy { groupOf(it, today) }
                for (g in Group.entries) {
                    val list = grouped[g].orEmpty().sortedBy { it.dueAt }
                    if (list.isEmpty() && g != Group.DONE) continue
                    item(key = "head-$g") {
                        GroupHeader(
                            label = g.label,
                            count = list.size,
                            collapsible = g == Group.DONE,
                            expanded = doneExpanded,
                            onToggle = { doneExpanded = !doneExpanded },
                            ink = ink,
                            muted = muted,
                            hair = hair,
                        )
                    }
                    if (g == Group.DONE && !doneExpanded) continue
                    items(list, key = { it.id }) { item ->
                        ItemRow(
                            item = item,
                            today = today,
                            paper = paper,
                            ink = ink,
                            muted = muted,
                            hair = hair,
                            onEdit = { editing = item },
                            onToggle = {
                                store.setDone(item.id, !item.done)
                                reload()
                                refreshWidgets(context)
                            },
                            onDelete = { delete(item) },
                        )
                    }
                }
            }
        } else if (screen == Screen.TIMETABLE) {
            TimetableScreen(
                paper = paper, ink = ink, muted = muted, hair = hair,
                courses = courses,
                // v1.36 列头事项条：只是把 items 显示到课表上，点一下复用同一个编辑框。
                items = items,
                onEditItem = { editing = it },
                sessions = sessionSlots.map { it.start / 60 to it.start % 60 },
                slotLabels = sessionSlots.map { "${SessionTimes.Slot.fmt(it.start)}\n-${SessionTimes.Slot.fmt(it.end)}" },
                nonCurrentWeekLabel = if (weekShift != 0) "非本周 · 第 $viewingWeek 周" else "",
                startOnSunday = startOnSunday,
                onKeepThisCourse = { keep, others ->
                    conflictKeep = keep
                    conflictOthers = others
                },
                termStatusMessage = TermStatus.of(
                    termStart,
                    courses.filter { CourseWeeks.normalize(it.weeks).isNotEmpty() }
                        .mapNotNull { CourseWeeks.parse(it.weeks).maxOrNull() }.maxOrNull(),
                    viewingWeek ?: 0,
                    today,
                ).message,
                overflowMessage = SessionOverflow.message(
                    SessionOverflow.of(courses, sessionSlots.size), sessionSlots.size
                ) ?: "",
                termStart = termStart,
                weekShift = weekShift,
                today = today,
                overrides = weekOverrides,
                courseRemindOn = courseRemindOn,
                courseLead = courseLead,
                hideTimes = hideTimes,
                hideRoom = hideRoom,
                terms = terms,
                currentTermId = currentTermId,
                onSwitchTerm = { switchTerm(it) },
                onNewTerm = {
                    newTerm()
                    Toast.makeText(context, "已新建学期", Toast.LENGTH_SHORT).show()
                },
                onManageTerms = { termsDialog = true },
                onShift = { weekShift += it },
                onToday = { weekShift = 0 },
                onEditCourse = { editingCourse = it; addCourse = true },
                onAddAt = { day, session ->
                    addDay = day?.dayOfWeek?.value
                    addSession = session
                    editingCourse = null
                    addCourse = true
                },
                onAdd = { addDay = null; addSession = null; editingCourse = null; addCourse = true },
                onSessions = { sessionsDialog = true },
                onBack = { screen = Screen.LIST },
            )
        } else {
            SettingsScreen(
                paper = paper, ink = ink, muted = muted, hair = hair,
                darkPref = darkPref, clipboardOn = clipboardOn, startMonday = startMonday,
                hideTimes = hideTimes, hideRoom = hideRoom, widgetSize = widgetSize,
                hour = hour, minute = minute,
                lastBackupAt = lastBackupAt,
                quietOn = quietOn,
                offsets = offsets,
                termStart = termStart,
                termCount = terms.size,
                termName = terms.firstOrNull { it.id == currentTermId }?.name ?: "未命名",
                onTerms = { termsDialog = true },
                courseRemindOn = courseRemindOn,
                courseLead = courseLead,
                onDark = {
                    darkPref = it
                    Prefs.setDark(context, it)
                    onDarkChanged(it)
                },
                onClipboard = {
                    clipboardOn = it
                    Prefs.setClipboard(context, it)
                },
                onHideTimes = {
                    hideTimes = it
                    Prefs.setHideTimes(context, it)
                },
                onHideRoom = {
                    hideRoom = it
                    Prefs.setHideRoom(context, it)
                },
                onWidgetSize = {
                    widgetSize = it
                    Prefs.setWidgetTextSize(context, it)
                    refreshWidgets(context)
                },
                onWeekStart = {
                    startMonday = it
                    Prefs.setWeekStartMonday(context, it)
                },
                onQuiet = {
                    quietOn = it
                    Prefs.setQuietEnabled(context, it)
                    AlarmScheduler.scheduleAll(context)
                },
                onRemindOffsets = { offsetsDialog = true },
                onCourseRemind = { on ->
                    courseRemindOn = on
                    Prefs.setCourseRemindEnabled(context, on)
                    CourseAlarmScheduler.rescheduleAll(context)
                    // 打开时把三项权限一次查完：国产 ROM 上闹钟不响多半是这三样没给。
                    if (on) permissionCheckDialog = true
                },
                onCourseLead = { courseLeadDialog = true },
                onCourseTest = { CourseAlarmScheduler.fireTest(context) },
                onTest = { AlarmScheduler.fireTest(context) },
                onExactAlarm = { AlarmScheduler.requestExactAlarmPermission(context) },
                onTime = { timeDialog = true },
                onExport = { exportLauncher.launch("neardue-backup.json") },
                onImport = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                onCourseSchedule = { courseDialog = true },
                onImportPdf = { pdfLauncher.launch(arrayOf("application/pdf")) },
                onTermStart = { termPick = true },
                onTimetable = { weekShift = 0; screen = Screen.TIMETABLE },
                onSessions = { sessionsDialog = true },
                sessionCount = sessionSlots.size,
                onBack = { screen = Screen.LIST },
            )
        }
    }

    if (timeDialog) {
        TimePrefDialog(
            paper = paper, ink = ink, muted = muted,
            initialHour = hour, initialMinute = minute,
            onCancel = { timeDialog = false },
            onConfirm = { h, m ->
                hour = h; minute = m
                Prefs.setTime(context, h, m)
                timeDialog = false
            },
        )
    }

    if (offsetsDialog) {
        RemindOffsetsDialog(
            paper = paper, ink = ink, muted = muted,
            current = offsets,
            onCancel = { offsetsDialog = false },
            onConfirm = { list ->
                offsets = list
                Prefs.setRemindOffsets(context, list)
                AlarmScheduler.scheduleAll(context)
                offsetsDialog = false
            },
        )
    }

    if (permissionCheckDialog) {
        PermissionCheckDialog(
            paper = paper, ink = ink, muted = muted,
            onNotification = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onExactAlarm = { AlarmScheduler.requestExactAlarmPermission(context) },
            onBattery = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (ignored: Exception) { }
            },
            onClose = { permissionCheckDialog = false },
        )
    }

    if (courseLeadDialog) {
        CourseLeadDialog(
            paper = paper, ink = ink, muted = muted,
            current = courseLead,
            onCancel = { courseLeadDialog = false },
            onConfirm = { v ->
                courseLead = v
                Prefs.setCourseLeadMinutes(context, v)
                CourseAlarmScheduler.rescheduleAll(context)
                courseLeadDialog = false
            },
        )
    }

    if (courseDialog) {
        CourseScheduleDialog(
            paper = paper, ink = ink, muted = muted,
            existingCourseKeys = courseStore.existingKeys(currentTermId),
            defaultHour = hour, defaultMinute = minute,
            termStart = termStart,
            termId = currentTermId,
            onPickTermStart = { termPick = true },
            initialText = pdfText,
            onTextConsumed = { pdfText = null },
            onPickPdf = { pdfLauncher.launch(arrayOf("application/pdf")) },
            pdfBusy = pdfBusy,
            termName = terms.firstOrNull { it.id == currentTermId }?.name,
            onCancel = { courseDialog = false; pdfText = null },
            onConfirm = { result, replace ->
                var addedCourses = 0
                var addedItems = 0
                if (result.courses.isNotEmpty()) {
                    if (replace) {
                        // 整表替换只动当前学期：别的学期的课表和单周例外一概不碰。
                        addedCourses = courseStore.replaceTerm(currentTermId, result.courses)
                    } else {
                        addedCourses = courseStore.addAll(result.courses)
                    }
                    reloadCourses()
                }
                if (result.items.isNotEmpty()) {
                    result.items.forEach { store.add(it) }
                    addedItems = result.items.size
                    reload()
                }
                refreshWidgets(context)
                courseDialog = false
                pdfText = null
                if (addedCourses > 0) {
                    // v1.30 导入课表后直接切到课表，省得用户还要自己找过去看结果。
                    weekShift = 0
                    screen = Screen.TIMETABLE
                }
                Toast.makeText(
                    context,
                    buildString {
                        if (addedCourses > 0) append(if (replace) "已替换 $addedCourses 门课" else "已加 $addedCourses 门课")
                        if (addedCourses > 0 && addedItems > 0) append("、")
                        if (addedItems > 0) append("已加 $addedItems 件事")
                        if (addedCourses == 0 && addedItems == 0) append("没有可导入的行")
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }

    if (termsDialog) {
        TermsDialog(
            paper = paper, ink = ink, muted = muted, hair = hair,
            terms = terms,
            currentTermId = currentTermId,
            onSwitch = { switchTerm(it) },
            onNew = {
                newTerm()
                Toast.makeText(context, "已新建学期", Toast.LENGTH_SHORT).show()
            },
            onRename = { t, name ->
                termStore.save(t.copy(name = name))
                terms = termStore.list()
            },
            onArchive = { t ->
                if (t.archived) {
                    termStore.save(t.copy(archived = false))
                    terms = termStore.list()
                    Toast.makeText(context, "已取出「${t.name}」", Toast.LENGTH_SHORT).show()
                } else if (!TermArchive.canArchive(t, terms)) {
                    Toast.makeText(context, "至少留一个活动学期", Toast.LENGTH_SHORT).show()
                } else {
                    val wasCurrent = t.id == currentTermId
                    termStore.save(t.copy(archived = true))
                    terms = termStore.list()
                    val next = TermArchive.effectiveCurrent(terms, currentTermId)
                    if (wasCurrent && next != null) switchTerm(next)
                    Toast.makeText(context, "已归档「${t.name}」，课都还在", Toast.LENGTH_SHORT).show()
                }
            },
            onDelete = { deleteTerm(it) },
            onClose = { termsDialog = false },
        )
    }

    if (sessionsDialog) {
        SessionTimesDialog(
            paper = paper, ink = ink, muted = muted,
            initialSlots = sessionSlots,
            initialUniform = sessionUniform,
            onCancel = { sessionsDialog = false },
            onConfirm = { slots, uniform ->
                sessionSlots = slots
                sessionUniform = uniform
                Prefs.setSessionSlots(context, slots, currentTermId)
                Prefs.setSessionUniform(context, uniform, currentTermId)
                // 同步写全局模板：新建学期默认继承当前作息。
                Prefs.setSessionSlots(context, slots, 0L)
                Prefs.setSessionUniform(context, uniform, 0L)
                CourseAlarmScheduler.rescheduleAll(context)
                sessionsDialog = false
            },
        )
    }

    // v1.28 撞课「显示这门课」：把落选的几门在本周停掉（写 skip 例外）。
    if (conflictKeep != null && viewingWeek != null) {
        AlertDialog(
            onDismissRequest = { conflictKeep = null },
            containerColor = paper,
            title = { Text("只显示这一门？", color = ink) },
            text = {
                Text(
                    ConflictResolver.summary(conflictKeep!!, conflictOthers, viewingWeek!!),
                    fontSize = 13.sp, color = muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    for (o in ConflictResolver.skipOthers(conflictOthers + conflictKeep!!, conflictKeep!!.id, viewingWeek!!)) {
                        courseStore.saveOverride(o)
                    }
                    overrides = courseStore.listOverrides()
                    addCourse = false
                    editingCourse = null
                    CourseAlarmScheduler.rescheduleAll(context)
                    Toast.makeText(context, "第 $viewingWeek 周已只留「${conflictKeep!!.title}」", Toast.LENGTH_SHORT).show()
                    conflictKeep = null
                }) { Text("只显示这门", color = ink) }
            },
            dismissButton = { TextButton(onClick = { conflictKeep = null }) { Text("取消", color = muted) } },
        )
    }
    if (addCourse) {
        AddCourseDialog(
            paper = paper, ink = ink, muted = muted,
            sessionCount = sessionSlots.size,
            initialCourse = editingCourse,
            initialDay = addDay,
            initialSession = addSession,
            weekIndex = viewingWeek,
            initialOverride = editingCourse?.let { weekOverrides[it.id] },
            termStart = termStart,
            globalLead = courseLead,
            onPickLead = { /* 选档只改弹窗状态，保存时随课程写库并重排闹钟 */ },
            onRestoreWeek = editingCourse?.let { c ->
                if (weekOverrides.containsKey(c.id) && viewingWeek != null) {
                    {
                        courseStore.removeOverride(c.id, viewingWeek)
                        overrides = courseStore.listOverrides()
                        addCourse = false
                        editingCourse = null
                        CourseAlarmScheduler.rescheduleAll(context)
                        Toast.makeText(context, "已恢复第 $viewingWeek 周原样", Toast.LENGTH_SHORT).show()
                    }
                } else null
            },
            onCancel = { addCourse = false; editingCourse = null },
            onDelete = editingCourse?.let { c ->
                { scope ->
                    when (scope) {
                        CourseDeletion.Scope.ALL -> {
                            courseStore.remove(c.id)
                        }
                        CourseDeletion.Scope.THIS_WEEK -> {
                            val rest = CourseDeletion.remainingSpec(c.weeks, scope, viewingWeek ?: 0)
                            if (rest.isEmpty()) {
                                courseStore.remove(c.id)
                                } else {
                                courseStore.save(c.copy(weeks = rest))
                            }
                        }
                        CourseDeletion.Scope.FROM_THIS_WEEK -> {
                            val rest = CourseDeletion.remainingSpec(c.weeks, scope, viewingWeek ?: 0)
                            if (rest.isEmpty()) {
                                courseStore.remove(c.id)
                                } else {
                                courseStore.save(c.copy(weeks = rest))
                            }
                        }
                    }
                    courses = courseStore.list(currentTermId)
                    overrides = courseStore.listOverrides()
                    addCourse = false
                    editingCourse = null
                    CourseAlarmScheduler.rescheduleAll(context)
                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                }
            },
            onConfirm = { edit ->
                val course = edit.course
                if (edit.scope == EditScope.THIS_WEEK && course.id > 0L && edit.week > 0) {
                    // 只改这一周：写一条例外，不动整学期的课。
                    // ★基线要用「整学期原记录」而不是 editingCourse —— 后者来自课表格子，
                    // 已经把本周的例外叠上去了，拿它当基线永远算出「什么都没改」。
                    val base = courses.firstOrNull { it.id == course.id } ?: course
                    if (edit.skipWeek) {
                        courseStore.saveOverride(
                            CourseOverride(courseId = course.id, week = edit.week, skip = true),
                        )
                    } else {
                        // 只存真正变了的字段（0 / null = 不改），这样以后改整学期能自动跟上。
                        val o = CourseOverride.of(
                            courseId = course.id,
                            week = edit.week,
                            skip = false,
                            dayOfWeek = if (course.dayOfWeek != base.dayOfWeek) course.dayOfWeek else 0,
                            sessionStart = if (course.sessionStart != base.sessionStart) course.sessionStart else 0,
                            sessionEnd = if (course.sessionEnd != base.sessionEnd) course.sessionEnd else 0,
                            room = if (course.room != base.room) course.room else null,
                        )
                        if (o != null) courseStore.saveOverride(o)
                    }
                    overrides = courseStore.listOverrides()
                    addCourse = false
                    editingCourse = null
                    CourseAlarmScheduler.rescheduleAll(context)
                    Toast.makeText(context, "只改了第 ${edit.week} 周", Toast.LENGTH_SHORT).show()
                } else {
                    // 新课和改课都落在当前学期上。
                    val target = course.copy(termId = currentTermId)
                    if (target.id > 0) {
                        courseStore.save(target)
                    } else {
                        courseStore.add(target)
                    }
                    courses = courseStore.list(currentTermId)
                    addCourse = false
                    editingCourse = null
                    CourseAlarmScheduler.rescheduleAll(context)
                    Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    if (termPick) {
        val base = termStart ?: LocalDate.now().with(java.time.DayOfWeek.MONDAY)
        DatePickerDialog(
            context,
            { _, y, m, d ->
                val picked = LocalDate.of(y, m + 1, d)
                // 不管用户点的是哪一天，都对齐到那一周的周一 —— 课表的「第 1 周」是以周为单位的。
                val monday = picked.minusDays((picked.dayOfWeek.value - 1).toLong())
                termStart = monday
                Prefs.setTermStartMonday(context, monday)
                // 开学日属于学期，不属于全局：同时写回当前学期。
                terms.firstOrNull { it.id == currentTermId }?.let { t ->
                    termStore.save(t.copy(startMonday = monday))
                    terms = termStore.list()
                }
                CourseAlarmScheduler.rescheduleAll(context)
                termPick = false
            },
            base.year, base.monthValue - 1, base.dayOfMonth,
        ).show()
    }

    if (adding || editing != null) {
        EditSheet(
            initial = editing,
            prefill = if (adding) clipPrefill else null,
            paper = paper, ink = ink, muted = muted, ok = ok, warn = warn,
            defaultHour = hour,
            defaultMinute = minute,
            onCancel = {
                adding = false; editing = null; clipPrefill = null
            },
            onSave = { result ->
                if (result.id == 0L) store.add(result) else store.save(result)
                reload()
                refreshWidgets(context)
                adding = false
                editing = null
                clipPrefill = null
            },
        )
    }
}

// 剪贴板预填：仅一次，用完即清。
private var clipPrefill: String? = null

@Composable
private fun EmptyState(
    paper: Color,
    ink: Color,
    muted: Color,
    hair: Color,
    onAdd: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("0", fontSize = 96.sp, fontWeight = FontWeight.Bold, color = ink, lineHeight = 96.sp)
        Spacer(Modifier.height(8.dp))
        Text("现在，一件都不用惦记", fontSize = 18.sp, color = ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "一句话，5 秒。\n剩下的交给将至。",
            fontSize = 13.sp,
            color = muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(containerColor = ink, contentColor = paper),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.height(50.dp),
        ) { Text("记第一件事") }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
    }
}

@Composable
private fun Hero(
    item: Item,
    today: LocalDate,
    ink: Color,
    muted: Color,
    hair: Color,
    onEdit: () -> Unit,
) {
    val d = daysLeft(item.dueAt, today)
    // v1.35：这块是全屏最大的一块，用户下意识会去点。以前点它没反应，
    // 得先滚到下面列表里找到同一件事——现在直接开编辑框。
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
            .semantics {
                contentDescription = "最近的截止：" + item.title + "。点按编辑"
            }
            .padding(vertical = 18.dp),
    ) {
        if (d < 0) {
            Text("已逾期", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ink)
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$d",
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    lineHeight = 96.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text("天后截止", fontSize = 14.sp, color = muted, modifier = Modifier.padding(bottom = 18.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, fontSize = 18.sp, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(fmt(item.dueAt), fontSize = 13.sp, color = muted)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
    }
}

@Composable
private fun GroupHeader(
    label: String,
    count: Int,
    collapsible: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    ink: Color,
    muted: Color,
    hair: Color,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (collapsible) Modifier.clickable { onToggle() } else Modifier)
            .heightIn(min = 48.dp)
            .padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = muted)
        if (collapsible) {
            Text(
                "$count 项 · ${if (expanded) "收起" else "展开"}",
                fontSize = 12.sp,
                color = muted,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
}

@Composable
private fun ItemRow(
    item: Item,
    today: LocalDate,
    paper: Color,
    ink: Color,
    muted: Color,
    hair: Color,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 110.dp.toPx() }
    var offsetX by remember(item.id) { mutableFloatStateOf(0f) }
    val d = daysLeft(item.dueAt, today)
    val rowColor = if (item.done) muted else ink

    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset((offsetX * 0.35f).toInt(), 0) }
                .background(paper)
                .semantics {
                    contentDescription = "${item.title}，${item.category.label}类，" +
                        (if (item.done) "已完成" else "还剩 $d 天") +
                        "，${fmt(item.dueAt)}。点按编辑，右滑完成，左滑删除"
                }
                .pointerInput(item.id) {
                    detectTapGestures(onTap = { onEdit() })
                }
                .pointerInput(item.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val x = offsetX
                            offsetX = 0f
                            when {
                                x > threshold -> onToggle()    // 右滑完成
                                x < -threshold -> onDelete()   // 左滑删除
                            }
                        },
                        onDragCancel = { offsetX = 0f },
                        onHorizontalDrag = { _, delta -> offsetX += delta },
                    )
                }
                .heightIn(min = 64.dp)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(58.dp)) {
                Text(
                    if (item.done) "·" else "$d",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = rowColor,
                    lineHeight = 34.sp,
                )
                if (!item.done) Text("天", fontSize = 11.sp, color = muted)
            }
            Column(
                Modifier.width(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(8.dp).background(Color(item.category.argb), CircleShape))
                Spacer(Modifier.height(4.dp))
                Text(item.category.label, fontSize = 10.sp, color = muted)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    fontSize = 16.sp,
                    color = rowColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(fmt(item.dueAt), fontSize = 12.sp, color = muted)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(hair)
                .align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun SettingsScreen(
    paper: Color,
    ink: Color,
    muted: Color,
    hair: Color,
    darkPref: Int,
    clipboardOn: Boolean,
    startMonday: Boolean,
    hideTimes: Boolean,
    hideRoom: Boolean,
    widgetSize: Int,
    hour: Int,
    minute: Int,
    lastBackupAt: Long,
    quietOn: Boolean,
    offsets: List<Int>,
    termStart: LocalDate?,
    termCount: Int,
    termName: String,
    onTerms: () -> Unit,
    courseRemindOn: Boolean,
    courseLead: Int,
    onQuiet: (Boolean) -> Unit,
    onRemindOffsets: () -> Unit,
    onCourseRemind: (Boolean) -> Unit,
    onCourseLead: () -> Unit,
    onCourseTest: () -> Unit,
    onTest: () -> Unit,
    onExactAlarm: () -> Unit,
    onDark: (Int) -> Unit,
    onClipboard: (Boolean) -> Unit,
    onHideTimes: (Boolean) -> Unit,
    onHideRoom: (Boolean) -> Unit,
    onWidgetSize: (Int) -> Unit,
    onWeekStart: (Boolean) -> Unit,
    onTime: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onCourseSchedule: () -> Unit,
    onImportPdf: () -> Unit,
    onTermStart: () -> Unit,
    onTimetable: () -> Unit,
    onSessions: () -> Unit,
    sessionCount: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 22.dp),
        contentPadding = PaddingValues(top = 26.dp, bottom = 60.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.clickable { onBack() }
                        .semantics { contentDescription = "返回列表" }
                        .padding(end = 24.dp, top = 12.dp, bottom = 12.dp)
                        .size(24.dp),
                )
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("设置", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ink)
            Text("该有的都有，不堆设置", fontSize = 13.sp, color = muted)
        }
        item {
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth()) {
                Text("让提醒准时响", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "国产 ROM 需要在系统里给「自启动 / 电池白名单」放行",
                    fontSize = 12.sp,
                    color = muted,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "去系统设置 ›",
                    fontSize = 13.sp,
                    color = ink,
                    modifier = Modifier.clickable {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) { }
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
        }
        item { SectionTitle("提醒", muted) }
        item {
            SettingRow("默认提醒时间", "新建事项自动带上", offsetsLabel(offsets), ink, muted, hair, onClick = onRemindOffsets)
            SettingRow(
                "默认截止时间", "没写具体时刻时用它",
                String.format(Locale.CHINA, "%02d:%02d", hour, minute),
                ink, muted, hair, onClick = onTime,
            )
            SettingSwitch("免打扰时段", "23:00 – 07:00 不响铃", quietOn, ink, muted, hair, onQuiet)
            SettingRow("发一条测试通知", "立刻验证", "发送 ›", ink, muted, hair, onClick = onTest)
            SettingRow("精确闹钟权限", "国产 ROM 必开", "去设置 ›", ink, muted, hair, onClick = onExactAlarm)
        }
        item { SectionTitle("上课提醒", muted) }
        item {
            SettingSwitch(
                "课前提醒",
                "课表默认不响",
                courseRemindOn, ink, muted, hair, onCourseRemind,
            )
            if (courseRemindOn) {
                SettingRow(
                    "提前多久",
                    "所有课共用",
                    "${CourseReminder.leadLabel(courseLead)} ›",
                    ink, muted, hair, onClick = onCourseLead,
                )
                SettingRow("发一条上课提醒", "预览通知", "发送 ›", ink, muted, hair, onClick = onCourseTest)
            }
        }
        item { SectionTitle("外观", muted) }
        item {
            SettingRow(
                "深色模式",
                when (darkPref) { 0 -> "始终浅色"; 1 -> "跟随系统"; else -> "始终深色" },
                when (darkPref) { 0 -> "浅色"; 1 -> "跟随系统"; else -> "深色" },
                ink, muted, hair,
                onClick = { onDark((darkPref + 1) % 3) },
            )
            SettingSwitch(
                "每周起始日", if (startMonday) "周一" else "周日",
                startMonday, ink, muted, hair, onWeekStart,
            )
            SettingSwitch(
                "课表只显示节次", "不显示 8:00 这类时刻",
                hideTimes, ink, muted, hair, onHideTimes,
            )
            SettingSwitch(
                "课表隐藏教室", "只是不画，数据还在",
                hideRoom, ink, muted, hair, onHideRoom,
            )
            SettingRow(
                "小组件字号", "不影响课表",
                WidgetText.widgetSizeLabel(widgetSize), ink, muted, hair,
                onClick = { onWidgetSize((widgetSize + 1) % 3) },
            )
        }
        item { SectionTitle("数据", muted) }
        item {
            SettingRow(
                "从 PDF 导入课程表", "教务导出的 PDF", "选文件 ›",
                ink, muted, hair, onClick = onImportPdf,
            )
            SettingRow(
                "导出 / 导入",
                backupDesc(lastBackupAt),
                "导出 ›",
                ink, muted, hair, onClick = onExport,
            )
            SettingRow("导入备份", "恢复备份，不清空课表", "导入 ›", ink, muted, hair, onClick = onImport)
            SettingRow(
                "导入课程表", "粘贴文字，展开成每次课", "导入 ›",
                ink, muted, hair, onClick = onCourseSchedule,
            )
            SettingRow(
                "学期开学日", "「第 1 周」从这天算",
                termStart?.let { "${fmtDate(it)} ›" } ?: "未设置 ›",
                ink, muted, hair, onClick = onTermStart,
            )
            SettingRow(
                "学期", "多个学期并存",
                "$termName · 共 $termCount 个 ›",
                ink, muted, hair, onClick = onTerms,
            )
            SettingRow(
                "上下课时间", "每节起止时刻 · 各学期独立", "$sessionCount 节 ›",
                ink, muted, hair, onClick = onSessions,
            )
            SettingRow(
                "查看课表", "按周看", "打开 ›",
                ink, muted, hair, onClick = onTimetable,
            )
            SettingSwitch(
                "剪贴板识别", "回 App 时读一次", clipboardOn,
                ink, muted, hair, onClipboard,
            )
        }
        item {
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                Text("无账号 · 无联网 · 无广告", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ink)
                Spacer(Modifier.height(4.dp))
                Text(
                    "不申请 INTERNET 权限。数据只在本地，不上传、不分析、不推广告。",
                    fontSize = 12.sp,
                    color = muted,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
            Spacer(Modifier.height(16.dp))
            Text(
                "将至 · v" + BuildConfig.VERSION_NAME,
                fontSize = 12.sp,
                color = muted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 导出时把单周例外换成 [BackupCodec.OverrideRecord]。
 *
 * 关键：备份里不能存自增 id —— 导入时 id 是 SQLite 重新生成的，旧 id 全失效。
 * 所以用 [CourseImporter.courseKey]（课名 + 周几 + 起止节次）当身份，导入后再寻回。
 */
private fun overridesToRecords(
    courses: List<Course>,
    overrides: List<CourseOverride>,
): List<BackupCodec.OverrideRecord> {
    if (overrides.isEmpty()) return emptyList()
    val keyById = courses.associate { it.id to CourseImporter.courseKey(it) }
    return overrides.mapNotNull { o ->
        val key = keyById[o.courseId] ?: return@mapNotNull null
        BackupCodec.OverrideRecord(
            courseKey = key,
            week = o.week,
            skip = o.skip,
            dayOfWeek = o.dayOfWeek,
            sessionStart = o.sessionStart,
            sessionEnd = o.sessionEnd,
            room = o.room,
        )
    }
}

/**
 * 导入时把 [BackupCodec.OverrideRecord] 接回真实的课 id。返回成功接上的条数。
 *
 * **当前库里的单周例外一定先清掉** —— 刚做完 `replaceAll`，课程 id 全变了，旧例外全是孤儿。
 * 旧备份（没有 overrides 键）绝不能把例外清空当成「用户不要了」：那种情况只在 hasOverrides 时才动。
 */
private fun restoreOverrides(courseStore: CourseStore, parsed: BackupCodec.Bundle): Int {
    if (!parsed.hasOverrides) return 0
    val keyById = courseStore.list().associate { CourseImporter.courseKey(it) to it.id }
    var n = 0
    for (r in parsed.overrides) {
        val id = keyById[r.courseKey] ?: continue
        val o = CourseOverride.of(
            courseId = id,
            week = r.week,
            skip = r.skip,
            dayOfWeek = r.dayOfWeek,
            sessionStart = r.sessionStart,
            sessionEnd = r.sessionEnd,
            room = r.room,
        ) ?: continue
        courseStore.saveOverride(o)
        n++
    }
    return n
}

private fun offsetsLabel(offsets: List<Int>): String = when {
    offsets == listOf(-1440, -120) -> "提前 1 天 + 2 小时"
    offsets == listOf(-120) -> "仅提前 2 小时"
    else -> offsets.joinToString(" + ") { m ->
        val v = -m
        if (v >= 1440) "提前 ${v / 1440} 天" else "提前 ${v / 60} 小时"
    }
}

@Composable
private fun TermsDialog(
    paper: Color,
    ink: Color,
    muted: Color,
    hair: Color,
    terms: List<Term>,
    currentTermId: Long,
    onSwitch: (Long) -> Unit,
    onNew: () -> Unit,
    onRename: (Term, String) -> Unit,
    onDelete: (Term) -> Unit,
    onArchive: (Term) -> Unit,
    onClose: () -> Unit,
) {
    var renaming by remember { mutableStateOf<Long?>(null) }
    var draft by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = paper,
        title = { Text("学期", color = ink) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "每个学期的课表、单周改动、开学日各自独立，互不干扰。",
                    fontSize = 12.sp, color = muted,
                )
                Spacer(Modifier.height(8.dp))
                val archivedCount = terms.count { it.archived }
                if (archivedCount > 0) {
                    Text(TermArchive.archiveHint(archivedCount), fontSize = 11.sp, color = muted)
                    Spacer(Modifier.height(6.dp))
                }
                terms.filter { !it.archived }.forEach { t ->
                    if (renaming == t.id) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text("学期名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row {
                            TextButton(onClick = {
                                if (draft.isNotBlank()) onRename(t, draft.trim())
                                renaming = null
                            }) { Text("保存", color = ink) }
                            TextButton(onClick = { renaming = null }) { Text("取消", color = muted) }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (t.id == currentTermId) "● " else "") + t.name,
                                    fontSize = 15.sp,
                                    color = if (t.id == currentTermId) ink else muted,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(t.subtitle(), fontSize = 11.sp, color = muted)
                            }
                            if (t.id != currentTermId) {
                                TextButton(onClick = { onSwitch(t.id); onClose() }) {
                                    Text("切换", color = ink)
                                }
                            }
                            TextButton(onClick = { renaming = t.id; draft = t.name }) {
                                Text("改名", color = muted)
                            }
                            TextButton(onClick = { onArchive(t) }) { Text("归档", color = muted) }
                            TextButton(onClick = { onDelete(t) }) { Text("删除", color = muted) }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
                    }
                }
                if (archivedCount > 0) {
                    Spacer(Modifier.height(10.dp))
                    Text("已归档", fontSize = 12.sp, color = muted)
                    terms.filter { it.archived }.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(t.name, fontSize = 14.sp, color = muted)
                                Text(t.subtitle(), fontSize = 11.sp, color = muted)
                            }
                            TextButton(onClick = { onArchive(t) }) { Text("取出", color = ink) }
                            TextButton(onClick = { onDelete(t) }) { Text("删除", color = muted) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNew) { Text("新建学期", color = ink) } },
        dismissButton = { TextButton(onClick = onClose) { Text("完成", color = muted) } },
    )
}

@Composable
private fun PermissionCheckDialog(
    paper: Color,
    ink: Color,
    muted: Color,
    onNotification: () -> Unit,
    onExactAlarm: () -> Unit,
    onBattery: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    val alarmOk = AlarmScheduler.canSchedule(context)
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val batteryOk = pm == null || pm.isIgnoringBatteryOptimizations(context.packageName)
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = paper,
        title = { Text("提醒能响吗？", color = ink) },
        text = {
            Column {
                Text(
                    "国产手机对闹钟管得严，三项都开了才不会漏。\n将至不后台联网，闹钟由系统本地触发。",
                    fontSize = 12.sp, color = muted, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                PermissionRow("通知权限", notifOk, ink, muted, onNotification)
                PermissionRow("精确闹钟", alarmOk, ink, muted, onExactAlarm)
                PermissionRow("忽略电池优化", batteryOk, ink, muted, onBattery)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("知道了", color = ink) } },
    )
}

@Composable
private fun PermissionRow(
    name: String,
    ok: Boolean,
    ink: Color,
    muted: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !ok, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (ok) "✓" else "!",
            fontSize = 14.sp, color = ink,
            modifier = Modifier.width(24.dp),
        )
        Text(name, fontSize = 13.sp, color = ink, modifier = Modifier.weight(1f))
        if (!ok) Text("去设置 ›", fontSize = 12.sp, color = muted)
    }
}

@Composable
private fun CourseLeadDialog(
    paper: Color,
    ink: Color,
    muted: Color,
    current: Int,
    onCancel: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = paper,
        title = { Text("提前多久提醒", color = ink) },
        text = {
            Column {
                Text(
                    "所有课都用这一个提前量。\n分开设容易调成“上节课还没下课就响”。",
                    color = muted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(8.dp))
                CourseReminder.LEAD_OPTIONS.forEach { m ->
                    TextButton(onClick = { onConfirm(m) }) {
                        Text(
                            CourseReminder.leadLabel(m),
                            color = if (m == current) ink else muted,
                            fontWeight = if (m == current) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消", color = muted) } },
    )
}

@Composable
private fun RemindOffsetsDialog(
    paper: Color,
    ink: Color,
    muted: Color,
    current: List<Int>,
    onCancel: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
) {
    val options = listOf(
        "提前 1 天 + 2 小时" to listOf(-1440, -120),
        "仅提前 2 小时" to listOf(-120),
        "提前 1 天 + 2 小时 + 1 小时" to listOf(-1440, -120, -60),
    )
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = paper,
        title = { Text("默认提醒时间", color = ink) },
        text = {
            Column {
                options.forEach { (label, list) ->
                    TextButton(onClick = { onConfirm(list) }) {
                        Text(
                            label,
                            color = if (list == current) ink else muted,
                            fontWeight = if (list == current) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消", color = muted) } },
    )
}

@Composable
private fun SectionTitle(text: String, muted: Color) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = muted,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingRow(
    name: String,
    desc: String,
    value: String,
    ink: Color,
    muted: Color,
    hair: Color,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .semantics { contentDescription = "$name，$desc，$value" }
            .heightIn(min = 48.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, color = ink)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 12.sp, color = muted)
        }
        Spacer(Modifier.width(10.dp))
        Text(value, fontSize = 13.sp, color = muted)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
}

@Composable
private fun SettingSwitch(
    name: String,
    desc: String,
    checked: Boolean,
    ink: Color,
    muted: Color,
    hair: Color,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, color = ink)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 12.sp, color = muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = "$name，$desc" },
            colors = SwitchDefaults.colors(
                checkedThumbColor = ink,
                checkedTrackColor = ink.copy(alpha = 0.35f),
                uncheckedThumbColor = muted,
                uncheckedTrackColor = muted.copy(alpha = 0.25f),
            ),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
}

@Composable
private fun CourseScheduleDialog(
    paper: Color,
    ink: Color,
    muted: Color,
    existingCourseKeys: Set<String>,
    defaultHour: Int,
    defaultMinute: Int,
    termStart: LocalDate?,
    termId: Long = 0L,
    onPickTermStart: () -> Unit,
    initialText: String? = null,
    onTextConsumed: () -> Unit = {},
    onPickPdf: () -> Unit = {},
    pdfBusy: Boolean = false,
    termName: String? = null,
    onCancel: () -> Unit,
    onConfirm: (CourseImporter.Result, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    // 追加还是整表替换：默认追加（更安全，不会把现有课表弄丢）。
    var replace by remember { mutableStateOf(false) }
    // 从 PDF 抽出来的文字只预填一次，之后归用户编辑。
    androidx.compose.runtime.LaunchedEffect(initialText) {
        if (!initialText.isNullOrBlank()) {
            text = initialText
            onTextConsumed()
        }
    }
    // 整表替换时必须放行重复行：否则拿同一张表重导一次，全被当成「已存在」跳掉。
    val keys = if (replace) emptySet() else existingCourseKeys
    val preview = remember(text, defaultHour, defaultMinute, keys, termId) {
        CourseImporter.parse(
            text,
            System.currentTimeMillis(),
            defaultHour,
            defaultMinute,
            keys,
            termId,
        )
    }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = paper,
        title = { Text("导入课程表", color = ink) },
        text = {
            Column {
                Text(
                    "选教务导出的 PDF，自动把文字抽到下面，能再改；也可以直接粘整张表，或写「高数期末 12月14日」。\n没写日期也没写周次的行跳过，不猜。",
                    fontSize = 12.sp, color = muted,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (termStart == null) "开学日未设置 · 周次课表不会展开" else "第 1 周周一 ${fmtDate(termStart)}",
                        fontSize = 12.sp,
                        color = if (termStart == null) muted else ink,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPickTermStart) {
                        Text(if (termStart == null) "设置 ›" else "改 ›", color = ink)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onPickPdf, enabled = !pdfBusy) {
                        Text(if (pdfBusy) "正在读 PDF…" else "选 PDF 课表…", color = ink)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        val clip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        if (clip.isNotBlank()) text = clip
                    }) { Text("从剪贴板粘贴", color = ink) }
                }
                Text(
                    "选教务系统导出的课表 PDF，会把里面的文字抽出来填到下面，你能再改。",
                    fontSize = 11.sp, color = muted,
                )
                Spacer(Modifier.height(6.dp))
                Text("导到哪里", fontSize = 12.sp, color = muted)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(false to "追加", true to "整表替换").forEach { (mode, label) ->
                        val on = replace == mode
                        Box(
                            Modifier
                                .padding(end = 8.dp)
                                .background(
                                    if (on) ink else Color.Transparent,
                                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                )
                                .border(
                                    1.dp,
                                    if (on) ink else muted.copy(alpha = 0.4f),
                                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                )
                                .clickable { replace = mode }
                                .semantics { contentDescription = label }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(label, fontSize = 13.sp, color = if (on) paper else ink)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (replace) {
                        "导入后「${termName ?: "当前学期"}」只剩这张表里的课，别的学期的课表不受影响。"
                    } else {
                        "新课接在后面，重复的格子跳过；「整表替换」只换当前学期。"
                    },
                    fontSize = 11.sp, color = muted,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 6,
                    maxLines = 10,
                    label = { Text("每行一件事 / 一门课") },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("课表 ${preview.courses.size} 门")
                        append(" · 事项 ${preview.items.size} 件")
                        append(" · 跳过 ${preview.skippedLines} 行")
                        append(" · 去重 ${preview.duplicateLines} 行")
                    },
                    fontSize = 12.sp,
                    color = if (preview.courses.isEmpty() && preview.items.isEmpty()) muted else ink,
                )
                if (preview.courses.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        preview.courses.take(3).joinToString("\n") {
                            CourseWeeks.dayName(it.dayOfWeek) + " " + it.sessionText() +
                                " " + it.title + " " + it.weeksText()
                        },
                        fontSize = 11.sp, color = muted,
                    )
                }
                if (preview.items.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        preview.items.take(3).joinToString("\n") { "${fmt(it.dueAt)} · ${it.title}" },
                        fontSize = 11.sp, color = muted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(preview, replace) },
                enabled = preview.courses.isNotEmpty() || preview.items.isNotEmpty(),
            ) {
                Text(
                    if (replace) "替换 ${preview.courses.size + preview.items.size} 项"
                    else "导入 ${preview.courses.size + preview.items.size} 项",
                    color = ink,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消", color = muted) } },
    )
}

@Composable
private fun TimePrefDialog(
    paper: Color,
    ink: Color,
    muted: Color,
    initialHour: Int,
    initialMinute: Int,
    onCancel: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    var h by remember { mutableStateOf(initialHour) }
    var m by remember { mutableStateOf(initialMinute) }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = paper,
        title = { Text("默认截止时间", color = ink) },
        text = {
            Column {
                Text("没写具体时刻时用它", fontSize = 12.sp, color = muted)
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = {
                    TimePickerDialog(
                        context,
                        { _, hh, mm -> h = hh; m = mm },
                        h, m, true,
                    ).show()
                }) {
                    Text(String.format(Locale.CHINA, "%02d:%02d", h, m), fontSize = 28.sp, color = ink)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(h, m) }) {
                Text("保存", color = ink, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消", color = muted) } },
    )
}

@Composable
private fun EditSheet(
    initial: Item?,
    prefill: String?,
    paper: Color,
    ink: Color,
    muted: Color,
    ok: Color,
    warn: Color,
    defaultHour: Int,
    defaultMinute: Int,
    onCancel: () -> Unit,
    onSave: (Item) -> Unit,
) {
    val context = LocalContext.current
    val base = initial?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.dueAt), ZoneId.systemDefault()) }

    var text by remember { mutableStateOf(initial?.title ?: prefill.orEmpty()) }
    var date by remember { mutableStateOf(base?.toLocalDate() ?: LocalDate.now()) }
    var hour by remember { mutableStateOf(base?.hour ?: defaultHour) }
    var minute by remember { mutableStateOf(base?.minute ?: defaultMinute) }
    var category by remember { mutableStateOf(initial?.category ?: Category.OTHER) }
    var manual by remember { mutableStateOf(initial != null) }
    // 外部来的文字（剪贴板 / 分享 / 选中）保持原样；只有显式点「提取名字」才替换。
    var fromExternal by remember { mutableStateOf(initial == null && prefill != null) }
    val parsed = remember(text, defaultHour, defaultMinute) {
        ChineseDateParser.parse(text, System.currentTimeMillis(), defaultHour, defaultMinute)
    }
    // 解析成功且用户没手动改过 → 同步日期/时间/分类到下面的选择器（预览即确认）。
    androidx.compose.runtime.LaunchedEffect(parsed.dueAt) {
        if (!manual && parsed.matched) {
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(parsed.dueAt), ZoneId.systemDefault())
            date = dt.toLocalDate()
            hour = dt.hour
            minute = dt.minute
            category = Category.of(parsed.categoryOrdinal)
        }
    }
    val previewDue = if (parsed.matched && !manual) parsed.dueAt else date.toMillis(hour, minute)
    val previewCategory = if (parsed.matched && !manual) Category.of(parsed.categoryOrdinal) else category
    // 编辑已有事项时标题里通常不带日期，但日期是存在的（来自事项本身或用户手选）。
    // 此时若还按「没识别出日期」报红，就是误报——红字反而比黑字更误导（v1.35 真机发现）。
    val hasDue = parsed.matched || manual

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = paper,
        title = { Text(if (initial == null) "记一件事" else "编辑", color = ink) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; manual = false; fromExternal = false },
                    singleLine = true,
                    label = { Text("做什么 · 一句话就行") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val suggested = if (fromExternal) TitleExtract.suggestion(parsed.title, text) else null
                if (suggested != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { text = suggested; fromExternal = false }) {
                        Text("提取名字：" + suggested.take(18) + " ›", fontSize = 12.sp, color = ink)
                    }
                }
                if (text.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    // v1.34：竖条与标题跟着状态走。未识别时整块转铁锈红并加粗，
                    // 否则它和成功态长得一样重，用户不会注意到「日期得自己选」。
                    // 竖条顺带从硬编码绿改走主题色（暗底原来只有 3.40:1，没过 AA）。
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .height(38.dp)
                                .background(if (hasDue) ok else warn)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            if (hasDue) {
                                Text(
                                    "${fmt(previewDue)} · ${previewCategory.label} · 还剩 ${daysLeft(previewDue, LocalDate.now())} 天",
                                    fontSize = 14.sp, color = ink, fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text("预览即确认 · 点下面的日期 / 时间可改", fontSize = 11.sp, color = muted)
                            } else {
                                Text(
                                    "没识别出日期",
                                    fontSize = 14.sp, color = warn, fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text("请在下面手动选，不会瞎猜一个时间", fontSize = 11.sp, color = muted)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        DatePickerDialog(
                            context,
                            { _, y, m, day -> date = LocalDate.of(y, m + 1, day); manual = true },
                            date.year, date.monthValue - 1, date.dayOfMonth,
                        ).show()
                    }) { Text("${date.monthValue}月${date.dayOfMonth}日", color = ink) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        TimePickerDialog(
                            context,
                            { _, h, min -> hour = h; minute = min; manual = true },
                            hour, minute, true,
                        ).show()
                    }) {
                        Text(String.format(Locale.CHINA, "%02d:%02d", hour, minute), color = ink)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("分类", fontSize = 12.sp, color = muted)
                Row {
                    Category.entries.forEach { c ->
                        TextButton(onClick = { category = c; manual = true }) {
                            Text(
                                c.label,
                                color = if (category == c) ink else muted,
                                fontWeight = if (category == c) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "一句话输入，如「高数作业 11.20」· 没写时刻默认 ${String.format(Locale.CHINA, "%02d:%02d", defaultHour, defaultMinute)} · 右滑完成 / 左滑删除",
                    fontSize = 11.sp,
                    color = muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val finalTitle = TitleExtract.finalTitle(parsed.title, text, parsed.matched, fromExternal, manual)
                if (finalTitle.isBlank()) return@TextButton
                onSave(
                    Item(
                        id = initial?.id ?: 0L,
                        title = finalTitle,
                        dueAt = previewDue,
                        category = previewCategory,
                        done = initial?.done ?: false,
                        remindOffsets = initial?.remindOffsets ?: "-1440,-120",
                    )
                )
            }) { Text("保存", color = ink, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消", color = muted) } },
    )
}
