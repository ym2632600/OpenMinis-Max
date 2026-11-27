package com.openminis.app.sandbox.offload

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
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

/**
 * Host-side implementation of `android-call` — places a phone call, or
 * opens the dialer with a number pre-filled, from the agent sandbox.
 *
 * Usage:
 *   android-call call <number|contact-name>
 *   android-call dial <number|contact-name>
 *   android-call --help
 *
 * `call` places the call immediately (ACTION_CALL), so — like sending an
 * SMS — it runs the full gauntlet first: the tri-state agent gate on
 * "call" (ASK_ONCE), the CALL_PHONE runtime-permission flow (system
 * dialog, post-DENY poll, settings gate, mirroring the SMS handler) and
 * a [ConsentManager] prompt for [SensitiveScope.PLACE_CALL] naming the
 * number. A call cannot be undone, so the outcome is written to the
 * activity log with no undo entry.
 *
 * `dial` only opens the dialer (ACTION_VIEW tel:, the same path the
 * open handler uses); the user still has to press call. It needs no
 * CALL_PHONE permission and no consent prompt.
 *
 * A contact name is resolved to a number with the same
 * ContactsContract query the contacts handler uses (display-name LIKE
 * match on the phone table). An exact name match wins; a missing name
 * is a structured error, and several matches are reported with the
 * candidates so the agent can ask the user to pick one.
 *
 * Success output is JSON: {"status", "number", "name"?}. Failures are
 * structured JSON too: call_permission_denied (77),
 * call_permission_timeout (77), consent_denied, consent_timeout,
 * contact_not_found, contact_ambiguous, contact_permission_denied (77),
 * no_dialer, call_permission_refused (77) and call_error (exit 1
 * unless noted).
 */
