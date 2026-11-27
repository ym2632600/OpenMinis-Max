package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.offload.HealthManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * android-health — read steps, heart rate and sleep via Health Connect.
 *
 *   android-health status
 *   android-health steps     [--days N (default 1)]
 *   android-health heartrate [--days N (default 1)]
 *   android-health sleep     [--days N (default 7)]
 *
 * All output is JSON. Missing Health Connect permissions return a
 * structured `health_permission_missing` error — the agent should ask the
 * user to grant them in Settings, never retry in a loop.
 */
class HealthOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        // Tri-state agent gate before any work (health data is sensitive:
        // defaults to ASK_ONCE in OffloadPermissionManager).
        OffloadGate.enforce("health", "android-health", args, request)?.let { return it }

        return try {
            when (val sub = args.positional.firstOrNull() ?: "status") {
                "status" -> NativeOffloadResult(0, runBlocking { HealthManager.status(context) } + "\n")
                "steps" -> NativeOffloadResult(0, runBlocking {
                    val (start, end) = dayRange(args.getInt("days") ?: 1)
                    HealthManager.readSteps(context, start, end)
                } + "\n")
                "heartrate" -> NativeOffloadResult(0, runBlocking {
                    val (start, end) = dayRange(args.getInt("days") ?: 1)
                    HealthManager.readHeartRate(context, start, end)
                } + "\n")
                "sleep" -> NativeOffloadResult(0, runBlocking {
                    val (start, end) = dayRange(args.getInt("days") ?: 7)
                    HealthManager.readSleep(context, start, end)
                } + "\n")
                else -> NativeOffloadResult(2, "android-health: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            NativeOffloadResult(1, "android-health: ${e.message ?: "unknown error"}\n")
        }
    }

    /** [days] back from now; returns (startMs, endMs). */
    private fun dayRange(days: Int): Pair<Long, Long> {
        val d = days.coerceIn(1, 90)
        val end = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = end; add(Calendar.DAY_OF_YEAR, -d) }
        return cal.timeInMillis to end
    }

    companion object {
        private const val HELP = """android-health — steps, heart rate, sleep via Health Connect (JSON)

Usage:
  android-health status                 Availability + granted/missing permissions
  android-health steps [--days N]       Total steps per range (default 1 day)
  android-health heartrate [--days N]   Heart-rate samples (default 1 day, max 200 rows)
  android-health sleep [--days N]       Sleep sessions (default 7 days)

Missing permissions return error=health_permission_missing — ask the user
to grant steps/heart rate/sleep reads in Settings > System Permissions.
"""
    }
}
