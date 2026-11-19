package com.neardue.app

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 把课表（[Course] 列表）排成某一张「周 × 节次」的格子。
 *
 * v1.9 起课表**独立存**（`courses` 表），不再是 items 的投影 —— 早期需求
 * 「课表不用变成事项提醒」。所以这里读的是 Course，不是 Item：
 *
 * - 一门课只存一条（周几 + 起止节 + 周次规格），换周显示时按「第几周」筛，
 *   不会像以前那样把一门课展开成几十条。
 * - 单双周 / 零散周（`1-16单`、`1,3,5`）在显示时判断，不落库成几十行。
 *
 * 纯 Kotlin + java.time，不依赖 Android，可在 JVM 单测里直接跑。
 */
object Timetable {

    /**
     * 一周的课表。
     *
     * @param weekIndex 第几周（1 起）。没设开学日时为 null，此时不按周次过滤。
     * @param cells key = `session * 10 + dayOfWeek`，value = 这一格里的课。
     */
    data class Grid(
        val weekStart: LocalDate,
        val weekIndex: Int?,
        val sessions: List<Pair<Int, Int>>,
        val cells: Map<Int, List<Course>>,
        /**
         * 不在这一周的课。只用来「灰着显示」——看得见整学期的排布，
         * 但不计入「这周几门课」，也不占正常课的位置（渲染在下面一层）。
         */
        val ghosts: Map<Int, List<Course>> = emptyMap(),
        /**
         * 这一周被「单周改课」动过的课：key = 课程 id。
         * 停课的课不会出现在 [cells] 里，只在这里留个记录（界面上可以说「这周停」）。
         */
        val overrides: Map<Long, CourseOverride> = emptyMap(),
        /**
         * 这一周停课的课，按它本来的位置摆（画成虚线框 + 「停」）。
         * 不画会把用户搞糊涂：上一周有的课这周凭空没了，不解释就是丢了。
         */
        val skipped: Map<Int, List<Course>> = emptyMap(),
        /**
         * 同一格里有两门以上的课（撞课）。只做提示，不改数据：
         * 「将至」不替用户决定该上哪门，只把冲突摆出来让人自己看。
         */
        val conflicts: List<Conflict> = emptyList(),
    ) {
        data class Conflict(
            val dayOfWeek: Int,
            /** 撞了的节次，可能跨好几节；同一对课只算一处冲突。 */
            val sessions: List<Int>,
            val courses: List<Course>,
        ) {
            val session: Int get() = sessions.first()
            fun sessionText(): String = if (sessions.size == 1)
                "第 ${sessions.first()} 节"
            else
                "第 ${sessions.first()}-${sessions.last()} 节"
        }

        fun at(session: Int, dayOfWeek: Int): List<Course> =
            cells[key(session, dayOfWeek)].orEmpty()

        /** 这一格不在本周的课。 */
        fun ghostsAt(session: Int, dayOfWeek: Int): List<Course> =
            ghosts[key(session, dayOfWeek)].orEmpty()

        /**
         * 某一天的全部课（跨节课只算一次），按起始节排序。
         * 画跨节色块时用：要知道这门课从第几节占几节，不能从单格反推。
         */
        fun forDay(dayOfWeek: Int): List<Course> {
            val seen = LinkedHashMap<Long, Course>()
            for (s in 1..sessions.size) {
                cells[key(s, dayOfWeek)]?.forEach { seen[it.id] = it }
            }
            return seen.values.sortedBy { it.sessionStart }
        }

        /** 某一天里不在本周的课（跨节课只算一次），画在正常课下面一层。 */
        fun ghostsForDay(dayOfWeek: Int): List<Course> {
            val seen = LinkedHashSet<Course>()
            for (s in 1..sessions.size) {
                ghosts[key(s, dayOfWeek)]?.let { seen.addAll(it) }
            }
            return seen.sortedBy { it.sessionStart }
        }

        fun isEmpty(): Boolean = cells.values.all { it.isEmpty() }

        /**
         * 这一周实际的课程条数（一门课算一条，不按节次拆）。
         *
         * 用整条 [Course] 去重而不是按 id：还没入库的课程 id 全是 0，
         * 按 id 去重会把两门不同的课算成一门。
         */
        fun courseCount(): Int = cells.values.flatten().distinct().size

        /** 灰显层里有几门（不在本周的课）。 */
        fun otherCount(): Int = ghosts.values.flatten().distinct().size

        /** 这一周有几门课被调课 / 停课（改过的课另算，停课的不在 [cells] 里）。 */
        fun changedCount(): Int = overrides.count { !it.value.skip }

        /** 这一周停了几门。 */
        fun skippedCount(): Int = overrides.count { it.value.skip }

        /** 这门课这周是不是被动过（布一个小标记用）。 */
        fun isChanged(courseId: Long): Boolean = overrides[courseId]?.changesAnything == true

        /** 撞课的格子数。 */
        fun conflictCount(): Int = conflicts.size

        /** 这门课是否在某一格撞了别的课。 */
        fun isConflict(courseId: Long): Boolean =
            conflicts.any { c -> c.courses.any { it.id == courseId } }

        /** 某一天停课的课（跨节只算一次）。 */
        fun skippedForDay(dayOfWeek: Int): List<Course> {
            val seen = LinkedHashSet<Course>()
            for (s in 1..sessions.size) {
                skipped[key(s, dayOfWeek)]?.let { seen.addAll(it) }
            }
            return seen.sortedBy { it.sessionStart }
        }
    }

