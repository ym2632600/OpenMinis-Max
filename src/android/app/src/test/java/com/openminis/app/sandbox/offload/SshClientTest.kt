package com.openminis.app.sandbox.offload

import com.jcraft.jsch.HostKeyRepository as JSchHostKeyRepository
import com.openminis.app.ssh.CappedOutputStream
import com.openminis.app.ssh.HostKeyRepository
import com.openminis.app.ssh.OutputCap
import com.openminis.app.ssh.PinnedHostKeyRepository
import com.openminis.app.ssh.SshClient
import com.openminis.app.ssh.SshErrorCode
import com.openminis.app.ssh.SshException
import com.openminis.app.ssh.SshResult
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [SshClient] (B9). No live SSH server is used: these tests
 * cover fingerprint formatting, output truncation, timeout defaults and
 * error shapes only. The pooled-session and `exec` paths that need a server
 * are exercised through the no-connection failure shape below.
 */
@RunWith(RobolectricTestRunner::class)
class SshClientTest {

    private lateinit var client: SshClient

    private val keyBytes = "test-host-key".toByteArray(Charsets.UTF_8)
    private val fingerprint = SshClient.formatFingerprint(keyBytes)

    @Before
    fun setUp() {
        client = SshClient(FakeHostKeyRepository(null))
    }

    @After
    fun tearDown() {
        client.close()
    }

    @Test
    fun fingerprintFormatting_isStableAndPrefixed() {
        assertEquals(fingerprint, SshClient.formatFingerprint(keyBytes))
        assertTrue(fingerprint.startsWith("SHA256:"))
        // SHA-256 is 32 bytes, so the unpadded Base64 body is 43 characters.
        assertEquals(50, fingerprint.length)
        assertFalse(fingerprint.contains("="))
    }

    @Test
    fun fingerprintFormatting_differentKeysProduceDifferentFingerprints() {
        val other = SshClient.formatFingerprint("other-host-key".toByteArray(Charsets.UTF_8))
        assertFalse(other == fingerprint)
        assertTrue(other.startsWith("SHA256:"))
    }

    @Test
    fun normalizeFingerprint_addsPrefixWhenMissing() {
        assertEquals("SHA256:abc123", SshClient.normalizeFingerprint("abc123"))
        assertEquals("SHA256:abc123", SshClient.normalizeFingerprint("SHA256:abc123"))
        assertEquals("SHA256:abc123", SshClient.normalizeFingerprint("  SHA256:abc123  "))
    }

    @Test
    fun truncateOutputs_keepsSmallOutputsUntruncated() {
        val (stdout, stderr, truncated) = SshClient.truncateOutputs(
            "hello".toByteArray(Charsets.UTF_8),
            "warn".toByteArray(Charsets.UTF_8),
        )
        assertFalse(truncated)
        assertEquals("hello", stdout.toString(Charsets.UTF_8))
        assertEquals("warn", stderr.toString(Charsets.UTF_8))
    }

    @Test
    fun truncateOutputs_capsSingleLargeStreamAt256K() {
        val (stdout, stderr, truncated) = SshClient.truncateOutputs(
            ByteArray(300 * 1024) { 'a'.code.toByte() },
            ByteArray(0),
        )
        assertTrue(truncated)
        assertEquals(SshClient.MAX_OUTPUT_BYTES, stdout.size)
        assertEquals(0, stderr.size)
    }

    @Test
    fun truncateOutputs_capsTotalAcrossStdoutAndStderr() {
        val (stdout, stderr, truncated) = SshClient.truncateOutputs(
            ByteArray(200 * 1024) { 'a'.code.toByte() },
            ByteArray(100 * 1024) { 'b'.code.toByte() },
        )
        assertTrue(truncated)
        assertEquals(200 * 1024, stdout.size)
        assertEquals(SshClient.MAX_OUTPUT_BYTES - 200 * 1024, stderr.size)
        assertEquals(SshClient.MAX_OUTPUT_BYTES, stdout.size + stderr.size)
    }

    @Test
    fun cappedOutputStream_enforcesTotalCapAcrossBothStreams() {
        val cap = OutputCap()
        val stdoutSink = ByteArrayOutputStream()
        val stderrSink = ByteArrayOutputStream()
        CappedOutputStream(cap, stdoutSink).write(ByteArray(200 * 1024) { 'a'.code.toByte() })
        CappedOutputStream(cap, stderrSink).write(ByteArray(100 * 1024) { 'b'.code.toByte() })
        assertTrue(cap.truncated)
        assertEquals(SshClient.MAX_OUTPUT_BYTES, stdoutSink.size() + stderrSink.size())
        assertEquals(200 * 1024, stdoutSink.size())
        assertEquals(SshClient.MAX_OUTPUT_BYTES - 200 * 1024, stderrSink.size())
    }

    @Test
    fun timeoutDefaults_are120SecondsWithMax600() {
        assertEquals(120, SshClient.DEFAULT_EXEC_TIMEOUT_SECONDS)
        assertEquals(600, SshClient.MAX_EXEC_TIMEOUT_SECONDS)
        assertEquals(120, SshClient.validateTimeout(120))
        assertEquals(600, SshClient.validateTimeout(600))
    }

