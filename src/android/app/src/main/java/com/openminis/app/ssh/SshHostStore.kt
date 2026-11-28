package com.openminis.app.ssh

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.EncryptedPrefsFactory
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * How an [SshHost] authenticates to its server.
 *
 * Stored as the plain strings "key" and "password" in the JSON record.
 */
enum class SshAuthType(val value: String) {
    KEY("key"),
    PASSWORD("password");

    companion object {
        fun fromValue(value: String?): SshAuthType? =
            values().firstOrNull { it.value == value }
    }
}

/**
 * One saved SSH host (B9, built-in SSH client).
 *
 * The record is stored as JSON inside EncryptedSharedPreferences (see
 * [SshHostStore]); the private key is kept as PEM text and is never written
 * to a file, never logged, and never returned in any output.
 */
data class SshHost(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType = SshAuthType.KEY,
    val keyLabel: String? = null,
    val privateKeyPem: String? = null,
    val keyPassphrase: String? = null,
    val password: String? = null,
    val knownHostFingerprint: String? = null,
    val lastConnected: Long? = null,
) {
    /**
     * Full JSON record, including secrets. This is only ever written to the
     * encrypted store — never log it, and never return it from a public API.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("authType", authType.value)
        if (keyLabel != null) put("keyLabel", keyLabel)
        if (privateKeyPem != null) put("privateKeyPem", privateKeyPem)
        if (keyPassphrase != null) put("keyPassphrase", keyPassphrase)
        if (password != null) put("password", password)
        if (knownHostFingerprint != null) put("knownHostFingerprint", knownHostFingerprint)
        if (lastConnected != null) put("lastConnected", lastConnected)
    }

    /**
     * Log-safe JSON: the same record with every secret replaced by a
     * presence flag. Use this (or [toString]) for any log line or UI text.
     */
    fun toSafeJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("authType", authType.value)
        if (keyLabel != null) put("keyLabel", keyLabel)
        put("hasPrivateKey", privateKeyPem != null)
        put("hasKeyPassphrase", keyPassphrase != null)
        put("hasPassword", password != null)
        if (knownHostFingerprint != null) put("knownHostFingerprint", knownHostFingerprint)
        if (lastConnected != null) put("lastConnected", lastConnected)
    }

    /**
     * Redacted: the default data-class toString() would print the private
     * key PEM, the key passphrase and the password, so logging a host must
     * never leak them.
     */
    override fun toString(): String = toSafeJson().toString()

    companion object {
        /**
         * Parses a record produced by [toJson]. Returns null for a record
         * that is missing a required field or has an invalid port or
         * auth type, so one corrupt record cannot break the whole store.
         */
        fun fromJson(json: JSONObject): SshHost? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val name = json.optString("name").takeIf { it.isNotBlank() } ?: return null
            val host = json.optString("host").takeIf { it.isNotBlank() } ?: return null
            val username = json.optString("username").takeIf { it.isNotBlank() } ?: return null
            val port = json.optInt("port", 22)
            if (port !in 1..65535) return null
            val authType = SshAuthType.fromValue(
                json.optString("authType").takeIf { it.isNotBlank() },
            ) ?: return null
            return SshHost(
                id = id,
                name = name,
                host = host,
                port = port,
                username = username,
                authType = authType,
                keyLabel = json.optString("keyLabel").takeIf { it.isNotEmpty() },
                privateKeyPem = json.optString("privateKeyPem").takeIf { it.isNotEmpty() },
                keyPassphrase = json.optString("keyPassphrase").takeIf { it.isNotEmpty() },
                password = json.optString("password").takeIf { it.isNotEmpty() },
                knownHostFingerprint = json.optString("knownHostFingerprint").takeIf { it.isNotEmpty() },
                lastConnected = json.optLong("lastConnected", -1L).takeIf { it >= 0 },
            )
        }
    }
}

/**
 * CRUD store for saved SSH hosts (B9), backed by one EncryptedSharedPreferences
 * file holding a JSON list of [SshHost] records.
 *
 * Private keys are stored as PEM text inside the encrypted preferences only:
 * they are never written to a file, never logged, and never appear in
 * [SshHost.toString] or [SshHost.toSafeJson]. The store also implements
 * [HostKeyRepository], so [SshClient] can look up the one SHA-256 fingerprint
 * pinned for a host (trust-on-first-use).
 *
 * The preferences file is excluded from system backup and device transfer
 * (see backup_rules.xml and data_extraction_rules.xml): the encrypted values
 * could not be decrypted after a restore, and the plain-text fallback must
 * never leave the device.
 */
class SshHostStore(private val prefs: SharedPreferences) : HostKeyRepository {

    /** Opens the encrypted host store via [EncryptedPrefsFactory]. */
    constructor(context: Context) : this(
        EncryptedPrefsFactory.safeCreate(context, PREFS_FILE),
    )

