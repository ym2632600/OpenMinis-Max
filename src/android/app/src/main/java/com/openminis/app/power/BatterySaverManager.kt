package com.openminis.app.power

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
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

/**
 * A4: battery-saver mode. When enabled, a once-a-minute watchdog stops
 * persistent background shells that have been idle for [IDLE_TIMEOUT_MS];
 * the next command respawns them transparently. This cuts idle wakeups and
 * the RAM the warm shell pool holds while the user is not chatting.
 *
 * Deliberately conservative: a shell with a command in flight (slot mutex
 * locked) is never stopped, and the mode defaults to OFF so behaviour only
 * changes for users who opt in.
 */
object BatterySaverManager {

    private const val TAG = "BatterySaverManager"
    private const val PREFS_NAME = "battery_saver"
    private const val KEY_ENABLED = "enabled"

    /** Idle threshold before a warm shell is reaped. */
    const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs?.getBoolean(KEY_ENABLED, false) ?: false
        if (_enabled.value) startWatchdog()
    }

    fun setEnabled(on: Boolean) {
        if (_enabled.value == on) return
        _enabled.value = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
        AppLogger.info(TAG, if (on) "enabled" else "disabled")
        if (on) startWatchdog() else watchJob?.cancel()
    }

    private fun startWatchdog() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                try {
                    val reaped = ExecutionCoordinator.reapIdleShells(IDLE_TIMEOUT_MS)
                    if (reaped > 0) AppLogger.info(TAG, "reaped $reaped idle shell(s)")
                } catch (t: Throwable) {
                    AppLogger.warning(TAG, "reap failed: ${t.message}")
                }
            }
        }
    }
}
