package com.cinesort.app.feature.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import com.cinesort.core.designsystem.component.FilmPerforationStrip
import com.cinesort.core.designsystem.component.StageStepper

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
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }

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
                    androidx.compose.foundation.layout.WindowInsets.statusBars,
                )
                .padding(20.dp),
        ) {
            FilmPerforationStrip()
            Spacer(Modifier.height(16.dp))
            StageStepper(
                stages = listOf(
                    stringResource(R.string.onboarding_welcome_title),
                    stringResource(R.string.sources_destination_title),
                    stringResource(R.string.tab_sources),
                    stringResource(R.string.settings_metadata),
                    stringResource(R.string.onboarding_done_title),
                ),
                currentIndex = step,
            )
            Spacer(Modifier.height(24.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (step) {
                    0 -> {
                        Text(
                            text = stringResource(R.string.onboarding_welcome_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.onboarding_welcome_message),
                            style = MaterialTheme.typography.bodyLarge,
                        )
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
                            Text(
                                text = stringResource(
                                    R.string.onboarding_destination_chosen,
                                    destination.name,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
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
                            statusMessage = state.tmdbTestMessage,
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
                            statusMessage = state.omdbTestMessage,
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
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (step > 0) {
                    OutlinedButton(onClick = { step-- }) {
                        Text(stringResource(R.string.common_back))
                    }
                } else {
                    Spacer(Modifier.height(1.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step in 1..3) {
                        TextButton(onClick = { step++ }) {
                            Text(stringResource(R.string.common_skip))
                        }
                    }
                    if (step < LAST_STEP) {
                        Button(onClick = { step++ }) {
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
