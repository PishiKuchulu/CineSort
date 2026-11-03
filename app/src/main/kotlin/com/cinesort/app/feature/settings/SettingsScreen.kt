package com.cinesort.app.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.cinesort.core.designsystem.component.ApiKeyStatusKind
import com.cinesort.core.designsystem.component.StepperRow
import com.cinesort.core.designsystem.component.SwitchRow
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

// SwitchRow / StepperRow are the shared designsystem components
// (component/Controls.kt) since the granular-audit fix: one implementation,
// whole-row toggle target, and labeled stepper buttons.

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onOpenSources: () -> Unit,
    updateController: AppUpdateController,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val cacheClearedMessage = stringResource(R.string.settings_cache_cleared)
    val settingsOverrideDeletedMessage = stringResource(R.string.settings_override_deleted)
    val commonUndoLabel = stringResource(R.string.common_undo)
    var diagnosticsBusy by remember { mutableStateOf(false) }
    // The correction being edited in place (audit A.1-5).
    var editingOverride by remember {
        mutableStateOf<com.cinesort.core.data.db.TitleOverrideEntity?>(null)
    }
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
            // 0. Storage recovery notice (section-4 audit): one-time and
            // dismissible; appears only when a store failed to read and
            // silently fell back to empty/defaults, so the loss is never
            // mistaken for "nothing was ever saved here".
            if (state.storageHealth.noticePending) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                text = stringResource(R.string.settings_storage_notice_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (state.storageHealth.settingsReset) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_storage_notice_settings),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (state.storageHealth.secretsUnreadable) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_storage_notice_keys),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { viewModel.dismissStorageHealthNotice() }) {
                                Text(stringResource(R.string.settings_storage_notice_dismiss))
                            }
                        }
                    }
                }
            }
            // 1. Language
            item { SectionHeader(stringResource(R.string.settings_language)) }
            item {
                SettingsCard {
                    // No in-card title: the section header above already
                    // names the section (granular audit 1-7 — the language
                    // card used to repeat its own name inside).
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                    if (settings.dynamicColor) {
                        // The brand palette is silently replaced when this
                        // is on — say so next to the switch (audit 1-1).
                        Text(
                            text = stringResource(R.string.settings_dynamic_color_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                        decreaseLabel = stringResource(R.string.common_decrease),
                        increaseLabel = stringResource(R.string.common_increase),
                        persianDigits = settings.persianDigits,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_title_casing),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                                // No raw provider/exception detail in the UI
                                // (audit 1-7): a generic localized failure.
                                stringResource(R.string.settings_key_test_failed_generic)
                            }
                        },
                        statusKind = when {
                            state.tmdbTest == null -> ApiKeyStatusKind.Neutral
                            state.tmdbTest?.success == true -> ApiKeyStatusKind.Success
                            else -> ApiKeyStatusKind.Error
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
                                stringResource(R.string.settings_key_test_failed_generic)
                            }
                        },
                        statusKind = when {
                            state.omdbTest == null -> ApiKeyStatusKind.Neutral
                            state.omdbTest?.success == true -> ApiKeyStatusKind.Success
                            else -> ApiKeyStatusKind.Error
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                    val minFileAgeDescription = stringResource(
                        R.string.settings_min_file_age,
                        settings.minFileAgeSeconds,
                    )
                    Slider(
                        value = settings.minFileAgeSeconds.toFloat(),
                        onValueChange = { value ->
                            viewModel.update { it.copy(minFileAgeSeconds = value.toInt()) }
                        },
                        valueRange = 0f..600f,
                        steps = 59,
                        modifier = Modifier.semantics {
                            stateDescription = minFileAgeDescription
                        },
                    )
                    SwitchRow(
                        label = stringResource(R.string.settings_ignore_samples),
                        checked = settings.ignoreSamples,
                        onCheckedChange = { value ->
                            viewModel.update { it.copy(ignoreSamples = value) }
                        },
                    )
                    // What "samples" means here, in the app's own words
                    // (audit A.1-3): the parser term was never defined.
                    Text(
                        text = stringResource(R.string.settings_ignore_samples_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StepperRow(
                        label = stringResource(R.string.settings_max_depth_label),
                        value = settings.maxScanDepth,
                        min = 1,
                        max = 10,
                        onValueChange = { value ->
                            viewModel.update { it.copy(maxScanDepth = value) }
                        },
                        decreaseLabel = stringResource(R.string.common_decrease),
                        increaseLabel = stringResource(R.string.common_increase),
                        persianDigits = settings.persianDigits,
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                    // The fields bind the ViewModel's local (debounced)
                    // values, not the settings flow (audit B.10): typing no
                    // longer round-trips through DataStore per keystroke.
                    val extensionsInvalid = state.videoExtensions.isNotBlank() &&
                        !state.videoExtensions.all { c ->
                            c.isLetterOrDigit() || c == ',' || c.isWhitespace()
                        }
                    OutlinedTextField(
                        value = state.videoExtensions,
                        onValueChange = viewModel::onVideoExtensionsChange,
                        label = { Text(stringResource(R.string.settings_video_extensions)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        isError = extensionsInvalid,
                        supportingText = {
                            Text(
                                stringResource(
                                    if (extensionsInvalid) {
                                        R.string.settings_video_extensions_error
                                    } else {
                                        R.string.settings_video_extensions_hint
                                    },
                                ),
                            )
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = state.extraNoiseWords,
                        onValueChange = viewModel::onExtraNoiseWordsChange,
                        label = { Text(stringResource(R.string.settings_extra_noise)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        supportingText = {
                            Text(stringResource(R.string.settings_extra_noise_hint))
                        },
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
                                    onClick = { editingOverride = override },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = stringResource(
                                            R.string.settings_correction_edit_title,
                                        ),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        // Delete with an Undo action instead of
                                        // a silent, unrecoverable removal
                                        // (granular audit 1-7).
                                        val deletedMessage = settingsOverrideDeletedMessage
                                        val undoLabel = commonUndoLabel
                                        viewModel.deleteOverride(override.id)
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = deletedMessage,
                                                actionLabel = undoLabel,
                                                duration = SnackbarDuration.Long,
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                viewModel.restoreOverride(override)
                                            }
                                        }
                                    },
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
                            if (diagnosticsBusy) return@OutlinedButton
                            diagnosticsBusy = true
                            scope.launch {
                                try {
                                    val text = viewModel.buildDiagnostics(diagnosticsHeader)
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(sendIntent, chooserTitle),
                                    )
                                } finally {
                                    diagnosticsBusy = false
                                }
                            }
                        },
                        enabled = !diagnosticsBusy,
                    ) {
                        if (diagnosticsBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        }
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
                    // An interrupted earlier download now says so instead
                    // of vanishing silently (audit B.11).
                    if (updateController.interruptedDownload &&
                        updateState !is AppUpdateController.State.Downloading
                    ) {
                        Text(
                            text = stringResource(R.string.update_interrupted_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (updateState is AppUpdateController.State.Downloading) {
                        val total = if (updateState.total > 0) {
                            updateState.total
                        } else {
                            updateState.info.sizeBytes
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (total > 0) {
                                    (updateState.downloaded.toFloat() / total)
                                        .coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    when (updateState) {
                        AppUpdateController.State.Idle,
                        AppUpdateController.State.UpToDate,
                        is AppUpdateController.State.Failed ->
                            OutlinedButton(onClick = { updateController.check(manual = true) }) {
                                Text(stringResource(R.string.update_check))
                            }
                        AppUpdateController.State.Checking ->
                            // Intentional non-interactive placeholder (audit
                            // A.2-3, informational): there is no cancel-check
                            // operation in the updater API, so this disabled
                            // button is a pure progress affordance, not a
                            // dead control.
                            OutlinedButton(onClick = {}, enabled = false) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
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

    // In-place correction editor (audit A.1-5): fixing a typo in a saved
    // correction used to mean delete + re-create from a new scan.
    editingOverride?.let { override ->
        var editTitle by remember(override.id) {
            mutableStateOf(override.correctedTitle)
        }
        var editYear by remember(override.id) {
            mutableStateOf(override.correctedYear?.toString() ?: "")
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editingOverride = null },
            title = {
                Text(stringResource(R.string.settings_correction_edit_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text(stringResource(R.string.edit_field_title)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = editYear,
                        onValueChange = { editYear = it },
                        label = { Text(stringResource(R.string.edit_field_year)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        viewModel.updateOverride(override.id, editTitle, editYear)
                        editingOverride = null
                    },
                    enabled = editTitle.isNotBlank(),
                ) {
                    Text(stringResource(R.string.common_apply))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { editingOverride = null },
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