class CallOffloadHandler internal constructor(
    private val context: Context,
    private val system: CallSystem,
) : NativeOffloadHandler {

    constructor(context: Context) : this(context, RealCallSystem(context))

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val rawArgs = request.argv.drop(1)
        if (rawArgs.isEmpty() || rawArgs[0] == "-h" || rawArgs[0] == "--help") {
            return NativeOffloadResult(0, HELP_TEXT)
        }
        val parsedArgs = OffloadArgs(rawArgs)
        val sub = parsedArgs.positional.getOrNull(0)
        if (sub != "call" && sub != "dial") {
            return NativeOffloadResult(
                2,
                "android-call: usage: android-call <call|dial> <number|contact-name>\n$HELP_TEXT",
            )
        }
        if (parsedArgs.positional.size < 2 || hasUnknownOption(rawArgs)) {
            return NativeOffloadResult(
                2,
                "android-call: usage: android-call $sub <number|contact-name>\n",
            )
        }
        // Extra positionals are part of the target, so a multi-word
        // contact name works unquoted: `android-call call John Smith`.
        val target = parsedArgs.positional.drop(1).joinToString(" ")

        // Tri-state agent gate on "call" (ASK_ONCE) for both subcommands.
        OffloadGate.enforce("call", "android-call $sub", parsedArgs, request)?.let { return it }

        return try {
            when (sub) {
                "call" -> doCall(target, parsedArgs)
                else -> doDial(target, parsedArgs)
            }
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "SecurityException: ${e.message}")
            errorResult(
                77,
                "call_permission_refused",
                "The system refused the call even though the phone permission is granted. Underlying: ${e.message}",
                parsedArgs,
            )
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            errorResult(1, "call_error", e.message ?: "unknown call error", parsedArgs)
        }
    }

    private fun doCall(target: String, args: OffloadArgs): NativeOffloadResult {
        val resolved = when (val result = resolveTarget(target, args)) {
            is TargetResolution.Resolved -> result
            is TargetResolution.Failed -> return result.result
        }
        val number = resolved.number

        // CALL_PHONE runtime permission: system dialog, post-DENY poll,
        // settings gate — the same flow as the SMS handler.
        system.ensureCallPermission(args)?.let { return it }

        // Per-call user consent, naming the number.
        when (system.requestConsent(number)) {
            ConsentManager.ConsentOutcome.Approved -> Unit
            ConsentManager.ConsentOutcome.ApprovedAlways -> Unit
            ConsentManager.ConsentOutcome.Denied -> {
                AppLogger.warning(TAG, "consent denied for call to $number")
                return NativeOffloadResult(
                    1,
                    OffloadOutput.formatBody(
                        JSONObject()
                            .put("error", "consent_denied")
                            .put("message", "The user declined to place this call.")
                            .put("number", number)
                            .toString(),
                        args,
                    ) + "\n",
                )
            }
            ConsentManager.ConsentOutcome.TimedOut -> {
                AppLogger.warning(TAG, "consent timed out for call to $number")
                return NativeOffloadResult(
                    1,
                    OffloadOutput.formatBody(
                        JSONObject()
                            .put("error", "consent_timeout")
                            .put("message", "Timed out waiting for the user to approve placing this call.")
                            .put("number", number)
                            .toString(),
                        args,
                    ) + "\n",
                )
            }
        }

        return when (val launch = system.placeCall(number)) {
            CallLaunch.Launched -> {
                // A call cannot be undone, like an SMS: log it, with no
                // undo entry.
                ActivityLog.record(
                    ActivityLog.CATEGORY_TOOL_CALL,
                    context.getString(R.string.consent_call_title, number),
                    number,
                )
                NativeOffloadResult(
                    0,
                    OffloadOutput.formatBody(successJson("called", number, resolved.name).toString(), args) + "\n",
                )
            }
            CallLaunch.NoHandler -> errorResult(
                1,
                "no_dialer",
                "No phone app is installed on this device, so the call cannot be placed.",
                args,
            )
            is CallLaunch.Refused -> errorResult(77, "call_permission_refused", launch.message, args)
        }
    }

    private fun doDial(target: String, args: OffloadArgs): NativeOffloadResult {
        val resolved = when (val result = resolveTarget(target, args)) {
            is TargetResolution.Resolved -> result
            is TargetResolution.Failed -> return result.result
        }
        return when (val launch = system.dial(resolved.number)) {
            CallLaunch.Launched -> NativeOffloadResult(
                0,
                OffloadOutput.formatBody(successJson("dialed", resolved.number, resolved.name).toString(), args) + "\n",
            )
            CallLaunch.NoHandler -> errorResult(
                1,
                "no_dialer",
                "No phone/dialer app is installed on this device, so the dialer cannot be opened.",
                args,
            )
            is CallLaunch.Refused -> errorResult(77, "call_permission_refused", launch.message, args)
        }
    }

    private fun resolveTarget(target: String, args: OffloadArgs): TargetResolution {
        if (looksLikeNumber(target)) {
            return TargetResolution.Resolved(normalizeNumber(target), null)
        }
        return when (val lookup = system.resolveContact(target)) {
            is ContactLookup.Resolved -> TargetResolution.Resolved(lookup.number, lookup.name)
            ContactLookup.NotFound -> TargetResolution.Failed(
                errorResult(1, "contact_not_found", "No contact found for '$target'.", args),
            )
            is ContactLookup.Ambiguous -> TargetResolution.Failed(
                NativeOffloadResult(
                    1,
                    OffloadOutput.formatBody(
                        JSONObject()
                            .put("error", "contact_ambiguous")
                            .put(
                                "message",
                                "Multiple contacts match '$target': ${lookup.candidates.joinToString(", ")}.",
                            )
                            .put("candidates", JSONArray(lookup.candidates))
                            .toString(),
                        args,
                    ) + "\n",
                ),
            )
            ContactLookup.PermissionDenied -> TargetResolution.Failed(
                errorResult(
                    77,
                    "contact_permission_denied",
                    "Contacts permission is needed to look up '$target'. Grant it in Settings, or call a phone number directly.",
                    args,
                ),
            )
        }
    }

    private fun successJson(status: String, number: String, name: String?): JSONObject {
        val json = JSONObject()
            .put("status", status)
            .put("number", number)
        if (name != null) json.put("name", name)
        return json
    }

    private fun hasUnknownOption(args: List<String>): Boolean =
        args.any { it.startsWith("-") && it.trimStart('-') !in OffloadOutput.OUTPUT_FLAGS }

    companion object {
        private const val TAG = "CallOffloadHandler"

        private val HELP_TEXT = """
            android-call — place a phone call or open the dialer.

            Usage:
              android-call call <number|contact-name>   Place the call now (asks first)
              android-call dial <number|contact-name>   Open the dialer with the number filled in
              android-call --help                        Show this help

            `call` asks for the phone permission and your approval before dialling;
            a call cannot be undone, so it is written to the activity log.
            `dial` only opens the dialer — you still have to press call.
            A contact name is looked up in your contacts; if several contacts
            match, the call is not placed and the matches are listed instead.

            Output options:
              --compact   Print JSON without indentation
              -q, --quiet  Print only the result value
        """.trimIndent()
    }
}

/** Outcome of resolving a `call`/`dial` target to a phone number. */
private sealed interface TargetResolution {
    data class Resolved(val number: String, val name: String?) : TargetResolution
    data class Failed(val result: NativeOffloadResult) : TargetResolution
}

/** Contact-name lookup result, mirroring the contacts handler's query. */
internal sealed interface ContactLookup {
    data class Resolved(val number: String, val name: String) : ContactLookup
    data object NotFound : ContactLookup
    data class Ambiguous(val candidates: List<String>) : ContactLookup
    data object PermissionDenied : ContactLookup
}

/** Outcome of launching the phone/dialer intent. */
internal sealed interface CallLaunch {
    data object Launched : CallLaunch
    data object NoHandler : CallLaunch
    data class Refused(val message: String) : CallLaunch
}

/**
 * Android-only seams (permission, consent, contacts, intents), so the
 * handler's decisions stay testable under Robolectric. Mirrors
 * [CameraSystem].
 */
