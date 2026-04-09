package com.pocketirc.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun KickReasonDialog(
    nick: String,
    channel: String,
    onCancel: () -> Unit,
    onConfirm: (reason: String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Kick $nick from $channel") },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Reason (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.trim()) }) { Text("Kick") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun InviteToChannelDialog(
    nick: String,
    defaultChannel: String?,
    onCancel: () -> Unit,
    onConfirm: (channel: String) -> Unit,
) {
    var channel by remember { mutableStateOf(defaultChannel ?: "#") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Invite $nick") },
        text = {
            OutlinedTextField(
                value = channel,
                onValueChange = { channel = it },
                label = { Text("Channel") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = channel.trim().length > 1,
                onClick = { onConfirm(channel.trim()) },
            ) { Text("Invite") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
