package com.openminis.app.usage

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatTokenUsage
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A11: daily token cap with a pause-and-prompt gate.
 *
 * Keeps a cached today-total (summed from per-message token_usage rows) and a
 * user-settable daily cap (0 = off). The chat send funnel asks
 * [shouldPauseForCap] before starting a turn; when the cap is reached the UI
 * shows a prompt and the user can send anyway ([allowOnce]) or stay paused.
 * The cache refreshes on app start, after each send, and whenever the usage
 * surfaces open — a soft guard, exact to the last completed turn.
 */
object UsageCapManager {

    private const val TAG = "UsageCapManager"
    private const val PREFS_NAME = "usage_cap"
    private const val KEY_DAILY_CAP = "daily_token_cap"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var db: AppDatabase? = null

    /** Called once from MinisApp.onCreate. */
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        db = AppDatabase.getInstance(context)
        _dailyCap.value = prefs?.getLong(KEY_DAILY_CAP, 0L) ?: 0L
        refreshToday()
    }

    private val _dailyCap = MutableStateFlow(0L)
    val dailyCap: StateFlow<Long> = _dailyCap.asStateFlow()

    /** Daily cap in tokens; 0 disables the gate. */
    fun setDailyCap(tokens: Long) {
        val v = tokens.coerceAtLeast(0L)
        _dailyCap.value = v
        prefs?.edit()?.putLong(KEY_DAILY_CAP, v)?.apply()
        AppLogger.info(TAG, "daily cap set to $v")
    }

    private val _todayTotal = MutableStateFlow(0L)
    val todayTotal: StateFlow<Long> = _todayTotal.asStateFlow()

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Recompute today's total from the messages table (async, cached). */
    fun refreshToday() {
        val dao = db?.chatDao() ?: return
        refreshScope.launch {
            try {
                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                var total = 0L
                for (row in dao.usageRowsSince(cal.timeInMillis)) {
                    val u = ChatTokenUsage.parse(row.tokenUsage) ?: continue
                    total += (u.inputTokens + u.outputTokens + u.cacheCreationTokens + u.cacheReadTokens).toLong()
                }
                _todayTotal.value = total
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "refresh failed: ${t.message}")
            }
        }
    }

    @Volatile
    private var bypassOnce = false

    /** UI callback from the cap dialog: allow exactly one more prompt over the cap. */
    fun allowOnce() {
        bypassOnce = true
    }

    /** True when a new prompt should pause behind the cap prompt. */
    fun shouldPauseForCap(): Boolean {
        val cap = _dailyCap.value
        if (cap <= 0L) return false
        if (bypassOnce) {
            bypassOnce = false
            return false
        }
        return _todayTotal.value >= cap
    }
}

/** Compact 1.2K / 3.4M formatting for token counters. */
fun formatTokenCount(n: Long): String = when {
    n >= 1_000_000L -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000L -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}
