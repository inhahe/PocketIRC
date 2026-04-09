package com.pocketirc.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp

@Composable
fun BanHostmaskDialog(
    title: String,
    confirmLabel: String,
    nick: String,
    onCancel: () -> Unit,
    onConfirm: (mask: String) -> Unit,
) {
    var mask by remember { mutableStateOf("$nick!*@*") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Hostmask format: nick!user@host. Use * as a wildcard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    "Examples:\n" +
                    "  $nick!*@*    — ban by nick (easy to evade)\n" +
                    "  *!*@host     — ban by host (best, requires WHOIS)\n" +
                    "  *!user@host  — ban by user@host",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                OutlinedTextField(
                    value = mask,
                    onValueChange = { mask = it },
                    label = { Text("Hostmask") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = mask.isNotBlank(),
                onClick = { onConfirm(mask.trim()) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
