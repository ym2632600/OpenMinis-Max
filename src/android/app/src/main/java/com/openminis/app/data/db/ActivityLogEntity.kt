package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * B14: one row per agent action (tool calls, messages sent, files changed,
 * consent decisions). [undoKind] + [undoPayload] describe how to reverse the
 * action when it is reversible; [undoneAt] marks an entry already undone.
 */
@Entity(tableName = "activity_log", indices = [Index("timestamp")])
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val category: String,
    val title: String,
    val details: String?,
    val undoKind: String?,
    val undoPayload: String?,
    val undoneAt: Long?,
)

@Dao
interface ActivityLogDao {
    @Insert
    suspend fun insert(entry: ActivityLogEntity): Long

    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivityLogEntity>>

    @Query("SELECT * FROM activity_log WHERE id = :id")
    suspend fun byId(id: Long): ActivityLogEntity?

    @Query("UPDATE activity_log SET undoneAt = :at WHERE id = :id")
    suspend fun markUndone(id: Long, at: Long)

    @Query("DELETE FROM activity_log WHERE timestamp < :before")
    suspend fun pruneOlderThan(before: Long)
}
