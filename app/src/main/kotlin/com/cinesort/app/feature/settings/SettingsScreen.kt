package com.cinesort.app.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.cinesort.app.AppInfo
import com.cinesort.app.BuildConfig
import com.cinesort.app.feature.update.AppUpdateController
import com.cinesort.app.R
import com.cinesort.core.data.settings.ConflictDefault
import com.cinesort.core.data.settings.MetadataUnavailableBehavior
import com.cinesort.core.data.settings.RatingSourcePreference
import com.cinesort.core.data.settings.ThemeMode
import com.cinesort.core.designsystem.component.ApiKeyField
import com.cinesort.core.designsystem.component.toPersianDigits
import com.cinesort.core.parser.TitleCaseMode
import kotlinx.coroutines.launch

private const val TMDB_HELP_URL = "https://www.themoviedb.org/settings/api"
private const val OMDB_HELP_URL = "https://www.omdbapi.com/apikey.aspx"

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onValueChange((value - 1).coerceAtLeast(min)) },
                enabled = value > min,
            ) {
                Icon(imageVector = Icons.Filled.Remove, contentDescription = null)
            }
            Text(text = value.toString(), style = MaterialTheme.typography.titleMedium)
            IconButton(
                onClick = { onValueChange((value + 1).coerceAtMost(max)) },
                enabled = value < max,
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenSources: () -> Unit,
    updateController: AppUpdateController,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val settings = state.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val cacheClearedMessage = stringResource(R.string.settings_cache_cleared)
    LaunchedEffect(state.cacheCleared) {
        if (state.cacheCleared) {
            snackbarHostState.showSnackbar(cacheClearedMessage)
            viewModel.consumeCacheCleared()
        }
    }

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. Language
            item { SectionHeader(stringResource(R.string.settings_language)) }
            item {
                SettingsCard {
                    Text(
                        text = stringResource(R.string.settings_language),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = (settings.languageCode ?: "en") == "fa",
                            onClick = {
                                // Language pick also sets its digit/date
                                // conventions; the toggles below can still
                                // override them afterwards.
                                viewModel.update {
                                    it.copy(
                                        languageCode = "fa",
                                        persianDigits = true,
                                        jalaliDates = true,
                                    )
                                }
                                AppCompatDelegate.setApplicationLocales(
                                    LocaleListCompat.forLanguageTags("fa"),
                                )
                            },
                            label = { Text(stringResource(R.string.settings_language_fa)) },
                        )
                        FilterChip(
                            selected = (settings.languageCode ?: "en") == "en",
                            onClick = {
                                viewModel.update {
                                    it.copy(
                                        languageCode = "en",
                                        persianDigits = false,
                                        jalaliDates = false,
                                    )
                                }
                                AppCompatDelegate.setApplicationLocales(
                                    LocaleListCompat.forLanguageTags("en"),
                                )
                            },
                            label = { Text(stringResource(R.string.settings_language_en)) },
                        )
                    }
                    SwitchRow(
                        label = stringResource(R.string.settings_persian_digits),
                        checked = settings.persianDigits,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(persianDigits = value) }
                        },
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_jalali_dates),
                        checked = settings.jalaliDates,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(jalaliDates = value) }
                        },
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.settings_section_appearance)) }
            item {
                SettingsCard {
                    Text(
                        text = stringResource(R.string.settings_theme),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = settings.themeMode == ThemeMode.SYSTEM,
                            onClick = {
                                viewModel.update { it.copy(themeMode = ThemeMode.SYSTEM) }
                            },
                            label = { Text(stringResource(R.string.settings_theme_system)) },
                        )
                        FilterChip(
                            selected = settings.themeMode == ThemeMode.DARK,
                            onClick = {
                                viewModel.update { it.copy(themeMode = ThemeMode.DARK) }
                            },
                            label = { Text(stringResource(R.string.settings_theme_dark)) },
                        )
                        FilterChip(
                            selected = settings.themeMode == ThemeMode.LIGHT,
                            onClick = {
                                viewModel.update { it.copy(themeMode = ThemeMode.LIGHT) }
                            },
                            label = { Text(stringResource(R.string.settings_theme_light)) },
                        )
                    }
                    SwitchRow(
                        label = stringResource(R.string.settings_dynamic_color),
                        checked = settings.dynamicColor,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(dynamicColor = value) }
                        },
                    )
                }
            }

            // 2. Folders
            item { SectionHeader(stringResource(R.string.settings_folders)) }
            item {
                SettingsCard {
                    Text(
                        text = stringResource(R.string.settings_folders_destination),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = state.destinationName
                            ?: stringResource(R.string.common_not_set),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onOpenSources) {
                        Text(stringResource(R.string.settings_folders_manage))
                    }
                }
            }

            // 3. Naming
            item { SectionHeader(stringResource(R.string.settings_naming)) }
            item {
                SettingsCard {
                    Text(
                        text = stringResource(R.string.preview_filter_movies),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        label = stringResource(R.string.settings_movie_add_year),
                        checked = settings.movieAddYear,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(movieAddYear = value) }
                        },
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_movie_add_genres),
                        checked = settings.movieAddGenres,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(movieAddGenres = value) }
                        },
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_movie_add_rating),
                        checked = settings.movieAddRating,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(movieAddRating = value) }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.preview_filter_series),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        label = stringResource(R.string.settings_series_add_year),
                        checked = settings.seriesAddYear,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(seriesAddYear = value) }
                        },
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_series_add_genres),
                        checked = settings.seriesAddGenres,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(seriesAddGenres = value) }
                        },
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_series_add_rating),
                        checked = settings.seriesAddRating,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(seriesAddRating = value) }
                        },
                    )
                    StepperRow(
                        label = stringResource(R.string.settings_max_genres_label),
                        value = settings.maxGenres,
                        min = 1,
                        max = 6,
                        onValueChange = { value ->
                            viewModel.update { it.copy(maxGenres = value) }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_title_casing),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = settings.titleCaseMode == TitleCaseMode.SMART,
                            onClick = {
                                viewModel.update { it.copy(titleCaseMode = TitleCaseMode.SMART) }
                            },
                            label = {
                                Text(stringResource(R.string.settings_title_casing_smart))
                            },
                        )
                        FilterChip(
                            selected = settings.titleCaseMode == TitleCaseMode.AS_IN_FILENAME,
                            onClick = {
                                viewModel.update {
                                    it.copy(titleCaseMode = TitleCaseMode.AS_IN_FILENAME)
                                }
                            },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.settings_title_casing_as_filename,
                                    ),
                                )
                            },
                        )
                    }
                    SwitchRow(
                        label = stringResource(R.string.settings_fill_missing_year),
                        checked = settings.fillMissingYear,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(fillMissingYear = value) }
                        },
                    )
                }
            }

            // 4. API keys
            item { SectionHeader(stringResource(R.string.settings_section_api_keys)) }
            item {
                SettingsCard {
                    ApiKeyField(
                        label = stringResource(R.string.settings_tmdb_key),
                        value = state.tmdbKey,
                        onValueChange = { viewModel.onTmdbKeyChange(it) },
                        testLabel = stringResource(R.string.settings_test_key),
                        onTest = { viewModel.testTmdbKey() },
                        helpLabel = stringResource(R.string.settings_get_key),
                        onHelp = { openUrl(TMDB_HELP_URL) },
                        clearLabel = stringResource(R.string.settings_clear_key),
                        onClear = { viewModel.clearTmdbKey() },
                        statusMessage = state.tmdbTest?.let { test ->
                            if (test.success) {
                                stringResource(R.string.settings_key_test_success, test.message)
                            } else {
                                stringResource(R.string.settings_key_test_failed, test.message)
                            }
                        },
                        isTesting = state.isTestingTmdb,
                        showContentDescription = stringResource(R.string.settings_show_key),
                        hideContentDescription = stringResource(R.string.settings_hide_key),
                    )
                    Spacer(Modifier.height(20.dp))
                    ApiKeyField(
                        label = stringResource(R.string.settings_omdb_key),
                        value = state.omdbKey,
                        onValueChange = { viewModel.onOmdbKeyChange(it) },
                        testLabel = stringResource(R.string.settings_test_key),
                        onTest = { viewModel.testOmdbKey() },
                        helpLabel = stringResource(R.string.settings_get_key),
                        onHelp = { openUrl(OMDB_HELP_URL) },
                        clearLabel = stringResource(R.string.settings_clear_key),
                        onClear = { viewModel.clearOmdbKey() },
                        statusMessage = state.omdbTest?.let { test ->
                            if (test.success) {
                                stringResource(R.string.settings_key_test_success, test.message)
                            } else {
                                stringResource(R.string.settings_key_test_failed, test.message)
                            }
                        },
                        isTesting = state.isTestingOmdb,
                        showContentDescription = stringResource(R.string.settings_show_key),
                        hideContentDescription = stringResource(R.string.settings_hide_key),
                    )
                }
            }
            item { SectionHeader(stringResource(R.string.settings_metadata)) }
            item {
                SettingsCard {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.settings_rating_source),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = settings.ratingSource == RatingSourcePreference.ANY,
                            onClick = {
                                viewModel.update {
                                    it.copy(ratingSource = RatingSourcePreference.ANY)
                                }
                            },
                            label = {
                                Text(stringResource(R.string.settings_rating_source_any))
                            },
                        )
                        FilterChip(
                            selected = settings.ratingSource ==
                                RatingSourcePreference.IMDB_ONLY,
                            onClick = {
                                viewModel.update {
                                    it.copy(ratingSource = RatingSourcePreference.IMDB_ONLY)
                                }
                            },
                            label = {
                                Text(stringResource(R.string.settings_rating_source_imdb_only))
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_metadata_unavailable),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = settings.metadataUnavailable ==
                                MetadataUnavailableBehavior.CONTINUE,
                            onClick = {
                                viewModel.update {
                                    it.copy(
                                        metadataUnavailable =
                                        MetadataUnavailableBehavior.CONTINUE,
                                    )
                                }
                            },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.settings_metadata_unavailable_continue,
                                    ),
                                )
                            },
                        )
                        FilterChip(
                            selected = settings.metadataUnavailable ==
                                MetadataUnavailableBehavior.HOLD,
                            onClick = {
                                viewModel.update {
                                    it.copy(
                                        metadataUnavailable =
                                        MetadataUnavailableBehavior.HOLD,
                                    )
                                }
                            },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.settings_metadata_unavailable_hold,
                                    ),
                                )
                            },
                        )
                    }
                    SwitchRow(
                        label = stringResource(R.string.settings_genre_normalization),
                        checked = settings.genreNormalization,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(genreNormalization = value) }
                        },
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_download_posters),
                        checked = settings.downloadPosters,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(downloadPosters = value) }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { viewModel.clearMetadataCache() }) {
                        Text(stringResource(R.string.settings_clear_cache))
                    }
                }
            }

            // 5. Safety & behavior
            item { SectionHeader(stringResource(R.string.settings_safety)) }
            item {
                SettingsCard {
                    Text(
                        text = stringResource(R.string.settings_sub_scanning),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.settings_min_file_age,
                            settings.minFileAgeSeconds,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Slider(
                        value = settings.minFileAgeSeconds.toFloat(),
                        onValueChange = { value ->
                            viewModel.update { it.copy(minFileAgeSeconds = value.toInt()) }
                        },
                        valueRange = 0f..600f,
                        steps = 59,
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_ignore_samples),
                        checked = settings.ignoreSamples,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(ignoreSamples = value) }
                        },
                    )
                    StepperRow(
                        label = stringResource(R.string.settings_max_depth_label),
                        value = settings.maxScanDepth,
                        min = 1,
                        max = 10,
                        onValueChange = { value ->
                            viewModel.update { it.copy(maxScanDepth = value) }
                        },
                    )
                }
            }
            item {
                SettingsCard {
                    Text(
                        text = stringResource(R.string.settings_sub_organizing),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        label = stringResource(R.string.settings_skip_preview),
                        checked = settings.skipPreview,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(skipPreview = value) }
                        },
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_move_companions),
                        checked = settings.moveCompanions,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(moveCompanions = value) }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_conflict_default),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = settings.conflictDefault == ConflictDefault.ASK,
                            onClick = {
                                viewModel.update { it.copy(conflictDefault = ConflictDefault.ASK) }
                            },
                            label = { Text(stringResource(R.string.settings_conflict_ask)) },
                        )
                        FilterChip(
                            selected = settings.conflictDefault == ConflictDefault.SKIP,
                            onClick = {
                                viewModel.update {
                                    it.copy(conflictDefault = ConflictDefault.SKIP)
                                }
                            },
                            label = { Text(stringResource(R.string.settings_conflict_skip)) },
                        )
                        FilterChip(
                            selected = settings.conflictDefault == ConflictDefault.KEEP_BOTH,
                            onClick = {
                                viewModel.update {
                                    it.copy(conflictDefault = ConflictDefault.KEEP_BOTH)
                                }
                            },
                            label = {
                                Text(stringResource(R.string.settings_conflict_keep_both))
                            },
                        )
                    }
                    SwitchRow(
                        label = stringResource(R.string.settings_remove_empty),
                        checked = settings.removeEmptyFolders,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(removeEmptyFolders = value) }
                        },
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_remove_empty_junk),
                        checked = settings.treatJunkAsEmpty,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(treatJunkAsEmpty = value) }
                        },
                        enabled = settings.removeEmptyFolders,
                    )
                }
            }

            // 6. Advanced
            item { SectionHeader(stringResource(R.string.settings_advanced)) }
            item {
                SettingsCard {
                    OutlinedTextField(
                        value = settings.videoExtensions,
                        onValueChange = { value ->
                            viewModel.update { it.copy(videoExtensions = value) }
                        },
                        label = { Text(stringResource(R.string.settings_video_extensions)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = settings.extraNoiseWords,
                        onValueChange = { value ->
                            viewModel.update { it.copy(extraNoiseWords = value) }
                        },
                        label = { Text(stringResource(R.string.settings_extra_noise)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.settings_saved_corrections),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (state.overrides.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_saved_corrections_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.overrides.forEach { override ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.settings_correction_item,
                                        override.normalizedKey,
                                        override.correctedTitle,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { viewModel.deleteOverride(override.id) },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(
                                            R.string.common_remove,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val diagnosticsHeader = stringResource(R.string.settings_diagnostics_header)
                    val chooserTitle = stringResource(R.string.settings_diagnostics_chooser)
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val text = viewModel.buildDiagnostics(diagnosticsHeader)
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(
                                    Intent.createChooser(sendIntent, chooserTitle),
                                )
                            }
                        },
                    ) {
                        Text(stringResource(R.string.settings_export_diagnostics))
                    }
                }
            }

            // 7. About
            item { SectionHeader(stringResource(R.string.settings_about)) }
            item {
                SettingsCard {
                    Text(
                        text = AppInfo.APP_NAME,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.settings_about_version,
                            BuildConfig.VERSION_NAME,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_about_developer,
                            AppInfo.DEVELOPER_CREDIT,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.settings_licenses),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_licenses_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.update_section),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    val updateState = updateController.state
                    Text(
                        text = when (updateState) {
                            AppUpdateController.State.Idle ->
                                stringResource(R.string.update_check)
                            AppUpdateController.State.Checking ->
                                stringResource(R.string.update_checking)
                            AppUpdateController.State.UpToDate ->
                                stringResource(R.string.update_up_to_date)
                            is AppUpdateController.State.Available ->
                                stringResource(R.string.update_available_title) +
                                    " — v" + updateState.info.version
                            is AppUpdateController.State.Downloading -> {
                                val total = if (updateState.total > 0) {
                                    updateState.total
                                } else {
                                    updateState.info.sizeBytes
                                }
                                val pct = if (total > 0) {
                                    (updateState.downloaded * 100 / total).toInt()
                                } else {
                                    0
                                }
                                stringResource(R.string.update_downloading) +
                                    " " + pct.toString().let {
                                        if (settings.persianDigits) {
                                            it.toPersianDigits()
                                        } else {
                                            it
                                        }
                                    } + "%"
                            }
                            is AppUpdateController.State.Downloaded ->
                                stringResource(R.string.update_downloaded)
                            is AppUpdateController.State.Failed ->
                                stringResource(
                                    R.string.update_status_failed,
                                    updateState.message,
                                )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    when (updateState) {
                        AppUpdateController.State.Idle,
                        AppUpdateController.State.UpToDate,
                        is AppUpdateController.State.Failed ->
                            OutlinedButton(onClick = { updateController.check() }) {
                                Text(stringResource(R.string.update_check))
                            }
                        AppUpdateController.State.Checking ->
                            OutlinedButton(onClick = {}, enabled = false) {
                                Text(stringResource(R.string.update_checking))
                            }
                        is AppUpdateController.State.Available ->
                            OutlinedButton(
                                onClick = { updateController.download(updateState.info) },
                            ) {
                                Text(stringResource(R.string.update_download))
                            }
                        is AppUpdateController.State.Downloading ->
                            OutlinedButton(
                                onClick = { updateController.cancelDownload() },
                            ) {
                                Text(stringResource(R.string.update_cancel))
                            }
                        is AppUpdateController.State.Downloaded ->
                            OutlinedButton(
                                onClick = { updateController.install(updateState.file) },
                            ) {
                                Text(stringResource(R.string.update_install))
                            }
                    }
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        label = stringResource(R.string.settings_auto_check_updates),
                        checked = settings.autoCheckUpdates,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(autoCheckUpdates = value) }
                        },
                    )
                }
            }
        }
    }
}
