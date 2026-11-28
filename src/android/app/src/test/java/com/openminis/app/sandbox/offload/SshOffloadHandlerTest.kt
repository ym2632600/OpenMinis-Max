package com.openminis.app.sandbox.offload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.consent.ConsentManager
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.ssh.SshAuthType
import com.openminis.app.ssh.SshException
import com.openminis.app.ssh.SshHost
import com.openminis.app.ssh.SshResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SshOffloadHandler] — arg parsing, the hosts list (with no
 * secrets in it), test/exec consent and the structured error JSON shapes,
 * and timeout validation. No live server: a [FakeSshSystem] stands in for
 * the store, consent and client. Mirrors [CallOffloadHandlerTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SshOffloadHandlerTest {

    private lateinit var context: Context
    private lateinit var fake: FakeSshSystem
    private lateinit var handler: SshOffloadHandler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        OffloadPermissionManager.init(context)
        OffloadPermissionManager.setLevel("ssh", OffloadPermissionManager.PermissionLevel.BYPASS)
        OffloadPermissionManager.setLevel("ssh_exec", OffloadPermissionManager.PermissionLevel.BYPASS)
        fake = FakeSshSystem()
        handler = SshOffloadHandler(context, fake)
    }

    @After
    fun tearDown() {
        OffloadPermissionManager.resetAll()
    }

    private fun request(vararg args: String) =
        NativeOffloadRequest(
            pid = 1,
            argv = listOf("android-ssh") + args,
            env = emptyMap(),
            cwd = "/",
            sessionId = null,
        )

    private fun errorOf(result: NativeOffloadResult): JSONObject = JSONObject(result.output)

    private fun keyHost(lastConnected: Long? = 1_700_000_000_000L) = SshHost(
        id = "host-1",
        name = "prod",
        host = "example.com",
        port = 2222,
        username = "deploy",
        authType = SshAuthType.KEY,
        keyLabel = "id_ed25519",
        privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nFAKE-KEY-BYTES\n-----END OPENSSH PRIVATE KEY-----",
        keyPassphrase = null,
        password = null,
        knownHostFingerprint = "SHA256:abc",
        lastConnected = lastConnected,
    )

    private fun passwordHost() = SshHost(
        id = "host-2",
        name = "db",
        host = "db.example.com",
        port = 22,
        username = "admin",
        authType = SshAuthType.PASSWORD,
        keyLabel = null,
        privateKeyPem = null,
        keyPassphrase = null,
        password = "fake-test-password",
        knownHostFingerprint = "SHA256:def",
        lastConnected = null,
    )

    @Test
    fun helpReturnsUsage() {
        val result = handler.handle(request("--help"))
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("android-ssh hosts"))
        assertTrue(result.output.contains("android-ssh exec"))
    }

    @Test
    fun unknownSubcommandReturnsUsage() {
        val result = handler.handle(request("frobnicate"))
        assertEquals(2, result.exitCode)
        assertTrue(result.output.contains("unknown subcommand"))
    }

    @Test
    fun hostsListsConfiguredHostsWithoutSecrets() {
        fake.hosts = listOf(keyHost(), passwordHost())
        val result = handler.handle(request("hosts"))
        assertEquals(0, result.exitCode)
        val arr = JSONArray(result.output)
        assertEquals(2, arr.length())
        val prod = arr.getJSONObject(0)
        assertEquals("prod", prod.getString("name"))
        assertEquals("deploy@example.com:2222", prod.getString("address"))
        assertEquals("key", prod.getString("auth_type"))
        assertEquals(1_700_000_000_000L, prod.getLong("last_connected"))
        val db = arr.getJSONObject(1)
        assertEquals("db", db.getString("name"))
        assertEquals("admin@db.example.com:22", db.getString("address"))
        assertEquals("password", db.getString("auth_type"))
        assertTrue(db.isNull("last_connected"))
        assertFalse(result.output.contains("FAKE-KEY-BYTES"))
        assertFalse(result.output.contains("fake-test-password"))
        assertFalse(result.output.contains("SHA256:"))
    }

    @Test
    fun hostsEmptyReturnsEmptyArray() {
        val result = handler.handle(request("hosts"))
        assertEquals(0, result.exitCode)
        assertEquals(0, JSONArray(result.output).length())
    }

    @Test
    fun hostsCompactPrintsSingleLineJson() {
        fake.hosts = listOf(keyHost())
        val result = handler.handle(request("hosts", "--compact"))
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("\"name\":\"prod\""))
    }

    @Test
    fun testReturnsOkForKeyHost() {
        fake.hosts = listOf(keyHost())
        val result = handler.handle(request("test", "prod"))
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output)
        assertEquals("prod", json.getString("host"))
        assertEquals("key", json.getString("auth"))
        assertTrue(json.getBoolean("ok"))
        assertTrue(json.isNull("server_version"))
        assertEquals("echo ok", fake.lastCommand)
        assertEquals(1, fake.connectCalls)
        assertEquals(1, fake.markConnectedCalls)
    }

    @Test
    fun testReturnsPasswordAuth() {
        fake.hosts = listOf(passwordHost())
        val result = handler.handle(request("test", "db"))
        assertEquals(0, result.exitCode)
        assertEquals("password", JSONObject(result.output).getString("auth"))
    }

    @Test
    fun testUnknownHostReturnsSshNoHost() {
        fake.hosts = listOf(keyHost())
        val result = handler.handle(request("test", "missing"))
        assertEquals(1, result.exitCode)
        assertEquals("ssh_no_host", errorOf(result).getString("error"))
        assertEquals(0, fake.connectCalls)
    }

    @Test
    fun testConsentDeniedReturns77AndDoesNotConnect() {
        fake.hosts = listOf(keyHost())
        fake.consent = ConsentManager.ConsentOutcome.Denied
        val result = handler.handle(request("test", "prod"))
        assertEquals(77, result.exitCode)
        assertEquals("consent_denied", errorOf(result).getString("error"))
        assertEquals(0, fake.connectCalls)
    }

    @Test
    fun testConsentTimeoutReturns77AndDoesNotConnect() {
        fake.hosts = listOf(keyHost())
        fake.consent = ConsentManager.ConsentOutcome.TimedOut
        val result = handler.handle(request("test", "prod"))
        assertEquals(77, result.exitCode)
        assertEquals("consent_timeout", errorOf(result).getString("error"))
        assertEquals(0, fake.connectCalls)
    }

    @Test
    fun testUnknownHostKeyReturnsFingerprintAndSettingsHint() {
        fake.hosts = listOf(keyHost())
        fake.connectError = SshException.unknownHost("example.com", "SHA256:xyz")
        val result = handler.handle(request("test", "prod"))
        assertEquals(1, result.exitCode)
        val json = errorOf(result)
        assertEquals("ssh_unknown_host", json.getString("error"))
        assertEquals("SHA256:xyz", json.getString("fingerprint"))
        assertTrue(json.getString("message").contains("SHA256:xyz"))
        assertTrue(json.getString("message").contains("Settings"))
    }

    @Test
    fun testHostKeyChangedReturnsError() {
        fake.hosts = listOf(keyHost())
        fake.connectError = SshException.hostKeyChanged("example.com", "SHA256:changed")
        val result = handler.handle(request("test", "prod"))
        assertEquals(1, result.exitCode)
        assertEquals("ssh_host_key_changed", errorOf(result).getString("error"))
    }

    @Test
    fun testAuthFailedReturnsError() {
        fake.hosts = listOf(keyHost())
        fake.connectError = SshException.authFailed("example.com")
        val result = handler.handle(request("test", "prod"))
        assertEquals(1, result.exitCode)
        assertEquals("ssh_auth_failed", errorOf(result).getString("error"))
    }

    @Test
    fun testConnectFailedReturnsError() {
        fake.hosts = listOf(keyHost())
        fake.connectError = SshException.connectFailed("example.com")
        val result = handler.handle(request("test", "prod"))
        assertEquals(1, result.exitCode)
        assertEquals("ssh_connect_failed", errorOf(result).getString("error"))
    }

    @Test
    fun testConnectTimeoutReturnsError() {
        fake.hosts = listOf(keyHost())
        fake.connectError = SshException.timeout("example.com")
        val result = handler.handle(request("test", "prod"))
        assertEquals(1, result.exitCode)
        assertEquals("ssh_timeout", errorOf(result).getString("error"))
    }

    @Test
    fun execReturnsStdoutStderrExitCodeAndTruncated() {
        fake.hosts = listOf(keyHost())
        fake.execResult = SshResult(stdout = "hello\n", stderr = "warn\n", exitCode = 3, truncated = true)
        val result = handler.handle(request("exec", "prod", "echo", "hi"))
        assertEquals(0, result.exitCode)
        val json = JSONObject(result.output)
        assertEquals("hello\n", json.getString("stdout"))
        assertEquals("warn\n", json.getString("stderr"))
        assertEquals(3, json.getInt("exit_code"))
        assertTrue(json.getBoolean("truncated"))
        assertEquals("echo hi", fake.lastCommand)
        assertEquals(1, fake.markConnectedCalls)
    }

    @Test
    fun execKeepsCommandFlags() {
        fake.hosts = listOf(keyHost())
        val result = handler.handle(request("exec", "prod", "ls", "-la", "/tmp"))
        assertEquals(0, result.exitCode)
        assertEquals("ls -la /tmp", fake.lastCommand)
    }

    @Test
    fun execMissingCommandReturnsUsage() {
        fake.hosts = listOf(keyHost())
        val result = handler.handle(request("exec", "prod"))
        assertEquals(2, result.exitCode)
        assertEquals(0, fake.connectCalls)
    }

    @Test
    fun execMissingHostReturnsUsage() {
        val result = handler.handle(request("exec"))
        assertEquals(2, result.exitCode)
        assertEquals(0, fake.connectCalls)
    }

    @Test
    fun execRejectsAdHocUserAtHost() {
        fake.hosts = listOf(keyHost())
        val result = handler.handle(request("exec", "user@example.com", "ls"))
        assertEquals(1, result.exitCode)
        assertEquals("ssh_no_host", errorOf(result).getString("error"))
        assertEquals(0, fake.connectCalls)
    }

    @Test
    fun execTimeoutValidationReturnsUsage() {
        fake.hosts = listOf(keyHost())
        assertEquals(2, handler.handle(request("exec", "prod", "--timeout", "0", "ls")).exitCode)
        assertEquals(2, handler.handle(request("exec", "prod", "--timeout", "601", "ls")).exitCode)
        assertEquals(2, handler.handle(request("exec", "prod", "--timeout", "abc", "ls")).exitCode)
        assertEquals(2, handler.handle(request("test", "prod", "--timeout", "601")).exitCode)
        assertEquals(0, fake.connectCalls)
    }

    @Test
    fun execTimeoutIsPassedThrough() {
        fake.hosts = listOf(keyHost())
        assertEquals(0, handler.handle(request("test", "prod", "--timeout", "45")).exitCode)
        assertEquals(45, fake.lastTimeoutSeconds)
        assertEquals(0, handler.handle(request("exec", "prod", "--timeout", "30", "ls")).exitCode)
        assertEquals(30, fake.lastTimeoutSeconds)
    }

    @Test
    fun execTimeoutErrorReturnsExecTimeout() {
        fake.hosts = listOf(keyHost())
        fake.execError = SshException.execTimeout("example.com")
        val result = handler.handle(request("exec", "prod", "ls"))
        assertEquals(1, result.exitCode)
        assertEquals("ssh_exec_timeout", errorOf(result).getString("error"))
    }

    @Test
    fun gateDeniedForExecReturns126() {
        OffloadPermissionManager.setLevel("ssh_exec", OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
        val result = handler.handle(request("exec", "prod", "ls"))
        assertEquals(126, result.exitCode)
        assertTrue(result.output.contains("permission_denied"))
        assertEquals(0, fake.connectCalls)
    }

    @Test
    fun gateDeniedForHostsReturns126() {
        OffloadPermissionManager.setLevel("ssh", OffloadPermissionManager.PermissionLevel.NOT_ALLOWED)
        val result = handler.handle(request("hosts"))
        assertEquals(126, result.exitCode)
        assertTrue(result.output.contains("permission_denied"))
    }
}

