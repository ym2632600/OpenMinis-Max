package com.openminis.app.sandbox.offload

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * B10: `android-termux-cli` — run commands in Termux and share files with it,
 * so the agent has both toolchains (the app's own offloads + Termux's full
 * Linux userland) from one shell.
 *
 * Command execution goes through Termux's documented RUN_COMMAND interface:
 * a `com.termux.service_execute` intent against `com.termux.app.TermuxService`
 * with the executable as the data Uri and `com.termux.execute.*` extras
 * (arguments / cwd / background). Termux only honors it when the user has
 * set `allow-external-apps=true` in ~/.termux/termux.properties, and this app
 * declares `com.termux.permission.RUN_COMMAND` (manifest). Both surfaces are
 * probed by `status` and re-checked on every `run`.
 *
 * Output capture uses the shared folder, because RUN_COMMAND has no stdout
 * channel back to the caller: the wrapped command redirects stdout/stderr to
 * `<shared>/<session>/out.txt` / `err.txt` and writes its exit code to
 * `exit.txt`; the handler polls for the exit file up to `--timeout`
 * (default 60s, max 600s) and returns everything as one JSON envelope.
 *
 * File sharing is the same folder in both directions: the app writes via
 * MANAGE_EXTERNAL_STORAGE (already declared), Termux reads/writes it after
 * the user runs `termux-setup-storage` once. `path` prints the location so
 * the agent can quote it into Termux-side commands.
 *
 * Usage:
 *   android-termux-cli status
 *   android-termux-cli path
 *   android-termux-cli run [--timeout SECONDS] [--cwd DIR] <command...>
 *   android-termux-cli run -- ls -la
 *
 * Every failure is a typed JSON envelope ({error, message, ...}) so the
 * caller can distinguish termux_not_installed / permission_missing /
 * allow_external_apps_off / all_files_access_required /
 * shared_dir_unwritable / background_start_failed / timeout from a real
 * non-zero exit_code.
 */
class TermuxOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, HELP)
        }
        OffloadGate.enforce("termux", "android-termux-cli", args, request)?.let { return it }

        return try {
            when (val sub = args.positional.firstOrNull()) {
                "status" -> okJson(statusJson(), args)
                "path" -> okJson(JSONObject().put("shared_dir", sharedDir().absolutePath), args)
                "run" -> doRun(args)
                null -> NativeOffloadResult(2, "android-termux-cli: missing subcommand\n$HELP")
                else -> NativeOffloadResult(2, "android-termux-cli: unknown subcommand '$sub'\n$HELP")
            }
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            errJson("internal", e.message ?: "unknown", args)
        }
    }

    // ------------------------------------------------------------------
    // status / capability probes
    // ------------------------------------------------------------------

    private fun isTermuxInstalled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun hasRunCommandPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, TERMUX_RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun sharedDir(): File =
        File(Environment.getExternalStorageDirectory(), "OpenMinis/termux")

    private fun statusJson(): JSONObject {
        val dir = sharedDir()
        return JSONObject()
            .put("termux_installed", isTermuxInstalled())
            .put("run_command_permission", hasRunCommandPermission())
            .put("all_files_access", hasAllFilesAccess())
            .put("shared_dir", dir.absolutePath)
            .put("shared_dir_writable", dir.isDirectory && dir.canWrite() || dir.mkdirs())
            .put(
                "hint",
                "Termux needs allow-external-apps=true in ~/.termux/termux.properties " +
                    "and termux-setup-storage for the shared folder.",
            )
    }

    // ------------------------------------------------------------------
    // run
    // ------------------------------------------------------------------

    private fun doRun(args: OffloadArgs): NativeOffloadResult {
        val command = args.positional.drop(1).joinToString(" ").trim()
        if (command.isEmpty()) {
            return NativeOffloadResult(2, "android-termux-cli: run needs a command\n$HELP")
        }
        if (!isTermuxInstalled()) {
            return errJson("termux_not_installed", "Termux is not installed on this device.", args)
        }
        if (!hasRunCommandPermission()) {
            return errJson(
                "permission_missing",
                "com.termux.permission.RUN_COMMAND is not granted to this app.",
                args,
            )
        }
        if (!hasAllFilesAccess()) {
            return errJson(
                "all_files_access_required",
                "Grant All Files Access so the shared Termux folder is readable and writable.",
                args,
            )
        }

        val base = sharedDir()
        if (!(base.isDirectory || base.mkdirs()) || !base.canWrite()) {
            return errJson(
                "shared_dir_unwritable",
                "Cannot write ${base.absolutePath}. Run termux-setup-storage in Termux and " +
                    "check All Files Access for this app.",
                args,
            )
        }

        val session = UUID.randomUUID().toString().substring(0, 8)
        val sessionDir = File(base, session)
        if (!sessionDir.mkdirs() && !sessionDir.isDirectory) {
            return errJson("shared_dir_unwritable", "Cannot create ${sessionDir.absolutePath}.", args)
        }
        val outFile = File(sessionDir, "out.txt")
        val errFile = File(sessionDir, "err.txt")
        val exitFile = File(sessionDir, "exit.txt")

        // Redirect inside the executed command: RUN_COMMAND returns nothing.
        val wrapped = "$command > '${outFile.absolutePath}' 2> '${errFile.absolutePath}'" +
            "; echo \$? > '${exitFile.absolutePath}'"
        val cwd = args.get("cwd") ?: base.absolutePath

        val intent = Intent(TERMUX_EXECUTE_ACTION).apply {
            component = ComponentName(TERMUX_PACKAGE, TERMUX_SERVICE_CLASS)
            data = Uri.parse("file://$TERMUX_SH")
            putExtra("com.termux.execute.arguments", arrayOf("-c", wrapped))
            putExtra("com.termux.execute.cwd", cwd)
            putExtra("com.termux.execute.background", true)
        }
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            AppLogger.warning(TAG, "background start failed: ${e.message}")
            return errJson(
                "background_start_failed",
                "Android refused the background start. Open Termux once, then retry.",
                args,
            )
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "security: ${e.message}")
            return errJson(
                "permission_missing",
                "Termux rejected the call: ${e.message ?: "RUN_COMMAND denied"}. " +
                    "Set allow-external-apps=true in ~/.termux/termux.properties.",
                args,
            )
        }

        val timeoutSec = (args.getInt("timeout") ?: DEFAULT_TIMEOUT_SEC).coerceIn(1, MAX_TIMEOUT_SEC)
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (exitFile.isFile) break
            Thread.sleep(POLL_MS)
        }
        if (!exitFile.isFile) {
            return errJson(
                "timeout",
                "No exit marker after ${timeoutSec}s; the command may still be running. " +
                    "Files stay in ${sessionDir.absolutePath}.",
                args,
            )
        }

        val body = JSONObject()
            .put("exit_code", exitFile.readText().trim().toIntOrNull() ?: -1)
            .put("stdout", outFile.readCapped())
            .put("stderr", errFile.readCapped())
            .put("session_dir", sessionDir.absolutePath)
        return okJson(body, args)
    }

    private fun File.readCapped(): String = try {
        if (!isFile) "" else readText().let { if (it.length > MAX_OUTPUT_CHARS) it.substring(0, MAX_OUTPUT_CHARS) else it }
    } catch (_: Throwable) { "" }

    // ------------------------------------------------------------------
    // envelopes
    // ------------------------------------------------------------------

    private fun okJson(body: JSONObject, args: OffloadArgs? = null): NativeOffloadResult {
        val s = body.toString()
        return NativeOffloadResult(0, (if (args != null) OffloadOutput.formatBody(s, args) else s) + "\n")
    }

    private fun errJson(code: String, message: String, args: OffloadArgs): NativeOffloadResult {
        val body = JSONObject().put("error", code).put("message", message).toString()
        return NativeOffloadResult(1, OffloadOutput.formatBody(body, args) + "\n")
    }

    companion object {
        private const val TAG = "TermuxOffload"
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_SERVICE_CLASS = "com.termux.app.TermuxService"
        private const val TERMUX_EXECUTE_ACTION = "com.termux.service_execute"
        private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val TERMUX_SH = "/data/data/com.termux/files/usr/bin/sh"
        private const val DEFAULT_TIMEOUT_SEC = 60
        private const val MAX_TIMEOUT_SEC = 600
        private const val POLL_MS = 500L
        private const val MAX_OUTPUT_CHARS = 64 * 1024

        private val HELP = """
android-termux-cli — run commands in Termux and share files with it. (B10)

Usage:
  android-termux-cli status
  android-termux-cli path
  android-termux-cli run [--timeout SECONDS] [--cwd DIR] <command...>

Subcommands:
  status   Probe the integration: Termux installed, RUN_COMMAND permission,
           All Files Access, and the shared folder state.
  path     Print the shared folder both sides can read/write
           (app side: MANAGE_EXTERNAL_STORAGE; Termux side: termux-setup-storage).
  run      Execute a command in Termux via its RUN_COMMAND interface
           (background execution service). stdout/stderr/exit code are
           captured through the shared folder and returned as JSON
           {exit_code, stdout, stderr, session_dir}.
           --timeout SECONDS  wait budget for the exit marker (default 60, max 600)
           --cwd DIR          working directory (default: the shared folder)

Requirements on the device:
  - Termux installed (com.termux)
  - allow-external-apps=true in ~/.termux/termux.properties
  - termux-setup-storage run once (for the shared folder)

Errors are JSON envelopes: termux_not_installed, permission_missing,
all_files_access_required, shared_dir_unwritable, background_start_failed,
timeout, internal.
""".trimIndent()
    }
}
