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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    val state by viewModel.uiState.collectAsState()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
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
                        message = if (error == OrganizeSession.ERROR_NO_SETUP) {
                            stringResource(R.string.scan_error_no_setup)
                        } else {
                            error
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
                    )
                    Text(
                        text = stringResource(
                            R.string.scan_planned_message,
                            session.plan.size,
                        ).let { if (persianDigits) it.toPersianDigits() else it },
                        style = MaterialTheme.typography.bodyLarge,
                    )
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
                    )
                    Text(
                        text = stringResource(R.string.scan_running_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
