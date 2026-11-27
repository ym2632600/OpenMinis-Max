package com.openminis.app.focus

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
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.atomic.AtomicInteger

/**
 * C13: focus mode. While active, notifications from apps outside the
 * user-chosen allowlist are held (cancelled from the status bar) and stored
 * locally; when focus ends - manually or at the scheduled end time - one
 * batched summary notification shows what was held, grouped by app.
 *
 * Everything is on-device: no content leaves the phone. Enabling the mode
 * is consent-gated through the B13 layer (SensitiveScope.FOCUS_MODE).
 */
object FocusModeManager {

    private const val TAG = "FocusMode"
    private const val PREFS_NAME = "focus_mode"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ALLOWED = "allowed_packages"
    private const val KEY_START_MIN = "schedule_start_min"
    private const val KEY_END_MIN = "schedule_end_min"
    private const val KEY_HELD = "held_notifications"
    private const val CHANNEL_ID = "focus_mode"
    private const val MAX_HELD = 100

    /** A notification held while focus mode was active. */
    data class HeldNotification(
        val packageName: String,
        val title: String?,
        val text: String?,
        val postedAt: Long,
    )

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var appContext: Context? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _allowedPackages = MutableStateFlow<Set<String>>(emptySet())
    val allowedPackages: StateFlow<Set<String>> = _allowedPackages.asStateFlow()

    /** Scheduled window in minutes-from-midnight; -1 means "not set". */
    private val _scheduleStartMin = MutableStateFlow(-1)
    val scheduleStartMin: StateFlow<Int> = _scheduleStartMin.asStateFlow()

    private val _scheduleEndMin = MutableStateFlow(-1)
    val scheduleEndMin: StateFlow<Int> = _scheduleEndMin.asStateFlow()

    private val _held = MutableStateFlow<List<HeldNotification>>(emptyList())
    val held: StateFlow<List<HeldNotification>> = _held.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null
    private val notificationId = AtomicInteger(5200)

    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        appContext = app
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val p = prefs ?: return
        _enabled.value = p.getBoolean(KEY_ENABLED, false)
        _allowedPackages.value = p.getStringSet(KEY_ALLOWED, emptySet()) ?: emptySet()
        _scheduleStartMin.value = p.getInt(KEY_START_MIN, -1)
        _scheduleEndMin.value = p.getInt(KEY_END_MIN, -1)
        _held.value = parseHeld(p.getString(KEY_HELD, null))
        startWatchdog()
    }

    fun setEnabled(on: Boolean, automatic: Boolean = false) {
        if (_enabled.value == on) return
        _enabled.value = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
        AppLogger.info(TAG, (if (on) "enabled" else "disabled") + if (automatic) " (schedule)" else "")
        ActivityLog.record(
            category = "focus_mode",
            title = if (on) "Focus mode started" else "Focus mode ended",
        )
        if (!on) postSummary()
    }

    fun setAllowedPackages(packages: Set<String>) {
        _allowedPackages.value = packages
        prefs?.edit()?.putStringSet(KEY_ALLOWED, packages)?.apply()
    }

    fun setSchedule(startMin: Int, endMin: Int) {
        _scheduleStartMin.value = startMin
        _scheduleEndMin.value = endMin
        prefs?.edit()
            ?.putInt(KEY_START_MIN, startMin)
            ?.putInt(KEY_END_MIN, endMin)
            ?.apply()
    }

    fun clearSchedule() = setSchedule(-1, -1)

    fun isAllowed(packageName: String): Boolean =
        packageName == appContext?.packageName || _allowedPackages.value.contains(packageName)

    /**
     * Called from the notification listener for every posted notification.
     * Returns true when the notification was held (the caller cancels it
     * from the status bar via [cancel]).
     */
    fun holdIfBlocked(packageName: String, title: String?, text: String?, cancel: () -> Unit): Boolean {
        if (!_enabled.value) return false
        if (isAllowed(packageName)) return false
        runCatching { cancel() }
        val entry = HeldNotification(packageName, title, text, System.currentTimeMillis())
        val updated = (_held.value + entry).takeLast(MAX_HELD)
        _held.value = updated
        persistHeld(updated)
        return true
    }

    /** Post the batched "while you focused" summary and clear the held list. */
    fun postSummary() {
        val context = appContext ?: return
        val items = _held.value
        if (items.isEmpty()) return
        _held.value = emptyList()
        persistHeld(emptyList())

        val byApp = items.groupBy { appLabel(context, it.packageName) }
        val lines = byApp.entries.sortedByDescending { it.value.size }.map { (label, list) ->
            val sample = list.take(2).mapNotNull { it.title }.filter { it.isNotBlank() }
                .joinToString(" · ")
            val count = list.size
            if (sample.isEmpty()) "$label ($count)" else "$label ($count): $sample"
        }
        val body = context.getString(R.string.focus_summary_body, items.size) +
            "\n" + lines.take(8).joinToString("\n")

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
            .setContentTitle(context.getString(R.string.focus_summary_title))
            .setContentText(context.getString(R.string.focus_summary_body, items.size))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()
        runCatching {
            ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?.notify(notificationId.getAndIncrement(), notification)
        }
        AppLogger.info(TAG, "summary posted for ${items.size} held notification(s)")
    }

    private fun startWatchdog() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                try {
                    checkSchedule()
                } catch (t: Throwable) {
                    AppLogger.warning(TAG, "schedule check failed: ${t.message}")
                }
            }
        }
    }

    private fun checkSchedule() {
        val start = _scheduleStartMin.value
        val end = _scheduleEndMin.value
        if (start < 0 || end < 0) return
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val inWindow = if (start <= end) {
            nowMin in start until end
        } else {
            // Overnight window, e.g. 22:00-07:00.
            nowMin >= start || nowMin < end
        }
        if (inWindow && !_enabled.value) setEnabled(true, automatic = true)
        if (!inWindow && _enabled.value) setEnabled(false, automatic = true)
    }

    private fun persistHeld(items: List<HeldNotification>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().apply {
                put("pkg", it.packageName)
                put("title", it.title ?: "")
                put("text", it.text ?: "")
                put("at", it.postedAt)
            })
        }
        prefs?.edit()?.putString(KEY_HELD, arr.toString())?.apply()
    }

    private fun parseHeld(json: String?): List<HeldNotification> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HeldNotification(
                    packageName = o.optString("pkg"),
                    title = o.optString("title").ifBlank { null },
                    text = o.optString("text").ifBlank { null },
                    postedAt = o.optLong("at"),
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun appLabel(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (t: Throwable) {
        packageName
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.focus_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.focus_channel_description) },
        )
    }
}
