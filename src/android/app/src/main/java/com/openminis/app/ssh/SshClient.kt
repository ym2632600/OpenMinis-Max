package com.openminis.app.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Structured SSH error codes (B9). The [code] values match the user-facing
 * string names added in "B9: SSH strings (AR)" (for example `ssh_unknown_host`).
 * Messages carry only a host name and, for unknown hosts, the host-key
 * fingerprint. They never contain a private key, passphrase or password.
 */
enum class SshErrorCode(val code: String) {
    UNKNOWN_HOST("ssh_unknown_host"),
    HOST_KEY_CHANGED("ssh_host_key_changed"),
    AUTH_FAILED("ssh_auth_failed"),
    CONNECT_FAILED("ssh_connect_failed"),
    TIMEOUT("ssh_timeout"),
    EXEC_TIMEOUT("ssh_exec_timeout"),
}

/** An SSH failure, structured by [errorCode] so callers can map it to a string. */
class SshException(val errorCode: SshErrorCode, message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    companion object {
        fun unknownHost(host: String, fingerprint: String?): SshException =
            SshException(
                SshErrorCode.UNKNOWN_HOST,
                "ssh_unknown_host: unknown host key for $host; " +
                    "fingerprint=${fingerprint ?: "unavailable"}",
            )

        fun hostKeyChanged(host: String, fingerprint: String?): SshException =
            SshException(
                SshErrorCode.HOST_KEY_CHANGED,
                "ssh_host_key_changed: host key for $host has changed; " +
                    "fingerprint=${fingerprint ?: "unavailable"}; refusing to connect",
            )

        fun authFailed(host: String): SshException =
            SshException(
                SshErrorCode.AUTH_FAILED,
                "ssh_auth_failed: authentication failed for $host",
            )

        fun connectFailed(host: String, cause: Throwable? = null): SshException =
            SshException(
                SshErrorCode.CONNECT_FAILED,
                "ssh_connect_failed: could not connect to $host",
                cause,
            )

        fun timeout(host: String): SshException =
            SshException(
                SshErrorCode.TIMEOUT,
                "ssh_timeout: timed out connecting to $host",
            )

        fun execTimeout(host: String): SshException =
            SshException(
                SshErrorCode.EXEC_TIMEOUT,
                "ssh_exec_timeout: command timed out on $host",
            )
    }
}

/** Result of one `exec` call. [truncated] is true when the 256 KB output cap was hit. */
data class SshResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val truncated: Boolean,
)

/**
 * Host-key pinning, kept behind an interface so the client never touches storage.
 * The concrete implementation (encrypted preferences) comes in a later commit;
 * it returns the one pinned SHA-256 fingerprint for a host, or null when the
 * host has no pinned key yet. A host with no pinned key is never auto-accepted.
 */
interface HostKeyRepository {
    fun pinnedFingerprint(host: String): String?
}

/** Shared byte counter for the stdout/stderr streams of one `exec` call. */
class OutputCap {
    @Volatile
    var writtenBytes: Long = 0L

    @Volatile
    var truncated: Boolean = false
}

/**
 * An [OutputStream] that forwards at most [SshClient.MAX_OUTPUT_BYTES] bytes
 * in total (shared through [cap] with the other stream of the same `exec` call)
 * and discards the rest, setting [OutputCap.truncated].
 */
class CappedOutputStream(
    private val cap: OutputCap,
    private val sink: ByteArrayOutputStream,
) : OutputStream() {

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val remaining = (SshClient.MAX_OUTPUT_BYTES - cap.writtenBytes).coerceAtLeast(0L)
        val allowed = minOf(len.toLong(), remaining).toInt()
        if (allowed < len) cap.truncated = true
        if (allowed > 0) {
            sink.write(b, off, allowed)
            cap.writtenBytes += allowed
        }
    }
}

/**
 * Adapts the app's [HostKeyRepository] to JSch's host-key repository.
 * `check` returns OK only for the pinned fingerprint, NOT_INCLUDED when the
 * host has no pinned key, and CHANGED when the key differs from the pin.
 * Adding a key is never allowed here: a changed or unknown key is a hard fail,
 * never an auto-accept. The last fingerprint JSch presented is kept so
 * [SshClient] can include it in the `ssh_unknown_host` error.
 */
