package com.openminis.app.sandbox.offload

import android.content.Context
import com.openminis.app.R
import com.openminis.app.activitylog.ActivityLog
import com.openminis.app.consent.ConsentManager
import com.openminis.app.consent.SensitiveScope
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.ssh.SshClient
import com.openminis.app.ssh.SshErrorCode
import com.openminis.app.ssh.SshException
import com.openminis.app.ssh.SshHost
import com.openminis.app.ssh.SshHostStore
import com.openminis.app.ssh.SshResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-side implementation of `android-ssh` — lists configured SSH hosts,
 * tests a connection, and runs a command on a configured host, from the
 * agent sandbox.
 *
 * Usage:
 *   android-ssh hosts [--compact]
 *   android-ssh test <host> [--timeout <s>]
 *   android-ssh exec <host> [--timeout <s>] <command...>
 *   android-ssh --help
 *
 * `hosts` is a local list only: name, `user@host:port`, auth type and
 * last-connected time. Secrets, keys and passwords are never in the output.
 *
 * `test` connects and authenticates via [SshClient], then runs `echo ok`.
 * It returns `{host, server_version, auth, ok}` — `auth` is `"key"` or
 * `"password"` from the configured host. `server_version` is null in v1:
 * [SshClient] does not expose the SSH protocol banner, and the value is
 * reported as null rather than invented.
 *
 * `exec` connects via the pooled session, opens the exec channel and runs
 * the command, returning `{stdout, stderr, exit_code, truncated}` as JSON.
 * It is non-interactive by design: no PTY and stdin closed ([SshClient]).
 * The default command timeout is 120 s, `--timeout` allows up to 600 s,
 * and output is capped at 256 KB in total with `truncated: true`.
 *
 * `<host>` is a configured host NAME (see `hosts`); v1 requires a
 * configured host and never connects to an ad-hoc `user@host`.
 *
 * Gates: `hosts` runs under the tri-state agent gate on `"ssh"`
 * (PRIVACY / BYPASS); `test` and `exec` run under `"ssh_exec"`
 * (PRIVACY / ASK_ONCE) and additionally ask the user once per run via
 * [ConsentManager] for [SensitiveScope.SSH_EXEC], naming the host and
 * the command. A completed test / exec is written to the activity log
 * with the host and exit code — never credentials. A host key that is
 * unknown or has changed is a hard fail, never an auto-accept.
 *
 * Errors are structured JSON. Exit codes follow the other handlers:
 * 77 permission/consent, 2 usage, 1 failure (the agent gate itself
 * returns its own 126 on denial): ssh_no_host, ssh_unknown_host,
 * ssh_host_key_changed, ssh_auth_failed, ssh_connect_failed, ssh_timeout,
 * ssh_exec_timeout, consent_denied, consent_timeout.
 */
