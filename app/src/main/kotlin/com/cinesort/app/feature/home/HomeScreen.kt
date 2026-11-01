package com.cinesort.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
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

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) },
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
                            text = stringResource(R.string.home_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = onScanClick,
                            enabled = state.canScan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.home_scan_button),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        if (!state.canScan) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(
                                    if (state.destination == null) {
                                        R.string.home_scan_disabled_no_destination
                                    } else {
                                        R.string.home_scan_disabled_no_sources
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Warnings
            val warnings = mutableListOf<String>()
            if (state.destination == null) {
                warnings += stringResource(R.string.home_warning_no_destination)
            }
            if (state.sourcesCount == 0) {
                warnings += stringResource(R.string.home_warning_no_sources)
            }
            if (state.anySourceInvalid) {
                warnings += stringResource(R.string.home_warning_source_needs_access)
            }
            if (state.destination != null && !state.destinationValid) {
                warnings += stringResource(R.string.home_warning_destination_needs_access)
            }
            if (!state.hasApiKey) {
                warnings += stringResource(R.string.home_warning_no_api_key)
            }
            if (warnings.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = stringResource(R.string.home_warnings_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        warnings.forEach { warning ->
                            Text(
                                text = "• " + warning,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        OutlinedButton(onClick = onOpenSources) {
                            Text(stringResource(R.string.tab_sources))
                        }
                    }
                }
            }

            // Stat tiles
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile(
                    value = state.sourcesCount.toString()
                        .let { if (persianDigits) it.toPersianDigits() else it },
                    label = stringResource(R.string.home_stat_sources),
                    modifier = Modifier.weight(1f),
                )
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

            // Naming quick chips (persisted, same settings as the Settings screen)
            Text(
                text = stringResource(R.string.home_naming_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = state.settings.movieAddGenres,
                    onClick = {
                        viewModel.onGenresChipChanged(!state.settings.movieAddGenres)
                    },
                    label = { Text(stringResource(R.string.home_genres)) },
                )
                FilterChip(
                    selected = state.settings.movieAddRating,
                    onClick = {
                        viewModel.onRatingChipChanged(!state.settings.movieAddRating)
                    },
                    label = { Text(stringResource(R.string.home_rating)) },
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
                                Text(stringResource(R.string.home_undo))
                            }
                        }
                    }
                }
            }
        }
    }
}
