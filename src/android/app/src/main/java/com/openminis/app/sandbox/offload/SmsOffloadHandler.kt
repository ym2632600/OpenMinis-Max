package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.activitylog.ActivityLog
import com.openminis.app.consent.ConsentManager
import com.openminis.app.consent.SensitiveScope
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Host-side implementation of `android-sms` — sends and reads device SMS via
 * SmsManager + the Telephony content provider.
 *
 * Usage:
 *   android-sms send <number> <message...>
 *   android-sms list [--max N]
 *   android-sms search <query> [--max N]
 *   android-sms thread <thread_id> [--max N]
 *   android-sms --help
 *
 * Agent gate: reads are gated on the "sms" permission level, sends on
 * "sms_send" (default ASK_ONCE so every outgoing message asks first).
 */
class SmsOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val rawArgs = request.argv.drop(1)
        if (rawArgs.isEmpty() || rawArgs[0] == "-h" || rawArgs[0] == "--help") {
            return NativeOffloadResult(0, HELP_TEXT)
        }
        val parsedArgs = OffloadArgs(rawArgs)
        val sub = rawArgs[0]
        val isSend = sub == "send"

        // T330-style tri-state gate: sends always go through the stricter
        // "sms_send" level (default ASK_ONCE) so the agent can never fire an
        // SMS silently.
        if (isSend) {
            OffloadGate.enforce("sms_send", "android-sms send", parsedArgs, request)?.let { return it }
        } else {
            OffloadGate.enforce("sms", "android-sms", parsedArgs, request)?.let { return it }
        }

        ensurePermission(needsSend = isSend, args = parsedArgs)?.let { return it }

        return try {
            when (sub) {
                "send" -> doSend(rawArgs.drop(1), parsedArgs)
                "list" -> doList(rawArgs.drop(1), parsedArgs)
                "search" -> doSearch(rawArgs.drop(1), parsedArgs)
                "thread" -> doThread(rawArgs.drop(1), parsedArgs)
                else -> NativeOffloadResult(2, "android-sms: unknown subcommand '$sub'\n$HELP_TEXT")
            }
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "SecurityException: ${e.message}")
            val body = JSONObject()
                .put("error", "sms_permission_refused")
                .put("message", "The SMS permission is granted but the system refused the operation. Some OEMs gate SMS behind a separate per-app privacy switch. Underlying: ${e.message}")
                .toString()
            NativeOffloadResult(77, OffloadOutput.formatBody(body, parsedArgs) + "\n")
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val body = JSONObject()
                .put("error", "sms_error")
                .put("message", e.message ?: "unknown SMS error")
                .toString()
            NativeOffloadResult(1, OffloadOutput.formatBody(body, parsedArgs) + "\n")
        }
    }

    // ---------------------------------------------------------------- send

    private fun doSend(args: List<String>, parsedArgs: OffloadArgs): NativeOffloadResult {
        val positional = args.filter { !it.startsWith("-") }
        if (positional.size < 2) {
            return NativeOffloadResult(2, "android-sms send: usage: send <number> <message...>\n")
        }
        val number = positional[0]
        val message = positional.drop(1).joinToString(" ")
        if (message.isBlank()) {
            return NativeOffloadResult(2, "android-sms send: message must not be empty\n")
        }

        // B13: sending SMS is a sensitive action - route it through the central
        // consent layer. An always-allow grant auto-approves; otherwise the
        // user sees exactly what will be sent and must approve (or deny /
        // let it time out).
        val consent = runBlocking {
            ConsentManager.requireConsent(
                SensitiveScope.SEND_SMS,
                context.getString(R.string.consent_sms_title, number),
                listOf(message),
            )
        }
        when (consent) {
            ConsentManager.ConsentOutcome.Approved,
            ConsentManager.ConsentOutcome.ApprovedAlways -> Unit
            ConsentManager.ConsentOutcome.Denied -> {
                val body = JSONObject()
                    .put("error", "consent_denied")
                    .put("message", "The user denied sending this SMS.")
                    .toString()
                return NativeOffloadResult(1, OffloadOutput.formatBody(body, parsedArgs) + "\n")
            }
            ConsentManager.ConsentOutcome.TimedOut -> {
                val body = JSONObject()
                    .put("error", "consent_timeout")
                    .put("message", "No approval within the time limit; SMS not sent.")
                    .toString()
                return NativeOffloadResult(1, OffloadOutput.formatBody(body, parsedArgs) + "\n")
            }
        }

        val smsManager: SmsManager = try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            }
        } catch (e: Throwable) {
            val body = JSONObject()
                .put("error", "no_telephony")
                .put("message", "This device has no SMS-capable telephony (e.g. a tablet without a SIM).")
                .toString()
            return NativeOffloadResult(1, OffloadOutput.formatBody(body, parsedArgs) + "\n")
        }

        val parts = smsManager.divideMessage(message)
        if (parts.size > 1) {
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
        } else {
            smsManager.sendTextMessage(number, null, message, null, null)
        }
        AppLogger.info(TAG, "SMS sent to $number (${parts.size} part(s), ${message.length} chars)")
        // B14: an SMS cannot be unsent, so this entry carries no undo.
        ActivityLog.record(
            ActivityLog.CATEGORY_MESSAGE_SENT,
            context.getString(R.string.activity_log_sms_sent, number),
            details = message,
        )
        val body = JSONObject()
            .put("sent", true)
            .put("to", number)
            .put("parts", parts.size)
            .put("chars", message.length)
            .toString()
        return NativeOffloadResult(0, OffloadOutput.formatBody(body, parsedArgs) + "\n")
    }

    // ---------------------------------------------------------------- reads

    private fun doList(args: List<String>, parsedArgs: OffloadArgs): NativeOffloadResult {
        val max = parseMax(args, default = 20)
        return querySms(
            selection = null, selectionArgs = null, max = max,
            sort = "${Telephony.Sms.DATE} DESC", parsedArgs = parsedArgs,
        )
    }

    private fun doSearch(args: List<String>, parsedArgs: OffloadArgs): NativeOffloadResult {
        val query = args.firstOrNull { !it.startsWith("-") }
            ?: return NativeOffloadResult(2, "android-sms search: missing <query>\n")
        val max = parseMax(args, default = 20)
        return querySms(
            selection = "${Telephony.Sms.BODY} LIKE ?",
            selectionArgs = arrayOf("%$query%"),
            max = max,
            sort = "${Telephony.Sms.DATE} DESC",
            parsedArgs = parsedArgs,
        )
    }

    private fun doThread(args: List<String>, parsedArgs: OffloadArgs): NativeOffloadResult {
        val threadId = args.firstOrNull { !it.startsWith("-") }
            ?: return NativeOffloadResult(2, "android-sms thread: missing <thread_id>\n")
        val max = parseMax(args, default = 50)
        return querySms(
            selection = "${Telephony.Sms.THREAD_ID} = ?",
            selectionArgs = arrayOf(threadId),
            max = max,
            sort = "${Telephony.Sms.DATE} ASC",
            parsedArgs = parsedArgs,
        )
    }

    private fun querySms(
        selection: String?,
        selectionArgs: Array<String>?,
        max: Int,
        sort: String,
        parsedArgs: OffloadArgs,
    ): NativeOffloadResult {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
        )
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, projection, selection, selectionArgs, sort,
        ) ?: return NativeOffloadResult(1, OffloadOutput.formatBody("android-sms: failed to query SMS provider", parsedArgs) + "\n")

        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val arr = JSONArray()
        cursor.use {
            var n = 0
            while (it.moveToNext() && n < max) {
                val type = it.getInt(5)
                arr.put(JSONObject()
                    .put("id", it.getLong(0))
                    .put("thread_id", it.getLong(1))
                    .put("address", it.getString(2) ?: "")
                    .put("body", it.getString(3) ?: "")
                    .put("date", df.format(Date(it.getLong(4))))
                    .put("direction", if (type == Telephony.Sms.MESSAGE_TYPE_SENT) "sent" else "inbox")
                    .put("read", it.getInt(6) == 1))
                n++
            }
        }
        return NativeOffloadResult(0, OffloadOutput.formatBody(arr.toString(2), parsedArgs) + "\n")
    }

    // ---------------------------------------------------------------- permissions

    private fun hasPermission(needsSend: Boolean = false): Boolean {
        val perm = if (needsSend) Manifest.permission.SEND_SMS else Manifest.permission.READ_SMS
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Same system-dialog → settings-gate flow as contacts/location/calendar.
     * Returns null if permission ends up granted, otherwise a ready-to-return
     * error result.
     */
    private fun ensurePermission(needsSend: Boolean = false, args: OffloadArgs): NativeOffloadResult? {
        if (hasPermission(needsSend)) return null
        val perms = if (needsSend) {
            listOf(Manifest.permission.SEND_SMS)
        } else {
            listOf(Manifest.permission.READ_SMS)
        }
        val kind = if (needsSend) "send SMS" else "read SMS"
        AppLogger.warning(TAG, "${perms.joinToString("+")} not granted — routing through permission flow")
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(perms)
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant({ hasPermission(needsSend) })
            ) {
                AppLogger.info(TAG, "SMS permission granted during post-DENY poll")
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = if (needsSend) Manifest.permission.SEND_SMS else Manifest.permission.READ_SMS,
                        title = "SMS permission needed",
                        message = "Minis needs permission to $kind. Open Settings to allow it.",
                        settingsAction = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = "Open Settings",
                    ),
                    check = { hasPermission(needsSend) },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "permission_denied")
                        .put("message", "The user declined the SMS permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT -> NativeOffloadResult(
                77,
                OffloadOutput.formatBody(
                    JSONObject().put("error", "timeout")
                        .put("message", "Timed out waiting for the user to grant the SMS permission.")
                        .toString(),
                    args,
                ) + "\n",
            )
        }
    }

    private fun parseMax(args: List<String>, default: Int): Int {
        val idx = args.indexOf("--max")
        if (idx >= 0) return args.getOrNull(idx + 1)?.toIntOrNull() ?: default
        val eq = args.firstOrNull { it.startsWith("--max=") }
        return eq?.substringAfter('=')?.toIntOrNull() ?: default
    }

    companion object {
        private const val TAG = "SmsOffloadHandler"
        private val HELP_TEXT = """
            android-sms — send and read device SMS.
            Usage:
              android-sms send <number> <message...>   Send an SMS (asks for approval by default).
              android-sms list [--max N]               Most recent messages (default 20).
              android-sms search <query> [--max N]     Full-text search in message bodies.
              android-sms thread <thread_id> [--max N] All messages in a thread, oldest first.
            Output: JSON array (reads) or JSON object (send).
            Requires READ_SMS for reads, SEND_SMS for sends.
        """.trimIndent()
    }
}
