package com.cinesort.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.cinesort.core.designsystem.theme.LocalCineSortTokens

/**
 * Horizontal stepper for the scan pipeline stages (spec 10.4 screen 4).
 * Stage state is not carried by color alone for assistive tech: each stage
 * exposes a localized [stageDescription] ("Step 2 of 4: Parsing") as its
 * semantics state description, and the numerals follow [persianDigits]
 * (granular audit 1-2).
 */
@Composable
fun StageStepper(
    stages: List<String>,
    currentIndex: Int,
    stageDescription: (index: Int, label: String) -> String,
    modifier: Modifier = Modifier,
    persianDigits: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        stages.forEachIndexed { index, label ->
            val active = index <= currentIndex
            val color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .semantics { stateDescription = stageDescription(index, label) },
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(color),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (index + 1).toString().let {
                            if (persianDigits) it.toPersianDigits() else it
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * Film-reel styled progress: a perforation strip above a linear bar and a
 * percentage label (spec 10.1 / 10.4 screen 6).
 */
@Composable
fun ReelProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    persianDigits: Boolean = false,
    currentFileName: String? = null,
    progressDescription: String? = null,
) {
    val tokens = LocalCineSortTokens.current
    val clamped = progress.coerceIn(0f, 1f)
    Column(modifier = modifier.fillMaxWidth()) {
        FilmPerforationStrip()
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { clamped },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
            color = tokens.movieAccent,
            trackColor = MaterialTheme.colorScheme.surfaceContainer,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val percent = (clamped * 100).toInt().toString() + "%"
            Text(
                text = if (persianDigits) percent.toPersianDigits() else percent,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics {
                    // The bar itself carries no semantics; the percentage
                    // announces a full localized description as a live
                    // region instead (granular audit 1-2, minor).
                    if (progressDescription != null) {
                        contentDescription = progressDescription
                    }
                    liveRegion = LiveRegionMode.Polite
                },
            )
            if (currentFileName != null) {
                LtrText(
                    text = currentFileName,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
            }
        }
    }
}
