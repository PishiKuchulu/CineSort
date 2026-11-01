package com.cinesort.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.cinesort.app.navigation.CineSortNavHost
import com.cinesort.core.data.repository.SettingsRepository
import com.cinesort.core.data.settings.AppSettings
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
            val settings by settingsRepository.settings.collectAsState(initial = AppSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            CineSortTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColor,
            ) {
                CineSortNavHost(
                    startOnOnboarding = startOnOnboarding,
                    persianDigits = settings.persianDigits,
                )
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
