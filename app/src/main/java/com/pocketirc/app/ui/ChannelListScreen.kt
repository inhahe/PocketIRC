package com.pocketirc.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketirc.app.irc.ConnectionManager.ChannelListEntry
import com.pocketirc.app.irc.ConnectionManager.ChannelListState

private enum class SortMode { Users, Name }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelListScreen(
    serverName: String,
    state: ChannelListState,
    onClose: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortMode.Users) }

    val filtered = remember(state.entries, query, sort) {
        val matched = if (query.isBlank()) state.entries
        else state.entries.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.topic.contains(query, ignoreCase = true)
        }
        when (sort) {
            SortMode.Users -> matched.sortedByDescending { it.users }
            SortMode.Name -> matched.sortedBy { it.name.lowercase() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Channels on $serverName", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.loading) "Loading… ${state.entries.size} so far"
                            else "${filtered.size} of ${state.entries.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onClose) { Text("Close") } },
                actions = {
                    IconButton(onClick = {
                        sort = if (sort == SortMode.Users) SortMode.Name else SortMode.Users
                    }) {
                        Icon(
                            Icons.Default.Sort,
                            contentDescription = "Sort: ${if (sort == SortMode.Users) "users" else "name"}",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Filter by name or topic…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(filtered, key = { it.name }) { entry ->
                    ChannelRow(entry = entry, onJoin = { onJoin(entry.name) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(entry: ChannelListEntry, onJoin: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onJoin)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(48.dp)
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                )
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                entry.users.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (entry.topic.isNotBlank()) {
                Text(
                    entry.topic,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