class SshOffloadHandler internal constructor(
    private val context: Context,
    private val system: SshSystem,
) : NativeOffloadHandler {

    constructor(context: Context) : this(context, RealSshSystem(context))

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val rawArgs = request.argv.drop(1)
        if (rawArgs.isEmpty() || rawArgs[0] == "-h" || rawArgs[0] == "--help") {
            return NativeOffloadResult(0, HELP_TEXT)
        }
        val parsedArgs = OffloadArgs(rawArgs)
        val sub = rawArgs[0]
        if (sub != "hosts" && sub != "test" && sub != "exec") {
            return NativeOffloadResult(2, "android-ssh: unknown subcommand '$sub'\n$HELP_TEXT")
        }
        // Tri-state agent gate before any work: "ssh" (BYPASS) for the
        // local list, "ssh_exec" (ASK_ONCE) for anything that connects.
        val gateTool = if (sub == "hosts") "ssh" else "ssh_exec"
        OffloadGate.enforce(gateTool, "android-ssh $sub", parsedArgs, request)?.let { return it }

        return try {
            when (sub) {
                "hosts" -> doHosts(rawArgs.drop(1), parsedArgs)
                "test" -> doTest(rawArgs.drop(1), parsedArgs)
                else -> doExec(rawArgs.drop(1), parsedArgs)
            }
        } catch (e: SshException) {
            AppLogger.warning(TAG, "ssh error: ${e.message}")
            errorResult(1, e.errorCode.code, e.message ?: "unknown SSH error", parsedArgs)
        } catch (e: Throwable) {
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            errorResult(1, "ssh_connect_failed", e.message ?: "unknown SSH error", parsedArgs)
        }
    }

    private fun doHosts(args: List<String>, parsedArgs: OffloadArgs): NativeOffloadResult {
        if (args.any { it.trimStart('-') !in OffloadOutput.OUTPUT_FLAGS }) {
            return NativeOffloadResult(2, "android-ssh hosts: usage: android-ssh hosts [--compact]\n")
        }
        val arr = JSONArray()
        for (host in system.listHosts()) {
            arr.put(
                JSONObject()
                    .put("name", host.name)
                    .put("address", "${host.username}@${host.host}:${host.port}")
                    .put("auth_type", host.authType.value)
                    .put("last_connected", host.lastConnected ?: JSONObject.NULL),
            )
        }
        return NativeOffloadResult(0, OffloadOutput.formatBody(arr.toString(2), parsedArgs) + "\n")
    }

    private fun doTest(args: List<String>, parsedArgs: OffloadArgs): NativeOffloadResult {
        val parsed = parseHostAndTimeout(args)
            ?: return NativeOffloadResult(2, "android-ssh test: usage: android-ssh test <host> [--timeout <s>]\n")
        if (parsed.remaining.isNotEmpty()) {
            return NativeOffloadResult(2, "android-ssh test: usage: android-ssh test <host> [--timeout <s>]\n")
        }
        val host = system.findHost(parsed.hostName)
            ?: return errorResult(1, "ssh_no_host", context.getString(R.string.ssh_no_host), parsedArgs, parsed.hostName)
        requestExecConsent(host, TEST_COMMAND, parsedArgs)?.let { return it }
        return try {
            system.connect(host)
            val result = system.exec(host, TEST_COMMAND, parsed.timeoutSeconds)
            system.markConnected(host)
            if (result.exitCode != 0 || result.stdout.trim() != "ok") {
                return errorResult(
                    1,
                    "ssh_connect_failed",
                    context.getString(R.string.ssh_connect_failed, host.name) +
                        " (echo ok exited ${result.exitCode})",
                    parsedArgs,
                    host.name,
                )
            }
            ActivityLog.record(
                ActivityLog.CATEGORY_TOOL_CALL,
                context.getString(R.string.consent_scope_ssh_exec, host.name, TEST_COMMAND),
                "host=${host.name}, exit_code=${result.exitCode}",
            )
            val body = JSONObject()
                .put("host", host.name)
                .put("server_version", JSONObject.NULL)
                .put("auth", host.authType.value)
                .put("ok", true)
            NativeOffloadResult(0, OffloadOutput.formatBody(body.toString(2), parsedArgs) + "\n")
        } catch (e: SshException) {
            AppLogger.warning(TAG, "test failed: ${e.message}")
            sshErrorResult(e, host, parsedArgs)
        }
    }

    private fun doExec(args: List<String>, parsedArgs: OffloadArgs): NativeOffloadResult {
        val parsed = parseHostAndTimeout(args)
            ?: return NativeOffloadResult(2, "android-ssh exec: usage: android-ssh exec <host> [--timeout <s>] <command...>\n")
        if (parsed.remaining.isEmpty()) {
            return NativeOffloadResult(2, "android-ssh exec: usage: android-ssh exec <host> [--timeout <s>] <command...>\n")
        }
        val command = parsed.remaining.joinToString(" ")
        val host = system.findHost(parsed.hostName)
            ?: return errorResult(1, "ssh_no_host", context.getString(R.string.ssh_no_host), parsedArgs, parsed.hostName)
        requestExecConsent(host, command, parsedArgs)?.let { return it }
        return try {
            system.connect(host)
            val result = system.exec(host, command, parsed.timeoutSeconds)
            system.markConnected(host)
            ActivityLog.record(
                ActivityLog.CATEGORY_TOOL_CALL,
                context.getString(R.string.consent_scope_ssh_exec, host.name, command),
                "host=${host.name}, exit_code=${result.exitCode}",
            )
            val body = JSONObject()
                .put("stdout", result.stdout)
                .put("stderr", result.stderr)
                .put("exit_code", result.exitCode)
                .put("truncated", result.truncated)
            NativeOffloadResult(0, OffloadOutput.formatBody(body.toString(2), parsedArgs) + "\n")
        } catch (e: SshException) {
            AppLogger.warning(TAG, "exec failed: ${e.message}")
            sshErrorResult(e, host, parsedArgs)
        }
    }

    /**
     * Per-run user consent for [SensitiveScope.SSH_EXEC], naming the host
     * and the command. Returns null when approved, otherwise the
     * ready-to-return consent error (exit 77, like the SMS handler).
     */
    private fun requestExecConsent(host: SshHost, command: String, args: OffloadArgs): NativeOffloadResult? {
        return when (system.requestConsent(host.name, command)) {
            ConsentManager.ConsentOutcome.Approved,
            ConsentManager.ConsentOutcome.ApprovedAlways -> null
            ConsentManager.ConsentOutcome.Denied -> {
                AppLogger.warning(TAG, "consent denied for SSH exec on ${host.name}")
                errorResult(77, "consent_denied", "The user declined to run this SSH command.", args, host.name)
            }
            ConsentManager.ConsentOutcome.TimedOut -> {
                AppLogger.warning(TAG, "consent timed out for SSH exec on ${host.name}")
                errorResult(77, "consent_timeout", "Timed out waiting for the user to approve running this SSH command.", args, host.name)
            }
        }
    }

    /**
     * Parses `test`/`exec` arguments from the raw tokens. `--timeout` may
     * appear before or just after the host name; everything after the host
     * name is the command, verbatim (so `ls -la` keeps its flags).
     * [OffloadArgs] is not used for the command: it strips flag/positional
     * ordering, which would eat command flags. Returns null on a usage
     * error (missing host, unknown option, bad or out-of-range timeout).
     */
    private fun parseHostAndTimeout(args: List<String>): HostCommand? {
        var timeoutSeconds = SshClient.DEFAULT_EXEC_TIMEOUT_SECONDS
        var hostName: String? = null
        var i = 0
        while (i < args.size) {
            val token = args[i]
            when {
                token == "--timeout" -> {
                    if (i + 1 >= args.size) return null
                    val value = args[i + 1].toIntOrNull() ?: return null
                    try {
                        timeoutSeconds = SshClient.validateTimeout(value)
                    } catch (e: IllegalArgumentException) {
                        return null
                    }
                    i += 2
                }
                token.startsWith("-") && token.trimStart('-') in OffloadOutput.OUTPUT_FLAGS -> i++
                hostName == null && !token.startsWith("-") -> {
                    hostName = token
                    i++
                }
                hostName != null -> return HostCommand(hostName, timeoutSeconds, args.subList(i, args.size))
                else -> return null
            }
        }
        val name = hostName ?: return null
        return HostCommand(name, timeoutSeconds, emptyList())
    }

    /** Maps an [SshException] to its structured JSON error (exit 1). */
    private fun sshErrorResult(e: SshException, host: SshHost, args: OffloadArgs): NativeOffloadResult {
        val fingerprint = e.message?.substringAfter("fingerprint=", "")?.trim().orEmpty()
        val message = when (e.errorCode) {
            SshErrorCode.UNKNOWN_HOST ->
                context.getString(R.string.ssh_unknown_host, fingerprint.ifEmpty { "unavailable" })
            SshErrorCode.HOST_KEY_CHANGED ->
                context.getString(R.string.ssh_host_key_changed, host.host)
            SshErrorCode.AUTH_FAILED -> context.getString(R.string.ssh_auth_failed)
            SshErrorCode.CONNECT_FAILED ->
                context.getString(R.string.ssh_connect_failed, host.name)
            SshErrorCode.TIMEOUT -> context.getString(R.string.ssh_timeout)
            SshErrorCode.EXEC_TIMEOUT -> context.getString(R.string.ssh_exec_timeout)
        }
        val json = JSONObject()
            .put("error", e.errorCode.code)
            .put("message", message)
            .put("host", host.name)
        if (e.errorCode == SshErrorCode.UNKNOWN_HOST) {
            json.put("fingerprint", fingerprint.ifEmpty { "unavailable" })
        }
        return NativeOffloadResult(1, OffloadOutput.formatBody(json.toString(), args) + "\n")
    }

    private fun errorResult(
        exitCode: Int,
        code: String,
        message: String,
        args: OffloadArgs,
        hostName: String? = null,
    ): NativeOffloadResult {
        val json = JSONObject()
            .put("error", code)
            .put("message", message)
        if (hostName != null) json.put("host", hostName)
        return NativeOffloadResult(
            exitCode,
            OffloadOutput.formatBody(json.toString(), args) + "\n",
        )
    }

    /** Parsed `test`/`exec` arguments: host name, timeout, command words. */
    private data class HostCommand(
        val hostName: String,
        val timeoutSeconds: Int,
        val remaining: List<String>,
    )

    companion object {
        private const val TAG = "SshOffloadHandler"
        private const val TEST_COMMAND = "echo ok"

        private val HELP_TEXT = """
            android-ssh — list SSH hosts, test a connection, run a command on a configured host.

            Usage:
              android-ssh hosts [--compact]                    List configured SSH hosts (no secrets)
              android-ssh test <host> [--timeout <s>]          Connect, authenticate and run `echo ok`
              android-ssh exec <host> [--timeout <s>] <command...>  Run a command on <host>
              android-ssh --help                               Show this help

            <host> is a configured host NAME (see hosts). No ad-hoc user@host.
            test and exec ask for your approval first (SSH exec consent) and are
            written to the activity log. exec is non-interactive: no PTY, stdin
            closed. Default timeout 120 s, up to 600 s; output is capped at 256 KB.

            Output options:
              --compact   Print JSON without indentation
              -q, --quiet  Print only the result value
        """.trimIndent()
    }
}

