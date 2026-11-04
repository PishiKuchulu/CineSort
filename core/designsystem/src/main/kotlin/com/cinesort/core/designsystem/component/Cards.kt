package com.cinesort.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Formats a byte count for display; [persianDigits] converts ASCII digits when requested. */
fun formatBytes(bytes: Long?, persianDigits: Boolean = false, unknownLabel: String = "—"): String {
    if (bytes == null || bytes < 0) return unknownLabel
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val raw = if (unit == 0) {
        bytes.toString() + " " + units[unit]
    } else {
        String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit])
    }
    return if (persianDigits) raw.toPersianDigits() else raw
}

/** Converts ASCII digits in a string to Persian digits. */
fun String.toPersianDigits(): String {
    val persian = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    return map { c -> if (c in '0'..'9') persian[c - '0'] else c }.joinToString("")
}

/** Horizontal bar showing used vs free space of the destination volume. */
@Composable
fun FreeSpaceBar(
    freeBytes: Long?,
    totalBytes: Long?,
    freeLabel: String,
    modifier: Modifier = Modifier,
    persianDigits: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // The bar renders only when the total is known (granular audit 1-2):
        // an always-empty bar falsely reads as "no free space" — and the
        // gateway exposes no total, so on Sources the bar is hidden and only
        // the text (with "—" for an unknown free value) remains.
        if (totalBytes != null && totalBytes > 0) {
            val fraction = if (freeBytes != null) {
                (freeBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = freeLabel + ": " + formatBytes(freeBytes, persianDigits),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun DestinationCard(
    name: String,
    path: String,
    freeBytes: Long?,
    totalBytes: Long?,
    freeLabel: String,
    changeLabel: String,
    statusLabel: String?,
    isStatusOk: Boolean,
    onChangeClick: () -> Unit,
    modifier: Modifier = Modifier,
    persianDigits: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (statusLabel != null) {
                    StatusChip(label = statusLabel, isOk = isStatusOk)
                }
            }
            Spacer(Modifier.height(6.dp))
            LtrText(text = path, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            FreeSpaceBar(
                freeBytes = freeBytes,
                totalBytes = totalBytes,
                freeLabel = freeLabel,
                persianDigits = persianDigits,
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onChangeClick) { Text(changeLabel) }
            }
        }
    }
}

@Composable
fun SourceCard(
    name: String,
    path: String,
    includeSubfolders: Boolean,
    includeSubfoldersLabel: String,
    depthLabel: String,
    statusLabel: String,
    isStatusOk: Boolean,
    onIncludeSubfoldersChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    depthValue: Int? = null,
    depthMin: Int = 1,
    depthMax: Int = 10,
    onDepthChange: ((Int) -> Unit)? = null,
    depthDecreaseLabel: String = "",
    depthIncreaseLabel: String = "",
    trailing: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(label = statusLabel, isOk = isStatusOk)
                if (trailing != null) trailing()
            }
            Spacer(Modifier.height(6.dp))
            LtrText(text = path, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            // The whole row toggles (granular audit 1-2 pattern, same as the
            // shared SwitchRow); the switch itself is a passive indicator.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = includeSubfolders,
                        role = Role.Switch,
                        onValueChange = onIncludeSubfoldersChange,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = includeSubfoldersLabel, style = MaterialTheme.typography.bodyMedium)
                    if (onDepthChange == null) {
                        Text(
                            text = depthLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(checked = includeSubfolders, onCheckedChange = null)
            }
            // The scan depth is editable right on the card (granular audit
            // 1-6) instead of being a display-only value whose editor lives
            // on another screen. [depthLabel] becomes the stepper's label.
            if (onDepthChange != null && depthValue != null) {
                StepperRow(
                    label = depthLabel,
                    value = depthValue,
                    min = depthMin,
                    max = depthMax,
                    onValueChange = onDepthChange,
                    decreaseLabel = depthDecreaseLabel,
                    increaseLabel = depthIncreaseLabel,
                )
            }
        }
    }
}

/** Compact statistic tile for the Home dashboard; tappable when [onClick] is set. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = if (onClick != null) {
            modifier.clickable(onClick = onClick)
        } else {
            modifier
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = value, style = MaterialTheme.typography.headlineSmall)
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