class PinnedHostKeyRepository(
    private val pinned: HostKeyRepository,
) : com.jcraft.jsch.HostKeyRepository {

    @Volatile
    var lastSeenFingerprint: String? = null
        private set

    @Volatile
    var lastSeenHost: String? = null
        private set

    @Volatile
    var lastCheckWasChanged: Boolean = false
        private set

    override fun check(host: String, key: ByteArray): Int {
        val fingerprint = SshClient.formatFingerprint(key)
        lastSeenHost = host
        lastSeenFingerprint = fingerprint
        val expected = pinned.pinnedFingerprint(host)?.let { SshClient.normalizeFingerprint(it) }
        return when {
            expected == null -> com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED
            expected == fingerprint -> {
                lastCheckWasChanged = false
                com.jcraft.jsch.HostKeyRepository.OK
            }
            else -> {
                lastCheckWasChanged = true
                com.jcraft.jsch.HostKeyRepository.CHANGED
            }
        }
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        throw UnsupportedOperationException("Host keys are never auto-accepted; pin them explicitly.")
    }

    override fun remove(host: String, type: String?) {
        // Pinned keys are managed by the app's HostKeyRepository, not by JSch.
    }

    override fun remove(host: String, type: String?, key: ByteArray) {
        // Pinned keys are managed by the app's HostKeyRepository, not by JSch.
    }

    override fun getKnownHostsRepositoryID(): String = "pinned-host-keys"

    override fun getHostKey(host: String, type: String?): Array<HostKey> = emptyArray()

    override fun getHostKey(): Array<HostKey> = emptyArray()
}

/**
 * JSch SSH client (B9, artifact `com.github.mwiede:jsch`; the JSch API itself
 * still lives in the `com.jcraft.jsch` package).
 *
 * - [connect] opens a session with a 15-second connect timeout and
 *   `StrictHostKeyChecking=yes` against the pinned fingerprint only.
 * - [exec] runs one command with no PTY and stdin closed (an empty input
 *   stream, so a prompting command hits EOF and fails fast instead of
 *   hanging). Output is capped at 256 KB in total across stdout and stderr.
 * - Sessions are pooled in memory only: one [Session] per host, at most
 *   [MAX_SESSIONS] sessions, idle-evicted after [IDLE_EVICTION_MS].
 *   Sessions and keys are never persisted by this client.
 * - No secrets are ever written to output or logs: keys, passphrases and
 *   passwords are passed to JSch only and never appear in an error message.
 */
