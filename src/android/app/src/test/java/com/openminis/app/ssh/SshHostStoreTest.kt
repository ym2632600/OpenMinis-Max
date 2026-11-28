package com.openminis.app.ssh

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Unit tests for [SshHostStore] and the [SshHost] JSON record.
 *
 * The secrets below are clearly fake marker strings, not real credentials.
 * They exist only to prove that no secret value ever appears in a logged or
 * otherwise serialized (non-encrypted) representation of a host.
 */
@RunWith(RobolectricTestRunner::class)
class SshHostStoreTest {

    private lateinit var store: SshHostStore

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("SshHostStoreTest", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = SshHostStore(prefs)
    }

    @Test
    fun jsonRoundTripPreservesEveryField() {
        val host = keyHost()
        val restored = SshHost.fromJson(host.toJson())
        assertEquals(host, restored)
    }

    @Test
    fun jsonRoundTripDefaultsPortTo22() {
        val host = keyHost()
        val json = host.toJson()
        json.remove("port")
        val restored = SshHost.fromJson(json)
        assertNotNull(restored)
        assertEquals(22, restored!!.port)
    }

    @Test
    fun saveListGetAndDeleteRoundTrip() {
        val first = store.saveHost(keyHost())
        val second = store.saveHost(passwordHost())
        assertEquals(2, store.listHosts().size)
        assertEquals(first, store.getHost(first.id))
        assertEquals(second, store.getHostByAddress("files.example.org", 2222))

        val renamed = store.saveHost(first.copy(name = "Renamed host"))
        assertEquals("Renamed host", renamed.name)
        assertEquals(2, store.listHosts().size)
        assertEquals("Renamed host", store.getHost(first.id)?.name)

        assertTrue(store.deleteHost(first.id))
        assertNull(store.getHost(first.id))
        assertEquals(1, store.listHosts().size)
        assertFalse(store.deleteHost(first.id))
    }

    @Test
    fun pinnedFingerprintComesFromStoredHost() {
        val host = store.saveHost(keyHost())
        assertEquals(
            "SHA256:FAKE-PINNED-FINGERPRINT",
            store.pinnedFingerprint("EXAMPLE.com"),
        )
        assertNull(store.pinnedFingerprint("unknown.example.org"))
        val updated = store.updateKnownHostFingerprint(host.id, "SHA256:FAKE-ROTATED-FINGERPRINT")
        assertEquals("SHA256:FAKE-ROTATED-FINGERPRINT", updated?.knownHostFingerprint)
        assertEquals("SHA256:FAKE-ROTATED-FINGERPRINT", store.pinnedFingerprint("example.com"))
    }

    @Test
    fun markConnectedRecordsTimestamp() {
        val host = store.saveHost(keyHost())
        val updated = store.markConnected(host.id, 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, updated?.lastConnected)
        assertEquals(1_700_000_000_000L, store.getHost(host.id)?.lastConnected)
    }

    @Test
    fun saveHostRejectsInvalidRecords() {
        assertThrows(IllegalArgumentException::class.java) {
            store.saveHost(keyHost().copy(name = "  "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.saveHost(keyHost().copy(host = ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.saveHost(keyHost().copy(port = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.saveHost(keyHost().copy(port = 65536))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.saveHost(keyHost().copy(privateKeyPem = null))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.saveHost(passwordHost().copy(password = null))
        }
    }

    @Test
    fun blankIdGetsGeneratedOnSave() {
        val saved = store.saveHost(keyHost().copy(id = ""))
        assertTrue(saved.id.isNotBlank())
        assertEquals(saved, store.getHost(saved.id))
    }

    @Test
    fun noSecretsAppearInLoggedOrSerializedOutput() {
        val host = keyHost()
        val logged = host.toString()
        val safeJson = host.toSafeJson().toString()
        for (secret in listOf(PRIVATE_KEY_PEM, KEY_PASSPHRASE, HOST_PASSWORD)) {
            assertFalse("logged output must not contain a secret", logged.contains(secret))
            assertFalse("safe JSON must not contain a secret", safeJson.contains(secret))
        }
        assertFalse(safeJson.contains("privateKeyPem"))
        assertFalse(safeJson.contains("keyPassphrase"))
        assertFalse(safeJson.contains("\"password\""))
        assertTrue(host.toSafeJson().getBoolean("hasPrivateKey"))
        assertTrue(host.toSafeJson().getBoolean("hasKeyPassphrase"))
        assertFalse(host.toSafeJson().getBoolean("hasPassword"))
    }

    @Test
    fun contextConstructorOpensTheEncryptedStore() {
        val context: Context = RuntimeEnvironment.getApplication()
        val contextStore = SshHostStore(context)
        val saved = contextStore.saveHost(keyHost())
        assertEquals(saved, contextStore.getHost(saved.id))
        assertEquals(
            "SHA256:FAKE-PINNED-FINGERPRINT",
            contextStore.pinnedFingerprint("example.com"),
        )
        contextStore.deleteHost(saved.id)
    }

    private fun keyHost() = SshHost(
        id = "host-1",
        name = "Build server",
        host = "example.com",
        username = "builder",
        authType = SshAuthType.KEY,
        keyLabel = "build key",
        privateKeyPem = PRIVATE_KEY_PEM,
        keyPassphrase = KEY_PASSPHRASE,
        knownHostFingerprint = "SHA256:FAKE-PINNED-FINGERPRINT",
        lastConnected = 1_700_000_000_000L,
    )

    private fun passwordHost() = SshHost(
        id = "host-2",
        name = "File server",
        host = "files.example.org",
        port = 2222,
        username = "files",
        authType = SshAuthType.PASSWORD,
        password = HOST_PASSWORD,
    )

    companion object {
        private const val PRIVATE_KEY_PEM =
            "-----BEGIN OPENSSH PRIVATE KEY-----\nFAKE-PRIVATE-KEY-MATERIAL\n-----END OPENSSH PRIVATE KEY-----"
        private const val KEY_PASSPHRASE = "FAKE-KEY-PASSPHRASE"
        private const val HOST_PASSWORD = "FAKE-HOST-PASSWORD"
    }
}
