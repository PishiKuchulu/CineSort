package com.cinesort.app.feature.flow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.PlayCircle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.work.WorkInfo
import com.cinesort.app.R
import com.cinesort.core.designsystem.component.ConfirmDialog
import com.cinesort.core.designsystem.component.EmptyState
import com.cinesort.core.designsystem.component.ReelProgress

/**
 * Run screen (spec 10.4 screen 6): film-reel progress mirroring the worker,
 * current file, counts, and a confirmed Cancel. Navigates to the Report when
 * the work succeeds; failures offer a way back to Preview or Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(
    onBack: () -> Unit,
    onOpenReport: (String) -> Unit = {},
    onOpenHome: () -> Unit = {},
    viewModel: RunViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // POST_NOTIFICATIONS on Android 13+ so the foreground progress
    // notification is visible (spec 8.3).
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* The run continues either way; the notification is best-effort. */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(state.navigateToReport, state.runId) {
        val runId = state.runId
        if (state.navigateToReport && runId != null) {
            viewModel.consumeNavigation()
            onOpenReport(runId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.run_title)) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                state.runId == null -> EmptyState(
                    icon = Icons.Filled.PlayCircle,
                    title = stringResource(R.string.run_no_run_title),
                    message = stringResource(R.string.run_no_run_message),
                    modifier = Modifier.fillMaxWidth(),
                    actionLabel = stringResource(R.string.run_back_home),
                    onAction = onOpenHome,
                )
                state.workState == WorkInfo.State.FAILED ||
                    state.workState == WorkInfo.State.CANCELLED -> {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(
                            if (state.workState == WorkInfo.State.CANCELLED) {
                                R.string.run_cancelled_title
                            } else {
                                R.string.run_failed_title
                            },
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.run_failed_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.run_back_preview))
                    }
                    OutlinedButton(
                        onClick = onOpenHome,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.run_back_home))
                    }
                }
                else -> {
                    val total = if (state.totalItems > 0) {
                        state.totalItems
                    } else {
                        state.doneItems
                    }
                    val done = maxOf(state.doneItems, state.journalDone)
                    val percent = when {
                        state.percent > 0 -> state.percent
                        total > 0 -> (done * 100) / total
                        else -> 0
                    }
                    ReelProgress(
                        progress = percent / 100f,
                        persianDigits = false,
                        currentFileName = state.currentFile,
                    )
                    Text(
                        text = if (total > 0) {
                            stringResource(R.string.run_counts, done, total)
                        } else {
                            stringResource(R.string.run_preparing)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.currentFile == null) {
                        Text(
                            text = stringResource(R.string.run_preparing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.requestCancel() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            }
        }
    }

    if (state.showCancelConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.run_cancel_confirm_title),
            message = stringResource(R.string.run_cancel_confirm_message),
            confirmLabel = stringResource(R.string.common_cancel),
            dismissLabel = stringResource(R.string.common_close),
            isDestructive = true,
            onConfirm = { viewModel.confirmCancel() },
            onDismiss = { viewModel.dismissCancel() },
        )
    }
}
