package com.cinesort.app.feature.flow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cinesort.app.R
import com.cinesort.app.orchestration.OrganizeSession
import com.cinesort.app.orchestration.SessionPhase
import com.cinesort.core.designsystem.component.ErrorState
import com.cinesort.core.designsystem.component.StageStepper
import com.cinesort.core.designsystem.component.toPersianDigits

/**
 * Scan progress screen (spec 10.4 screen 4): stage stepper, live counters,
 * cancel. Auto-navigates to Preview when the plan is ready — or straight to
 * Run when the user enabled "Skip preview" (spec 2.3 / 11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onOpenPreview: () -> Unit = {},
    onOpenRun: () -> Unit = {},
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val session = state.session
    val persianDigits = state.settings.persianDigits

    LaunchedEffect(Unit) { viewModel.startIfNeeded() }
    LaunchedEffect(state.navigateToPreview) {
        if (state.navigateToPreview) {
            viewModel.consumeNavigation()
            onOpenPreview()
        }
    }
    LaunchedEffect(state.navigateToRun) {
        if (state.navigateToRun) {
            viewModel.consumeNavigation()
            onOpenRun()
        }
    }

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(session.error) {
        if (session.error != null) {
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            // Back during an active scan behaves exactly
                            // like the Cancel button (audit 1-8): two exit
                            // paths with different side effects confused
                            // users — one cancels the scan, the other
                            // silently left it running.
                            if (state.isBusy) viewModel.cancelScan()
                            onBack()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val error = session.error
            when {
                error != null -> {
                    ErrorState(
                        icon = Icons.Filled.ErrorOutline,
                        title = stringResource(R.string.scan_error_title),
                        // Raw error strings never reach the UI (audit
                        // 1-8): the one known case gets its specific
                        // message, everything else a generic localized
                        // one; technical detail belongs in Diagnostics.
                        message = if (error == OrganizeSession.ERROR_NO_SETUP) {
                            stringResource(R.string.scan_error_no_setup)
                        } else {
                            stringResource(R.string.scan_error_generic)
                        },
                        retryLabel = stringResource(R.string.scan_start),
                        onRetry = { viewModel.startScan() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                session.phase == SessionPhase.PLANNED -> {
                    StageStepper(
                        stages = stageLabels(),
                        currentIndex = 3,
                        stageDescription = stageDescription(),
                        persianDigits = persianDigits,
                    )
                    Text(
                        text = stringResource(
                            R.string.scan_planned_message,
                            session.plan.size,
                        ).let { if (persianDigits) it.toPersianDigits() else it },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    // What the scan could not read is stated here too
                    // (audit A.3-6), not only in Preview.
                    if (session.scanFailedCount > 0 || session.scanSkippedCount > 0) {
                        Text(
                            text = stringResource(
                                R.string.scan_issues_summary,
                                session.scanFailedCount,
                                session.scanSkippedCount,
                            ).let { if (persianDigits) it.toPersianDigits() else it },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = onOpenPreview,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.scan_view_preview))
                    }
                    OutlinedButton(
                        onClick = { viewModel.startScan() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.scan_start))
                    }
                }
                else -> {
                    StageStepper(
                        stages = stageLabels(),
                        currentIndex = session.stageIndex.coerceIn(0, 3),
                        stageDescription = stageDescription(),
                        persianDigits = persianDigits,
                    )
                    // A moving indicator (audit 1-8): without one, a scan
                    // whose counters pause for a few seconds looks frozen.
                    if (state.isBusy) {
                        CircularProgressIndicator()
                    }
                    Text(
                        text = stringResource(R.string.scan_running_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        modifier = Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                        },
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.scan_entries,
                                session.scannedEntries,
                            ).let { if (persianDigits) it.toPersianDigits() else it },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(
                                R.string.scan_videos,
                                session.videosFound,
                            ).let { if (persianDigits) it.toPersianDigits() else it },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (session.scanFailedCount > 0 || session.scanSkippedCount > 0) {
                            Text(
                                text = stringResource(
                                    R.string.scan_issues_summary,
                                    session.scanFailedCount,
                                    session.scanSkippedCount,
                                ).let {
                                    if (persianDigits) it.toPersianDigits() else it
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (state.isBusy) {
                        OutlinedButton(
                            onClick = {
                                viewModel.cancelScan()
                                onBack()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    } else {
                        Button(
                            onClick = { viewModel.startScan() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                            )
                            Text(stringResource(R.string.scan_start))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun stageLabels(): List<String> = listOf(
    stringResource(R.string.scan_stage_scanning),
    stringResource(R.string.scan_stage_parsing),
    stringResource(R.string.scan_stage_metadata),
    stringResource(R.string.scan_stage_planning),
)

/** Localized "Step N of M: label" for the stepper's semantics. */
@Composable
private fun stageDescription(): (Int, String) -> String {
    val template = stringResource(R.string.scan_stage_state)
    val total = stageLabels().size
    return { index, label ->
        String.format(java.util.Locale.ROOT, template, index + 1, total, label)
    }
}
