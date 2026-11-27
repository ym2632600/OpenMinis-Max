package com.openminis.app.finance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.activitylog.ActivityLog
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ExpenseEntity
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * C10: mini finance agent. Parses bank/wallet/merchant notifications into a
 * local expense + income ledger (no credentials, notification-based only)
 * and posts a weekly summary notification. Tracking is opt-in and gated by
 * the B13 consent layer (SensitiveScope.FINANCE_TRACK).
 */
object FinanceRepository {

    private const val TAG = "Finance"
    private const val PREFS_NAME = "finance_tracker"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_WEEKLY_AT = "last_weekly_at"
    private const val CHANNEL_ID = "finance_summaries"
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val DEDUPE_WINDOW_MS = 60L * 60 * 1000

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var db: AppDatabase? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var weeklyJob: Job? = null
    private val notificationId = AtomicInteger(6300)

    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        db = runCatching { AppDatabase.getInstance(app) }.getOrNull()
        _enabled.value = prefs?.getBoolean(KEY_ENABLED, false) ?: false
        startWeeklyLoop()
    }

    fun setEnabled(on: Boolean) {
        if (_enabled.value == on) return
        _enabled.value = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
        AppLogger.info(TAG, if (on) "enabled" else "disabled")
        ActivityLog.record(
            category = "finance",
            title = if (on) "Finance tracking enabled" else "Finance tracking disabled",
        )
    }

    fun recent(limit: Int = 200): Flow<List<ExpenseEntity>>? = db?.expenseDao()?.observeRecent(limit)

    suspend fun delete(id: Long) = db?.expenseDao()?.delete(id)

    /** Called from the notification listener for every posted notification. */
    fun recordFromNotification(sourcePackage: String, title: String?, text: String?) {
        if (!_enabled.value) return
        val context = appContext ?: return
        if (sourcePackage == context.packageName) return
        val parsed = try {
            ExpenseParser.parse(title, text)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "parse failed: ${t.message}")
            return
        } ?: return
        val dao = db?.expenseDao() ?: return
        val key = "$sourcePackage|${title ?: ""}|${parsed.amount}|${parsed.currency}".hashCode().toString()
        scope.launch {
            try {
                val dupes = dao.duplicatesSince(key, System.currentTimeMillis() - DEDUPE_WINDOW_MS)
                if (dupes > 0) return@launch
                dao.insert(
                    ExpenseEntity(
                        timestamp = System.currentTimeMillis(),
                        sourcePackage = sourcePackage,
                        direction = if (parsed.direction == ExpenseParser.Direction.DEBIT) "debit" else "credit",
                        amount = parsed.amount,
                        currency = parsed.currency,
                        merchant = parsed.merchant,
                        rawTitle = title?.take(200),
                        dedupeKey = key,
                    ),
                )
                AppLogger.info(TAG, "recorded ${parsed.direction} ${parsed.amount} ${parsed.currency}")
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "insert failed: ${t.message}")
            }
        }
    }

    private fun startWeeklyLoop() {
        if (weeklyJob?.isActive == true) return
        weeklyJob = scope.launch {
            while (isActive) {
                delay(30 * 60 * 1000L)
                try {
                    maybePostWeeklySummary()
                } catch (t: Throwable) {
                    AppLogger.warning(TAG, "weekly summary failed: ${t.message}")
                }
            }
        }
    }

    private suspend fun maybePostWeeklySummary() {
        if (!_enabled.value) return
        val context = appContext ?: return
        val now = System.currentTimeMillis()
        val last = prefs?.getLong(KEY_LAST_WEEKLY_AT, 0L) ?: 0L
        if (now - last < WEEK_MS) return
        val dao = db?.expenseDao() ?: return
        val items = dao.since(now - WEEK_MS)
        prefs?.edit()?.putLong(KEY_LAST_WEEKLY_AT, now)?.apply()
        if (items.isEmpty()) return

        val spent = items.filter { it.direction == "debit" }
        val received = items.filter { it.direction == "credit" }
        fun totals(list: List<ExpenseEntity>) = list.groupBy { it.currency }
            .map { (cur, rows) -> formatAmount(rows.sumOf { it.amount }, cur) }
            .joinToString(" + ")
        val topMerchants = spent.groupBy { it.merchant ?: "?" }
            .entries.sortedByDescending { it.value.sumOf { r -> r.amount } }
            .take(3)
            .joinToString("، ") { (m, rows) -> "$m (${formatAmount(rows.sumOf { it.amount }, rows.first().currency)})" }

        val body = buildString {
            if (spent.isNotEmpty()) append(context.getString(R.string.finance_weekly_spent, totals(spent)))
            if (received.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(context.getString(R.string.finance_weekly_received, totals(received)))
            }
            if (topMerchants.isNotEmpty()) {
                append('\n').append(context.getString(R.string.finance_weekly_top, topMerchants))
            }
        }

        ensureChannel(context)
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                context, notificationId.get(), it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.finance_weekly_title))
            .setContentText(body.lines().firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()
        runCatching {
            ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?.notify(notificationId.getAndIncrement(), notification)
        }
    }

    fun formatAmount(amount: Double, currency: String): String =
        String.format(Locale.US, "%,.2f %s", amount, currency)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.finance_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.finance_channel_description) },
        )
    }
}
