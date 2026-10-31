package com.cinesort.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Extra Cinema Noir tokens that have no direct Material 3 color role (spec 10.1). */
@Immutable
data class CineSortTokens(
    val movieAccent: Color,
    val seriesAccent: Color,
    val success: Color,
    val needsReview: Color,
)

val LocalCineSortTokens = staticCompositionLocalOf {
    CineSortTokens(
        movieAccent = CinePrimaryAmber,
        seriesAccent = CineSeriesAccent,
        success = CineSuccess,
        needsReview = CineNeedsReview,
    )
}

internal val DarkTokens = CineSortTokens(
    movieAccent = CinePrimaryAmber,
    seriesAccent = CineSeriesAccent,
    success = CineSuccess,
    needsReview = CineNeedsReview,
)

internal val LightTokens = CineSortTokens(
    movieAccent = CinePrimaryLight,
    seriesAccent = CineSeriesAccentLight,
    success = CineSuccessLight,
    needsReview = CineNeedsReviewLight,
)
