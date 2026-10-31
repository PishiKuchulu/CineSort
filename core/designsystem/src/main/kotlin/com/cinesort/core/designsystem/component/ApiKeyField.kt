package com.cinesort.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Masked API-key field with visibility toggle, Test, help and Clear actions
 * (spec 9.1 / 10.5). The value itself is owned by the caller (Settings state);
 * the key is never logged by this component.
 */
@Composable
fun ApiKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    testLabel: String,
    onTest: () -> Unit,
    helpLabel: String,
    onHelp: () -> Unit,
    clearLabel: String,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    statusMessage: String? = null,
    isTesting: Boolean = false,
    showContentDescription: String = "Show key",
    hideContentDescription: String = "Hide key",
) {
    var visible by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (visible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) hideContentDescription else showContentDescription,
                    )
                }
            },
        )
        if (statusMessage != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Start) {
            Button(onClick = onTest, enabled = value.isNotBlank() && !isTesting) {
                Text(testLabel)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onHelp) { Text(helpLabel) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClear, enabled = value.isNotEmpty()) { Text(clearLabel) }
        }
    }
}
