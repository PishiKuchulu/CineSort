package com.cinesort.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cinesort.core.designsystem.theme.LocalCineSortTokens

enum class MediaKind { MOVIE, SERIES }

/** Type chip: Movie in projector amber, Series in signal teal (spec 10.1). */
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
    Text(
        text = label,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF14100A),
    )
}

enum class ConfidenceLevel { HIGH, MEDIUM, LOW }

/** Confidence badge coloured by level: high = success, medium = amber, low = needs-review. */
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
    Text(
        text = label,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = background,
    )
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
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Status chip used on source cards (OK / Needs access). */
@Composable
fun StatusChip(
    label: String,
    isOk: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalCineSortTokens.current
    val color = if (isOk) tokens.success else MaterialTheme.colorScheme.error
    Text(
        text = label,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}
