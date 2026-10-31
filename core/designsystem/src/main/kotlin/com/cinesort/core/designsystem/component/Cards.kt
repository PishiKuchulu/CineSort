package com.cinesort.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
        val fraction = if (freeBytes != null && totalBytes != null && totalBytes > 0) {
            (freeBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
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
                Text(text = name, style = MaterialTheme.typography.titleMedium)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(text = includeSubfoldersLabel, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = depthLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = includeSubfolders, onCheckedChange = onIncludeSubfoldersChange)
            }
        }
    }
}

/** Compact statistic tile for the Home dashboard. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
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