    private val lock = Any()

    /** All saved hosts, in the order they were saved. */
    fun listHosts(): List<SshHost> = synchronized(lock) { readHostsLocked() }

    fun getHost(id: String): SshHost? = synchronized(lock) {
        readHostsLocked().firstOrNull { it.id == id }
    }

    fun getHostByAddress(host: String, port: Int = 22): SshHost? = synchronized(lock) {
        readHostsLocked().firstOrNull {
            it.host.equals(host, ignoreCase = true) && it.port == port
        }
    }

    /**
     * Inserts a new host, or replaces the host with the same id. A blank id
     * is replaced with a generated one; the stored record is returned.
     *
     * @throws IllegalArgumentException for a blank name, host or username,
     * a port outside 1..65535, a key host without a private key, or a
     * password host without a password.
     */
    fun saveHost(host: SshHost): SshHost {
        require(host.name.isNotBlank()) { "SSH host name must not be blank" }
        require(host.host.isNotBlank()) { "SSH host address must not be blank" }
        require(host.username.isNotBlank()) { "SSH username must not be blank" }
        require(host.port in 1..65535) { "SSH port must be between 1 and 65535" }
        if (host.authType == SshAuthType.KEY) {
            require(!host.privateKeyPem.isNullOrBlank()) {
                "Key authentication requires a private key"
            }
        } else {
            require(!host.password.isNullOrBlank()) {
                "Password authentication requires a password"
            }
        }
        val stored = if (host.id.isBlank()) {
            host.copy(id = UUID.randomUUID().toString())
        } else {
            host
        }
        synchronized(lock) { saveHostLocked(stored) }
        return stored
    }

    /** Removes the host with [id]. Returns false when no such host existed. */
    fun deleteHost(id: String): Boolean {
        synchronized(lock) {
            val hosts = readHostsLocked()
            val remaining = hosts.filterNot { it.id == id }
            if (remaining.size == hosts.size) return false
            writeHostsLocked(remaining)
            return true
        }
    }

    /**
     * Pins the SHA-256 host-key fingerprint for a host (trust on first use).
     * Returns the updated host, or null when no host with [id] exists.
     */
    fun updateKnownHostFingerprint(id: String, fingerprint: String): SshHost? {
        val pinned = fingerprint.trim()
        require(pinned.isNotBlank()) { "Known-host fingerprint must not be blank" }
        synchronized(lock) {
            val host = readHostsLocked().firstOrNull { it.id == id } ?: return null
            return saveHostLocked(host.copy(knownHostFingerprint = pinned))
        }
    }

    /**
     * Records a successful connection. Returns the updated host, or null
     * when no host with [id] exists.
     */
    fun markConnected(id: String, connectedAtMillis: Long = System.currentTimeMillis()): SshHost? {
        synchronized(lock) {
            val host = readHostsLocked().firstOrNull { it.id == id } ?: return null
            return saveHostLocked(host.copy(lastConnected = connectedAtMillis))
        }
    }

    /** The one pinned SHA-256 fingerprint for [host], if the host is saved. */
    override fun pinnedFingerprint(host: String): String? = synchronized(lock) {
        readHostsLocked()
            .firstOrNull {
                it.host.equals(host, ignoreCase = true) && it.knownHostFingerprint != null
            }
            ?.knownHostFingerprint
    }

    private fun saveHostLocked(host: SshHost): SshHost {
        val hosts = readHostsLocked().filterNot { it.id == host.id } + host
        writeHostsLocked(hosts)
        return host
    }

    private fun readHostsLocked(): List<SshHost> {
        val raw = prefs.getString(KEY_HOSTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val hosts = ArrayList<SshHost>(array.length())
            var skipped = 0
            for (i in 0 until array.length()) {
                val parsed = runCatching { SshHost.fromJson(array.getJSONObject(i)) }.getOrNull()
                if (parsed == null) skipped++ else hosts.add(parsed)
            }
            if (skipped > 0) {
                AppLogger.warning(TAG, "Skipped $skipped unparseable stored SSH host record(s)")
            }
            hosts
        } catch (e: Exception) {
            AppLogger.warning(
                TAG,
                "Stored SSH hosts are unreadable (${e.javaClass.simpleName}); returning no hosts",
            )
            emptyList()
        }
    }

    private fun writeHostsLocked(hosts: List<SshHost>) {
        val array = JSONArray()
        hosts.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_HOSTS, array.toString()).apply()
    }

    companion object {
        /** EncryptedSharedPreferences file name ("ssh_hosts"). */
        const val PREFS_FILE = "ssh_hosts"
        private const val KEY_HOSTS = "hosts"
        private const val TAG = "SshHostStore"
    }
}
