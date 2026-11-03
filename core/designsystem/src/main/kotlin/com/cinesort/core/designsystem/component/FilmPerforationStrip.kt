package com.cinesort.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Film-strip perforation motif used sparingly as a header/divider (spec 10.1). */
@Composable
fun FilmPerforationStrip(
    modifier: Modifier = Modifier,
    holes: Int = 24,
) {
    Row(
        modifier = modifier
            // Purely decorative: keep the whole strip out of the
            // accessibility tree.
            .clearAndSetSemantics {}
            .fillMaxWidth()
            .height(18.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(holes) {
            Box(
                modifier = Modifier
                    .size(width = 10.dp, height = 7.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.background),
            )
        }
    }
}
