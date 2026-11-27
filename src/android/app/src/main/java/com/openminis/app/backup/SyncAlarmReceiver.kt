package com.openminis.app.backup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openminis.app.logging.AppLogger

/**
 * B12: fires the nightly sync and re-arms it. AlarmManager drops pending
 * alarms on reboot and on package replace, so both reschedule here (same
 * pattern as .offload.AlarmReceiver).
 */
class SyncAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Re-arm only; never run a catch-up sync on boot.
                if (SyncScheduler.isEnabled(app)) SyncScheduler.scheduleNext(app)
            }
            SyncScheduler.ACTION_SYNC -> {
                // Re-arm first so a crash below cannot silently end the chain.
                SyncScheduler.scheduleNext(app)
                val passphrase = SyncScheduler.storedPassphrase(app)
                if (passphrase.isNullOrEmpty()) {
                    // Encryption is what makes the sync end-to-end; without a
                    // passphrase there is no sync. Loud in the log, not silent.
                    AppLogger.error(TAG, "[Sync] skipped: no stored passphrase")
                    return
                }
                val started = BackupRunController.start(
                    app,
                    BackupRunController.Request(
                        categories = SyncScheduler.SYNC_CATEGORIES,
                        maxFileBytes = null,
                        passphrase = passphrase,
                    ),
                )
                if (!started) {
                    // A manual backup is already running; it covers the same
                    // data, so skipping tonight is correct, not a failure.
                    AppLogger.info(TAG, "[Sync] skipped: a backup is already running")
                }
            }
        }
    }

    private companion object {
        const val TAG = "SyncAlarmReceiver"
    }
}
