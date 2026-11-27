package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * C10: one row per expense/income parsed from a bank, wallet or merchant
 * notification. Notification-based only - no account credentials, no
 * network access; the parser reads only what the notification shows.
 */
@Entity(tableName = "expenses", indices = [Index("timestamp"), Index("dedupeKey")])
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val sourcePackage: String,
    /** "debit" (money out) or "credit" (money in). */
    val direction: String,
    /** Amount in the currency's minor unit-free decimal form, e.g. 120.50. */
    val amount: Double,
    val currency: String,
    val merchant: String?,
    val rawTitle: String?,
    /** Hash of source+title+amount for duplicate suppression. */
    val dedupeKey: String,
)

@Dao
interface ExpenseDao {
    @Insert
    suspend fun insert(entry: ExpenseEntity): Long

    @Query("SELECT * FROM expenses ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun since(since: Long): List<ExpenseEntity>

    @Query("SELECT COUNT(*) FROM expenses WHERE dedupeKey = :key AND timestamp >= :since")
    suspend fun duplicatesSince(key: String, since: Long): Int

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM expenses")
    suspend fun clear()
}
