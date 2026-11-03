package com.cinesort.core.designsystem.theme

import androidx.compose.ui.graphics.Color

// Atelier tokens, ported 1:1 from Anvex's Atelier («کارگاه») theme at the
// owner's request (2026-11-01): Ink dark scheme — warm near-black surfaces,
// #FF8A3D accent. The accent is reserved for the primary action; everything
// else stays warm-neutral.
val CineBackgroundDark = Color(0xFF0F0E0D)
val CineSurfaceDark = Color(0xFF1A1816)
val CineSurfaceContainerDark = Color(0xFF24211E)
val CineOutlineDark = Color(0xFF34302B)
val CineOutlineVariantDark = Color(0xFF2A2723)
val CineOnSurfaceDark = Color(0xFFF5F1E8)
val CineOnSurfaceVariantDark = Color(0xFFB3ADA2)
val CinePrimaryAmber = Color(0xFFFF8A3D)
val CineOnPrimaryDark = Color(0xFF2A1503)
val CinePrimaryContainerDark = Color(0xFF4A2A12)
val CineOnPrimaryContainerDark = Color(0xFFFFDCC2)
val CineSecondaryDark = Color(0xFFB3ADA2)
val CineOnSecondaryDark = Color(0xFF1A1816)
val CineSecondaryContainerDark = Color(0xFF24211E)
val CineOnSecondaryContainerDark = Color(0xFFF5F1E8)
val CineSeriesAccent = Color(0xFF6C9BFF)
val CineOnSeriesDark = Color(0xFF0B1B3A)
val CineSeriesContainerDark = Color(0xFF1C2B4A)
val CineOnSeriesContainerDark = Color(0xFFD6E4FF)
val CineSuccess = Color(0xFF47C98F)
val CineNeedsReview = Color(0xFFFFC53D)
val CineError = Color(0xFFFF6B6B)
val CineOnErrorDark = Color(0xFF3D0A0A)
val CineErrorContainerDark = Color(0xFF4A1D1D)
val CineOnErrorContainerDark = Color(0xFFFFDAD6)

// Paper light scheme: #F4F1EA paper background, white surface, #AC4500 accent
// (the original #F26A1B failed AA with white text — see CinePrimaryLight).
val CineBackgroundLight = Color(0xFFF4F1EA)
val CineSurfaceLight = Color(0xFFFFFFFF)
val CineSurfaceContainerLight = Color(0xFFECE8DF)
val CineOutlineLight = Color(0xFF8A8171)
val CineOutlineVariantLight = Color(0xFFE0DACB)
val CineOnSurfaceLight = Color(0xFF17181C)
val CineOnSurfaceVariantLight = Color(0xFF5C5D66)
// Darkened from the original #F26A1B for WCAG AA (granular audit 1-1):
// white-on-primary 3.06 -> 5.82, primary-as-text on paper 2.72 -> 5.16.
val CinePrimaryLight = Color(0xFFAC4500)
val CineOnPrimaryLight = Color(0xFFFFFFFF)
val CinePrimaryContainerLight = Color(0xFFFFE3D3)
val CineOnPrimaryContainerLight = Color(0xFF4A1E02)
val CineSecondaryLight = Color(0xFF5C5D66)
val CineOnSecondaryLight = Color(0xFFFFFFFF)
val CineSecondaryContainerLight = Color(0xFFECE8DF)
val CineOnSecondaryContainerLight = Color(0xFF17181C)
val CineSeriesAccentLight = Color(0xFF2F6FED)
val CineOnSeriesLight = Color(0xFFFFFFFF)
val CineSeriesContainerLight = Color(0xFFD9E4FD)
val CineOnSeriesContainerLight = Color(0xFF0B2A5B)
val CineSuccessLight = Color(0xFF1F9D6B)
val CineNeedsReviewLight = Color(0xFF9A7200)
// Darkened from #D83A3A for AA as small text (4.06 -> 5.19 on paper).
val CineErrorLight = Color(0xFFC12727)
val CineOnErrorLight = Color(0xFFFFFFFF)
val CineErrorContainerLight = Color(0xFFFBD9D9)
val CineOnErrorContainerLight = Color(0xFF4A0E0E)

// "Status text" shades (granular audit 1-1): the identity colors above stay
// for fills/tints/icons; these darker shades are for status TEXT on light
// surfaces and on the identity colors' own tints, where the identity colors
// measured 3.1–3.9:1. All three reach >= 4.9:1 on white, paper and tints.
val CineSuccessTextLight = Color(0xFF0A6336)
val CineNeedsReviewTextLight = Color(0xFF755600)
val CineAccentTextLight = Color(0xFF9E3D00)
