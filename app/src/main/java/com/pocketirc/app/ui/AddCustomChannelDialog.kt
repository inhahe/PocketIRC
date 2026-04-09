package com.pocketirc.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pocketirc.app.PocketIrcApp
import com.pocketirc.app.notif.CustomAlertChannel
import com.pocketirc.app.notif.CustomChannelsManager

/**
 * Dialog for creating a new custom alert channel. The user picks a short
 * name (used as `/alert -c <name>`), an importance level, an optional sound,
 * and the vibration / lights / badge toggles. Tapping Save persists the
 * channel and creates the underlying Android NotificationChannel.
 *
 * Once saved, the channel's settings are frozen as far as the app is
 * concerned — only the user can change them, via Android Settings → Apps →
 * Pocket IRC → Notifications.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCustomChannelDialog(
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (CustomAlertChannel) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var importance by remember { mutableStateOf("normal") }
    var sound by remember { mutableStateOf("") }
    var vibration by remember { mutableStateOf(false) }
    var lights by remember { mutableStateOf(false) }
    var badge by remember { mutableStateOf(true) }

    val builtInNames = PocketIrcApp.USER_CHANNEL_NAMES.keys
    val nameTaken = name.isNotBlank() && (
        name.lowercase() in builtInNames ||
        existingNames.any { it.equals(name, ignoreCase = true) }
    )
    val canSave = name.isNotBlank() && !nameTaken

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .heightIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("New alert channel", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pick a short name to use with /alert -c. Sound, importance, " +
                    "vibration, lights, and badge are baked in at creation and " +
                    "can later be fine-tuned only from Android Settings → Apps → " +
                    "Pocket IRC → Notifications.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    isError = nameTaken,
                    supportingText = {
                        if (nameTaken) Text("Name is already in use.")
                        else Text("Used as the value of /alert -c <name>.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                ImportanceDropdown(current = importance, onChange = { importance = it })

                SoundPickerRow(
                    label = "Sound (optional)",
                    current = sound,
                    onPick = { sound = it },
                )

                ToggleRow("Vibration", vibration) { vibration = it }
                ToggleRow("LED light", lights) { lights = it }
                Text(
                    "Lights only have an effect on devices that have a notification LED. " +
                    "Most modern phones don't.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                ToggleRow("Show badge on launcher icon", badge) { badge = it }

                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            val ch = CustomAlertChannel(
                                name = name.trim(),
                                channelId = CustomChannelsManager.generateId(name.trim()),
                                importance = importance,
                                sound = sound,
                                vibration = vibration,
                                lights = lights,
                                badge = badge,
                            )
                            onSave(ch)
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportanceDropdown(current: String, onChange: (String) -> Unit) {
    val options = listOf(
        "silent" to "Silent (in shade overflow only)",
        "quiet" to "Quiet (in shade, no sound)",
        "normal" to "Normal (sound, in shade)",
        "loud" to "Loud (sound + heads-up + screen wake)",
    )
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == current }?.second ?: options[2].second
    Column {
        Text("Importance", style = MaterialTheme.typography.bodyMedium)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                for ((value, lbl) in options) {
                    DropdownMenuItem(
                        text = { Text(lbl) },
                        onClick = { onChange(value); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
