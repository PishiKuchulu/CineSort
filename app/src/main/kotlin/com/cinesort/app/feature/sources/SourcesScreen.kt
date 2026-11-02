package com.cinesort.app.feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val state by viewModel.uiState.collectAsState()
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

    var pendingRemoval by remember { mutableStateOf<SourceFolderEntity?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.sources_title)) }) },
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
                Text(
                    text = stringResource(R.string.sources_destination_title),
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
            }
            item {
                val destination = state.destination
                if (destination == null) {
                    EmptyState(
                        compact = true,
                        icon = Icons.Filled.FolderOpen,
                        title = stringResource(R.string.sources_no_destination_title),
                        message = stringResource(R.string.sources_no_destination_message),
                        actionLabel = stringResource(R.string.sources_pick_destination),
                        onAction = { destinationPicker.launch(null) },
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

            item {
                Text(
                    text = stringResource(R.string.tab_sources),
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
            }
            if (state.sources.isEmpty()) {
                item {
                    EmptyState(
                        compact = true,
                        icon = Icons.Filled.FolderOpen,
                        title = stringResource(R.string.sources_empty_title),
                        message = stringResource(R.string.sources_empty_message),
                        actionLabel = stringResource(R.string.sources_add_source),
                        onAction = { sourcePicker.launch(null) },
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
                        depthLabel = stringResource(R.string.sources_depth, source.maxDepth),
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
