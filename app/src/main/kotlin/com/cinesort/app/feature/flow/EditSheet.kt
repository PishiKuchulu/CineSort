package com.cinesort.app.feature.flow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.cinesort.app.R
import com.cinesort.app.orchestration.ItemEdit
import com.cinesort.core.data.metadata.MetadataCandidate
import com.cinesort.core.designsystem.component.LtrText
import com.cinesort.core.designsystem.theme.MonoFontFamily
import com.cinesort.core.domain.planner.ConflictChoice
import com.cinesort.core.domain.planner.ItemType
import com.cinesort.core.domain.planner.PlannedItem
import com.cinesort.core.domain.planner.PlannedStatus
import com.cinesort.core.parser.ParseResult
import java.util.Locale

/**
 * Edit sheet for one planned item (spec 10.4 screen 5): the segmented
 * "Why?" explanation from the parse trace, editable type/title/year/season/
 * episodes, metadata candidate search, scope + remember switches, and the
 * conflict resolution controls for CONFLICT items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSheet(
    item: PlannedItem,
    rawParse: ParseResult?,
    conflictChoice: ConflictChoice?,
    targetExists: Boolean,
    hasApiKey: Boolean,
    candidates: List<MetadataCandidate>,
    candidatesSearched: Boolean,
    isSearching: Boolean,
    onDismiss: () -> Unit,
    onApply: (ItemEdit, Boolean, Boolean) -> Unit,
    onConflictChoice: (ConflictChoice, Boolean) -> Unit,
    onSearch: (ItemType, String, Int?) -> Unit,
    onCandidateChosen: (MetadataCandidate) -> Unit,
) {
    val effective = item.effective
    var type by remember(item.id) {
        mutableStateOf(item.type ?: ItemType.MOVIE)
    }
    var title by remember(item.id) {
        mutableStateOf(
            when (effective) {
                is ParseResult.Movie -> effective.title
                is ParseResult.Episode -> effective.seriesTitle
                else -> item.scanned.file.name.substringBeforeLast('.', "")
            },
        )
    }
    var year by remember(item.id) {
        mutableStateOf(
            when (effective) {
                is ParseResult.Movie -> effective.year?.toString() ?: ""
                is ParseResult.Episode -> effective.seriesYearHint?.toString() ?: ""
                else -> ""
            },
        )
    }
    var season by remember(item.id) {
        mutableStateOf(
            (effective as? ParseResult.Episode)?.season?.toString() ?: "1",
        )
    }
    var episodes by remember(item.id) {
        mutableStateOf(
            (effective as? ParseResult.Episode)?.episodes?.joinToString(", ") ?: "1",
        )
    }
    var applyToSeries by remember(item.id) { mutableStateOf(false) }
    var rememberCorrection by remember(item.id) { mutableStateOf(false) }
    var conflictApplyAll by remember(item.id) { mutableStateOf(false) }
    var chosenConflict by remember(item.id) { mutableStateOf(conflictChoice) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.edit_title),
                style = MaterialTheme.typography.titleLarge,
            )
            LtrText(
                text = item.scanned.file.name,
                style = MaterialTheme.typography.bodyMedium,
            )

            // "Why?" — segmented explanation + trace rules.
            Text(
                text = stringResource(R.string.edit_why),
                style = MaterialTheme.typography.titleMedium,
            )
            NameSegments(item = item, rawParse = rawParse)
            val trace = (item.effective ?: rawParse)?.trace
            trace?.rules?.forEach { rule ->
                Text(
                    text = ruleText(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val unrecognized = (item.effective ?: rawParse) as? ParseResult.Unrecognized
            if (unrecognized != null) {
                LtrText(
                    text = stringResource(
                        R.string.edit_unrecognized_reason,
                        unrecognized.reason,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Editable fields.
            Text(
                text = stringResource(R.string.edit_type),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = type == ItemType.MOVIE,
                    onClick = { type = ItemType.MOVIE },
                )
                Text(stringResource(R.string.preview_type_movie))
                Spacer(Modifier.width(16.dp))
                RadioButton(
                    selected = type == ItemType.EPISODE,
                    onClick = { type = ItemType.EPISODE },
                )
                Text(stringResource(R.string.preview_type_series))
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.edit_field_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = year,
                onValueChange = { year = it.filter { c -> c.isDigit() }.take(4) },
                label = { Text(stringResource(R.string.edit_field_year)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (type == ItemType.EPISODE) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = season,
                        onValueChange = {
                            season = it.filter { c -> c.isDigit() }.take(3)
                        },
                        label = { Text(stringResource(R.string.edit_field_season)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = episodes,
                        onValueChange = { episodes = it },
                        label = { Text(stringResource(R.string.edit_field_episodes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            SwitchRow(
                label = stringResource(R.string.edit_apply_series),
                checked = applyToSeries,
                onCheckedChange = { applyToSeries = it },
            )
            SwitchRow(
                label = stringResource(R.string.edit_remember),
                checked = rememberCorrection,
                onCheckedChange = { rememberCorrection = it },
            )

            // Metadata search.
            Text(
                text = stringResource(R.string.edit_search_metadata),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!hasApiKey) {
                Text(
                    text = stringResource(R.string.edit_no_keys),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(
                    onClick = {
                        onSearch(type, title.trim(), year.toIntOrNull())
                    },
                    enabled = !isSearching && title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.edit_search_metadata))
                }
                if (candidatesSearched && candidates.isEmpty() && !isSearching) {
                    Text(
                        text = stringResource(R.string.edit_no_candidates),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(candidates, key = { it.title + (it.year ?: 0) }) { candidate ->
                        CandidateRow(
                            candidate = candidate,
                            onClick = {
                                title = candidate.title
                                year = candidate.year?.toString() ?: year
                                onCandidateChosen(candidate)
                            },
                        )
                    }
                }
            }

            // Conflict resolution.
            if (item.status == PlannedStatus.CONFLICT || chosenConflict != null) {
                Text(
                    text = stringResource(R.string.edit_conflict_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (targetExists) {
                    Text(
                        text = stringResource(
                            R.string.edit_conflict_target,
                            item.targetPath,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    item.existingTarget?.let { existing ->
                        Text(
                            text = stringResource(
                                R.string.edit_conflict_existing,
                                com.cinesort.core.designsystem.component.formatBytes(existing.size),
                                java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.MEDIUM,
                                    java.text.DateFormat.SHORT,
                                ).format(java.util.Date(existing.lastModified)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (existing.size == item.scanned.file.size) {
                            Text(
                                text = stringResource(R.string.edit_conflict_probably_same),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                } else {
                    LtrText(
                        text = item.targetPath,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = chosenConflict == ConflictChoice.SKIP,
                        onClick = {
                            chosenConflict = ConflictChoice.SKIP
                            onConflictChoice(ConflictChoice.SKIP, conflictApplyAll)
                        },
                    )
                    Text(stringResource(R.string.edit_conflict_skip))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = chosenConflict == ConflictChoice.KEEP_BOTH,
                        onClick = {
                            chosenConflict = ConflictChoice.KEEP_BOTH
                            onConflictChoice(ConflictChoice.KEEP_BOTH, conflictApplyAll)
                        },
                    )
                    Text(stringResource(R.string.edit_conflict_keep_both))
                }
                SwitchRow(
                    label = stringResource(R.string.edit_conflict_apply_all),
                    checked = conflictApplyAll,
                    onCheckedChange = { conflictApplyAll = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onApply(
                            ItemEdit(
                                type = type,
                                title = title.trim().ifBlank { null },
                                year = year.toIntOrNull(),
                                clearYear = year.isBlank(),
                                season = season.toIntOrNull(),
                                episodes = episodes.split(',')
                                    .mapNotNull { it.trim().toIntOrNull() }
                                    .ifEmpty { null },
                            ),
                            applyToSeries,
                            rememberCorrection,
                        )
                    },
                ) {
                    Text(stringResource(R.string.common_apply))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CandidateRow(
    candidate: MetadataCandidate,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (candidate.posterUrl != null) {
            AsyncImage(
                model = candidate.posterUrl,
                contentDescription = null,
                modifier = Modifier.size(width = 40.dp, height = 60.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.bodyLarge,
            )
            val details = buildList {
                candidate.year?.let { add(it.toString()) }
                candidate.rating?.let {
                    add(String.format(Locale.ROOT, "%.1f", it))
                }
            }.joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The file name split into title / anchor / ignored tail. The title and anchor
 * come from the effective parse; the tail is the raw file name after the
 * anchor token, when it can be located.
 */
