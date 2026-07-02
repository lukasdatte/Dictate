package net.devemperor.dictate.database.dao

/**
 * Escapes user-typed text for use inside a SQL `LIKE ... ESCAPE '\'`
 * pattern (F-054 bonus defect, docs/research/
 * "2026-07-02 - history-pagination-and-scale.md" §1.1).
 *
 * SQLite's `LIKE` treats `%` (any sequence) and `_` (any single char)
 * as wildcards. Without escaping, a user searching for the literal
 * text `100%` matches every row starting with `100`, and a lone `_`
 * matches everything. This helper backslash-escapes the two wildcard
 * characters plus the escape character itself; the companion query
 * MUST declare `ESCAPE '\'` (see [SessionDao.pagedHistory]).
 *
 * Boundary convention: escaping happens at the call site (the layer
 * that owns the raw user input), mirroring the project's Double-Enum
 * rule of keeping conversions at the application boundary — the DAO
 * receives the already-escaped pattern fragment.
 */
object LikeEscape {

    /**
     * Returns [term] with `\`, `%` and `_` prefixed by `\` so the
     * result matches the characters literally inside a
     * `LIKE '%' || :arg || '%' ESCAPE '\'` clause.
     *
     * `\` must be replaced first — otherwise the backslashes added
     * for `%`/`_` would themselves get double-escaped.
     */
    fun escape(term: String): String = term
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}