internal interface CallSystem {
    fun ensureCallPermission(args: OffloadArgs): NativeOffloadResult?
    fun requestConsent(number: String): ConsentManager.ConsentOutcome
    fun resolveContact(name: String): ContactLookup
    fun placeCall(number: String): CallLaunch
    fun dial(number: String): CallLaunch
}

private class RealCallSystem(private val context: Context) : CallSystem {

    override fun ensureCallPermission(args: OffloadArgs): NativeOffloadResult? {
        if (hasCallPermission()) return null
        val result = runBlocking {
            var r = OffloadPermissionManager.requestAndroidPermission(
                listOf(Manifest.permission.CALL_PHONE),
            )
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED &&
                OffloadPermissionManager.pollForPermissionGrant({ hasCallPermission() })
            ) {
                r = OffloadPermissionManager.AndroidPermissionResult.GRANTED
            }
            if (r == OffloadPermissionManager.AndroidPermissionResult.DENIED) {
                // The user denied twice: offer one last chance in the
                // app's system settings, mirroring the SMS handler.
                // No call-specific permission strings were landed and
                // no new strings may be added, so the settings gate
                // reuses the call-scope strings for its title/message
                // and the existing generic "Open Settings" label.
                r = OffloadPermissionManager.requestSettingsGate(
                    OffloadPermissionManager.SettingsGateRequest(
                        id = Manifest.permission.CALL_PHONE,
                        title = context.getString(R.string.consent_scope_place_call),
                        message = context.getString(R.string.consent_scope_place_call_desc),
                        settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        requiresPackageUri = true,
                        positiveLabel = context.getString(R.string.camera_permission_open_settings),
                    ),
                    check = { hasCallPermission() },
                )
            }
            r
        }
        return when (result) {
            OffloadPermissionManager.AndroidPermissionResult.GRANTED -> null
            OffloadPermissionManager.AndroidPermissionResult.DENIED ->
                errorResult(77, "call_permission_denied", "The user declined the phone permission.", args)
            OffloadPermissionManager.AndroidPermissionResult.TIMEOUT ->
                errorResult(77, "call_permission_timeout", "Timed out waiting for the user to grant the phone permission.", args)
        }
    }

    override fun requestConsent(number: String): ConsentManager.ConsentOutcome =
        runBlocking {
            ConsentManager.requireConsent(
                SensitiveScope.PLACE_CALL,
                context.getString(R.string.consent_call_title, number),
                listOf(context.getString(R.string.consent_scope_place_call_desc)),
            )
        }

    override fun resolveContact(name: String): ContactLookup {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return ContactLookup.PermissionDenied
        }
        // Same ContactsContract query the contacts handler uses:
        // display-name LIKE match on the phone table.
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
        ) ?: return ContactLookup.NotFound
        val matches = mutableListOf<Pair<String, String>>()
        cursor.use {
            val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val displayName = if (nameIndex >= 0) it.getString(nameIndex).orEmpty() else ""
                val number = if (numberIndex >= 0) it.getString(numberIndex).orEmpty() else ""
                if (number.isNotBlank()) matches.add(displayName to number)
            }
        }
        if (matches.isEmpty()) return ContactLookup.NotFound
        // An exact name match wins over partial matches.
        val exact = matches.filter { (displayName, _) -> displayName.equals(name, ignoreCase = true) }
        val pool = exact.ifEmpty { matches }
        val numbers = pool.map { it.second }.distinct()
        if (numbers.size == 1) {
            val number = numbers.single()
            val displayName = pool.first { it.second == number }.first
            return ContactLookup.Resolved(number, displayName)
        }
        val candidates = pool.distinct().take(10)
            .map { (displayName, number) -> "$displayName ($number)" }
        return ContactLookup.Ambiguous(candidates)
    }

    override fun placeCall(number: String): CallLaunch = launch(number, Intent.ACTION_CALL)

    override fun dial(number: String): CallLaunch = launch(number, Intent.ACTION_VIEW)

    private fun launch(number: String, action: String): CallLaunch {
        // Same tel: intent path the open handler uses for ACTION_VIEW.
        val intent = Intent(action, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return CallLaunch.NoHandler
        return try {
            context.startActivity(intent)
            CallLaunch.Launched
        } catch (e: ActivityNotFoundException) {
            CallLaunch.NoHandler
        } catch (e: SecurityException) {
            CallLaunch.Refused(e.message ?: "The system refused to place the call.")
        }
    }

    private fun hasCallPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED
}

private fun looksLikeNumber(target: String): Boolean {
    val digits = target.count { it.isDigit() }
    return digits >= 3 && target.all { it.isDigit() || it in "+-(). " }
}

private fun normalizeNumber(target: String): String {
    val digits = target.filter { it.isDigit() }
    return if (target.trimStart().startsWith("+")) "+$digits" else digits
}

private fun errorResult(exitCode: Int, code: String, message: String, args: OffloadArgs): NativeOffloadResult =
    NativeOffloadResult(
        exitCode,
        OffloadOutput.formatBody(
            JSONObject()
                .put("error", code)
                .put("message", message)
                .toString(),
            args,
        ) + "\n",
    )
