package com.cinesort.app.feature.onboarding

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cinesort.app.OnboardingFlag
import com.cinesort.app.R
import com.cinesort.app.util.displayNameOf
import com.cinesort.app.util.rememberTreePicker
import com.cinesort.core.designsystem.component.ApiKeyField
import com.cinesort.core.designsystem.component.ApiKeyStatusKind
import com.cinesort.core.designsystem.component.FilmPerforationStrip
import com.cinesort.core.designsystem.component.StageStepper
import com.cinesort.core.designsystem.theme.LocalCineSortTokens

private const val TMDB_HELP_URL = "https://www.themoviedb.org/settings/api"
private const val OMDB_HELP_URL = "https://www.omdbapi.com/apikey.aspx"
private const val LAST_STEP = 4

/**
 * First-launch onboarding (spec 10.4 screen 1):
 * Welcome -> Destination -> Sources -> API keys (optional) -> Done.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // rememberSaveable (audit B.1): a configuration change mid-onboarding
    // used to restart the wizard at step 0 with picks seemingly lost.
    var step by rememberSaveable { mutableIntStateOf(0) }

    val destinationPicker = rememberTreePicker { uri ->
        viewModel.onDestinationPicked(uri.toString(), displayNameOf(context, uri.toString()))
    }
    val sourcePicker = rememberTreePicker { uri ->
        viewModel.onSourcePicked(uri.toString(), displayNameOf(context, uri.toString()))
    }

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(
                    WindowInsets.statusBars,
                )
                .padding(20.dp),
        ) {
            FilmPerforationStrip()
            Spacer(Modifier.height(16.dp))
            val stageStateTemplate = stringResource(R.string.scan_stage_state)
            StageStepper(
                stages = listOf(
                    stringResource(R.string.onboarding_welcome_title),
                    stringResource(R.string.sources_destination_title),
                    stringResource(R.string.tab_sources),
                    stringResource(R.string.settings_metadata),
                    stringResource(R.string.onboarding_done_title),
                ),
                currentIndex = step,
                stageDescription = { index, label ->
                    String.format(
                        java.util.Locale.ROOT,
                        stageStateTemplate,
                        index + 1,
                        LAST_STEP + 1,
                        label,
                    )
                },
                persianDigits = state.languageCode == "fa",
            )
            Spacer(Modifier.height(24.dp))
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    (slideInHorizontally { width -> width / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { width -> -width / 3 } + fadeOut())
                },
                label = "onboardingStep",
                modifier = Modifier.weight(1f),
            ) { targetStep ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (targetStep) {
                    0 -> {
                        Text(
                            text = stringResource(R.string.onboarding_welcome_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.onboarding_welcome_message),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_language),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(
                                selected = state.languageCode == "en",
                                onClick = {
                                    viewModel.onLanguagePicked("en")
                                    AppCompatDelegate.setApplicationLocales(
                                        LocaleListCompat.forLanguageTags("en"),
                                    )
                                },
                                label = { Text(stringResource(R.string.settings_language_en)) },
                            )
                            FilterChip(
                                selected = state.languageCode == "fa",
                                onClick = {
                                    viewModel.onLanguagePicked("fa")
                                    AppCompatDelegate.setApplicationLocales(
                                        LocaleListCompat.forLanguageTags("fa"),
                                    )
                                },
                                label = { Text(stringResource(R.string.settings_language_fa)) },
                            )
                        }
                    }
                    1 -> {
                        Text(
                            text = stringResource(R.string.onboarding_destination_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.onboarding_destination_message),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { destinationPicker.launch(null) }) {
                            Text(stringResource(R.string.onboarding_pick_destination))
                        }
                        val rootsError = state.rootsError
                        if (rootsError != null) {
                            Text(
                                text = stringResource(
                                    R.string.roots_prepare_error,
                                    rootsError,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        val destination = state.destination
                        if (destination != null) {
                            // A visible success marker, not plain text
                            // only (audit 1-4).
                            Row(
                                verticalAlignment =
                                androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = LocalCineSortTokens.current.success,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.onboarding_destination_chosen,
                                        destination.name,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.sources_roots_result,
                                    state.rootsCreated.joinToString(", ").ifEmpty { "—" },
                                    state.rootsFound.joinToString(", ").ifEmpty { "—" },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    2 -> {
                        Text(
                            text = stringResource(R.string.onboarding_sources_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.onboarding_sources_message),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { sourcePicker.launch(null) }) {
                            Text(stringResource(R.string.onboarding_add_source))
                        }
                        Text(
                            text = stringResource(
                                R.string.onboarding_sources_count,
                                state.sourcesCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    3 -> {
                        Text(
                            text = stringResource(R.string.onboarding_keys_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.onboarding_keys_message),
                            style = MaterialTheme.typography.bodyLarge,
                        )
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
                            statusMessage = when (state.tmdbTestSuccess) {
                                true -> state.tmdbTestMessage
                                false -> stringResource(
                                    R.string.settings_key_test_failed_generic,
                                )
                                null -> null
                            },
                            statusKind = when (state.tmdbTestSuccess) {
                                true -> ApiKeyStatusKind.Success
                                false -> ApiKeyStatusKind.Error
                                null -> ApiKeyStatusKind.Neutral
                            },
                            isTesting = state.isTestingTmdb,
                            showContentDescription = stringResource(
                                R.string.settings_show_key,
                            ),
                            hideContentDescription = stringResource(
                                R.string.settings_hide_key,
                            ),
                        )
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
                            statusMessage = when (state.omdbTestSuccess) {
                                true -> state.omdbTestMessage
                                false -> stringResource(
                                    R.string.settings_key_test_failed_generic,
                                )
                                null -> null
                            },
                            statusKind = when (state.omdbTestSuccess) {
                                true -> ApiKeyStatusKind.Success
                                false -> ApiKeyStatusKind.Error
                                null -> ApiKeyStatusKind.Neutral
                            },
                            isTesting = state.isTestingOmdb,
                            showContentDescription = stringResource(
                                R.string.settings_show_key,
                            ),
                            hideContentDescription = stringResource(
                                R.string.settings_hide_key,
                            ),
                        )
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.onboarding_done_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.onboarding_done_message),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                if (step > 0) {
                    OutlinedButton(onClick = { step-- }) {
                        Text(stringResource(R.string.common_back))
                    }
                }
                // Weight spacer replaces the old 1.dp alignment hack
                // (audit 1-4).
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step in 1..3) {
                        TextButton(onClick = { step++ }) {
                            Text(stringResource(R.string.common_skip))
                        }
                    }
                    if (step < LAST_STEP) {
                        // Next is gated on the step's own requirement
                        // (audit 1-4); Skip stays as the explicit,
                        // labeled bypass for steps 1-2.
                        val nextEnabled = when (step) {
                            1 -> state.destination != null
                            2 -> state.sourcesCount > 0
                            else -> true
                        }
                        Button(
                            onClick = { step++ },
                            enabled = nextEnabled,
                        ) {
                            Text(stringResource(R.string.common_next))
                        }
                    } else {
                        Button(
                            onClick = {
                                OnboardingFlag.markDone(context)
                                onFinished()
                            },
                        ) {
                            Text(stringResource(R.string.onboarding_finish))
                        }
                    }
                }
            }
        }
    }
}
