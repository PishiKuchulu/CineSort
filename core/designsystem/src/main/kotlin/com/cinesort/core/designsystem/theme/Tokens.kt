package com.cinesort.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extra Atelier tokens that have no direct Material 3 color role.
 *
 * The `*Text` shades are for status/accent TEXT: the identity colors stay
 * for fills, tints and icons, but in the light scheme they measured below
 * WCAG AA as text (granular audit 1-1), so text gets its own darker shade.
 * In the dark scheme the identity colors already pass, so text == identity.
 */
@Immutable
data class CineSortTokens(
    val movieAccent: Color,
    val seriesAccent: Color,
    val success: Color,
    val needsReview: Color,
    val successText: Color,
    val needsReviewText: Color,
    val accentText: Color,
)

val LocalCineSortTokens = staticCompositionLocalOf {
    CineSortTokens(
        movieAccent = CinePrimaryAmber,
        seriesAccent = CineSeriesAccent,
        success = CineSuccess,
        needsReview = CineNeedsReview,
        successText = CineSuccess,
        needsReviewText = CineNeedsReview,
        accentText = CinePrimaryAmber,
    )
}

internal val DarkTokens = CineSortTokens(
    movieAccent = CinePrimaryAmber,
    seriesAccent = CineSeriesAccent,
    success = CineSuccess,
    needsReview = CineNeedsReview,
    successText = CineSuccess,
    needsReviewText = CineNeedsReview,
    accentText = CinePrimaryAmber,
)

internal val LightTokens = CineSortTokens(
    movieAccent = CinePrimaryLight,
    seriesAccent = CineSeriesAccentLight,
    success = CineSuccessLight,
    needsReview = CineNeedsReviewLight,
    successText = CineSuccessTextLight,
    needsReviewText = CineNeedsReviewTextLight,
    accentText = CineAccentTextLight,
)
