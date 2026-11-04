package com.cinesort.app.feature.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cinesort.app.R
import com.cinesort.app.util.displayNameOf
import com.cinesort.app.util.displayPathOf
import com.cinesort.app.util.rememberTreePicker
import com.cinesort.core.data.db.SourceFolderEntity
import com.cinesort.core.designsystem.component.ConfirmDialog
import com.cinesort.core.designsystem.component.DestinationCard
import com.cinesort.core.designsystem.component.EmptyState
import com.cinesort.core.designsystem.component.SourceCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val destinationPicker = rememberTreePicker { uri ->
        viewModel.onDestinationPicked(uri.toString(), displayNameOf(context, uri.toString()))
    }
    val sourcePicker = rememberTreePicker { uri ->
        viewModel.onSourcePicked(uri.toString(), displayNameOf(context, uri.toString()))
    }

    val rootsMessage = state.rootsResult?.let { result ->
        if (result.error != null) {
            stringResource(R.string.roots_prepare_error, result.error)
        } else if (result.created.isEmpty() && result.found.isEmpty()) {
            stringResource(R.string.sources_roots_none)
        } else {
            stringResource(
                R.string.sources_roots_result,
                result.created.joinToString(", ").ifEmpty { "—" },
                result.found.joinToString(", ").ifEmpty { "—" },
            )
        }
    }
    LaunchedEffect(rootsMessage) {
        if (rootsMessage != null) {
            snackbarHostState.showSnackbar(rootsMessage)
            viewModel.consumeRootsResult()
        }
    }

    // Validity is re-checked on entry (audit B.3): a grant revoked in the
    // system picker while this tab sat in the back stack used to keep
    // showing OK.
    LaunchedEffect(Unit) { viewModel.refreshValidity() }

    val alreadyAddedMessage =
        stringResource(R.string.sources_already_added)
    LaunchedEffect(state.sourceAlreadyExists) {
        if (state.sourceAlreadyExists) {
            snackbarHostState.showSnackbar(alreadyAddedMessage)
            viewModel.consumeSourceAlreadyExists()
        }
    }

    val undoWarningMessage =
        stringResource(R.string.sources_destination_undo_warning)
    LaunchedEffect(state.destinationUndoWarning) {
        if (state.destinationUndoWarning) {
            snackbarHostState.showSnackbar(undoWarningMessage)
            viewModel.consumeDestinationUndoWarning()
        }
    }

    var pendingRemoval by remember { mutableStateOf<SourceFolderEntity?>(null) }
    var pendingClearDestination by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_sources)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                StepHeader(
                    number = 1,
                    title = stringResource(R.string.sources_destination_title),
                )
            }
            item {
                val destination = state.destination
                // The current required step is highlighted with the accent
                // border (UI/UX wave 1.1.0): Step 1 while no destination
                // is chosen, Step 2 afterwards.
                val stepHighlight = Modifier.border(
                    width = 2.dp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                )
                if (destination == null) {
                    EmptyState(
                        compact = true,
                        icon = Icons.Filled.FolderOpen,
                        title = stringResource(R.string.sources_no_destination_title),
                        message = stringResource(R.string.sources_no_destination_message),
                        actionLabel = stringResource(R.string.sources_pick_destination),
                        onAction = { destinationPicker.launch(null) },
                        modifier = stepHighlight,
                    )
                } else {
                    DestinationCard(
                        name = destination.name,
                        path = displayPathOf(destination.uriString),
                        freeBytes = state.destinationFreeBytes,
                        totalBytes = null,
                        freeLabel = stringResource(R.string.sources_free_space),
                        changeLabel = stringResource(R.string.common_change),
                        statusLabel = stringResource(
                            if (state.destinationValid) {
                                R.string.sources_status_ok
                            } else {
                                R.string.sources_status_needs_access
                            },
                        ),
                        isStatusOk = state.destinationValid,
                        onChangeClick = { destinationPicker.launch(null) },
                    )
                }
            }
            if (state.destination != null) {
                item {
                    // Removing the destination was impossible in-app
                    // (audit A.1-2); Home's canScan recomputes from the
                    // cleared value, so no stale enabled state survives.
                    androidx.compose.material3.TextButton(
                        onClick = { pendingClearDestination = true },
                    ) {
                        Text(stringResource(R.string.sources_remove_destination))
                    }
                }
            }

            item {
                StepHeader(
                    number = 2,
                    title = stringResource(R.string.tab_sources),
                )
            }
            if (state.sources.isEmpty()) {
                item {
                    EmptyState(
                        compact = true,
                        icon = Icons.Filled.Folder,
                        title = stringResource(R.string.sources_empty_title),
                        message = stringResource(R.string.sources_empty_message),
                        actionLabel = stringResource(R.string.sources_add_source),
                        onAction = { sourcePicker.launch(null) },
                        // Step 2 is current only once a destination
                        // exists; before that its action stays secondary
                        // so the screen has exactly one primary button.
                        actionOutlined = state.destination == null,
                        modifier = if (state.destination != null) {
                            Modifier.border(
                                width = 2.dp,
                                color = androidx.compose.material3.MaterialTheme
                                    .colorScheme.primary,
                                shape = androidx.compose.foundation.shape
                                    .RoundedCornerShape(16.dp),
                            )
                        } else {
                            Modifier
                        },
                    )
                }
            } else {
                items(state.sources, key = { it.id }) { source ->
                    val isValid = state.sourceValidity[source.id] ?: true
                    SourceCard(
                        name = source.name,
                        path = displayPathOf(source.uriString),
                        includeSubfolders = source.includeSubfolders,
                        includeSubfoldersLabel = stringResource(
                            R.string.sources_include_subfolders,
                        ),
                        // With the stepper wired, the label is the plain
                        // "Scan depth"; the value shows in the stepper.
                        depthLabel = stringResource(R.string.sources_depth_label),
                        statusLabel = stringResource(
                            if (isValid) {
                                R.string.sources_status_ok
                            } else {
                                R.string.sources_status_needs_access
                            },
                        ),
                        isStatusOk = isValid,
                        onIncludeSubfoldersChange = { include ->
                            viewModel.onSourceOptionsChanged(
                                source.id,
                                include,
                                source.maxDepth,
                            )
                        },
                        depthValue = source.maxDepth,
                        onDepthChange = { depth ->
                            viewModel.onSourceOptionsChanged(
                                source.id,
                                source.includeSubfolders,
                                depth,
                            )
                        },
                        depthDecreaseLabel = stringResource(R.string.common_decrease),
                        depthIncreaseLabel = stringResource(R.string.common_increase),
                        trailing = {
                            SourceOverflowMenu(
                                onRemove = { pendingRemoval = source },
                            )
                        },
                    )
                }
                item {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { sourcePicker.launch(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                        )
                        Text(
                            text = stringResource(R.string.sources_add_source),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }

    pendingRemoval?.let { source ->
        ConfirmDialog(
            title = stringResource(R.string.sources_remove_confirm_title),
            message = stringResource(R.string.sources_remove_confirm_message),
            confirmLabel = stringResource(R.string.common_remove),
            dismissLabel = stringResource(R.string.common_cancel),
            isDestructive = true,
            onConfirm = {
                viewModel.onRemoveSource(source.id)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }

    if (pendingClearDestination) {
        ConfirmDialog(
            title = stringResource(R.string.sources_remove_destination_title),
            message = stringResource(R.string.sources_remove_destination_message),
            confirmLabel = stringResource(R.string.common_remove),
            dismissLabel = stringResource(R.string.common_cancel),
            isDestructive = true,
            onConfirm = {
                viewModel.onClearDestination()
                pendingClearDestination = false
            },
            onDismiss = { pendingClearDestination = false },
        )
    }
}

/** Numbered section header for the two-step Sources flow (UI/UX wave 1.1.0). */
@Composable
private fun StepHeader(number: Int, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text(
            text = title,
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun SourceOverflowMenu(onRemove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.common_more_options),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_remove)) },
                onClick = {
                    expanded = false
                    onRemove()
                },
            )
        }
    }
}
