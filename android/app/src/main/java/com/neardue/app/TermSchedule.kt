package com.neardue.app

/**
 * v1.29：一个学期一套作息（WakeUp「一个课表一套上课时间」的轻量版）。
 *
 * 存 SharedPreferences 的 per-term 键；未设过的学期回落全局模板 [Prefs.KEY_SESSION_TIMES]，
 * 让老用户升级后作息不变、新建学期默认继承当前作息。作息仍不进备份（既有取舍）。
 */
object TermSchedule {

    fun slotsKey(termId: Long): String = if (termId > 0L) "session_times_$termId" else "session_times"

    fun uniformKey(termId: Long): String = if (termId > 0L) "session_uniform_$termId" else "session_uniform"

    /** 学期自己有值用自己的，否则回落全局；都没有返回 null 让调用方退到默认作息。 */
    fun resolve(own: String?, global: String?): String? = own ?: global

    fun resolveUniform(own: Boolean?, global: Boolean?): Boolean = own ?: global ?: true
}
