package com.neardue.app

/**
 * v1.26 — naming policy for the "记一件事" editor.
 *
 * External text (clipboard / share / selection) is pasted verbatim: the user sees exactly what
 * will be saved. Auto-cleaning the name is an explicit action ("提取名字"), never a silent side
 * effect of saving. Hand-typed one-liners keep the old auto-clean behaviour.
 */
object TitleExtract {
    /** Suggested button label target, or null when there is nothing worth cleaning. */
    fun suggestion(parsedTitle: String, raw: String): String? {
        val candidate = parsedTitle.trim()
        if (candidate.isEmpty() || candidate == raw.trim()) return null
        return candidate
    }

    /** Final stored title. External text defaults to raw unless the user explicitly extracted. */
    fun finalTitle(
        parsedTitle: String,
        raw: String,
        matched: Boolean,
        fromExternal: Boolean,
        manual: Boolean,
    ): String {
        val trimmed = raw.trim()
        if (!fromExternal && matched && !manual) return parsedTitle.trim().ifBlank { trimmed }
        return trimmed
    }
}
