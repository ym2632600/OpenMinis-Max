package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.consent.ConsentManager
import com.openminis.app.consent.SensitiveScope
import com.openminis.app.data.db.ExpenseEntity
import com.openminis.app.finance.FinanceRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * C10: the mini finance agent's ledger screen - expenses and income parsed
 * from notifications, plus the opt-in tracking toggle (consent-gated).
 */
@Composable
fun FinanceScreen(onBack: () -> Unit) {
    val enabled by FinanceRepository.enabled.collectAsState()
    val scope = rememberCoroutineScope()
    val consentTitle = stringResource(R.string.consent_scope_finance_track)
    val flow = remember { FinanceRepository.recent(300) }
    val items by (flow ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())

    val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
    val week = items.filter { it.timestamp >= weekAgo }
    val spentByCurrency = week.filter { it.direction == "debit" }
        .groupBy { it.currency }
        .map { (cur, rows) -> FinanceRepository.formatAmount(rows.sumOf { it.amount }, cur) }
        .joinToString(" + ")
    val timeFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    SettingsScaffold(
        title = stringResource(R.string.finance_title),
        onBack = onBack,
    ) {
        SettingsRow(
            title = stringResource(R.string.finance_tracking_toggle),
            subtitle = stringResource(R.string.finance_tracking_subtitle),
            showChevron = false,
            showDivider = true,
            minHeight = 72.dp,
            trailing = {
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        if (on) {
                            scope.launch {
                                val outcome = ConsentManager.requireConsent(
                                    SensitiveScope.FINANCE_TRACK,
                                    consentTitle,
                                )
                                if (outcome is ConsentManager.ConsentOutcome.Approved ||
                                    outcome is ConsentManager.ConsentOutcome.ApprovedAlways
                                ) {
                                    FinanceRepository.setEnabled(true)
                                }
                            }
                        } else {
                            FinanceRepository.setEnabled(false)
                        }
                    },
                )
            },
        )
        Text(
            text = if (spentByCurrency.isEmpty()) {
                stringResource(R.string.finance_week_empty)
            } else {
                stringResource(R.string.finance_week_total, spentByCurrency)
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items, key = { it.id }) { entry ->
                ExpenseRow(entry, timeFormat)
            }
        }
    }
}

@Composable
private fun ExpenseRow(entry: ExpenseEntity, timeFormat: SimpleDateFormat) {
    val isDebit = entry.direction == "debit"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.merchant ?: entry.sourcePackage,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = timeFormat.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = (if (isDebit) "- " else "+ ") +
                FinanceRepository.formatAmount(entry.amount, entry.currency),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isDebit) Color(0xFFFF3B30) else Color(0xFF34C759),
        )
    }
}
