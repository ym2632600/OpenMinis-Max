package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * A1: device bridge — first-class agent tool that exposes the Android
 * offload CLIs (weather, calendar, alarms, notifications, media player,
 * device info) without the model having to hand-write shell commands.
 *
 * The tool definition lives in [definition]; the ChatViewModel dispatch
 * case translates the JSON args into the equivalent `android-*` offload
 * command via [buildCommand] and runs it through the shell_execute path,
 * so OffloadGate permission gating applies exactly as it does for the
 * hand-written CLIs.
 */
object DeviceTool {

    const val NAME = "device"

    private fun q(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun opt(args: JSONObject, key: String): String? =
        args.optString(key, "").takeIf { it.isNotBlank() }

    private fun flag(name: String, value: String?): String =
        if (value == null) "" else " $name ${q(value)}"

    /**
     * Translate the tool's JSON args into the equivalent android-* offload
     * command, or null when the action/args combination is invalid (the
     * caller then returns a usage error instead of executing anything).
     */
    fun buildCommand(argsJson: String): String? = runCatching {
        val args = JSONObject(argsJson)
        val action = args.optString("action", "")
        buildString {
            when (action) {
                "weather_current" -> append("android-weather current")
                "weather_hourly" -> append("android-weather hourly")
                "weather_daily" -> append("android-weather daily")
                "weather_alerts" -> append("android-weather alerts")
                "weather_report" -> append("android-weather report")
                "calendar_list" -> {
                    append("android-calendar list")
                    // --days comes from the shared flags below; --today only here.
                    if (args.optBoolean("today", false)) append(" --today")
                }
                "calendar_create" -> append("android-calendar create")
                "calendar_update" -> append("android-calendar update")
                "calendar_delete" -> append("android-calendar delete")
                "calendar_freebusy" -> append("android-calendar freebusy")
                "calendar_calendars" -> append("android-calendar calendars")
                "alarm_set" -> append("android-alarm set")
                "alarm_timer" -> append("android-alarm timer")
                "alarm_open" -> append("android-alarm open")
                "notification_schedule" -> append("android-notification schedule")
                "notification_list" -> {
                    append("android-notification list")
                    append(flag("--max", opt(args, "max")))
                }
                "notification_reply" -> append("android-notification reply")
                "notification_pending" -> append("android-notification pending")
                "notification_cancel" -> {
                    val id = opt(args, "id")
                    if (id == "all" || id == null) append("android-notification cancel --all")
                    else append("android-notification cancel --id ${q(id)}")
                }
                "notification_clear" -> append("android-notification clear")
                "notification_settings" -> append("android-notification settings")
                "player_play", "player_pause", "player_resume", "player_stop",
                "player_status", "player_list" -> {
                    append("android-player ")
                    append(action.removePrefix("player_").ifEmpty { "list" })
                }
                "device_info" -> append("android-device info")
                "device_battery" -> append("android-device battery")
                "device_storage" -> append("android-device storage")
                "device_all" -> append("android-device all")
                else -> return null
            }
            // Shared optional flags, appended only when present.
            append(flag("--lat", opt(args, "lat")))
            append(flag("--lon", opt(args, "lon")))
            append(flag("--hours", opt(args, "hours")))
            append(flag("--days", opt(args, "days")))
            append(flag("--start", opt(args, "start")))
            append(flag("--end", opt(args, "end")))
            append(flag("--title", opt(args, "title")))
            append(flag("--body", opt(args, "body")))
            append(flag("--id", opt(args, "event_id")))
            append(flag("--time", opt(args, "time")))
            append(flag("--duration", opt(args, "duration")))
            append(flag("--label", opt(args, "label")))
            opt(args, "repeat")?.let { append(" --repeat ${q(it.uppercase())}") }
            append(flag("--after", opt(args, "after")))
            append(flag("--at", opt(args, "at")))
            // Positional args: notification reply key + message, player session/path.
            opt(args, "key")?.let { append(" ${q(it)}") }
            opt(args, "message")?.let { append(" ${q(it)}") }
            opt(args, "session")?.let { append(" ${q(it)}") }
            opt(args, "path")?.let { append(" ${q(it)}") }
            opt(args, "position_ms")?.let { append(" ${q(it)}") }
        }.trim()
    }.getOrNull()

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Control the Android device: weather, calendar, alarms and timers, " +
            "notifications, media playback, and device info. Each action maps to the " +
            "corresponding android-* device bridge; Android runtime permission prompts " +
            "may appear the first time a capability is used. Prefer this tool over " +
            "hand-writing android-* shell commands. Weather needs lat/lon " +
            "(get coordinates from the location offload first when unknown).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Check weather forecast', 'Create calendar event'). Use the same language as the user."),
            "action" to AgentToolParam(
                "string",
                "The device action to perform.",
                enumValues = listOf(
                    "weather_current", "weather_hourly", "weather_daily", "weather_alerts", "weather_report",
                    "calendar_list", "calendar_create", "calendar_update", "calendar_delete",
                    "calendar_freebusy", "calendar_calendars",
                    "alarm_set", "alarm_timer", "alarm_open",
                    "notification_schedule", "notification_list", "notification_reply",
                    "notification_pending", "notification_cancel", "notification_clear",
                    "notification_settings",
                    "player_play", "player_pause", "player_resume", "player_stop",
                    "player_status", "player_list",
                    "device_info", "device_battery", "device_storage", "device_all",
                ),
            ),
            "lat" to AgentToolParam("string", "Latitude for weather_* actions (required for weather)."),
            "lon" to AgentToolParam("string", "Longitude for weather_* actions (required for weather)."),
            "hours" to AgentToolParam("string", "weather_hourly/report: forecast hours (default 24, max 48)."),
            "days" to AgentToolParam("string", "weather_daily/report: forecast days (default 7, max 10). calendar_list: list events for the next N days."),
            "today" to AgentToolParam("boolean", "calendar_list: restrict to today."),
            "start" to AgentToolParam("string", "calendar_*: event/range start, ISO 8601 or YYYY-MM-DD."),
            "end" to AgentToolParam("string", "calendar_*: event/range end, ISO 8601 or YYYY-MM-DD."),
            "title" to AgentToolParam("string", "calendar_create/update: event title (required for create). notification_schedule: notification title."),
            "body" to AgentToolParam("string", "notification_schedule: notification body text."),
            "event_id" to AgentToolParam("string", "calendar_update/delete: event id from calendar_list (required)."),
            "time" to AgentToolParam("string", "alarm_set: alarm time, HH:MM or ISO 8601 (required)."),
            "duration" to AgentToolParam("string", "alarm_timer: duration, e.g. 300, 5m, 1h (required)."),
            "label" to AgentToolParam("string", "alarm_set/timer: label shown in the Clock app."),
            "repeat" to AgentToolParam("string", "alarm_set: repeat rule.", enumValues = listOf("ONCE", "DAILY", "WEEKDAYS")),
            "after" to AgentToolParam("string", "notification_schedule: fire after this many seconds."),
            "at" to AgentToolParam("string", "notification_schedule: fire at this ISO 8601 time."),
            "max" to AgentToolParam("string", "notification_list: max entries to return."),
            "key" to AgentToolParam("string", "notification_reply: notification key from notification_list (required)."),
            "message" to AgentToolParam("string", "notification_reply: reply text (required)."),
            "id" to AgentToolParam("string", "notification_cancel: pending notification id, or 'all'."),
            "session" to AgentToolParam("string", "player_*: playback session name (required except player_list)."),
            "path" to AgentToolParam("string", "player_play: media file path (required)."),
            "position_ms" to AgentToolParam("string", "player_resume/seek: position in milliseconds."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf(
            "tool_title", "action", "lat", "lon", "hours", "days", "today",
            "start", "end", "title", "body", "event_id", "time", "duration",
            "label", "repeat", "after", "at", "max", "key", "message", "id",
            "session", "path", "position_ms",
        ),
    )
}
