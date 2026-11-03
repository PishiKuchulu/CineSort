package com.cinesort.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.cinesort.core.designsystem.theme.LocalCineSortTokens

enum class MediaKind { MOVIE, SERIES }

/** Type chip: Movie in the Atelier accent, Series in the Atelier tertiary blue. */
@Composable
fun MediaTypeChip(
    kind: MediaKind,
    label: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalCineSortTokens.current
    val background = when (kind) {
        MediaKind.MOVIE -> tokens.movieAccent
        MediaKind.SERIES -> tokens.seriesAccent
    }
    val foreground = when (kind) {
        MediaKind.MOVIE -> MaterialTheme.colorScheme.onPrimary
        MediaKind.SERIES -> MaterialTheme.colorScheme.onTertiary
    }
    Text(
        text = label,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
    )
}

enum class ConfidenceLevel { HIGH, MEDIUM, LOW }

/**
 * Confidence badge coloured by level: high = success, medium = amber,
 * low = needs-review. The tint keeps the identity color, but the text uses
 * the darker `*Text` tokens (the identity colors measure 3.1–3.9:1 as text
 * on their own tint in the light scheme — granular audit 1-2), and a small
 * per-level icon gives a second, non-color channel for the level.
 */
@Composable
fun ConfidenceBadge(
    level: ConfidenceLevel,
    label: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalCineSortTokens.current
    val background = when (level) {
        ConfidenceLevel.HIGH -> tokens.success
        ConfidenceLevel.MEDIUM -> tokens.movieAccent
        ConfidenceLevel.LOW -> tokens.needsReview
    }
    val foreground = when (level) {
        ConfidenceLevel.HIGH -> tokens.successText
        ConfidenceLevel.MEDIUM -> tokens.accentText
        ConfidenceLevel.LOW -> tokens.needsReviewText
    }
    val icon = when (level) {
        ConfidenceLevel.HIGH -> Icons.Filled.CheckCircle
        ConfidenceLevel.MEDIUM -> Icons.Filled.Info
        ConfidenceLevel.LOW -> Icons.Filled.Warning
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = foreground,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = foreground,
        )
    }
}

/** Small badge naming the metadata provider of a rating (IMDb / TMDB / OMDb). */
@Composable
fun ProviderBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        // labelMedium (12sp): 11sp labelSmall was the smallest text in the
        // app and borderline to read (granular audit 1-2).
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Semantic tone of a [StatusChip] (granular audit 1-2). */
enum class StatusTone { Ok, Warning, Error, Neutral }

/**
 * Status chip with a semantic tone, so a neutral state (e.g. a run still in
 * progress) is never painted with the error color. Text uses the darker
 * `*Text` tokens on the identity tints for AA contrast.
 */
@Composable
fun StatusChip(
    label: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalCineSortTokens.current
    val foreground = when (tone) {
        StatusTone.Ok -> tokens.successText
        StatusTone.Warning -> tokens.needsReviewText
        StatusTone.Error -> MaterialTheme.colorScheme.error
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val background = when (tone) {
        StatusTone.Ok -> tokens.success.copy(alpha = 0.16f)
        StatusTone.Warning -> tokens.needsReview.copy(alpha = 0.16f)
        StatusTone.Error -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
        StatusTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant
    }
    Text(
        text = label,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
    )
}

/** Status chip used on source cards (OK / Needs access). */
@Composable
fun StatusChip(
    label: String,
    isOk: Boolean,
    modifier: Modifier = Modifier,
) {
    StatusChip(
        label = label,
        tone = if (isOk) StatusTone.Ok else StatusTone.Error,
        modifier = modifier,
    )
}
