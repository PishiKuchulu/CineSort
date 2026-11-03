package com.cinesort.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Shared switch row (granular audit 1-7/1-10): the whole row is the toggle
 * target via [toggleable] with [Role.Switch], not just the switch thumb.
 * Replaces the three divergent private copies (Settings, EditSheet, Preview).
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    labelStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = labelStyle,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Shared numeric stepper (granular audit 1-7, the critical row): the +/-
 * buttons carry real localized content descriptions ([decreaseLabel] /
 * [increaseLabel] are required — icon-only buttons without labels are
 * invisible to TalkBack). Replaces the Settings and Preview copies.
 */
@Composable
fun StepperRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    decreaseLabel: String,
    increaseLabel: String,
    modifier: Modifier = Modifier,
    persianDigits: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onValueChange((value - 1).coerceAtLeast(min)) },
                enabled = value > min,
            ) {
                Icon(imageVector = Icons.Filled.Remove, contentDescription = decreaseLabel)
            }
            Text(
                text = value.toString().let {
                    if (persianDigits) it.toPersianDigits() else it
                },
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(
                onClick = { onValueChange((value + 1).coerceAtMost(max)) },
                enabled = value < max,
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = increaseLabel)
            }
        }
    }
}
