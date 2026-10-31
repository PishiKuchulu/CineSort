package com.cinesort.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
class MainActivity : ComponentActivity() {

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
                CineSortNavHost(startOnOnboarding = startOnOnboarding)
            }
        }
    }

    /**
     * Applies the persisted per-app language. On first launch (no stored choice)
     * the UI defaults to Persian (spec 1.2 / 10.2) without writing a choice.
     */
    private fun applyPersistedLanguage() {
        lifecycleScope.launch {
            val stored = settingsRepository.settings.first().languageCode
            val target = stored ?: "fa"
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            if (!current.startsWith(target)) {
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(target),
                )
            }
        }
    }
}
