package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.network.NetworkHttp
import com.openminis.app.provider.openai.OpenAIModelsApi
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID

/**
 * B15: one-field Ollama setup + connection test.
 *
 * Ollama is an OpenAI-compatible instance (providerType=openAI) whose base URL
 * points at the Ollama host; it needs no API key (see
 * ProviderInstance.allowsEmptyAPIKey). The form has exactly one field — the
 * server address — defaulting to Ollama's own default, http://localhost:11434.
 * The test hits the EFFECTIVE base URL (<host>/v1/models) via the same
 * OpenAIModelsApi call the model picker uses; do NOT pass the raw host the way
 * the A9 key test does (that omits /v1 and 404s on real Ollama servers).
 */
@Composable
fun OllamaSetupScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val config by providerRepository.config.collectAsState()
    // Label mirrors ConfigureProviderScreen: "Ollama", "Ollama 2", …
    val defaultLabel = remember(config) {
        val existing = config.instances.map { it.label }.toSet()
        if ("Ollama" !in existing) "Ollama" else {
            var n = 2
            while ("Ollama $n" in existing) n++
            "Ollama $n"
        }
    }
    var serverAddress by remember { mutableStateOf("http://localhost:11434") }
    var testState by remember { mutableStateOf<OllamaTestState>(OllamaTestState.Idle) }
    var testedBase by remember { mutableStateOf<String?>(null) }
    val saveOnce = remember { OllamaSaveOnce() }
    val invalidAddressText = stringResource(R.string.ollama_invalid_address)

    SettingsScaffold(title = stringResource(R.string.ollama_setup_title), onBack = onBack) {
        SettingsSection(
            header = stringResource(R.string.ollama_server_address),
            footer = stringResource(R.string.ollama_server_address_hint),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.ollama_server_address))
                SectionTextField(
                    value = serverAddress,
                    onValueChange = {
                        serverAddress = it
                        testState = OllamaTestState.Idle
                        testedBase = null
                    },
                    placeholder = "http://localhost:11434",
                    singleLine = true,
                )
            }
        }

        // Connection test (A9 pattern, keyless)
        SettingsSection {
            SettingsCardBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MinisButton(
                        onClick = {
                            val base = normalizeOllamaBase(serverAddress)
                            if (base == null) {
                                testState = OllamaTestState.Failure(invalidAddressText)
                            } else if (testState != OllamaTestState.Testing) {
                                testState = OllamaTestState.Testing
                                scope.launch {
                                    testState = testOllamaConnection(base)
                                    if (testState is OllamaTestState.Success) testedBase = base
                                }
                            }
                        },
                        enabled = serverAddress.isNotBlank() && testState != OllamaTestState.Testing,
                    ) {
                        Text(stringResource(
                            if (testState == OllamaTestState.Testing) R.string.ollama_testing_connection
                            else R.string.ollama_test_connection,
                        ))
                    }
                    when (val st = testState) {
                        is OllamaTestState.Success -> Text(
                            stringResource(
                                if (st.version != null) R.string.ollama_test_success_version
                                else R.string.ollama_test_success,
                                st.modelCount, st.version ?: "",
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                        is OllamaTestState.Failure -> Text(
                            stringResource(R.string.ollama_test_failed, st.message),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                        else -> {}
                    }
                }
            }
        }

        // Save (only after a green test for the address shown)
        Spacer(Modifier.height(20.dp))
        MinisButton(
            onClick = {
                if (!saveOnce.claim()) return@MinisButton
                val base = testedBase ?: return@MinisButton
                val instance = ProviderInstance(
                    id = UUID.randomUUID().toString(),
                    label = defaultLabel,
                    providerType = ProviderType.openAI,
                    credentialType = ProviderCredential.apiKey,
                    customBaseURL = base,
                    appendV1Suffix = true,
                    useResponsesAPI = false,
                )
                providerRepository.addInstance(instance)
                // No saveApiKey call: Ollama needs no key; persisting an empty
                // string is not allowed. addInstance seeds no built-in models
                // for third-party compat bases; the reconcile below populates
                // entries from /v1/models.
                providerRepository.triggerAsyncModelReconcile(instance.id)
                onSaved()
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            enabled = testState is OllamaTestState.Success &&
                testedBase != null &&
                normalizeOllamaBase(serverAddress) == testedBase,
        ) { Text(stringResource(R.string.ollama_save)) }
        if (testState !is OllamaTestState.Success) {
            Text(
                stringResource(R.string.ollama_test_first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

private class OllamaSaveOnce {
    private var claimed = false
    fun claim(): Boolean = if (claimed) false else { claimed = true; true }
}

// B15: connection-test plumbing (mirrors A9 KeyTestState)
private sealed interface OllamaTestState {
    data object Idle : OllamaTestState
    data object Testing : OllamaTestState
    data class Success(val modelCount: Int, val version: String?) : OllamaTestState
    data class Failure(val message: String) : OllamaTestState
}

/**
 * Primary check: GET <host>/v1/models through OpenAIModelsApi — the same call
 * the model picker and refreshModels use, so green means chat will work.
 * Empty API key: Ollama takes no auth by default. forceRefresh skips the
 * models cache. Never throws.
 * Secondary, best-effort: GET <host>/api/version for the "Ollama x.y.z" line;
 * any failure there leaves version null and the test still passes.
 */
private suspend fun testOllamaConnection(normalizedHost: String): OllamaTestState {
    val effectiveBase = "$normalizedHost/v1"
    return try {
        val r = OpenAIModelsApi.fetchModelsDetailed(
            apiKey = "", baseURL = effectiveBase, forceRefresh = true,
        )
        when {
            r.error != null -> OllamaTestState.Failure(r.error)
            r.models.isEmpty() -> OllamaTestState.Failure("no models returned")
            else -> OllamaTestState.Success(r.models.size, fetchOllamaVersion(normalizedHost))
        }
    } catch (t: Throwable) {
        OllamaTestState.Failure(t.message ?: t.javaClass.simpleName)
    }
}

private suspend fun fetchOllamaVersion(hostBase: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val request = Request.Builder().url("$hostBase/api/version").build()
        NetworkHttp.client(null, OkHttpClient()).newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            JSONObject(resp.body?.string() ?: "").optString("version", "")
                .takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

/**
 * Normalize one-field input to the stored host (no scheme default = http,
 * no trailing slash, no trailing /v1 — appendV1Suffix re-adds it). Returns
 * null for anything unusable. Pure and unit-tested (OllamaUrlTest).
 */
internal fun normalizeOllamaBase(raw: String): String? {
    var s = raw.trim()
    if (s.isEmpty() || s.contains(' ') || s.contains('\t')) return null
    s = s.trimEnd('/')
    if (s.endsWith("/v1", ignoreCase = true)) s = s.dropLast(3).trimEnd('/')
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = "http://$s"
    val scheme = s.substringBefore("://").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val authority = s.substringAfter("://")
    if (authority.isEmpty() || authority.contains('/')) return null
    val host = when {
        authority.startsWith("[") -> authority.substringAfter("[").substringBefore("]")
        else -> authority.substringBefore(':')
    }
    if (host.isEmpty()) return null
    val port = when {
        authority.startsWith("[") -> authority.substringAfter("]:", "").ifEmpty { null }
        authority.count { it == ':' } == 1 -> authority.substringAfter(':')
        authority.contains(':') -> return null
        else -> null
    }
    if (port != null && (port.isEmpty() || !port.all { it.isDigit() })) return null
    return s
}
