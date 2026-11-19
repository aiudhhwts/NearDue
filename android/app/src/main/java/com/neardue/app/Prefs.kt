package com.neardue.app

import android.content.Context

object Prefs {
    const val DEFAULT_HOUR = 19
    const val DEFAULT_MINUTE = 0

    private const val PREF_NAME = "neardue"
    private const val KEY_HOUR = "default_hour"
    private const val KEY_MINUTE = "default_minute"
    private const val KEY_DARK = "dark_mode"          // 0 始终浅色 / 1 跟随系统 / 2 始终深色
    private const val KEY_CLIPBOARD = "clipboard"
    private const val KEY_MONDAY = "week_start_monday"
    private const val KEY_REMIND_OFFSETS = "remind_offsets"
    private const val KEY_QUIET_ENABLED = "quiet_enabled"
    private const val KEY_QUIET_START = "quiet_start"
    private const val KEY_QUIET_END = "quiet_end"

    // 课表作息：一天几节 + 每节的开始时刻（8:0,8:55,... 以 , 分隔）。课表视图左侧时间轴用它。
    private const val KEY_SESSION_COUNT = "session_count"
    private const val KEY_SESSION_TIMES = "session_times"
    // v1.27 每节时长相同开关：开 = 统一时长，关 = 逐节改下课时刻。
    private const val KEY_SESSION_UNIFORM = "session_uniform"

    // 学期开学日：存第 1 周周一的 epochDay。课程表按「第几周 + 周几」展开时需要它。
    // v1.18 起开学日搬进 terms 表（每个学期一个），这里只留下来做升级迁移用。
    private const val KEY_TERM_START = "term_start_monday"

    // 当前正在看哪个学期（Term.id）。0 = 还没定，交给 TermStore.ensureAtLeastOne 挑一个。
    private const val KEY_CURRENT_TERM = "current_term_id"

    // 上课提醒：默认关。早期需求要的就是「课表只看不响」，所以开关必须显式打开才生效。
    private const val KEY_COURSE_REMIND = "course_remind"
    private const val KEY_COURSE_LEAD = "course_lead"

    // v1.20 课表显示做减法（只减绘制，不动数据）：
    // hideTimes = 左侧时间轴只显示第几节，不显示「8:00」这类具体时刻；
    // hideRoom  = 课程块不显示教室（教室仍在数据里，长按可复制、详情可编辑）。
    private const val KEY_HIDE_TIMES = "timetable_hide_times"
    private const val KEY_HIDE_ROOM = "timetable_hide_room"

    // v1.22 小组件字号档位：0 小 / 1 中（默认，与 v1.21 一致）/ 2 大。
    private const val KEY_WIDGET_TEXT_SIZE = "widget_text_size"

    // v1.35 上次成功导出的时刻（epoch millis）。0 = 从没备份过。
    // 不弹窗、不打扰，只在设置页「导出 / 导入」那行的副标题里静静写着。
    private const val KEY_LAST_BACKUP = "last_backup_at"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun hour(context: Context): Int =
        prefs(context).getInt(KEY_HOUR, DEFAULT_HOUR)

    fun minute(context: Context): Int =
        prefs(context).getInt(KEY_MINUTE, DEFAULT_MINUTE)

