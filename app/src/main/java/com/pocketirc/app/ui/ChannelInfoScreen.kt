package com.pocketirc.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketirc.app.irc.ChannelInfoState
import com.pocketirc.app.irc.ChannelModeDescriptions
import com.pocketirc.app.irc.MaskEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelInfoScreen(
    channel: String,
    state: ChannelInfoState?,
    isOp: Boolean,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onSetTopic: (String) -> Unit,
    onSetMode: (String) -> Unit,
    onAddMask: (modeChar: Char, mask: String) -> Unit,
    onRemoveMask: (modeChar: Char, mask: String) -> Unit,
    initialTopic: String,
    autoJoinEnabled: Boolean,
    onAutoJoinChange: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Channel info", style = MaterialTheme.typography.titleMedium)
                        Text(
                            channel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onClose) { Text("Close") } },
                actions = { TextButton(onClick = onRefresh) { Text("Refresh") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!isOp) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "You are not a channel operator. Settings are read-only.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ----- Auto-join toggle (works regardless of op status; this
            // is a per-channel preference, not a channel mode). Mirrored
            // into local state because the source-of-truth lives in the
            // manager which doesn't currently expose a Flow we can collect
            // — local state keeps the switch responsive without making
            // ConnectionManager observable for this single field.
            SectionHeader("Auto-join")
            var autoJoinLocal by remember(channel) { mutableStateOf(autoJoinEnabled) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-join on connect")
                    Text(
                        "Add this channel to the network's autojoin list so " +
                            "it's rejoined automatically next time you connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(
                    checked = autoJoinLocal,
                    onCheckedChange = {
                        autoJoinLocal = it
                        onAutoJoinChange(it)
                    },
                )
            }

            // ----- Topic editor
            SectionHeader("Topic")
            var topicDraft by rememberSaveable(initialTopic) { mutableStateOf(initialTopic) }
            OutlinedTextField(
                value = topicDraft,
                onValueChange = { topicDraft = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
            )
            Row {
                Button(
                    onClick = { onSetTopic(topicDraft) },
                    enabled = isOp || (state?.modes?.contains('t') != true),
                ) { Text("Set topic") }
            }

            // ----- Modes
            SectionHeader("Modes")
            if (state == null) {
                Text("Loading…", style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
            } else {
                Text(
                    "Current: +${state.modes.ifEmpty { "(none)" }}" +
                        (state.key?.let { "  key=$it" } ?: "") +
                        (state.userLimit?.let { "  limit=$it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                FlowFlagGrid(
                    modes = state.modes,
                    enabled = isOp,
                    onToggle = { mode, on ->
                        onSetMode(if (on) "+$mode" else "-$mode")
                    },
                )

                // Key (+k)
                var keyDraft by rememberSaveable(state.key) { mutableStateOf(state.key ?: "") }
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    label = { Text("Channel key (+k)") },
                    placeholder = { Text("(none)") },
                    enabled = isOp,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(
                        enabled = isOp,
                        onClick = {
                            val k = keyDraft.trim()
                            if (k.isNotEmpty()) onSetMode("+k $k") else onSetMode("-k *")
                        },
                    ) { Text("Apply key") }
                }

                // Limit (+l)
                var limitDraft by rememberSaveable(state.userLimit) {
                    mutableStateOf(state.userLimit?.toString() ?: "")
                }
                OutlinedTextField(
                    value = limitDraft,
                    onValueChange = { v -> limitDraft = v.filter(Char::isDigit) },
                    label = { Text("Max users (+l)") },
                    placeholder = { Text("(none)") },
                    enabled = isOp,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(
                        enabled = isOp,
                        onClick = {
                            val n = limitDraft.toIntOrNull() ?: 0
                            if (n > 0) onSetMode("+l $n") else onSetMode("-l")
                        },
                    ) { Text("Apply limit") }
                }
            }

            // ----- Lists
            if (state != null) {
                MaskListSection("Bans (+b)", 'b', state.bans, state.bansLoading, isOp,
                    onAddMask, onRemoveMask)
                MaskListSection("Excepts (+e)", 'e', state.excepts, state.exceptsLoading, isOp,
                    onAddMask, onRemoveMask)
                MaskListSection("Invite-ex (+I)", 'I', state.invites, state.invitesLoading, isOp,
                    onAddMask, onRemoveMask)
                MaskListSection("Quiets (+q)", 'q', state.quiets, state.quietsLoading, isOp,
                    onAddMask, onRemoveMask)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun FlowFlagGrid(
    modes: String,
    enabled: Boolean,
    onToggle: (Char, Boolean) -> Unit,
) {
    Column {
        for (chunk in ChannelModeDescriptions.flagOrder.chunked(2)) {
            Row {
                for (ch in chunk) {
                    val on = modes.contains(ch)
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { newVal -> onToggle(ch, newVal) },
                            enabled = enabled,
                        )
                        Text(
                            "+$ch  ${ChannelModeDescriptions.descriptions[ch] ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (chunk.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MaskListSection(
    title: String,
    modeChar: Char,
    entries: List<MaskEntry>,
    loading: Boolean,
    isOp: Boolean,
    onAddMask: (Char, String) -> Unit,
    onRemoveMask: (Char, String) -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    var addDraft by rememberSaveable(title) { mutableStateOf("") }

    SectionHeader(title)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide" else "Show")
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (loading) "Loading…" else "${entries.size} entries",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (entry in entries) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.mask, style = MaterialTheme.typography.bodyMedium)
                        if (entry.setter != null) {
                            Text(
                                "by ${entry.setter}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    if (isOp) {
                        IconButton(onClick = { onRemoveMask(modeChar, entry.mask) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    }
                }
            }
            if (isOp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = addDraft,
                        onValueChange = { addDraft = it },
                        placeholder = { Text("nick!*@*") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = addDraft.trim().isNotEmpty(),
                        onClick = {
                            onAddMask(modeChar, addDraft.trim())
                            addDraft = ""
                        },
                    ) { Text("Add") }
                }
            }
        }
    }
}