/**
 * Android-only seams (store, consent, client), so the handler's decisions
 * stay testable under Robolectric with no live server. Mirrors [CallSystem].
 */
internal interface SshSystem {
    fun listHosts(): List<SshHost>
    fun findHost(name: String): SshHost?
    fun requestConsent(hostName: String, command: String): ConsentManager.ConsentOutcome
    fun connect(host: SshHost)
    fun exec(host: SshHost, command: String, timeoutSeconds: Int): SshResult
    fun markConnected(host: SshHost)
}

private class RealSshSystem(private val context: Context) : SshSystem {

    private val store = SshHostStore(context)
    private val client = SshClient(store)

    override fun listHosts(): List<SshHost> = store.listHosts()

    override fun findHost(name: String): SshHost? =
        store.listHosts().firstOrNull { it.name.equals(name, ignoreCase = true) }

    override fun requestConsent(hostName: String, command: String): ConsentManager.ConsentOutcome =
        runBlocking {
            ConsentManager.requireConsent(
                SensitiveScope.SSH_EXEC,
                context.getString(R.string.consent_scope_ssh_exec, hostName, command),
                listOf(context.getString(R.string.consent_scope_ssh_exec_desc)),
            )
        }

    override fun connect(host: SshHost) {
        client.connect(
            host = host.host,
            port = host.port,
            username = host.username,
            privateKey = host.privateKeyPem?.toByteArray(Charsets.UTF_8) ?: ByteArray(0),
            passphrase = host.keyPassphrase,
            password = host.password,
        )
    }

    override fun exec(host: SshHost, command: String, timeoutSeconds: Int): SshResult =
        client.exec(host.host, command, timeoutSeconds)

    override fun markConnected(host: SshHost) {
        store.markConnected(host.id)
    }
}