class SshClient(
    private val hostKeyRepository: HostKeyRepository,
    private val jschFactory: () -> JSch = { JSch() },
) {

    private data class PooledSession(
        val session: Session,
        val host: String,
        val port: Int,
        val username: String,
        @Volatile var lastUsedAtMs: Long,
    )

    private val sessions = ConcurrentHashMap<String, PooledSession>()

    companion object {
        /** Connect timeout for [Session.connect] and channel connect, in milliseconds. */
        const val CONNECT_TIMEOUT_MS: Int = 15_000

        /** Default `exec` timeout, in seconds. */
        const val DEFAULT_EXEC_TIMEOUT_SECONDS: Int = 120

        /** Maximum `exec` timeout, in seconds. */
        const val MAX_EXEC_TIMEOUT_SECONDS: Int = 600

        /** Output cap in bytes, total across stdout and stderr. */
        const val MAX_OUTPUT_BYTES: Int = 256 * 1024

        /** Maximum number of pooled sessions. */
        const val MAX_SESSIONS: Int = 4

        /** Idle sessions older than this are evicted, in milliseconds (5 minutes). */
        const val IDLE_EVICTION_MS: Long = 5 * 60 * 1000L

        const val DEFAULT_SSH_PORT: Int = 22

        /** Formats raw host-key bytes as an OpenSSH `SHA256:` fingerprint. */
        fun formatFingerprint(hostKeyBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(hostKeyBytes)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

        /** Canonical form of a fingerprint: trimmed, with the `SHA256:` prefix. */
        fun normalizeFingerprint(fingerprint: String): String {
            val trimmed = fingerprint.trim()
            return if (trimmed.startsWith("SHA256:")) trimmed else "SHA256:$trimmed"
        }

        /** Returns [timeoutSeconds] if it is in range, otherwise throws. */
        fun validateTimeout(timeoutSeconds: Int): Int {
            require(timeoutSeconds in 1..MAX_EXEC_TIMEOUT_SECONDS) {
                "timeoutSeconds must be between 1 and $MAX_EXEC_TIMEOUT_SECONDS"
            }
            return timeoutSeconds
        }

        /**
         * Enforces the total output cap on already-collected output: stdout is
         * kept first, stderr gets what is left of the cap.
         */
        fun truncateOutputs(stdout: ByteArray, stderr: ByteArray): Triple<ByteArray, ByteArray, Boolean> {
            val stdoutAllowed = minOf(stdout.size, MAX_OUTPUT_BYTES)
            val stderrAllowed = minOf(stderr.size, MAX_OUTPUT_BYTES - stdoutAllowed)
            val truncated = stdout.size > stdoutAllowed || stderr.size > stderrAllowed
            return Triple(stdout.copyOf(stdoutAllowed), stderr.copyOf(stderrAllowed), truncated)
        }
    }

    /**
     * Connects (or reuses a live pooled session) for [host]. The private key is
     * held only in memory for the JSch identity; [passphrase] unlocks it and
     * [password] is used only when given. Either credential may be omitted,
     * but at least one authentication method must be available.
     */
    fun connect(
        host: String,
        port: Int = DEFAULT_SSH_PORT,
        username: String,
        privateKey: ByteArray,
        passphrase: String? = null,
        password: String? = null,
    ) {
        require(host.isNotBlank()) { "host must not be blank" }
        require(username.isNotBlank()) { "username must not be blank" }
        require(port in 1..65_535) { "port must be between 1 and 65535" }
        evictIdleSessions()
        val key = sessionKey(host, port, username)
        sessions[key]?.let { pooled ->
            if (pooled.session.isConnected) {
                pooled.lastUsedAtMs = System.currentTimeMillis()
                return
            }
            disconnectSession(key, pooled)
        }
        if (sessions.size >= MAX_SESSIONS) {
            sessions.entries.minByOrNull { it.value.lastUsedAtMs }?.let { oldest ->
                disconnectSession(oldest.key, oldest.value)
            }
        }
        val jsch = jschFactory()
        val pinned = PinnedHostKeyRepository(hostKeyRepository)
        if (privateKey.isNotEmpty()) {
            try {
                jsch.addIdentity(
                    "openminis-ssh",
                    privateKey,
                    null,
                    passphrase?.toByteArray(Charsets.UTF_8),
                )
            } catch (e: JSchException) {
                throw SshException(
                    SshErrorCode.AUTH_FAILED,
                    "ssh_auth_failed: private key could not be loaded for $host",
                    e,
                )
            }
        }
        val session = try {
            jsch.getSession(username, host, port)
        } catch (e: JSchException) {
            throw SshException.connectFailed(host, e)
        }
        if (password != null) session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig(
            "PreferredAuthentications",
            if (password != null) "publickey,password" else "publickey",
        )
        session.setHostKeyRepository(pinned)
        try {
            session.connect(CONNECT_TIMEOUT_MS)
        } catch (e: JSchException) {
            throw mapConnectException(host, pinned, e)
        } catch (e: UnknownHostException) {
            throw SshException.connectFailed(host, e)
        } catch (e: IOException) {
            throw SshException.connectFailed(host, e)
        }
        sessions[key] = PooledSession(session, host, port, username, System.currentTimeMillis())
    }

    /**
     * Runs [command] on the single pooled session. Use the host overload when
     * more than one session is pooled.
     */
    fun exec(command: String, timeoutSeconds: Int = DEFAULT_EXEC_TIMEOUT_SECONDS): SshResult {
        validateTimeout(timeoutSeconds)
        val pooled = when (sessions.size) {
            0 -> throw SshException.connectFailed("unknown host")
            1 -> sessions.values.single()
            else -> throw IllegalStateException(
                "Multiple SSH sessions are pooled; use exec(host, command, timeoutSeconds)",
            )
        }
        return execOn(pooled, command, timeoutSeconds)
    }

    /** Runs [command] on the pooled session for [host]. */
    fun exec(host: String, command: String, timeoutSeconds: Int = DEFAULT_EXEC_TIMEOUT_SECONDS): SshResult {
        validateTimeout(timeoutSeconds)
        val pooled = sessions.values.filter { it.host == host }
            .maxByOrNull { it.lastUsedAtMs }
            ?: throw SshException.connectFailed(host)
        return execOn(pooled, command, timeoutSeconds)
    }

    private fun execOn(pooled: PooledSession, command: String, timeoutSeconds: Int): SshResult {
        val session = pooled.session
        if (!session.isConnected) throw SshException.connectFailed(pooled.host)
        pooled.lastUsedAtMs = System.currentTimeMillis()
        val channel = try {
            session.openChannel("exec") as ChannelExec
        } catch (e: JSchException) {
            throw SshException.connectFailed(pooled.host, e)
        }
        try {
            channel.setCommand(command)
            // No PTY, and stdin is an empty stream: it is closed at once, so a
            // command that prompts for input fails fast instead of hanging.
            channel.setPty(false)
            channel.setInputStream(ByteArrayInputStream(ByteArray(0)))
            val cap = OutputCap()
            val stdoutSink = ByteArrayOutputStream()
            val stderrSink = ByteArrayOutputStream()
            channel.setOutputStream(CappedOutputStream(cap, stdoutSink))
            channel.setErrStream(CappedOutputStream(cap, stderrSink))
            channel.connect(CONNECT_TIMEOUT_MS)
            val deadlineMs = System.currentTimeMillis() + timeoutSeconds * 1000L
            while (!channel.isClosed) {
                if (System.currentTimeMillis() > deadlineMs) {
                    channel.disconnect()
                    throw SshException.execTimeout(pooled.host)
                }
                try {
                    Thread.sleep(25)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    channel.disconnect()
                    throw SshException.execTimeout(pooled.host)
                }
            }
            pooled.lastUsedAtMs = System.currentTimeMillis()
            return SshResult(
                stdout = stdoutSink.toString(Charsets.UTF_8.name()),
                stderr = stderrSink.toString(Charsets.UTF_8.name()),
                exitCode = channel.exitStatus,
                truncated = cap.truncated,
            )
        } finally {
            try {
                channel.disconnect()
            } catch (e: Exception) {
                // Disconnect is best-effort; the result or error already stands.
            }
        }
    }

    /** Returns true when a live pooled session exists for [host]. */
    fun isConnected(host: String): Boolean =
        sessions.values.any { it.host == host && it.session.isConnected }

    /** Number of sessions currently in the pool. */
    fun pooledSessionCount(): Int = sessions.size

    /** Disconnects the pooled session for [host], if any. */
    fun disconnect(host: String) {
        sessions.entries.filter { it.value.host == host }.forEach { entry ->
            disconnectSession(entry.key, entry.value)
        }
    }

    /** Disconnects every pooled session and clears the pool. */
    fun close() {
        sessions.values.forEach { pooled -> disconnectQuietly(pooled.session) }
        sessions.clear()
    }

    /**
     * Evicts sessions idle for longer than [IDLE_EVICTION_MS]. Called on
     * [connect] and available to callers that hold a client for a long time.
     */
    fun evictIdleSessions(nowMs: Long = System.currentTimeMillis()) {
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.lastUsedAtMs > IDLE_EVICTION_MS) {
                iterator.remove()
                disconnectQuietly(entry.value.session)
            }
        }
    }

    private fun mapConnectException(
        host: String,
        pinned: PinnedHostKeyRepository,
        e: JSchException,
    ): SshException {
        val lower = e.message.orEmpty().lowercase()
        return when {
            pinned.lastCheckWasChanged ||
                lower.contains("hostkey has been changed") ||
                lower.contains("host key has been changed") ->
                SshException.hostKeyChanged(host, pinned.lastSeenFingerprint)
            lower.contains("unknownhostkey") || lower.contains("unknown host key") ->
                SshException.unknownHost(host, pinned.lastSeenFingerprint)
            lower.contains("auth fail") || lower.contains("authentication") ->
                SshException.authFailed(host)
            lower.contains("timeout") || lower.contains("timed out") ->
                SshException.timeout(host)
            else -> SshException.connectFailed(host, e)
        }
    }

    private fun sessionKey(host: String, port: Int, username: String): String = "$username@$host:$port"

    private fun disconnectSession(key: String, pooled: PooledSession) {
        sessions.remove(key)
        disconnectQuietly(pooled.session)
    }

    private fun disconnectQuietly(session: Session) {
        try {
            if (session.isConnected) session.disconnect()
        } catch (e: Exception) {
            // Best-effort disconnect during eviction or close.
        }
    }
}