    @Test
    fun timeoutValidation_rejectsAboveMaxAndZero() {
        try {
            SshClient.validateTimeout(601)
            fail("Expected IllegalArgumentException for a timeout above 600")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("600"))
        }
        try {
            SshClient.validateTimeout(0)
            fail("Expected IllegalArgumentException for a zero timeout")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("timeoutSeconds"))
        }
    }

    @Test
    fun connectTimeout_is15Seconds() {
        assertEquals(15_000, SshClient.CONNECT_TIMEOUT_MS)
    }

    @Test
    fun sessionPoolLimits_areFourSessionsAndFiveMinuteIdleEviction() {
        assertEquals(4, SshClient.MAX_SESSIONS)
        assertEquals(5 * 60 * 1000L, SshClient.IDLE_EVICTION_MS)
        assertEquals(256 * 1024, SshClient.MAX_OUTPUT_BYTES)
    }

    @Test
    fun errorCodes_haveExpectedStringValues() {
        assertEquals("ssh_unknown_host", SshErrorCode.UNKNOWN_HOST.code)
        assertEquals("ssh_host_key_changed", SshErrorCode.HOST_KEY_CHANGED.code)
        assertEquals("ssh_auth_failed", SshErrorCode.AUTH_FAILED.code)
        assertEquals("ssh_connect_failed", SshErrorCode.CONNECT_FAILED.code)
        assertEquals("ssh_timeout", SshErrorCode.TIMEOUT.code)
        assertEquals("ssh_exec_timeout", SshErrorCode.EXEC_TIMEOUT.code)
    }

    @Test
    fun unknownHostError_includesFingerprint() {
        val error = SshException.unknownHost("example.com", "SHA256:abc123")
        assertEquals(SshErrorCode.UNKNOWN_HOST, error.errorCode)
        assertTrue(error.message.orEmpty().contains("ssh_unknown_host"))
        assertTrue(error.message.orEmpty().contains("example.com"))
        assertTrue(error.message.orEmpty().contains("SHA256:abc123"))
    }

    @Test
    fun hostKeyChangedError_isAHardFailShape() {
        val error = SshException.hostKeyChanged("example.com", "SHA256:xyz")
        assertEquals(SshErrorCode.HOST_KEY_CHANGED, error.errorCode)
        assertTrue(error.message.orEmpty().contains("ssh_host_key_changed"))
        assertTrue(error.message.orEmpty().contains("SHA256:xyz"))
    }

    @Test
    fun pinnedRepository_returnsOkForPinnedFingerprint() {
        val repository = PinnedHostKeyRepository(FakeHostKeyRepository(fingerprint))
        assertEquals(JSchHostKeyRepository.OK, repository.check("example.com", keyBytes))
        assertEquals(fingerprint, repository.lastSeenFingerprint)
        assertFalse(repository.lastCheckWasChanged)
    }

    @Test
    fun pinnedRepository_returnsNotIncludedWhenNoPin() {
        val repository = PinnedHostKeyRepository(FakeHostKeyRepository(null))
        assertEquals(JSchHostKeyRepository.NOT_INCLUDED, repository.check("example.com", keyBytes))
        assertEquals(fingerprint, repository.lastSeenFingerprint)
    }

    @Test
    fun pinnedRepository_returnsChangedWhenFingerprintDiffers() {
        val other = SshClient.formatFingerprint("other-host-key".toByteArray(Charsets.UTF_8))
        val repository = PinnedHostKeyRepository(FakeHostKeyRepository(other))
        assertEquals(JSchHostKeyRepository.CHANGED, repository.check("example.com", keyBytes))
        assertTrue(repository.lastCheckWasChanged)
    }

    @Test
    fun execWithoutConnection_failsWithConnectFailed() {
        try {
            client.exec("echo hi")
            fail("Expected SshException for exec with no session")
        } catch (e: SshException) {
            assertEquals(SshErrorCode.CONNECT_FAILED, e.errorCode)
            assertTrue(e.message.orEmpty().contains("ssh_connect_failed"))
        }
    }

    @Test
    fun execForUnknownHost_failsWithConnectFailed() {
        try {
            client.exec("example.com", "echo hi")
            fail("Expected SshException for exec with no session for the host")
        } catch (e: SshException) {
            assertEquals(SshErrorCode.CONNECT_FAILED, e.errorCode)
            assertTrue(e.message.orEmpty().contains("ssh_connect_failed"))
        }
    }

    @Test
    fun emptyClient_hasNoSessionsAndCloseIsSafe() {
        assertEquals(0, client.pooledSessionCount())
        assertFalse(client.isConnected("example.com"))
        client.evictIdleSessions()
        client.close()
        assertEquals(0, client.pooledSessionCount())
    }

    @Test
    fun sshResult_holdsOutputExitCodeAndTruncationFlag() {
        val result = SshResult(stdout = "out", stderr = "err", exitCode = 0, truncated = true)
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
        assertEquals(0, result.exitCode)
        assertTrue(result.truncated)
    }

    private class FakeHostKeyRepository(private val pinned: String?) : HostKeyRepository {
        override fun pinnedFingerprint(host: String): String? = pinned
    }
}
