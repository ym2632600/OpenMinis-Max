package com.openminis.app.sandbox.offload

import android.content.Context
import android.content.Intent
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject

/**
 * android-intent — send an explicit broadcast to another app.
 *
 * The bridge for automation apps with no public SDK: Tasker
 * (net.dinglisch.android.taskerm.ACTION_TASK), MacroDroid, or any app
 * exposing a documented broadcast receiver. android-open already covers
 * ACTION_VIEW launches; this handler is for broadcasts only.
 *
 *   android-intent broadcast --action A [--package P] [--extra-string k=v]...
 *
 * Example (Tasker task "WakeUp"):
 *   android-intent broadcast --action net.dinglisch.android.taskerm.ACTION_TASK \
 *     --package net.dinglisch.android.taskerm --extra-string task_name=WakeUp
 *
 * Gated ASK_ONCE by default (drives other apps).
 */
class IntentOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        OffloadGate.enforce("intent", "android-intent", args, request)?.let { return it }

        return try {
            when (args.positional.firstOrNull()) {
                "broadcast" -> doBroadcast(args)
                else -> NativeOffloadResult(2, "android-intent: expected subcommand 'broadcast'\n$HELP")
            }
        } catch (e: Throwable) {
            NativeOffloadResult(1, JSONObject().put("error", "internal").put("message", e.message ?: "unknown").toString() + "\n")
        }
    }

    private fun doBroadcast(args: OffloadArgs): NativeOffloadResult {
        val action = args.get("action")
            ?: return NativeOffloadResult(2, "android-intent broadcast: --action is required\n$HELP")
        val intent = Intent(action)
        args.get("package")?.let { intent.setPackage(it) }
        // Repeated --extra-string k=v flags become String extras.
        var extras = 0
        for (kv in args.getAll("extra-string")) {
            val eq = kv.indexOf('=')
            if (eq <= 0) {
                return NativeOffloadResult(2, "android-intent broadcast: bad --extra-string '$kv' (want k=v)\n")
            }
            intent.putExtra(kv.substring(0, eq), kv.substring(eq + 1))
            extras++
        }
        context.sendBroadcast(intent)
        return NativeOffloadResult(0, JSONObject()
            .put("sent", true)
            .put("action", action)
            .put("package", args.get("package") ?: JSONObject.NULL)
            .put("extras", extras)
            .toString() + "\n")
    }

    companion object {
        private const val HELP = """android-intent — broadcast to automation apps (JSON)

Usage:
  android-intent broadcast --action A [--package P] [--extra-string k=v]...

Examples:
  Tasker:   android-intent broadcast --action net.dinglisch.android.taskerm.ACTION_TASK \
              --package net.dinglisch.android.taskerm --extra-string task_name=MyTask
  (Tasker needs "Allow External Access" enabled in its own settings.)
"""
    }
}
