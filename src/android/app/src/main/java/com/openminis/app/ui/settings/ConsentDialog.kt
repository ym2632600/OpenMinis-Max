package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.consent.ConsentManager

/**
 * B13: root-level consent dialog for sensitive agent actions, bound to
 * [ConsentManager.pending]. Mounted next to ConfigConfirmDialogHost so it
 * shows over any active screen. Dismissing without a button is disabled:
 * the agent waits for an explicit Allow once / Always allow / Deny.
 */
@Composable
fun ConsentDialogHost() {
    val pending by ConsentManager.pending.collectAsState()
    val request = pending ?: return
    ConsentDialog(request)
}

@Composable
private fun ConsentDialog(request: ConsentManager.ConsentRequest) {
    AlertDialog(
        onDismissRequest = { /* force an explicit decision */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(stringResource(R.string.consent_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(request.scope.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(request.title, style = MaterialTheme.typography.bodyLarge)
                request.details.forEach { line ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = { ConsentManager.userApproveOnce() }) {
                    Text(stringResource(R.string.consent_allow_once))
                }
                TextButton(onClick = { ConsentManager.userApproveAlways() }) {
                    Text(stringResource(R.string.consent_allow_always))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { ConsentManager.userDeny() }) {
                Text(stringResource(R.string.consent_deny))
            }
        },
    )
}
