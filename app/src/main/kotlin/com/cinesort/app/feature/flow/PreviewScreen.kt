package com.cinesort.app.feature.flow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.cinesort.app.orchestration.OrganizeSession
import com.cinesort.core.designsystem.component.ConfidenceBadge
import com.cinesort.core.designsystem.component.ConfidenceLevel
import com.cinesort.core.designsystem.component.ConfirmDialog
import com.cinesort.core.designsystem.component.EmptyState
import com.cinesort.core.designsystem.component.ErrorState
import com.cinesort.core.designsystem.component.LtrText
import com.cinesort.core.designsystem.component.MediaKind
import com.cinesort.core.designsystem.component.MediaTypeChip
import com.cinesort.core.designsystem.component.ProviderBadge
import com.cinesort.core.designsystem.component.formatBytes
import com.cinesort.core.designsystem.component.toPersianDigits
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.planner.PlannedStatus
import com.cinesort.core.domain.planner.RatingSource
import com.cinesort.core.parser.Confidence
import com.cinesort.core.parser.ParseResult
import java.util.Locale

/**
 * Preview screen (spec 10.4 screen 5): summary header, filters + search,
 * live naming options, target-folder groups with selectable rows, the edit
 * sheet, bulk selection actions, and the organize confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    onBack: () -> Unit,
    onOpenRun: () -> Unit = {},
    viewModel: PreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val session = state.session
    val plan = session.plan
    val persianDigits = state.settings.persianDigits

    LaunchedEffect(state.navigateToRun) {
        if (state.navigateToRun) {
            viewModel.consumeNavigation()
            onOpenRun()
        }
    }

    val filtered = remember(plan, state.filter, state.query) {
        plan.filter { item -> matchesFilter(item, state.filter, state.query) }
    }
    val groups = remember(filtered) {
        filtered.groupBy { it.targetFolderPath }
            .entries.sortedBy { it.key }
    }
    val selectedItems = plan.filter { it.selected && it.status == PlannedStatus.READY }
    val selectedBytes = selectedItems.sumOf { it.sizeBytes }
    val crossItems = selectedItems.filter { it.crossVolume }
    val crossBytes = crossItems.sumOf { it.sizeBytes }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preview_title)) },
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
        bottomBar = {
            if (plan.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.preview_selected_summary,
                            selectedItems.size,
                            formatBytes(selectedBytes, persianDigits),
                        ).let { if (persianDigits) it.toPersianDigits() else it },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.requestOrganize() },
                        enabled = selectedItems.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                R.string.preview_organize,
                                selectedItems.size,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        when {
            session.error != null -> ErrorState(
                icon = Icons.Filled.ErrorOutline,
                title = stringResource(R.string.preview_error_title),
                message = session.error ?: "",
                retryLabel = stringResource(R.string.flow_go_home),
                onRetry = onBack,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            plan.isEmpty() -> EmptyState(
                icon = Icons.Filled.Movie,
                title = stringResource(R.string.preview_empty_title),
                message = stringResource(R.string.preview_empty_message),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                actionLabel = stringResource(R.string.flow_go_home),
                onAction = onBack,
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { SummaryHeader(plan = plan, crossBytes = crossBytes, persianDigits = persianDigits) }
                item {
                    FilterAndSearch(
                        filter = state.filter,
                        query = state.query,
                        onFilterChange = { viewModel.setFilter(it) },
                        onQueryChange = { viewModel.setQuery(it) },
                    )
                }
                item {
                    NamingRow(
                        genres = state.settings.movieAddGenres || state.settings.seriesAddGenres,
                        rating = state.settings.movieAddRating || state.settings.seriesAddRating,
                        maxGenres = state.settings.maxGenres,
                        onGenresChange = { viewModel.onGenresChanged(it) },
                        onRatingChange = { viewModel.onRatingChanged(it) },
                        onMaxGenresChange = { viewModel.onMaxGenresChanged(it) },
                    )
                }
                item { BulkActions(viewModel = viewModel) }
                if (filtered.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.Search,
                            title = stringResource(R.string.preview_no_results_title),
                            message = stringResource(R.string.preview_no_results_message),
                        )
                    }
                }
                groups.forEach { (folderPath, items) ->
                    val expanded = folderPath !in state.collapsedGroups
                    item(key = "group:$folderPath") {
                        GroupHeader(
                            folderPath = folderPath,
                            items = items,
                            expanded = expanded,
                            persianDigits = persianDigits,
                            onToggleExpand = { viewModel.toggleGroup(folderPath) },
                            onGroupSelected = { selected ->
                                viewModel.setGroupSelected(folderPath, selected)
                            },
                        )
                    }
                    if (expanded) {
                        items.forEach { planned ->
                            item(key = planned.id) {
                                PreviewRow(
                                    item = planned,
                                    persianDigits = persianDigits,
                                    onSelectedChange = { selected ->
                                        viewModel.setSelected(planned.id, selected)
                                    },
                                    onClick = { viewModel.openEdit(planned.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Edit sheet for the tapped row.
    val editingItem = state.editingItemId?.let { id -> plan.firstOrNull { it.id == id } }
    if (editingItem != null) {
        EditSheet(
            item = editingItem,
            rawParse = session.parseResults[editingItem.id],
            conflictChoice = session.conflictChoices[editingItem.id],
            targetExists = session.destinationSnapshot?.files
                ?.contains(editingItem.targetPath) == true,
            hasApiKey = state.hasApiKey,
            candidates = state.candidates,
            candidatesSearched = state.candidatesSearched,
            isSearching = state.isSearchingCandidates,
            onDismiss = { viewModel.dismissEdit() },
            onApply = { edit, applyToSeries, rememberCorrection ->
                viewModel.applyEdit(editingItem.id, edit, applyToSeries, rememberCorrection)
            },
            onConflictChoice = { choice, applyToAll ->
                if (applyToAll) {
                    viewModel.setConflictChoice(null, choice)
                } else {
                    viewModel.setConflictChoice(editingItem.id, choice)
                }
            },
            onSearch = { type, title, year ->
                viewModel.searchCandidates(type, title, year)
            },
            onCandidateChosen = { candidate ->
                viewModel.chooseCandidate(editingItem.id, candidate)
            },
        )
    }

    if (state.showConfirm) {
        val message = buildString {
            append(
                stringResource(
                    R.string.preview_confirm_message,
                    selectedItems.size,
                    formatBytes(selectedBytes, persianDigits),
                ),
            )
            if (crossBytes > 0) {
                append("\n")
                append(
                    stringResource(
                        R.string.preview_confirm_cross,
                        formatBytes(crossBytes, persianDigits),
                    ),
                )
            }
        }
        ConfirmDialog(
            title = stringResource(R.string.preview_confirm_title),
            message = message,
            confirmLabel = stringResource(R.string.preview_organize, selectedItems.size),
            dismissLabel = stringResource(R.string.common_cancel),
            onConfirm = { viewModel.confirmOrganize() },
            onDismiss = { viewModel.dismissConfirm() },
        )
    }
}

// ------------------------------------------------------------ sections

@Composable
private fun SummaryHeader(
    plan: List<PlannedItem>,
    crossBytes: Long,
    persianDigits: Boolean,
) {
    fun countText(count: Int): String =
        count.toString().let { if (persianDigits) it.toPersianDigits() else it }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SummaryStat(
                    value = countText(plan.count { it.type == ItemType.MOVIE }),
                    label = stringResource(R.string.preview_label_movies),
                    modifier = Modifier.weight(1f),
                )
                SummaryStat(
                    value = countText(plan.count { it.type == ItemType.EPISODE }),
                    label = stringResource(R.string.preview_label_episodes),
                    modifier = Modifier.weight(1f),
                )
                SummaryStat(
                    value = countText(
                        plan.count { it.status == PlannedStatus.NEEDS_REVIEW },
                    ),
                    label = stringResource(R.string.preview_label_needs_review),
                    modifier = Modifier.weight(1f),
                )
                SummaryStat(
                    value = countText(
                        plan.count { it.status == PlannedStatus.CONFLICT },
                    ),
                    label = stringResource(R.string.preview_label_conflicts),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(R.string.preview_label_total_size) + ": " +
                    formatBytes(plan.sumOf { it.sizeBytes }, persianDigits),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (crossBytes > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            R.string.preview_cross_volume,
                            formatBytes(crossBytes, persianDigits),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = value, style = MaterialTheme.typography.titleLarge)
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FilterAndSearch(
    filter: PreviewFilter,
    query: String,
    onFilterChange: (PreviewFilter) -> Unit,
    onQueryChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filter == PreviewFilter.ALL,
                onClick = { onFilterChange(PreviewFilter.ALL) },
                label = { Text(stringResource(R.string.preview_filter_all)) },
            )
            FilterChip(
                selected = filter == PreviewFilter.MOVIES,
                onClick = { onFilterChange(PreviewFilter.MOVIES) },
                label = { Text(stringResource(R.string.preview_filter_movies)) },
            )
            FilterChip(
                selected = filter == PreviewFilter.SERIES,
                onClick = { onFilterChange(PreviewFilter.SERIES) },
                label = { Text(stringResource(R.string.preview_filter_series)) },
            )
            FilterChip(
                selected = filter == PreviewFilter.NEEDS_REVIEW,
                onClick = { onFilterChange(PreviewFilter.NEEDS_REVIEW) },
                label = { Text(stringResource(R.string.preview_filter_needs_review)) },
            )
            FilterChip(
                selected = filter == PreviewFilter.CONFLICTS,
                onClick = { onFilterChange(PreviewFilter.CONFLICTS) },
                label = { Text(stringResource(R.string.preview_filter_conflicts)) },
            )
            FilterChip(
                selected = filter == PreviewFilter.SKIPPED,
                onClick = { onFilterChange(PreviewFilter.SKIPPED) },
                label = { Text(stringResource(R.string.preview_filter_skipped)) },
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(R.string.preview_search_hint)) },
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NamingRow(
    genres: Boolean,
    rating: Boolean,
    maxGenres: Int,
    onGenresChange: (Boolean) -> Unit,
    onRatingChange: (Boolean) -> Unit,
    onMaxGenresChange: (Int) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.preview_naming_genres),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = genres, onCheckedChange = onGenresChange)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.preview_naming_rating),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = rating, onCheckedChange = onRatingChange)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.preview_naming_max_genres),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onMaxGenresChange(maxGenres - 1) },
                        enabled = maxGenres > 1,
                    ) {
                        Icon(imageVector = Icons.Filled.Remove, contentDescription = null)
                    }
                    Text(
                        text = maxGenres.toString(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(
                        onClick = { onMaxGenresChange(maxGenres + 1) },
                        enabled = maxGenres < 6,
                    ) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun BulkActions(viewModel: PreviewViewModel) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = { viewModel.selectAllReady() }) {
            Text(stringResource(R.string.preview_bulk_select_ready))
        }
        TextButton(onClick = { viewModel.deselectNeedsReview() }) {
            Text(stringResource(R.string.preview_bulk_deselect_review))
        }
        TextButton(onClick = { viewModel.clearSelection() }) {
            Text(stringResource(R.string.preview_bulk_clear))
        }
    }
}

@Composable
private fun GroupHeader(
    folderPath: String,
    items: List<PlannedItem>,
    expanded: Boolean,
    persianDigits: Boolean,
    onToggleExpand: () -> Unit,
    onGroupSelected: (Boolean) -> Unit,
) {
    val readyItems = items.filter { it.status == PlannedStatus.READY }
    val allSelected = readyItems.isNotEmpty() && readyItems.all { it.selected }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = allSelected,
                onCheckedChange = { onGroupSelected(it) },
                enabled = readyItems.isNotEmpty(),
            )
            Column(Modifier.weight(1f)) {
                if (folderPath.isEmpty()) {
                    Text(
                        text = stringResource(R.string.preview_group_unplanned),
                        style = MaterialTheme.typography.titleSmall,
                    )
                } else {
                    LtrText(
                        text = folderPath,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    text = stringResource(R.string.preview_group_files, items.size)
                        .let { if (persianDigits) it.toPersianDigits() else it },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.ExpandLess
                } else {
                    Icons.Filled.ExpandMore
                },
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun PreviewRow(
    item: PlannedItem,
    persianDigits: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = item.selected,
                onCheckedChange = onSelectedChange,
                enabled = item.status == PlannedStatus.READY,
            )
            Column(Modifier.weight(1f)) {
                LtrText(
                    text = item.scanned.file.name,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (item.type) {
                        ItemType.MOVIE -> MediaTypeChip(
                            kind = MediaKind.MOVIE,
                            label = stringResource(R.string.preview_type_movie),
                        )
                        ItemType.EPISODE -> MediaTypeChip(
                            kind = MediaKind.SERIES,
                            label = stringResource(R.string.preview_type_series),
                        )
                        null -> Unit
                    }
                    val effective = item.effective
                    if (effective != null) {
                        ConfidenceBadge(
                            level = when (effective.confidence) {
                                Confidence.HIGH -> ConfidenceLevel.HIGH
                                Confidence.MEDIUM -> ConfidenceLevel.MEDIUM
                                Confidence.LOW -> ConfidenceLevel.LOW
                            },
                            label = stringResource(
                                when (effective.confidence) {
                                    Confidence.HIGH -> R.string.preview_confidence_high
                                    Confidence.MEDIUM -> R.string.preview_confidence_medium
                                    Confidence.LOW -> R.string.preview_confidence_low
                                },
                            ),
                        )
                        if (effective is ParseResult.Episode && effective.fromFolderContext) {
                            ProviderBadge(
                                label = stringResource(R.string.preview_from_folder),
                            )
                        }
                    }
                    val metadata = item.metadata
                    if (metadata?.rating != null) {
                        ProviderBadge(
                            label = ratingBadgeLabel(
                                metadata.rating,
                                metadata.ratingSource,
                            ),
                        )
                    }
                    StatusLabel(item = item)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatBytes(item.sizeBytes, persianDigits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.companions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.preview_companions) + ":",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.companions.forEach { companion ->
                        LtrText(
                            text = companion.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (item.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (
                                OrganizeSession.WARNING_METADATA_HELD in item.warnings
                            ) {
                                stringResource(R.string.preview_metadata_held)
                            } else {
                                stringResource(
                                    R.string.preview_warnings,
                                    item.warnings.size,
                                ).let {
                                    if (persianDigits) it.toPersianDigits() else it
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(item: PlannedItem) {
    val labelRes = when (item.status) {
        PlannedStatus.CONFLICT -> R.string.preview_status_conflict
        PlannedStatus.NEEDS_REVIEW -> R.string.preview_status_needs_review
        PlannedStatus.SKIPPED -> R.string.preview_status_skipped
        PlannedStatus.ALREADY_ORGANIZED -> R.string.preview_status_already
        PlannedStatus.RECENTLY_MODIFIED -> R.string.preview_status_recent
        PlannedStatus.READY -> null
    } ?: return
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

/** "IMDb 8.5" style badge text; the rating keeps ASCII digits (spec 7.1). */
private fun ratingBadgeLabel(rating: Double?, source: RatingSource?): String {
    val sourceLabel = when (source) {
        RatingSource.IMDB -> "IMDb"
        RatingSource.TMDB -> "TMDB"
        RatingSource.OMDB -> "OMDb"
        null -> ""
    }
    val value = rating?.let { String.format(Locale.ROOT, "%.1f", it) } ?: ""
    return listOf(sourceLabel, value).filter { it.isNotEmpty() }.joinToString(" ")
}

private fun matchesFilter(
    item: PlannedItem,
    filter: PreviewFilter,
    query: String,
): Boolean {
    val filterOk = when (filter) {
        PreviewFilter.ALL -> true
        PreviewFilter.MOVIES -> item.type == ItemType.MOVIE
        PreviewFilter.SERIES -> item.type == ItemType.EPISODE
        PreviewFilter.NEEDS_REVIEW -> item.status == PlannedStatus.NEEDS_REVIEW
        PreviewFilter.CONFLICTS -> item.status == PlannedStatus.CONFLICT
        PreviewFilter.SKIPPED -> item.status == PlannedStatus.SKIPPED ||
            item.status == PlannedStatus.ALREADY_ORGANIZED ||
            item.status == PlannedStatus.RECENTLY_MODIFIED
    }
    if (!filterOk) return false
    if (query.isBlank()) return true
    val title = when (val effective = item.effective) {
        is ParseResult.Movie -> effective.title
        is ParseResult.Episode -> effective.seriesTitle
        else -> ""
    }
    return item.scanned.file.name.contains(query, ignoreCase = true) ||
        title.contains(query, ignoreCase = true)
}
