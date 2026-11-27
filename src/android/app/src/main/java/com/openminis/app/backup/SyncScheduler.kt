package com.openminis.app.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.EncryptedPrefsFactory
import java.util.Calendar

/**
 * B12: opt-in nightly encrypted sync. Schedules a daily ~03:00 alarm that
 * runs an encrypted backup of the settings-shaped categories (memory, skills,
 * providers, MCP servers, env vars, voice corrections) to every enabled
 * destination, using the existing [BackupRunController] + foreground service
 * pipeline. Chats and shared files stay out on purpose: sync is for
 * configuration, and a nightly full-chat upload would surprise a metered
 * connection.
 *
 * No exact-alarm permission: setAndAllowWhileIdle may slide a few minutes,
 * which a nightly sync does not care about. The passphrase lives in the
 * Keystore-backed encrypted store ([EncryptedPrefsFactory]), same as API
 * keys, so packages stay end-to-end encrypted for any destination.
 */
object SyncScheduler {

    private const val TAG = "SyncScheduler"
    private const val PREFS = "minis_sync_prefs"
    private const val SECRETS = "minis_sync_secrets"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PASSPHRASE = "passphrase"
    private const val REQUEST_CODE = 9101

    const val ACTION_SYNC = "com.openminis.app.action.NIGHTLY_SYNC"

    /** Settings-shaped categories synced nightly. Chats/files stay out. */
    val SYNC_CATEGORIES: Set<BackupCategory> = setOf(
        BackupCategory.MEMORY,
        BackupCategory.SKILLS,
        BackupCategory.PROVIDERS,
        BackupCategory.MCP_SERVERS,
        BackupCategory.ENVIRONMENT_VARIABLES,
        BackupCategory.VOICE_CORRECTIONS,
    )

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun hasPassphrase(context: Context): Boolean =
        !storedPassphrase(context).isNullOrEmpty()

    fun storedPassphrase(context: Context): String? =
        EncryptedPrefsFactory.safeCreate(context, SECRETS)
            .getString(KEY_PASSPHRASE, null)

    /** Enable and schedule. [passphrase] encrypts every sync package. */
    fun enable(context: Context, passphrase: String) {
        val app = context.applicationContext
        EncryptedPrefsFactory.safeCreate(app, SECRETS).edit()
            .putString(KEY_PASSPHRASE, passphrase).apply()
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, true).apply()
        scheduleNext(app)
        AppLogger.info(TAG, "[Sync] nightly sync enabled")
    }

    fun disable(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, false).apply()
        cancelAlarm(app)
        AppLogger.info(TAG, "[Sync] nightly sync disabled")
    }

    /** Next 03:00 local, tomorrow if that time already passed today. */
    fun scheduleNext(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 3)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent(app))
        }.onFailure { AppLogger.error(TAG, "[Sync] schedule failed: ${it.message}") }
    }

    private fun cancelAlarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, SyncAlarmReceiver::class.java).setAction(ACTION_SYNC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
