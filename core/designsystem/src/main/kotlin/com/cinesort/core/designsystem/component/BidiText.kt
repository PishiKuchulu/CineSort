package com.cinesort.core.designsystem.component

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import com.cinesort.core.designsystem.theme.MonoFontFamily

/** Unicode isolate characters (spec 10.2): FSI (U+2068) opens, PDI (U+2069) closes. */
const val FSI: String = "\u2068"
const val PDI: String = "\u2069"

/** Wraps an LTR string (file name, path) in FSI/PDI isolates for embedding in RTL text. */
fun isolateLtr(value: String): String = FSI + value + PDI

/**
 * Text for embedding inside a (possibly RTL) sentence: [text] may already contain
 * plain prose; [ltrSegments] are wrapped in isolates by callers via [isolateLtr].
 * This composable simply renders [text] with the current style.
 */
@Composable
fun BidiText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * An LTR, monospace, single-line label for a file name / path / folder name.
 * Forced [TextDirection.Ltr] and ellipsized (spec 10.2).
 */
@Composable
fun LtrText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(fontFamily = MonoFontFamily, textDirection = TextDirection.Ltr),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
