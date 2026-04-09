package com.pocketirc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun JoinChannelDialog(
    onCancel: () -> Unit,
    onJoin: (channel: String, key: String?) -> Unit,
) {
    var channel by remember { mutableStateOf("#") }
    var key by remember { mutableStateOf("") }
    var keyRevealed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Join channel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it },
                    label = { Text("Channel") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Key (optional)") },
                    singleLine = true,
                    visualTransformation = if (keyRevealed)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { keyRevealed = !keyRevealed }) {
                            Icon(
                                imageVector = if (keyRevealed)
                                    Icons.Filled.VisibilityOff
                                else
                                    Icons.Filled.Visibility,
                                contentDescription = if (keyRevealed) "Hide key" else "Show key",
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = channel.trim().length > 1,
                onClick = { onJoin(channel.trim(), key.takeIf { it.isNotBlank() }) },
            ) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