    fun key(session: Int, dayOfWeek: Int): Int = session * 10 + dayOfWeek

    /** 某一周周一。周几都按 ISO：周一 = 1。 */
    fun weekStartOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())

    fun dayOf(date: LocalDate): DayOfWeek = date.dayOfWeek

    /**
     * 把课程排到某一周。
     *
     * @param courses 全部课程。
     * @param weekStart 这一周的周一。
     * @param weekIndex 这一周是学期第几周（1 起）；null 表示没设开学日，不按周次过滤。
     * @param sessions 节次时刻表（只用于时间轴显示）。
     * @param showOtherWeeks 把不在这一周的课也收进 [Grid.ghosts]，界面上灰着显示。
     *   参考 WakeUp 课表的做法：一屏能看出整学期的排布，本周的课是实的、其余是虚的，
     *   不用来回翻十几周才能知道「这门课几周后还在不在」。
     * @param overrides 这一周的单周例外：key = 课程 id。停课的不落格，改过的按改后的位置落格。
     */
    fun grid(
        courses: List<Course>,
        weekStart: LocalDate,
        weekIndex: Int?,
        sessions: List<Pair<Int, Int>> = CourseScheduleImporter.DEFAULT_SESSIONS,
        showOtherWeeks: Boolean = false,
        overrides: Map<Long, CourseOverride> = emptyMap(),
    ): Grid {
        val buckets = LinkedHashMap<Int, MutableList<Course>>()
        val ghostBuckets = LinkedHashMap<Int, MutableList<Course>>()
        val skipBuckets = LinkedHashMap<Int, MutableList<Course>>()
        val hit = LinkedHashMap<Long, CourseOverride>()
        for (c in courses) {
            val day = c.dayOfWeek.coerceIn(1, 7)
            // 没设开学日时 weekIndex 为 null：所有课都算「本周」（用户至少能看到课表长什么样）。
            val inWeek = weekIndex == null || CourseWeeks.includes(c.weeks, weekIndex)
            if (!inWeek && !showOtherWeeks) continue
            // ★按周过滤在这里做，不靠调用方：传进来的 map 就算混了别的周，也不会串到本周。
            val o = if (inWeek) overrides[c.id]?.takeIf { it.week == weekIndex } else null
            if (o != null) hit[c.id] = o
            // 这周停课：不落正常格，也不进灰显层（灰显层的意思是「每周都有，只是不在本周」），
            // 单放到 skipBuckets，按原位置画虚线框，用户才看得出「这课被停了」而不是「丢了」。
            if (o?.skip == true) {
                val from = c.sessionStart.coerceAtLeast(1)
                val to = c.sessionEnd.coerceAtLeast(from)
                for (s in from..to) {
                    skipBuckets.getOrPut(key(s, day)) { mutableListOf() } += c
                }
                continue
            }
            val target = if (inWeek) buckets else ghostBuckets
            val placed = o?.applyTo(c) ?: c
            val from = placed.sessionStart.coerceAtLeast(1)
            val to = placed.sessionEnd.coerceAtLeast(from)
            val onDay = placed.dayOfWeek.coerceIn(1, 7)
            for (s in from..to) {
                target.getOrPut(key(s, onDay)) { mutableListOf() } += placed
            }
        }
        fun sort(v: MutableList<Course>) = v.sortedWith(compareBy({ it.sessionStart }, { it.title }))
        val cells = buckets.mapValues { (_, v) -> sort(v) }
        val ghosts = ghostBuckets.mapValues { (_, v) -> sort(v) }
        val skipped = skipBuckets.mapValues { (_, v) -> sort(v) }
        // 撞课：同一格里超过一门。按「星期 × 这一对课」合并——
        // 两门课同占第 1-2 节，用户会说「撞了一处」，不是「撞了两处」。
        val conflictList = buckets.entries
            .filter { it.value.size >= 2 }
            .groupBy { (k, v) -> Pair(k % 10, v.sortedBy { CourseImporter.courseKey(it) }) }
            .map { (key, entries) ->
                Grid.Conflict(
                    dayOfWeek = key.first,
                    sessions = entries.map { it.key / 10 }.sorted(),
                    courses = key.second,
                )
            }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.sessions.first() }))
        return Grid(
            weekStart = weekStart,
            weekIndex = weekIndex,
            sessions = sessions,
            cells = cells,
            ghosts = ghosts,
            overrides = hit,
            skipped = skipped,
            conflicts = conflictList,
        )
    }

    /** 一节课的时刻文案 `08:00`。 */
    fun label(hourMinute: Pair<Int, Int>): String =
        "%02d:%02d".format(hourMinute.first, hourMinute.second)

    fun sessionLabel(index: Int, sessions: List<Pair<Int, Int>>): String =
        sessions.getOrNull(index - 1)?.let { label(it) }.orEmpty()

    /** 这一周里属于某天的日期。 */
    fun dateOf(grid: Grid, dayOfWeek: Int): LocalDate =
        grid.weekStart.plusDays((dayOfWeek - 1).toLong())

    /** 当前是第几周。没设开学日返回 null（不显示「第 0 周」）。 */
    fun weekNumber(termStartMonday: LocalDate?, today: LocalDate): Int? {
        if (termStartMonday == null) return null
        val start = weekStartOf(termStartMonday)
        val weeks = java.time.temporal.ChronoUnit.DAYS.between(start, today).toDouble() / 7.0
        val n = kotlin.math.floor(weeks).toInt() + 1
        return if (n < 1) null else n
    }

    fun minutesOf(time: java.time.LocalTime): Int = time.hour * 60 + time.minute

    /** 某个 epoch 毫秒是第几节（给「今天几点有课」这类提示用）。 */
    fun sessionAt(minutes: Int, sessions: List<Pair<Int, Int>>): Int? {
        var best: Int? = null
        var bestDiff = Long.MAX_VALUE
        sessions.forEachIndexed { i, (h, m) ->
            val diff = kotlin.math.abs((h * 60 + m) - minutes).toLong()
            if (diff <= 45 && diff < bestDiff) { best = i + 1; bestDiff = diff }
        }
        return best
    }

    /** 今天还有几节课（用于首页一句话提示）。 */
    fun todayCourses(courses: List<Course>, today: LocalDate, termStart: LocalDate?): List<Course> {
        val week = weekNumber(termStart, today)
        return courses.filter { c ->
            c.dayOfWeek == today.dayOfWeek.value &&
                (week == null || CourseWeeks.includes(c.weeks, week))
        }.sortedBy { it.sessionStart }
    }

    @Suppress("unused")
    private fun dateOf(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
