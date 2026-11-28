package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ssh.SshAuthType
import com.openminis.app.ssh.SshClient
import com.openminis.app.ssh.SshErrorCode
import com.openminis.app.ssh.SshException
import com.openminis.app.ssh.SshHost
import com.openminis.app.ssh.SshHostStore
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B9: SSH hosts settings screen (commit 5). Follows the Ollama setup pattern
 * (B15): [SettingsScaffold] + [SettingsSection] + [SettingsCardBlock] with
 * [RowLabel] / [SectionTextField] fields and a connection test whose result
 * gates saving, like the Ollama test gates its save.
 *
 * - Host list: name, `user@host:port`, auth badge ([SshAuthType.value]),
 *   pinned fingerprint (trust state) and last-connected date. Tap a host to
 *   load it into the form below.
 * - Test reuses the [SshClient] test path: connect + authenticate, then run
 *   `echo ok` and show its output. [SshClient] does not expose the SSH server
 *   banner (the offload handler records `server_version` as null in v1), so
 *   the screen shows the `echo ok` result instead of inventing a version.
 * - Trust: [SshClient] pins by host name from the store, so trusting an
 *   observed fingerprint persists it (via [SshHostStore]) and the test is
 *   re-run. For a new host that save happens at trust time; the Save button
 *   itself stays disabled until a test passes.
 * - Forget clears the pinned fingerprint with [SshHostStore.saveHost] and
 *   drops the pooled session, so the next test reports the key as unknown.
 * - PuTTY `.ppk` keys are rejected before any network call; see
 *   [PPK_NOT_SUPPORTED_MESSAGE].
 *
 * User-facing text reuses the `ssh_*` strings added in commit 1 (plus the
 * pre-existing generic `delete` / `cancel` strings). Three labels have no
 * string in commit 1, so they are the only hard-coded English labels here
 * (see [SSH_HOSTS_TITLE], [FORGET_HOST_KEY_LABEL] and
 * [PPK_NOT_SUPPORTED_MESSAGE]); a follow-up can move them to
 * `values/strings.xml` and `values-ar/strings.xml`. No new strings are
 * added in this commit.
 */
private const val SSH_HOSTS_TITLE = "SSH hosts"
private const val FORGET_HOST_KEY_LABEL = "Forget host key"
private const val PPK_NOT_SUPPORTED_MESSAGE =
    "PuTTY .ppk keys are not supported. Convert the key to OpenSSH format and paste it again."