@Composable
private fun NameSegments(item: PlannedItem, rawParse: ParseResult?) {
    val effective = item.effective ?: rawParse
    val titleText: String
    val anchorText: String
    when (effective) {
        is ParseResult.Movie -> {
            titleText = effective.title
            anchorText = effective.year?.toString() ?: ""
        }
        is ParseResult.Episode -> {
            titleText = effective.seriesTitle
            anchorText = "S" + effective.season.toString().padStart(2, '0') +
                effective.episodes.joinToString("") {
                    "E" + it.toString().padStart(2, '0')
                }
        }
        else -> {
            titleText = item.scanned.file.name
            anchorText = ""
        }
    }
    val tailText = remember(item.scanned.file.name, anchorText) {
        tailAfterAnchor(item.scanned.file.name, anchorText)
    }
    val titleColor = MaterialTheme.colorScheme.primary
    val anchorColor = MaterialTheme.colorScheme.error
    val tailColor = MaterialTheme.colorScheme.onSurfaceVariant
    val annotated = buildAnnotatedString {
        pushStyle(SpanStyle(color = titleColor, fontWeight = FontWeight.Bold))
        append(titleText)
        pop()
        if (anchorText.isNotEmpty()) {
            append("  ")
            pushStyle(SpanStyle(color = anchorColor, fontWeight = FontWeight.Bold))
            append(anchorText)
            pop()
        }
        if (tailText.isNotEmpty()) {
            append("  ")
            pushStyle(SpanStyle(color = tailColor))
            append(tailText)
            pop()
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = MonoFontFamily,
            textDirection = TextDirection.Ltr,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SegmentLegend(
            color = titleColor,
            label = stringResource(R.string.edit_segment_title),
        )
        if (anchorText.isNotEmpty()) {
            SegmentLegend(
                color = anchorColor,
                label = stringResource(R.string.edit_segment_anchor),
            )
        }
        if (tailText.isNotEmpty()) {
            SegmentLegend(
                color = tailColor,
                label = stringResource(R.string.edit_segment_tail),
            )
        }
    }
}

@Composable
private fun SegmentLegend(
    color: androidx.compose.ui.graphics.Color,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "■",
            color = color,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Raw file-name tail after the anchor token, or empty when not determinable. */
private fun tailAfterAnchor(fileName: String, anchorText: String): String {
    if (anchorText.isEmpty()) return ""
    val index = fileName.indexOf(anchorText, ignoreCase = true)
    if (index < 0) return ""
    var tail = fileName.substring(index + anchorText.length)
    tail = tail.trimStart(' ', '.', '_', '-', '(', '[', ')', ']')
    val dot = tail.lastIndexOf('.')
    if (dot > 0) tail = tail.substring(0, dot)
    return tail.trim()
}

/** Human-readable line for one parse-trace rule id (fallback: the raw id). */
@Composable
private fun ruleText(rule: String): String = when {
    rule == "episode:marker-in-name" -> stringResource(R.string.edit_rule_episode_marker)
    rule == "movie:last-year-before-noise" -> stringResource(R.string.edit_rule_year)
    rule == "movie:resolution-fallback" -> stringResource(R.string.edit_rule_resolution)
    rule == "movie:noise-run-fallback" -> stringResource(R.string.edit_rule_noise_run)
    rule == "episode:folder-context" -> stringResource(R.string.edit_rule_folder)
    rule.startsWith("unrecognized") -> stringResource(R.string.edit_rule_unrecognized)
    else -> rule
}
