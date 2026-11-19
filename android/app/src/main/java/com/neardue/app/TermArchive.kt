package com.neardue.app

/**
 * 学期归档：上完的学期收进「归档」区，不再挤在课表顶部的小标签里。
 *
 * 归档不是删除：课、单周例外、作息都还在，随时可以取消归档翻回去看。
 * 只解决一件事——标签越积越多时，常用的当前学期被淹没。
 *
 * 口径钉死在这里（纯 Kotlin，JVM 单测直接跑）：
 * - 至少要留一个「活动中」的学期：不能把最后一个活动学期归档。
 * - 当前学期不能被归档——要先切到别的学期。
 * - 老数据没有 archived 字段，回落 false，行为完全不变。
 */
object TermArchive {

    /** 活动中的学期（未归档），按传入顺序。 */
    fun active(terms: List<Term>): List<Term> = terms.filter { !it.archived }

    /** 已归档的学期，按传入顺序。 */
    fun archived(terms: List<Term>): List<Term> = terms.filter { it.archived }

    /** 能不能归档这个学期：不是最后一个活动学期、且它自己没归档。 */
    fun canArchive(term: Term, terms: List<Term>): Boolean =
        !term.archived && active(terms).size > 1 && term.id in active(terms).map { it.id }

    /**
     * 归档后新的「当前学期」id：如果当前被归档（或已失效），退到第一个活动学期。
     * 一个活动学期都没有时返回 null（调用方应保证至少留一个，这里是兜底）。
     */
    fun effectiveCurrent(terms: List<Term>, currentId: Long): Long? {
        val actives = active(terms)
        if (actives.isEmpty()) return null
        if (actives.any { it.id == currentId }) return currentId
        return actives.first().id
    }

    /** 归档区那行小字。 */
    fun archiveHint(count: Int): String = "已归档 $count 个学期（课都在，随时可取出）"
}
