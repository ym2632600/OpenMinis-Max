package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * A5: data-flow transparency screen. A plain-language map of what leaves the
 * device (and where to) versus what never does. Static content by design: it
 * describes the app's architecture, so it can be read offline and cannot go
 * stale with runtime state. Linked from Settings → Privacy & data flow.
 */
@Composable
fun DataFlowScreen(onBack: () -> Unit) {
    SettingsScaffold(title = stringResource(R.string.data_flow_title), onBack = onBack) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.data_flow_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsSection(header = stringResource(R.string.data_flow_sent_title)) {
            BulletText(stringResource(R.string.data_flow_sent_1))
            BulletText(stringResource(R.string.data_flow_sent_2))
            BulletText(stringResource(R.string.data_flow_sent_3))
            BulletText(stringResource(R.string.data_flow_sent_4))
        }

        SettingsSection(header = stringResource(R.string.data_flow_device_title)) {
            BulletText(stringResource(R.string.data_flow_device_1))
            BulletText(stringResource(R.string.data_flow_device_2))
            BulletText(stringResource(R.string.data_flow_device_3))
            BulletText(stringResource(R.string.data_flow_device_4))
            BulletText(stringResource(R.string.data_flow_device_5))
        }

        SettingsSection(header = stringResource(R.string.data_flow_network_title)) {
            BulletText(stringResource(R.string.data_flow_network_1))
            BulletText(stringResource(R.string.data_flow_network_2))
            BulletText(stringResource(R.string.data_flow_network_3))
        }

        SettingsSection(header = stringResource(R.string.data_flow_note_title)) {
            BulletText(stringResource(R.string.data_flow_note_1))
            BulletText(stringResource(R.string.data_flow_note_2))
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BulletText(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
