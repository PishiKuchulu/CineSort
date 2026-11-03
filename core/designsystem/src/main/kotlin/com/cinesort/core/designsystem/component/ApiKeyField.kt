package com.cinesort.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cinesort.core.designsystem.theme.LocalCineSortTokens

/** Semantic kind of an [ApiKeyField] status message (granular audit 1-2). */
enum class ApiKeyStatusKind { Neutral, Success, Error }

/**
 * Masked API-key field with visibility toggle, Test, help and Clear actions
 * (spec 9.1 / 10.5). The value itself is owned by the caller (Settings state);
 * the key is never logged by this component.
 *
 * The status line is semantic ([statusKind]): success and failure differ by
 * color AND icon, not by text alone; a failed test also puts the field
 * itself into its error state. While [isTesting], the Test button shows a
 * spinner. The visibility toggle descriptions are required parameters so no
 * hard-coded English default can leak into a localized UI.
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
    showContentDescription: String,
    hideContentDescription: String,
    modifier: Modifier = Modifier,
    statusMessage: String? = null,
    statusKind: ApiKeyStatusKind = ApiKeyStatusKind.Neutral,
    isTesting: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    val tokens = LocalCineSortTokens.current
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true,
            isError = statusKind == ApiKeyStatusKind.Error,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (statusKind) {
                    ApiKeyStatusKind.Success -> Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = tokens.successText,
                        modifier = Modifier.size(14.dp),
                    )
                    ApiKeyStatusKind.Error -> Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    ApiKeyStatusKind.Neutral -> Unit
                }
                if (statusKind != ApiKeyStatusKind.Neutral) {
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (statusKind) {
                        ApiKeyStatusKind.Success -> tokens.successText
                        ApiKeyStatusKind.Error -> MaterialTheme.colorScheme.error
                        ApiKeyStatusKind.Neutral ->
                            MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Start) {
            Button(onClick = onTest, enabled = value.isNotBlank() && !isTesting) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(testLabel)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onHelp) { Text(helpLabel) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClear, enabled = value.isNotEmpty()) { Text(clearLabel) }
        }
    }
}
