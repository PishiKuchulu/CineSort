package com.cinesort.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = CinePrimaryAmber,
    onPrimary = CineOnPrimaryDark,
    primaryContainer = CineSurfaceContainerDark,
    onPrimaryContainer = CineOnSurfaceDark,
    secondary = CineSeriesAccent,
    onSecondary = CineOnPrimaryDark,
    background = CineBackgroundDark,
    onBackground = CineOnSurfaceDark,
    surface = CineSurfaceDark,
    onSurface = CineOnSurfaceDark,
    surfaceVariant = CineSurfaceContainerDark,
    onSurfaceVariant = CineOnSurfaceVariantDark,
    surfaceContainer = CineSurfaceContainerDark,
    surfaceContainerHigh = CineSurfaceContainerDark,
    outline = CineOutlineDark,
    outlineVariant = CineOutlineDark,
    error = CineError,
    onError = CineOnSurfaceDark,
)

private val LightColorScheme: ColorScheme = lightColorScheme(
    primary = CinePrimaryLight,
    onPrimary = CineOnPrimaryLight,
    primaryContainer = CineSurfaceContainerLight,
    onPrimaryContainer = CineOnSurfaceLight,
    secondary = CineSeriesAccentLight,
    onSecondary = CineOnPrimaryLight,
    background = CineBackgroundLight,
    onBackground = CineOnSurfaceLight,
    surface = CineSurfaceLight,
    onSurface = CineOnSurfaceLight,
    surfaceVariant = CineSurfaceContainerLight,
    onSurfaceVariant = CineOnSurfaceVariantLight,
    surfaceContainer = CineSurfaceContainerLight,
    surfaceContainerHigh = CineSurfaceContainerLight,
    outline = CineOutlineLight,
    outlineVariant = CineOutlineLight,
    error = CineErrorLight,
    onError = CineOnPrimaryLight,
)

/**
 * CineSort theme ("Cinema Noir", spec 10.1).
 *
 * @param darkTheme whether the dark scheme applies.
 * @param dynamicColor when true and the device supports it (Android 12+), the
 * Material You dynamic scheme replaces the brand scheme; extra tokens stay brand-based.
 */
@Composable
fun CineSortTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val tokens = if (darkTheme) DarkTokens else LightTokens
    CompositionLocalProvider(LocalCineSortTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = CineSortTypography,
            shapes = CineSortShapes,
            content = content,
        )
    }
}

/** Accessor for the extra Cinema Noir tokens of the current theme. */
object CineSortThemeTokens {
    val current: CineSortTokens
        @Composable get() = LocalCineSortTokens.current
}
