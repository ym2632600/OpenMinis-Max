package com.openminis.app.guard

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * C1: guard mode. When the user opts in (consent-gated, off by default),
 * every posted notification is scored locally by [ScamAnalyzer]; likely
 * scams surface a high-priority plain-language warning and are written to
 * the activity log (B14). All analysis is on-device; nothing is uploaded.
 */
object GuardModeManager {

    private const val TAG = "GuardMode"
    private const val PREFS_NAME = "guard_mode"
    private const val KEY_ENABLED = "enabled"
    private const val CHANNEL_ID = "guard_mode_warnings"

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var appContext: Context? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val notificationId = AtomicInteger(4100)

    fun init(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs?.getBoolean(KEY_ENABLED, false) ?: false
    }

    fun setEnabled(on: Boolean) {
        if (_enabled.value == on) return
        _enabled.value = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
        AppLogger.info(TAG, if (on) "enabled" else "disabled")
        ActivityLog.record(
            category = "guard_mode",
            title = if (on) "Guard mode enabled" else "Guard mode disabled",
        )
    }

    /** Called from the notification listener for every posted notification. */
    fun inspect(sourcePackage: String, title: String?, text: String?) {
        val context = appContext ?: return
        if (!_enabled.value) return
        if (sourcePackage == context.packageName) return
        val verdict = try {
            ScamAnalyzer.analyze(title, text)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "analyze failed: ${t.message}")
            return
        }
        if (!verdict.isWarning) return
        warn(context, sourcePackage, title, verdict)
    }

    private fun warn(context: Context, sourcePackage: String, title: String?, verdict: ScamAnalyzer.Verdict) {
        ensureChannel(context)
        val reasonLines = verdict.reasons.joinToString("\n") { "- " + context.getString(reasonText(it)) }
        val sourceLabel = appLabel(context, sourcePackage)
        val body = context.getString(R.string.guard_warning_body, sourceLabel) + "\n" + reasonLines +
            "\n" + context.getString(R.string.guard_warning_advice)

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
            .setContentTitle(context.getString(R.string.guard_warning_title))
            .setContentText(reasonLines.lines().firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()

        runCatching {
            ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?.notify(notificationId.getAndIncrement(), notification)
        }
        AppLogger.info(TAG, "warning posted (score ${verdict.score}) from $sourcePackage")
        ActivityLog.record(
            category = "guard_mode",
            title = context.getString(R.string.guard_warning_title),
            details = "from=$sourceLabel; score=${verdict.score}; reasons=" +
                verdict.reasons.joinToString(",") { it.name } +
                "; subject=" + (title ?: "").take(120),
        )
    }

    private fun reasonText(reason: ScamAnalyzer.Reason): Int = when (reason) {
        ScamAnalyzer.Reason.LOOKALIKE_DOMAIN -> R.string.guard_reason_lookalike
        ScamAnalyzer.Reason.IP_HOST -> R.string.guard_reason_ip_host
        ScamAnalyzer.Reason.SUSPICIOUS_URL -> R.string.guard_reason_suspicious_url
        ScamAnalyzer.Reason.URL_SHORTENER -> R.string.guard_reason_shortener
        ScamAnalyzer.Reason.CREDENTIAL_REQUEST -> R.string.guard_reason_credential
        ScamAnalyzer.Reason.URGENCY_PRESSURE -> R.string.guard_reason_urgency
        ScamAnalyzer.Reason.PRIZE_OR_MONEY -> R.string.guard_reason_prize
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
                context.getString(R.string.guard_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.guard_channel_description) },
        )
    }
}
