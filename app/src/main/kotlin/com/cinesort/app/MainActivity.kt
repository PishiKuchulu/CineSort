package com.cinesort.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.cinesort.app.navigation.CineSortNavHost
import com.cinesort.core.data.repository.SettingsRepository
import com.cinesort.core.data.settings.ThemeMode
import com.cinesort.core.designsystem.theme.CineSortTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
// AppCompatActivity (review M11): per-app locales via AppCompatDelegate only
// take effect on API 24-32 when the activity is an AppCompatActivity; the theme
// already descends from Theme.AppCompat.
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        applyPersistedLanguage()
        val startOnOnboarding = !OnboardingFlag.isDone(this)
        setContent {
            // No default-settings first frame (audit 1-3): collecting with
            // initial = AppSettings() rendered one frame with the default
            // theme/language before the persisted values arrived — a
            // visible flash on cold start. Until the first real emission,
            // render only a themed blank surface.
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initial = null)
            val current = settings
            if (current == null) {
                CineSortTheme(darkTheme = isSystemInDarkTheme()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {}
                }
            } else {
                val darkTheme = when (current.themeMode) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                }
                CineSortTheme(
                    darkTheme = darkTheme,
                    dynamicColor = current.dynamicColor,
                ) {
                    CineSortNavHost(
                        startOnOnboarding = startOnOnboarding,
                        persianDigits = current.persianDigits,
                        settingsRepository = settingsRepository,
                    )
                }
            }
        }
    }

    /**
     * Applies the persisted per-app language. On first launch (no stored choice)
     * the UI defaults to English (owner decision 2026-11-01, superseding the
     * original Persian-default spec) without writing a choice; the onboarding
     * welcome step lets the user pick a language explicitly.
     */
    private fun applyPersistedLanguage() {
        lifecycleScope.launch {
            val stored = settingsRepository.settings.first().languageCode
            val target = stored ?: "en"
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (!current.startsWith(target)) {
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(target),
                )
            }
        }
    }
}
