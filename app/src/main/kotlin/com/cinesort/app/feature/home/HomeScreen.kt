package com.cinesort.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cinesort.app.R
import com.cinesort.core.designsystem.component.FilmPerforationStrip
import com.cinesort.core.designsystem.component.StatTile
import com.cinesort.core.designsystem.component.formatBytes
import com.cinesort.core.designsystem.component.toPersianDigits
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Pure-Kotlin Jalali conversion (jalaali algorithm, truncating division as in
// the reference implementation). android.icu.util has no PersianCalendar class
// in the Android framework subset, so the ICU import did not compile on CI.
private val jalaliBreaks = intArrayOf(
    -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181,
    1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178,
)

/** Returns (leap flag, Gregorian year, March day) of Farvardin 1 for a Jalali year. */
private fun jalaliYearInfo(jalaliYear: Int): Triple<Int, Int, Int> {
    var leapJalali = -14
    var previousBreak = jalaliBreaks[0]
    var jump = 0
    for (index in 1 until jalaliBreaks.size) {
        val breakYear = jalaliBreaks[index]
        jump = breakYear - previousBreak
        if (jalaliYear < breakYear) break
        leapJalali += jump / 33 * 8 + (jump % 33) / 4
        previousBreak = breakYear
    }
    var yearsSinceBreak = jalaliYear - previousBreak
    leapJalali += yearsSinceBreak / 33 * 8 + (yearsSinceBreak % 33 + 3) / 4
    if (jump % 33 == 4 && jump - yearsSinceBreak == 4) leapJalali += 1
    val gregorianYear = jalaliYear + 621
    val leapGregorian = gregorianYear / 4 - (gregorianYear / 100 + 1) * 3 / 4 - 150
    val marchDay = 20 + leapJalali - leapGregorian
    if (jump - yearsSinceBreak < 6) {
        yearsSinceBreak = yearsSinceBreak - jump + (jump + 4) / 33 * 33
    }
    var leap = (yearsSinceBreak + 1) % 33 - 1
    leap %= 4
    if (leap == -1) leap = 4
    return Triple(leap, gregorianYear, marchDay)
}

/** Day number of a Gregorian date (month is 1-based). */
private fun gregorianDayNumber(year: Int, month: Int, day: Int): Int {
    var dayNumber = (year + (month - 8) / 6 + 100100) * 1461 / 4 +
        (153 * ((month + 9) % 12) + 2) / 5 + day - 34840408
    dayNumber -= (year + 100100 + (month - 8) / 6) / 100 * 3 / 4
    return dayNumber + 752
}

/** Converts a Gregorian date (month is 1-based) to a Jalali (year, month, day). */
private fun gregorianToJalali(year: Int, month: Int, day: Int): Triple<Int, Int, Int> {
    val dayNumber = gregorianDayNumber(year, month, day)
    var jalaliYear = year - 621
    val (leap, marchYear, marchDay) = jalaliYearInfo(jalaliYear)
    var remaining = dayNumber - gregorianDayNumber(marchYear, 3, marchDay)
    if (remaining >= 0) {
        if (remaining <= 185) {
            return Triple(jalaliYear, 1 + remaining / 31, remaining % 31 + 1)
        }
        remaining -= 186
    } else {
        jalaliYear -= 1
        remaining += 179
        if (leap == 1) remaining += 1
    }
    return Triple(jalaliYear, 7 + remaining / 30, remaining % 30 + 1)
}

/** Formats a run's wall-clock time (HH:mm), honoring the Persian-digits setting. */
fun formatRunTime(epochMillis: Long, persianDigits: Boolean): String {
    val raw = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(epochMillis))
    return if (persianDigits) raw.toPersianDigits() else raw
}

