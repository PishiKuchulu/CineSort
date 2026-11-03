package com.cinesort.app.feature.flow

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cinesort.app.R
import com.cinesort.core.designsystem.component.EmptyState
import com.cinesort.core.designsystem.component.LoadingState
import com.cinesort.core.designsystem.component.LtrText
import com.cinesort.core.designsystem.component.StatTile
import com.cinesort.core.designsystem.component.toPersianDigits
import kotlinx.coroutines.launch

/**
 * Report screen (spec 10.4 screen 7 + 8.6): outcome counts, an expandable
 * failure list, Retry failed, Undo, Open destination, and redacted
 * diagnostics sharing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    runId: String?,
    onBack: () -> Unit,
    onOpenRun: () -> Unit = {},
    viewModel: ReportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var failuresExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(runId) { viewModel.load(runId) }
    LaunchedEffect(state.navigateToRunId) {
        if (state.navigateToRunId != null) {
            viewModel.consumeNavigation()
            onOpenRun()
        }
    }

    val undoReport = state.undoReport
    val undoMessage = undoReport?.let { report ->
        if (report.problems.isEmpty()) {
            stringResource(R.string.report_undo_done, report.movedBack)
        } else {
            stringResource(
                R.string.report_undo_problems,
                report.movedBack,
                report.problems.size,
            )
        }
    }
    LaunchedEffect(undoMessage) {
        if (undoMessage != null) {
            snackbarHostState.showSnackbar(undoMessage)
            viewModel.consumeUndoReport()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.report_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            // Loading branch (audit 1-12): before `loaded`, the body used
            // to render completely blank — a flash of empty screen on
            // every entry.
            !state.loaded -> LoadingState(
                message = stringResource(R.string.report_loading),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            state.run == null -> EmptyState(
                icon = Icons.Filled.Description,
                title = stringResource(R.string.report_no_run_title),
                message = stringResource(R.string.report_no_run_message),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                actionLabel = stringResource(R.string.flow_go_home),
                onAction = onBack,
            )
            state.run != null -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text(
                        text = stringResource(
                            R.string.report_summary,
                            state.doneCount,
                            state.totalItems,
                        ).let {
                            if (state.persianDigits) it.toPersianDigits() else it
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatTile(
                            value = state.doneCount.toString().let {
                                if (state.persianDigits) it.toPersianDigits() else it
                            },
                            label = stringResource(R.string.report_label_done),
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            value = state.failedCount.toString().let {
                                if (state.persianDigits) it.toPersianDigits() else it
                            },
                            label = stringResource(R.string.report_label_failed),
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            value = state.skippedCount.toString().let {
                                if (state.persianDigits) it.toPersianDigits() else it
                            },
                            label = stringResource(R.string.report_label_skipped),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                // Journal facts the three tiles don't carry (audit A.3-10
                // posters, A.3-3 undone): shown only when they happened.
                if (state.posterCount > 0 || state.undoneCount > 0) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (state.posterCount > 0) {
                                Text(
                                    text = stringResource(
                                        R.string.report_posters,
                                        state.posterCount,
                                    ).let {
                                        if (state.persianDigits) {
                                            it.toPersianDigits()
                                        } else {
                                            it
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (state.undoneCount > 0) {
                                Text(
                                    text = stringResource(
                                        R.string.report_undone_count,
                                        state.undoneCount,
                                    ).let {
                                        if (state.persianDigits) {
                                            it.toPersianDigits()
                                        } else {
                                            it
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (state.failedOps.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.report_failures_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            val failuresToggleDescription = stringResource(
                                if (failuresExpanded) {
                                    R.string.preview_group_expanded
                                } else {
                                    R.string.preview_group_collapsed
                                },
                            )
                            IconButton(
                                onClick = { failuresExpanded = !failuresExpanded },
                                modifier = Modifier.semantics {
                                    stateDescription = failuresToggleDescription
                                },
                            ) {
                                Icon(
                                    imageVector = if (failuresExpanded) {
                                        Icons.Filled.ExpandLess
                                    } else {
                                        Icons.Filled.ExpandMore
                                    },
                                    contentDescription = stringResource(
                                        if (failuresExpanded) {
                                            R.string.common_collapse
                                        } else {
                                            R.string.common_expand
                                        },
                                    ),
                                )
                            }
                        }
                    }
                    if (failuresExpanded) {
                        items(state.failedOps, key = { it.id }) { op ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    LtrText(
                                        text = op.fileName,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        // Localized mapping (audit 1-12):
                                        // failureReason holds a raw exception
                                        // message and the fallback was the
                                        // enum name — both technical English.
                                        // The raw text stays available in
                                        // the shared diagnostics.
                                        text = when {
                                            op.failureReason?.contains(
                                                "interrupted",
                                                ignoreCase = true,
                                            ) == true -> stringResource(
                                                R.string.report_failure_interrupted,
                                            )
                                            else -> stringResource(
                                                R.string.report_failure_generic,
                                            )
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.failedOps.isNotEmpty()) {
                            Button(
                                onClick = { viewModel.retryFailed() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.report_retry_failed))
                            }
                        }
                        // Visibility ignores isUndoing (unlike state.canUndo)
                        // so the button stays on screen — disabled, with a
                        // spinner — while the undo runs (audit 1-12).
                        if (state.run?.finishedAt != null && state.run?.undone == false) {
                            OutlinedButton(
                                onClick = { viewModel.undo() },
                                enabled = !state.isUndoing,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (state.isUndoing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.report_undo))
                            }
                        }
                        val openFailedMessage = stringResource(R.string.report_open_failed)
                        OutlinedButton(
                            onClick = {
                                val uri = state.destinationUri
                                val opened = if (uri == null) {
                                    false
                                } else {
                                    runCatching {
                                        val intent = Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse(uri),
                                        ).addFlags(
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                        )
                                        context.startActivity(
                                            Intent.createChooser(intent, null),
                                        )
                                        true
                                    }.getOrDefault(false)
                                }
                                if (!opened) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(openFailedMessage)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.report_open_destination))
                        }
                        val shareHeader = stringResource(R.string.report_diagnostics_header)
                        val shareChooser = stringResource(R.string.settings_diagnostics_chooser)
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val text = viewModel.buildShareText(shareHeader)
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(sendIntent, shareChooser),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.report_share))
                        }
                    }
                }
            }
        }
    }
}
