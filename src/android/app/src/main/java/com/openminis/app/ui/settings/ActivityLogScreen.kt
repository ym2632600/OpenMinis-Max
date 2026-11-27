package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.activitylog.ActivityLog
import com.openminis.app.data.db.ActivityLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * B14: chronological log of what the agent did. Tap an entry for its detail
 * dialog; reversible entries offer an Undo button there.
 */
@Composable
fun ActivityLogScreen(onBack: () -> Unit) {
    val entries by ActivityLog.observeRecent().collectAsState(initial = emptyList())
    var detail by remember { mutableStateOf<ActivityLogEntity?>(null) }
    val uiScope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    SettingsScaffold(
        title = stringResource(R.string.activity_log_title),
        onBack = onBack,
    ) {
        if (entries.isEmpty()) {
            Text(
                text = stringResource(R.string.activity_log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        entries.forEachIndexed { index, entry ->
            SettingsRow(
                title = entry.title,
                subtitle = timeFormat.format(Date(entry.timestamp)) + " · " + activityLogCategoryLabel(entry.category),
                onClick = { detail = entry },
                showDivider = index < entries.size - 1,
                minHeight = 72.dp,
            )
        }
    }

    detail?.let { entry ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(entry.title) },
            text = {
                Column {
                    Text(
                        text = timeFormat.format(Date(entry.timestamp)) + " · " + activityLogCategoryLabel(entry.category),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    entry.details?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (entry.undoneAt != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.activity_log_undone),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            },
            confirmButton = {
                if (entry.undoKind != null && entry.undoneAt == null) {
                    TextButton(onClick = {
                        uiScope.launch { ActivityLog.undo(entry.id) }
                        detail = null
                    }) {
                        Text(stringResource(R.string.activity_log_undo))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { detail = null }) {
                    Text(stringResource(R.string.activity_log_close))
                }
            },
        )
    }
}

@Composable
private fun activityLogCategoryLabel(category: String): String = when (category) {
    ActivityLog.CATEGORY_TOOL_CALL -> stringResource(R.string.activity_log_cat_tool_call)
    ActivityLog.CATEGORY_MESSAGE_SENT -> stringResource(R.string.activity_log_cat_message_sent)
    ActivityLog.CATEGORY_FILE_CHANGED -> stringResource(R.string.activity_log_cat_file_changed)
    ActivityLog.CATEGORY_CONSENT -> stringResource(R.string.activity_log_cat_consent)
    ActivityLog.CATEGORY_CONFIG -> stringResource(R.string.activity_log_cat_config)
    else -> category
}