    fun setTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit()
            .putInt(KEY_HOUR, hour)
            .putInt(KEY_MINUTE, minute)
            .apply()
    }

    fun dark(context: Context): Int =
        prefs(context).getInt(KEY_DARK, 1).coerceIn(0, 2)

    fun setDark(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_DARK, value.coerceIn(0, 2)).apply()
    }

    fun clipboard(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CLIPBOARD, true)

    fun setClipboard(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_CLIPBOARD, on).apply()
    }

    fun hideTimes(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_TIMES, false)

    fun setHideTimes(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_TIMES, on).apply()
    }

    fun hideRoom(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIDE_ROOM, false)

    fun setHideRoom(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_ROOM, on).apply()
    }

    fun widgetTextSize(context: Context): Int =
        prefs(context).getInt(KEY_WIDGET_TEXT_SIZE, 1).coerceIn(0, 2)

    fun setWidgetTextSize(context: Context, level: Int) {
        prefs(context).edit().putInt(KEY_WIDGET_TEXT_SIZE, level.coerceIn(0, 2)).apply()
    }

    fun weekStartMonday(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MONDAY, true)

    fun setWeekStartMonday(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONDAY, on).apply()
    }

    // 提醒偏移（分钟，负数 = 提前）。默认提前 1 天 + 2 小时。
    fun remindOffsets(context: Context): List<Int> {
        val raw = prefs(context).getString(KEY_REMIND_OFFSETS, "-1440,-120").orEmpty()
        val parsed = raw.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it < 0 }
        return parsed.ifEmpty { listOf(-1440, -120) }
    }

    fun setRemindOffsets(context: Context, offsets: List<Int>) {
        prefs(context).edit()
            .putString(KEY_REMIND_OFFSETS, offsets.distinct().sorted().joinToString(","))
            .apply()
    }

    fun quietEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_QUIET_ENABLED, false)

    fun setQuietEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_QUIET_ENABLED, on).apply()
    }

    // 免打扰窗口，24 小时制整点。默认 23:00 – 07:00。
    fun quietStart(context: Context): Int =
        prefs(context).getInt(KEY_QUIET_START, 23).coerceIn(0, 23)

    fun quietEnd(context: Context): Int =
        prefs(context).getInt(KEY_QUIET_END, 7).coerceIn(0, 23)

    fun setQuietWindow(context: Context, start: Int, end: Int) {
        prefs(context).edit()
            .putInt(KEY_QUIET_START, start.coerceIn(0, 23))
            .putInt(KEY_QUIET_END, end.coerceIn(0, 23))
            .apply()
    }

    /** 第 1 周的周一；没设过返回 null（课程表周次行不展开，绝不瞎猜）。 */
    fun termStartMonday(context: Context): java.time.LocalDate? {
        val raw = prefs(context).getLong(KEY_TERM_START, Long.MIN_VALUE)
        if (raw == Long.MIN_VALUE) return null
        return java.time.LocalDate.ofEpochDay(raw)
    }

    fun setTermStartMonday(context: Context, date: java.time.LocalDate?) {
        prefs(context).edit().apply {
            if (date == null) remove(KEY_TERM_START) else putLong(KEY_TERM_START, date.toEpochDay())
        }.apply()
    }

    // ── 当前学期（v1.18） ──────────────────────────────────────────────

    /** 当前正在用的学期 id；0 表示还没定过。 */
    fun currentTermId(context: Context): Long =
        prefs(context).getLong(KEY_CURRENT_TERM, 0L)

    fun setCurrentTermId(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY_CURRENT_TERM, id.coerceAtLeast(0L)).apply()
    }

    // ── 课表作息（左侧时间轴） ───────────────────────────────────────────

    /**
     * 每节课的开始时刻，下标 0 = 第 1 节。默认用常见高校作息。
     *
     * 存成 "8:0,8:55,10:0,..." 这种文本：小、可读、迁移时人眼能核对，也不需要序列化框架。
     */
    fun sessionTimes(context: Context, termId: Long = 0L): List<Pair<Int, Int>> =
        sessionSlots(context, termId).map { it.start / 60 to it.start % 60 }

    /**
     * v1.27：每节课的起止时刻。老格式（只有开始时刻）自动补出结束时刻。
     * v1.29：按学期隔离 —— termId>0 读该学期的作息，没设过则回落全局模板。
     */
    fun sessionSlots(context: Context, termId: Long = 0L): List<SessionTimes.Slot> {
        val own = prefs(context).getString(TermSchedule.slotsKey(termId), null)
        val global = prefs(context).getString(KEY_SESSION_TIMES, null)
        val raw = TermSchedule.resolve(own, global)
        val slots = SessionTimes.parse(raw)
        if (slots != null) return slots
        // 存坏了就退回默认，绝不给用户一张空课表。
        return SessionTimes.fromStarts(CourseScheduleImporter.DEFAULT_SESSIONS)
    }

    /** 写某个学期的作息；termId=0 写全局模板。 */
    fun setSessionSlots(context: Context, slots: List<SessionTimes.Slot>, termId: Long = 0L) {
        prefs(context).edit().putString(TermSchedule.slotsKey(termId), SessionTimes.format(slots)).apply()
    }

    /** 每节时长相同（默认开）。关掉后逐节改下课时刻。按学期隔离，回落全局。 */
    fun sessionUniform(context: Context, termId: Long = 0L): Boolean {
        val own: Boolean? = if (termId > 0L && prefs(context).contains(TermSchedule.uniformKey(termId)))
            prefs(context).getBoolean(TermSchedule.uniformKey(termId), true) else null
        val global: Boolean? = if (prefs(context).contains(KEY_SESSION_UNIFORM))
            prefs(context).getBoolean(KEY_SESSION_UNIFORM, true) else null
        return TermSchedule.resolveUniform(own, global)
    }

    fun setSessionUniform(context: Context, on: Boolean, termId: Long = 0L) {
        prefs(context).edit().putBoolean(TermSchedule.uniformKey(termId), on).apply()
    }

    /** 删学期时顺手清掉它的作息，免得 id 复用后读到上学期的残留。 */
    fun clearTermSchedule(context: Context, termId: Long) {
        if (termId <= 0L) return
        prefs(context).edit()
            .remove(TermSchedule.slotsKey(termId))
            .remove(TermSchedule.uniformKey(termId))
            .apply()
    }

    // ── 上课提醒（v1.17） ───────────────────────────────────────────────

    /** 上课提醒总开关。默认 false —— 课表默认只看不响。 */
    fun courseRemindEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COURSE_REMIND, false)

    fun setCourseRemindEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_COURSE_REMIND, on).apply()
    }

    /** 提前几分钟。只接受 [CourseReminder.LEAD_OPTIONS] 里的值，坏数据回落默认 10。 */
    fun courseLeadMinutes(context: Context): Int {
        val raw = prefs(context).getInt(KEY_COURSE_LEAD, CourseReminder.DEFAULT_LEAD)
        return if (raw in CourseReminder.LEAD_OPTIONS) raw else CourseReminder.DEFAULT_LEAD
    }

    fun setCourseLeadMinutes(context: Context, minutes: Int) {
        val v = if (minutes in CourseReminder.LEAD_OPTIONS) minutes else CourseReminder.DEFAULT_LEAD
        prefs(context).edit().putInt(KEY_COURSE_LEAD, v).apply()
    }

    /** v1.35：上次成功导出的时刻；0 = 从没备份过。 */
    fun lastBackupAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_BACKUP, 0L)

    fun setLastBackupAt(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_LAST_BACKUP, at).apply()
    }

    fun setSessionTimes(context: Context, times: List<Pair<Int, Int>>) {
        setSessionSlots(context, SessionTimes.fromStarts(times.take(16)))
    }

    /** 一天几节。与 [sessionTimes] 长度保持一致。 */
    fun sessionCount(context: Context): Int = sessionTimes(context).size
}
