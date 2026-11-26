package com.openminis.app.data.db

/**
 * [A10] Full-text search index over message content.
 *
 * The session-list search used to scan `messages.parts_json` with LIKE on
 * every keystroke — fine for hundreds of messages, visibly slow beyond a few
 * thousand. This table is an FTS4 index over the user-visible text of each
 * message (extracted in Kotlin at write time, see
 * [com.openminis.app.data.repository.ChatRepository.extractSearchableText]),
 * so a query is an index lookup instead of a full table scan, and it never
 * matches tool-call ids or base64 blobs that live in the raw JSON.
 *
 * Deliberately NOT a Room entity: the table is created in
 * [AppDatabase.MIGRATION_14_15] and touched with raw SQL only. Room's
 * open-time validation only checks declared entities, so an older build
 * opening a v15 database ignores these tables — which is what keeps the
 * 15 → 14 downgrade a verified no-op (see DatabaseVersionGuard).
 *
 * `docid` of an index row is the `rowid` of its `messages` row, so a search
 * JOINs back to the message (for the snippet) and its session. Messages of a
 * deleted session leave orphan index rows (the CASCADE delete does not reach
 * this table); the JOIN drops them from results and the backfill's final
 * sweep deletes them.
 */
object MessageFts {
    const val CREATE_TABLE_SQL =
        "CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts4(content, session_id, created_at, tokenize=unicode61)"

    /** One-row key/value store for the backfill watermark and completion flag. */
    const val CREATE_STATE_TABLE_SQL =
        "CREATE TABLE IF NOT EXISTS messages_fts_state(k TEXT PRIMARY KEY, v TEXT)"

    /** Newest-hit-first message rows whose visible text matches the FTS query. */
    const val SEARCH_SQL =
        "SELECT m.session_id AS session_id, m.parts_json AS parts_json, m.created_at AS created_at " +
            "FROM messages_fts JOIN messages m ON m.rowid = messages_fts.docid " +
            "WHERE messages_fts MATCH ? ORDER BY m.created_at DESC LIMIT 300"

    /**
     * The MATCH expression for [terms]: prefix matches, OR semantics — the
     * same "any keyword may hit" rule as [com.openminis.app.data.repository.SessionSearch].
     * Null when nothing searchable remains after sanitizing (the caller then
     * falls back to the LIKE path).
     */
    fun buildMatchQuery(terms: List<String>): String? {
        val parts = terms.map { sanitize(it) }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.joinToString(" OR ") { "$it*" }
    }

    /** FTS query-syntax characters dropped; Unicode letters/digits (Arabic included) kept. */
    private fun sanitize(term: String): String = term.filter { it.isLetterOrDigit() }
}