/** Formats a run timestamp, honoring the Jalali-dates and Persian-digits settings. */
fun formatRunDate(epochMillis: Long, jalali: Boolean, persianDigits: Boolean): String {
    val raw = if (jalali) {
        val gregorian = Calendar.getInstance()
        gregorian.timeInMillis = epochMillis
        val (year, month, day) = gregorianToJalali(
            gregorian.get(Calendar.YEAR),
            gregorian.get(Calendar.MONTH) + 1,
            gregorian.get(Calendar.DAY_OF_MONTH),
        )
        String.format(Locale.ROOT, "%04d/%02d/%02d", year, month, day)
    } else {
        SimpleDateFormat("yyyy/MM/dd", Locale.ROOT).format(Date(epochMillis))
    }
    return if (persianDigits) raw.toPersianDigits() else raw
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onScanClick: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
    onCompleteSetup: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val persianDigits = state.settings.persianDigits

    val undoMessage = state.undoResult?.let { result ->
        if (result.problems.isEmpty()) {
            stringResource(R.string.home_undo_done, result.movedBack)
        } else {
            stringResource(R.string.home_undo_failed)
        }
    }
    LaunchedEffect(undoMessage) {
        if (undoMessage != null) {
            snackbarHostState.showSnackbar(undoMessage)
            viewModel.consumeUndoResult()
        }
    }

    // Last-run state is refreshed on entry (audit B.2): an undo done from
    // the Report must not still be offered here on return.
    LaunchedEffect(Unit) { viewModel.refreshLastRun() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_home)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Hero card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    FilmPerforationStrip()
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            text = stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(18.dp))
                        // Single source of truth (UI/UX wave 1.1.0): the
                        // hero action follows canScan — never a disabled
                        // grey button. When setup is incomplete the button
                        // itself is the way into the wizard.
                        if (state.canScan) {
                            Button(
                                onClick = onScanClick,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.home_scan_button),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        } else {
                            Button(
                                onClick = onCompleteSetup,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.home_complete_setup),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.home_scan_needs_setup),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable(onClick = onCompleteSetup),
                            )
                        }
                        if (state.canScan && state.anySourceInvalid) {
                            // The button stays enabled with a dead source
                            // (audit B.2): the warning belongs here too,
                            // not only in the checklist below.
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(
                                    R.string.home_warning_source_needs_access,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // Setup checklist: replaces the old warnings card. Each pending
            // step navigates to the screen that completes it; the card hides
            // itself once everything is done.
            val steps = listOf(
                SetupStep(
                    number = 1,
                    done = state.destination != null && state.destinationValid,
                    text = when {
                        state.destination == null ->
                            stringResource(R.string.home_setup_destination_todo)
                        !state.destinationValid ->
                            stringResource(R.string.home_warning_destination_needs_access)
                        else -> stringResource(R.string.home_setup_destination_done)
                    },
                    onClick = onOpenSources,
                ),
                SetupStep(
                    number = 2,
                    done = state.sourcesCount > 0 && !state.anySourceInvalid,
                    text = when {
                        state.anySourceInvalid ->
                            stringResource(R.string.home_warning_source_needs_access)
                        state.sourcesCount == 0 ->
                            stringResource(R.string.home_setup_sources_todo)
                        else -> stringResource(R.string.home_setup_sources_done)
                    },
                    onClick = onOpenSources,
                ),
                SetupStep(
                    number = 3,
                    done = state.hasApiKey,
                    optional = true,
                    text = if (state.hasApiKey) {
                        stringResource(R.string.home_setup_api_done)
                    } else {
                        stringResource(R.string.home_setup_api_todo)
                    },
                    onClick = onOpenSettings,
                ),
            )
            if (steps.any { !it.done }) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.home_setup_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 2.dp),
                        )
                        steps.forEachIndexed { index, step ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            SetupStepRow(step, persianDigits)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // Stat tiles (UI/UX wave 1.1.0): before setup there is no
            // "0 source folders" tile and no "Unknown" — the destination
            // tile itself becomes the call to action.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.sourcesCount > 0) {
                    StatTile(
                        value = state.sourcesCount.toString()
                            .let { if (persianDigits) it.toPersianDigits() else it },
                        label = stringResource(R.string.home_stat_sources),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.destination == null) {
                    StatTile(
                        value = stringResource(R.string.common_not_set),
                        label = stringResource(R.string.home_stat_destination_choose),
                        modifier = Modifier.weight(1f),
                        onClick = onOpenSources,
                    )
                } else {
                    StatTile(
                        value = formatBytes(
                            state.destinationFreeBytes,
                            persianDigits,
                            stringResource(R.string.home_free_unknown),
                        ),
                        label = stringResource(R.string.home_stat_destination_free),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Naming lives only in Settings (UI/UX wave 1.1.0): Home shows
            // a one-line summary that links there instead of a second,
            // competing set of controls.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .semantics(mergeDescendants = true) {}
                    .heightIn(min = 52.dp)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_naming_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.home_naming_state,
                            stringResource(
                                if (state.settings.movieAddGenres) {
                                    R.string.common_on
                                } else {
                                    R.string.common_off
                                },
                            ),
                            stringResource(
                                if (state.settings.movieAddRating) {
                                    R.string.common_on
                                } else {
                                    R.string.common_off
                                },
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Last run card
            Text(
                text = stringResource(R.string.home_last_run_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    val lastRun = state.lastRun
                    if (lastRun == null) {
                        Text(
                            text = stringResource(R.string.home_last_run_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (lastRun.finishedAt == null && !lastRun.undone) {
                        // A run still in progress reads as progress, not
                        // as a finished result with an Undo (UI/UX wave).
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(
                                text = stringResource(R.string.history_status_running),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = formatRunDate(
                                lastRun.startedAt,
                                state.settings.jalaliDates,
                                persianDigits,
                            ) + " · " + formatRunTime(lastRun.startedAt, persianDigits),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = formatRunDate(
                                lastRun.startedAt,
                                state.settings.jalaliDates,
                                persianDigits,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.home_last_run_summary,
                                state.lastRunDoneCount,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (lastRun.undone) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.home_last_run_undone),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Spacer(Modifier.height(14.dp))
                            OutlinedButton(
                                onClick = { viewModel.onUndoLastRun() },
                                enabled = !state.isUndoing && state.destination != null,
                            ) {
                                if (state.isUndoing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.home_undo))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One row of the Home setup checklist. */
private data class SetupStep(
    val number: Int,
    val done: Boolean,
    val text: String,
    val onClick: () -> Unit,
    val optional: Boolean = false,
)

@Composable
private fun SetupStepRow(step: SetupStep, persianDigits: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = step.onClick)
            // One merged accessibility node per step and a comfortable
            // 52dp minimum touch height (audit 1-5).
            .semantics(mergeDescendants = true) {}
            .heightIn(min = 52.dp)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Numbered badge instead of a radio-like hollow circle (UI/UX
        // wave 1.1.0): pending steps show their number, done steps a tick.
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(
                    color = if (step.done) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (step.done) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = step.number.toString()
                        .let { if (persianDigits) it.toPersianDigits() else it },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = step.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (step.done) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        if (step.optional && !step.done) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(
                    text = stringResource(R.string.home_setup_optional),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (!step.done) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
