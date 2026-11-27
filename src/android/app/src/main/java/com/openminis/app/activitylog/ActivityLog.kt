package com.openminis.app.activitylog

import android.content.Context
import com.openminis.app.consent.ConsentManager
import com.openminis.app.consent.SensitiveScope
import com.openminis.app.data.db.ActivityLogEntity
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * B14: write/undo facade over the activity_log table. Recording never blocks
 * the caller (fire-and-forget on IO) and never throws into agent code paths.
 */
object ActivityLog {

    const val CATEGORY_TOOL_CALL = "tool_call"
    const val CATEGORY_MESSAGE_SENT = "message_sent"
    const val CATEGORY_FILE_CHANGED = "file_changed"
    const val CATEGORY_CONSENT = "consent"
    const val CATEGORY_CONFIG = "config"

    /** Undo: revoke an always-allow consent grant. Payload: scope name. */
    const val UNDO_REVOKE_CONSENT_GRANT = "revoke_consent_grant"

    private const val TAG = "ActivityLog"

    @Volatile
    private var db: AppDatabase? = null

    /** Called once from MinisApp.onCreate, after the database exists. */
    fun init(context: Context) {
        if (db == null) db = AppDatabase.getInstance(context)
    }

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun record(
        category: String,
        title: String,
        details: String? = null,
        undoKind: String? = null,
        undoPayload: String? = null,
    ) {
        val dao = db?.activityLogDao() ?: return
        writeScope.launch {
            try {
                dao.insert(
                    ActivityLogEntity(
                        timestamp = System.currentTimeMillis(),
                        category = category,
                        title = title,
                        details = details,
                        undoKind = undoKind,
                        undoPayload = undoPayload,
                        undoneAt = null,
                    )
                )
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "record failed: ${t.message}")
            }
        }
    }

    fun observeRecent(limit: Int = 200): Flow<List<ActivityLogEntity>> {
        val dao = db?.activityLogDao() ?: return flowOf(emptyList())
        return dao.observeRecent(limit)
    }

    /**
     * Reverse a reversible entry. Returns false when the entry is unknown,
     * not reversible, already undone, or the undo kind is not handled.
     */
    suspend fun undo(entryId: Long): Boolean {
        val dao = db?.activityLogDao() ?: return false
        val entry = dao.byId(entryId) ?: return false
        if (entry.undoKind == null || entry.undoneAt != null) return false
        val ok = when (entry.undoKind) {
            UNDO_REVOKE_CONSENT_GRANT -> {
                val consentScope = SensitiveScope.fromName(entry.undoPayload) ?: return false
                ConsentManager.revoke(consentScope)
                true
            }
            else -> false
        }
        if (ok) dao.markUndone(entryId, System.currentTimeMillis())
        return ok
    }
}
