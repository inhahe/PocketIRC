package com.pocketirc.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pocketirc.app.irc.HelpRegistry

@Composable
fun HelpDialog(
    initialCommand: String?,
    onDismiss: () -> Unit,
) {
    var selected by remember(initialCommand) { mutableStateOf(initialCommand) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Help",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected != null) {
                        TextButton(onClick = { selected = null }) { Text("All") }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                val sel = selected
                if (sel == null) {
                    Text(
                        "Tap a command for full usage and details.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        for ((name, entry) in HelpRegistry.entries) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = name }
                                    .padding(vertical = 6.dp),
                            ) {
                                Text(
                                    "/$name",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(110.dp),
                                )
                                Text(
                                    entry.summary.lineSequence().first(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                } else {
                    val entry = HelpRegistry.entries[sel]
                    if (entry == null) {
                        Text("No help for /$sel")
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                entry.usage,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                entry.summary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
