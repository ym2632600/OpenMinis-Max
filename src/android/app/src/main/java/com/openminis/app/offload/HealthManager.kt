package com.openminis.app.offload

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Health data bridge backed by Android Health Connect.
 *
 * Reads steps, heart rate and sleep through HealthConnectClient. The bridge
 * is agent-facing (android-health offload + `device` tool actions) and never
 * prompts by itself: when permissions are missing it returns a structured
 * error telling the agent to ask the user to grant them in Settings, which
 * links to the system permission screen.
 */
object HealthManager {

    private val READ_PERMISSIONS = setOf(
        "android.permission.health.READ_STEPS",
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.READ_SLEEP",
    )

    private val fmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    sealed interface Availability {
        data object Available : Availability
        data class Unavailable(val reason: String) : Availability
    }

    fun availability(context: Context): Availability {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                Availability.Unavailable("Health Connect needs an update. Open the Play Store and update the Health Connect app, then retry.")
            else ->
                Availability.Unavailable("Health Connect is not installed on this device. Install the Health Connect app (Android 14+ has it built in), then retry.")
        }
    }

    private fun client(context: Context): HealthConnectClient =
        HealthConnectClient.getOrCreate(context.applicationContext)

    /** Null when every required read permission is granted; otherwise a JSON error string. */
    private suspend fun missingPermissions(context: Context): String? {
        val granted = client(context).permissionController.getGrantedPermissions()
        val missing = READ_PERMISSIONS - granted
        if (missing.isEmpty()) return null
        return JSONObject()
            .put("error", "health_permission_missing")
            .put("missing", JSONArray(missing.toList()))
            .put(
                "message",
                "Health Connect permissions are not granted. Ask the user to open " +
                    "Settings > System Permissions (or the Health Connect app) and allow " +
                    "steps, heart rate and sleep reads for Minis, then retry.",
            )
            .toString()
    }

    suspend fun status(context: Context): String {
        val avail = availability(context)
        if (avail is Availability.Unavailable) {
            return JSONObject().put("available", false).put("reason", avail.reason).toString()
        }
        val granted = client(context).permissionController.getGrantedPermissions()
        return JSONObject()
            .put("available", true)
            .put("granted", JSONArray(granted.intersect(READ_PERMISSIONS).toList()))
            .put("missing", JSONArray((READ_PERMISSIONS - granted).toList()))
            .toString()
    }

    /** Total steps per day over [startMs, endMs). */
    suspend fun readSteps(context: Context, startMs: Long, endMs: Long): String {
        availability(context).let { if (it is Availability.Unavailable) return err("unavailable", it.reason) }
        missingPermissions(context)?.let { return it }
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs))
        val result: AggregationResult = client(context).aggregate(
            AggregateRequest(metrics = setOf(StepsRecord.COUNT_TOTAL), timeRangeFilter = range),
        )
        val total = result[StepsRecord.COUNT_TOTAL] ?: 0L
        return JSONObject()
            .put("start", fmt.format(Instant.ofEpochMilli(startMs)))
            .put("end", fmt.format(Instant.ofEpochMilli(endMs)))
            .put("total_steps", total)
            .toString()
    }

    /** Heart-rate samples over [startMs, endMs), capped at 200 rows. */
    suspend fun readHeartRate(context: Context, startMs: Long, endMs: Long): String {
        availability(context).let { if (it is Availability.Unavailable) return err("unavailable", it.reason) }
        missingPermissions(context)?.let { return it }
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs))
        val records = client(context).readRecords(
            ReadRecordsRequest(HeartRateRecord::class, range, pageSize = 50),
        ).records
        val samples = JSONArray()
        var count = 0
        outer@ for (rec in records) {
            for (s in rec.samples) {
                samples.put(
                    JSONObject()
                        .put("time", fmt.format(s.time))
                        .put("bpm", s.beatsPerMinute),
                )
                if (++count >= 200) break@outer
            }
        }
        return JSONObject()
            .put("start", fmt.format(Instant.ofEpochMilli(startMs)))
            .put("end", fmt.format(Instant.ofEpochMilli(endMs)))
            .put("samples", samples)
            .put("truncated", records.sumOf { it.samples.size } > 200)
            .toString()
    }

    /** Sleep sessions overlapping [startMs, endMs). */
    suspend fun readSleep(context: Context, startMs: Long, endMs: Long): String {
        availability(context).let { if (it is Availability.Unavailable) return err("unavailable", it.reason) }
        missingPermissions(context)?.let { return it }
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs))
        val records = client(context).readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, range, pageSize = 30),
        ).records
        val sessions = JSONArray()
        for (rec in records) {
            val durationMin = java.time.Duration.between(rec.startTime, rec.endTime).toMinutes()
            sessions.put(
                JSONObject()
                    .put("start", fmt.format(rec.startTime))
                    .put("end", fmt.format(rec.endTime))
                    .put("duration_minutes", durationMin)
                    .put("title", rec.title ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("start", fmt.format(Instant.ofEpochMilli(startMs)))
            .put("end", fmt.format(Instant.ofEpochMilli(endMs)))
            .put("sessions", sessions)
            .toString()
    }

    private fun err(code: String, message: String): String =
        JSONObject().put("error", code).put("message", message).toString()
}