/** Fake [SshSystem]: no store, consent dialog or live server. */
private class FakeSshSystem(
    var hosts: List<SshHost> = emptyList(),
    var consent: ConsentManager.ConsentOutcome = ConsentManager.ConsentOutcome.Approved,
    var execResult: SshResult = SshResult(stdout = "ok\n", stderr = "", exitCode = 0, truncated = false),
    var connectError: SshException? = null,
    var execError: SshException? = null,
) : SshSystem {

    var lastCommand: String? = null
    var lastTimeoutSeconds: Int? = null
    var connectCalls = 0
    var markConnectedCalls = 0

    override fun listHosts(): List<SshHost> = hosts

    override fun findHost(name: String): SshHost? =
        hosts.firstOrNull { it.name.equals(name, ignoreCase = true) }

    override fun requestConsent(hostName: String, command: String): ConsentManager.ConsentOutcome = consent

    override fun connect(host: SshHost) {
        connectCalls++
        connectError?.let { throw it }
    }

    override fun exec(host: SshHost, command: String, timeoutSeconds: Int): SshResult {
        lastCommand = command
        lastTimeoutSeconds = timeoutSeconds
        execError?.let { throw it }
        return execResult
    }

    override fun markConnected(host: SshHost) {
        markConnectedCalls++
    }
}
