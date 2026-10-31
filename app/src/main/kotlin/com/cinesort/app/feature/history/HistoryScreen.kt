package com.cinesort.app.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cinesort.app.R
import com.cinesort.app.feature.home.formatRunDate
import com.cinesort.core.designsystem.component.EmptyState
import com.cinesort.core.designsystem.component.StatusChip
import com.cinesort.core.designsystem.component.toPersianDigits

/**
 * History tab (spec 10.4 screen 8): the last 10 runs with Jalali/Gregorian
 * dates, outcome counts and status, Undo on eligible runs, and tap-through
 * to the run's Report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpenRun: (String) -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val persianDigits = state.settings.persianDigits

    LaunchedEffect(Unit) { viewModel.refresh() }

    val undoReport = state.undoReport
    val undoMessage = undoReport?.let { report ->
        if (report.problems.isEmpty()) {
            stringResource(R.string.history_undo_done, report.movedBack)
        } else {
            stringResource(R.string.history_undo_problems, report.problems.size)
        }
    }
    LaunchedEffect(undoMessage) {
        if (undoMessage != null) {
            snackbarHostState.showSnackbar(undoMessage)
            viewModel.consumeUndoReport()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.history_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.runs.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.History,
                title = stringResource(R.string.history_empty_title),
                message = stringResource(R.string.history_empty_message),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.runs, key = { it.run.id }) { entry ->
                    HistoryRunCard(
                        entry = entry,
                        jalaliDates = state.settings.jalaliDates,
                        persianDigits = persianDigits,
                        isUndoing = state.undoingRunId == entry.run.id,
                        onClick = { onOpenRun(entry.run.id) },
                        onUndo = { viewModel.undo(entry.run.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRunCard(
    entry: HistoryRunUi,
    jalaliDates: Boolean,
    persianDigits: Boolean,
    isUndoing: Boolean,
    onClick: () -> Unit,
    onUndo: () -> Unit,
) {
    val run = entry.run
    val statusRes = when {
        run.undone -> R.string.history_status_undone
        run.finishedAt == null -> R.string.history_status_running
        else -> R.string.history_status_finished
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatRunDate(run.startedAt, jalaliDates, persianDigits),
                    style = MaterialTheme.typography.titleMedium,
                )
                StatusChip(
                    label = stringResource(statusRes),
                    isOk = run.finishedAt != null && !run.undone,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.history_items, entry.total)
                    .let { if (persianDigits) it.toPersianDigits() else it },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.history_done, entry.done)
                    .let { if (persianDigits) it.toPersianDigits() else it },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (entry.failed > 0) {
                Text(
                    text = stringResource(R.string.history_failed, entry.failed)
                        .let { if (persianDigits) it.toPersianDigits() else it },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (entry.canUndo) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        onClick = onUndo,
                        enabled = !isUndoing,
                    ) {
                        Text(stringResource(R.string.history_undo))
                    }
                }
            }
        }
    }
}