@Composable
fun SshHostsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { SshHostStore(context) }
    val client = remember(store) { SshClient(store) }
    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    var hosts by remember { mutableStateOf<List<SshHost>>(emptyList()) }
    var editingHost by remember { mutableStateOf<SshHost?>(null) }
    var name by remember { mutableStateOf("") }
    var hostAddress by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf(SshClient.DEFAULT_SSH_PORT.toString()) }
    var username by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf(SshAuthType.KEY) }
    var privateKeyText by remember { mutableStateOf("") }
    var passphraseText by remember { mutableStateOf("") }
    var passwordText by remember { mutableStateOf("") }
    var trustedFingerprint by remember { mutableStateOf<String?>(null) }
    var pendingFingerprint by remember { mutableStateOf<String?>(null) }
    var pendingHostKeyChanged by remember { mutableStateOf(false) }
    var testPassed by remember { mutableStateOf(false) }
    var testRunning by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        hosts = withContext(Dispatchers.IO) { store.listHosts() }
    }

    fun refreshHosts() {
        scope.launch {
            hosts = withContext(Dispatchers.IO) { store.listHosts() }
        }
    }

    fun resetTest() {
        testPassed = false
        testResult = null
        pendingFingerprint = null
        pendingHostKeyChanged = false
    }

    fun clearForm() {
        editingHost = null
        name = ""
        hostAddress = ""
        portText = SshClient.DEFAULT_SSH_PORT.toString()
        username = ""
        authType = SshAuthType.KEY
        privateKeyText = ""
        passphraseText = ""
        passwordText = ""
        trustedFingerprint = null
        pendingFingerprint = null
        pendingHostKeyChanged = false
        testPassed = false
        testRunning = false
        testResult = null
        showDeleteConfirm = false
    }

    fun loadHost(host: SshHost) {
        editingHost = host
        name = host.name
        hostAddress = host.host
        portText = host.port.toString()
        username = host.username
        authType = host.authType
        privateKeyText = host.privateKeyPem ?: ""
        passphraseText = host.keyPassphrase ?: ""
        passwordText = host.password ?: ""
        trustedFingerprint = host.knownHostFingerprint
        pendingFingerprint = null
        pendingHostKeyChanged = false
        testPassed = false
        testRunning = false
        testResult = null
        showDeleteConfirm = false
    }

    fun buildDraft(fingerprint: String?): SshHost = SshHost(
        id = editingHost?.id ?: "",
        name = name.trim(),
        host = hostAddress.trim(),
        port = portText.toIntOrNull() ?: SshClient.DEFAULT_SSH_PORT,
        username = username.trim(),
        authType = authType,
        privateKeyPem = if (authType == SshAuthType.KEY) privateKeyText.ifBlank { null } else null,
        keyPassphrase = if (authType == SshAuthType.KEY) passphraseText.ifBlank { null } else null,
        password = if (authType == SshAuthType.PASSWORD) passwordText.ifBlank { null } else null,
        knownHostFingerprint = fingerprint,
        lastConnected = editingHost?.lastConnected,
    )

    fun errorMessage(outcome: SshTestOutcome, host: SshHost): String = when (outcome.errorCode) {
        SshErrorCode.UNKNOWN_HOST ->
            context.getString(R.string.ssh_unknown_host, outcome.fingerprint ?: "")
        SshErrorCode.HOST_KEY_CHANGED ->
            context.getString(R.string.ssh_host_key_changed, host.host)
        SshErrorCode.AUTH_FAILED -> context.getString(R.string.ssh_auth_failed)
        SshErrorCode.CONNECT_FAILED ->
            context.getString(R.string.ssh_connect_failed, host.host)
        SshErrorCode.TIMEOUT -> context.getString(R.string.ssh_timeout)
        SshErrorCode.EXEC_TIMEOUT -> context.getString(R.string.ssh_exec_timeout)
        null -> outcome.output ?: context.getString(R.string.ssh_connect_failed, host.host)
    }

    fun startTest(draft: SshHost) {
        if (testRunning) return
        scope.launch {
            testRunning = true
            testResult = null
            pendingFingerprint = null
            pendingHostKeyChanged = false
            testPassed = false
            val outcome = testSshHost(client, draft)
            testRunning = false
            testPassed = outcome.passed
            pendingFingerprint = outcome.fingerprint
            pendingHostKeyChanged = outcome.hostKeyChanged
            testResult = if (outcome.passed) outcome.output else errorMessage(outcome, draft)
            if (outcome.passed) {
                editingHost?.let { host ->
                    withContext(Dispatchers.IO) { store.markConnected(host.id) }
                    refreshHosts()
                }
            }
        }
    }

    fun trustPendingFingerprint() {
        val fingerprint = pendingFingerprint ?: return
        scope.launch {
            val draft = buildDraft(fingerprint)
            val saved = withContext(Dispatchers.IO) {
                val existing = editingHost
                if (existing != null) {
                    store.updateKnownHostFingerprint(existing.id, fingerprint)
                        ?: store.saveHost(draft)
                } else {
                    store.saveHost(draft)
                }
            }
            editingHost = saved
            trustedFingerprint = fingerprint
            pendingFingerprint = null
            pendingHostKeyChanged = false
            refreshHosts()
            startTest(saved)
        }
    }

    fun forgetTrustedFingerprint() {
        val host = editingHost ?: return
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                client.disconnect(host.host)
                store.saveHost(host.copy(knownHostFingerprint = null))
            }
            editingHost = updated
            trustedFingerprint = null
            resetTest()
            refreshHosts()
        }
    }

    val port = portText.toIntOrNull()
    val formValid = name.isNotBlank() && hostAddress.isNotBlank() && username.isNotBlank() &&
        port != null && port in 1..65_535 &&
        (authType != SshAuthType.KEY || privateKeyText.isNotBlank()) &&
        (authType != SshAuthType.PASSWORD || passwordText.isNotBlank())

    SettingsScaffold(title = SSH_HOSTS_TITLE, onBack = onBack) {
        SettingsSection(
            header = SSH_HOSTS_TITLE,
            footer = stringResource(R.string.consent_scope_ssh_exec_desc),
        ) {
            SettingsCardBlock {
                if (hosts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ssh_no_host),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                hosts.forEachIndexed { index, host ->
                    if (index > 0) {
                        HorizontalDivider()
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { loadHost(host) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = host.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${host.username}@${host.host}:${host.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            host.knownHostFingerprint?.let { fingerprint ->
                                Text(
                                    text = stringResource(R.string.ssh_fingerprint) +
                                        ": " + fingerprint,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            host.lastConnected?.let { lastConnected ->
                                Text(
                                    text = dateFormat.format(Date(lastConnected)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = host.authType.value,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SettingsSection(header = stringResource(R.string.ssh_save_host)) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.ssh_host_name))
                SectionTextField(
                    value = name,
                    onValueChange = { name = it; resetTest() },
                    placeholder = "home-server",
                )
                RowLabel(text = stringResource(R.string.ssh_host_address))
                SectionTextField(
                    value = hostAddress,
                    onValueChange = { hostAddress = it; resetTest() },
                    placeholder = "example.com",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                RowLabel(text = stringResource(R.string.ssh_host_port))
                SectionTextField(
                    value = portText,
                    onValueChange = { portText = it; resetTest() },
                    placeholder = SshClient.DEFAULT_SSH_PORT.toString(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                RowLabel(text = stringResource(R.string.ssh_username))
                SectionTextField(
                    value = username,
                    onValueChange = { username = it; resetTest() },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (authType == SshAuthType.KEY) {
                        MinisButton(onClick = {}, enabled = false) {
                            Text(text = SshAuthType.KEY.value)
                        }
                    } else {
                        MinisOutlinedButton(
                            onClick = { authType = SshAuthType.KEY; resetTest() },
                        ) {
                            Text(text = SshAuthType.KEY.value)
                        }
                    }
                    if (authType == SshAuthType.PASSWORD) {
                        MinisButton(onClick = {}, enabled = false) {
                            Text(text = SshAuthType.PASSWORD.value)
                        }
                    } else {
                        MinisOutlinedButton(
                            onClick = { authType = SshAuthType.PASSWORD; resetTest() },
                        ) {
                            Text(text = SshAuthType.PASSWORD.value)
                        }
                    }
                }
                if (authType == SshAuthType.KEY) {
                    RowLabel(text = stringResource(R.string.ssh_private_key))
                    SectionTextField(
                        value = privateKeyText,
                        onValueChange = { privateKeyText = it; resetTest() },
                        placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
                        singleLine = false,
                        maxLines = 8,
                    )
                    RowLabel(text = stringResource(R.string.ssh_key_passphrase))
                    SectionTextField(
                        value = passphraseText,
                        onValueChange = { passphraseText = it; resetTest() },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                } else {
                    RowLabel(text = SshAuthType.PASSWORD.value)
                    SectionTextField(
                        value = passwordText,
                        onValueChange = { passwordText = it; resetTest() },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                trustedFingerprint?.let { fingerprint ->
                    RowLabel(text = stringResource(R.string.ssh_fingerprint))
                    Text(
                        text = fingerprint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MinisOutlinedButton(onClick = { forgetTrustedFingerprint() }) {
                        Text(text = FORGET_HOST_KEY_LABEL)
                    }
                }
                pendingFingerprint?.let { fingerprint ->
                    Text(
                        text = if (pendingHostKeyChanged) {
                            stringResource(R.string.ssh_host_key_changed, hostAddress.trim())
                        } else {
                            stringResource(R.string.ssh_unknown_host, fingerprint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    MinisButton(onClick = { trustPendingFingerprint() }) {
                        Text(text = stringResource(R.string.ssh_trust_host_key))
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MinisButton(
                        onClick = {
                            if (authType == SshAuthType.KEY && isPpkKey(privateKeyText)) {
                                testPassed = false
                                pendingFingerprint = null
                                pendingHostKeyChanged = false
                                testResult = PPK_NOT_SUPPORTED_MESSAGE
                            } else {
                                startTest(buildDraft(trustedFingerprint))
                            }
                        },
                        enabled = formValid && !testRunning,
                    ) {
                        Text(text = stringResource(R.string.ssh_test_connection))
                    }
                    testResult?.let { result ->
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (testPassed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MinisButton(
                        onClick = {
                            scope.launch {
                                val draft = buildDraft(trustedFingerprint)
                                val saved = try {
                                    withContext(Dispatchers.IO) {
                                        val stored = store.saveHost(draft)
                                        store.markConnected(stored.id)
                                        stored
                                    }
                                } catch (e: IllegalArgumentException) {
                                    testResult = context.getString(
                                        R.string.ssh_connect_failed,
                                        draft.host,
                                    )
                                    return@launch
                                }
                                refreshHosts()
                                clearForm()
                            }
                        },
                        enabled = testPassed && !testRunning,
                    ) {
                        Text(text = stringResource(R.string.ssh_save_host))
                    }
                    MinisOutlinedButton(onClick = { clearForm() }) {
                        Text(text = stringResource(R.string.cancel))
                    }
                    if (editingHost != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.delete),
                            )
                        }
                    }
                }
            }
        }

        if (showDeleteConfirm) {
            val host = editingHost
            if (host != null) {
                MinisAlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = host.name,
                    confirmText = stringResource(R.string.delete),
                    onConfirm = {
                        showDeleteConfirm = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                client.disconnect(host.host)
                                store.deleteHost(host.id)
                            }
                            refreshHosts()
                            clearForm()
                        }
                    },
                    text = stringResource(R.string.filebrowser_delete_message),
                    isDestructive = true,
                )
            }
        }
    }
}

private data class SshTestOutcome(
    val passed: Boolean,
    val output: String?,
    val errorCode: SshErrorCode?,
    val fingerprint: String?,
    val hostKeyChanged: Boolean,
)

/**
 * Runs the connection test for [host] on [Dispatchers.IO]: connect +
 * authenticate with [SshClient.connect], then run `echo ok` with
 * [SshClient.exec]. A pooled session from earlier credentials is dropped
 * first, so the test validates the credentials in the form, not a stale
 * session. The fingerprint is read from the [SshException] message, which
 * is the only place [SshClient] exposes it
 * (`ssh_unknown_host: ...; fingerprint=...`).
 */
private suspend fun testSshHost(client: SshClient, host: SshHost): SshTestOutcome =
    withContext(Dispatchers.IO) {
        try {
            client.disconnect(host.host)
            val keyBytes = if (host.authType == SshAuthType.KEY) {
                host.privateKeyPem?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            } else {
                ByteArray(0)
            }
            client.connect(
                host = host.host,
                port = host.port,
                username = host.username,
                privateKey = keyBytes,
                passphrase = host.keyPassphrase,
                password = host.password,
            )
            val result = client.exec(host.host, "echo ok")
            if (result.exitCode == 0 && result.stdout.trim() == "ok") {
                SshTestOutcome(
                    passed = true,
                    output = result.stdout.trim(),
                    errorCode = null,
                    fingerprint = null,
                    hostKeyChanged = false,
                )
            } else {
                SshTestOutcome(
                    passed = false,
                    output = null,
                    errorCode = SshErrorCode.CONNECT_FAILED,
                    fingerprint = null,
                    hostKeyChanged = false,
                )
            }
        } catch (e: SshException) {
            val fingerprint = e.message
                ?.substringAfter("fingerprint=", "")
                ?.substringBefore(";")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && it != "unavailable" }
            SshTestOutcome(
                passed = false,
                output = null,
                errorCode = e.errorCode,
                fingerprint = fingerprint,
                hostKeyChanged = e.errorCode == SshErrorCode.HOST_KEY_CHANGED,
            )
        } catch (e: Exception) {
            SshTestOutcome(
                passed = false,
                output = null,
                errorCode = SshErrorCode.CONNECT_FAILED,
                fingerprint = null,
                hostKeyChanged = false,
            )
        }
    }

/** PuTTY `.ppk` files (`PuTTY-User-Key-File-...`) are not OpenSSH keys. */
private fun isPpkKey(pem: String): Boolean {
    val trimmed = pem.trimStart()
    return trimmed.startsWith("PuTTY-User-Key-File", ignoreCase = true) ||
        trimmed.startsWith("PuTTY", ignoreCase = true)
}
