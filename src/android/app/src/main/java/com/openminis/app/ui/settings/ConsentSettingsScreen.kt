package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.consent.ConsentManager
import com.openminis.app.consent.SensitiveScope

/**
 * B13: settings surface for the central consent layer. Every sensitive scope
 * is listed with its current mode; the switch grants or revokes "always
 * allow". Default for every scope is ask-every-time.
 */
@Composable
fun ConsentSettingsScreen(onBack: () -> Unit) {
    val granted by ConsentManager.grantedScopes.collectAsState()
    SettingsScaffold(
        title = stringResource(R.string.consent_settings_title),
        onBack = onBack,
    ) {
        Text(
            text = stringResource(R.string.consent_settings_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
        val scopes = SensitiveScope.values()
        scopes.forEachIndexed { index, scope ->
            val always = granted.contains(scope)
            SettingsRow(
                title = stringResource(scope.labelRes),
                subtitle = stringResource(scope.descriptionRes) + " · " +
                    stringResource(if (always) R.string.consent_state_always else R.string.consent_state_ask),
                showChevron = false,
                showDivider = index < scopes.size - 1,
                minHeight = 72.dp,
                trailing = {
                    Switch(
                        checked = always,
                        onCheckedChange = { on ->
                            if (on) ConsentManager.grantAlways(scope) else ConsentManager.revoke(scope)
                        },
                    )
                },
            )
        }
    }
}
