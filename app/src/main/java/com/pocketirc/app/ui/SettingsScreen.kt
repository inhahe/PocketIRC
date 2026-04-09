package com.pocketirc.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketirc.app.data.AppSettings
import com.pocketirc.app.data.ThemeMode
import com.pocketirc.app.model.ServerConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    servers: List<ServerConfig>,
    onClose: () -> Unit,
    onColorNicksChange: (Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onTimestampFormatChange: (String) -> Unit,
    onMonospaceChange: (Boolean) -> Unit,
    onSendOnEnterChange: (Boolean) -> Unit,
    onQuitMessageChange: (String) -> Unit,
    onMentionNotificationsChange: (Boolean) -> Unit,
    onPrivmsgNotificationsChange: (Boolean) -> Unit,
    onNoticeNotificationsChange: (Boolean) -> Unit,
    onNotificationReplyChange: (Boolean) -> Unit,
    onNotifyListNotificationsChange: (Boolean) -> Unit,
    onDefaultAlertChannelChange: (String) -> Unit,
    onReplayThresholdChange: (Int) -> Unit,
    customAlertChannels: List<com.pocketirc.app.notif.CustomAlertChannel>,
    onAddCustomChannel: (com.pocketirc.app.notif.CustomAlertChannel) -> Unit,
    onRemoveCustomChannel: (String) -> Unit,
    onStartupScriptChange: (String) -> Unit,
    onCtcpVersionChange: (String) -> Unit,
    onCtcpTimeChange: (String) -> Unit,
    onCtcpFingerChange: (String) -> Unit,
    onCtcpUserinfoChange: (String) -> Unit,
    onCtcpSourceChange: (String) -> Unit,
    onDefaultNicksCsvChange: (String) -> Unit,
    onDefaultRealNameChange: (String) -> Unit,
    onDefaultUserNameChange: (String) -> Unit,
    onAutoAddJoinedChannelsChange: (Boolean) -> Unit,
    onAddNetwork: () -> Unit,
    onEditNetwork: (String) -> Unit,
    onRemoveNetwork: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Done") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionLabel("Identity") }
            item {
                Text(
                    "Defaults used by every network unless overridden in that " +
                        "network's own editor. Setting these once here is the " +
                        "easiest way to keep your identity consistent across " +
                        "all your IRC connections — every network will use these " +
                        "values unless its editor explicitly fills the field in.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                // Local-state pattern: each persistent text field needs its
                // own rememberSaveable mutableState. Binding the field value
                // directly to settings.X scrambles the input under fast
                // typing because the DataStore round-trip is async — the
                // recomposition with the lagged value can clobber characters
                // typed in between. Local state is the source of truth for
                // the field; the upstream write happens as a fire-and-
                // forget side effect on each keystroke.
                var local by rememberSaveable { mutableStateOf(settings.defaultNicksCsv) }
                val parsed = local
                    .split(Regex("[\\s,]+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val firstInvalid = parsed.firstNotNullOfOrNull { n ->
                    com.pocketirc.app.irc.NickValidator.reasonInvalid(n)?.let { it1 -> n to it1 }
                }
                OutlinedTextField(
                    value = local,
                    onValueChange = {
                        local = it
                        onDefaultNicksCsvChange(it)
                    },
                    label = { Text("Default nicks") },
                    supportingText = {
                        if (firstInvalid != null) {
                            Text("'${firstInvalid.first}': ${firstInvalid.second}")
                        } else {
                            Text("Comma- or space-separated. The first entry is " +
                                "the primary; the rest are tried in order if it's " +
                                "taken. Per-network override available in each " +
                                "network's editor.")
                        }
                    },
                    isError = firstInvalid != null,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                var local by rememberSaveable { mutableStateOf(settings.defaultRealName) }
                OutlinedTextField(
                    value = local,
                    onValueChange = {
                        local = it
                        onDefaultRealNameChange(it)
                    },
                    label = { Text("Default real name") },
                    supportingText = {
                        Text("If blank, the resolved primary nick is used as " +
                            "the real name. Per-network override available.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                var local by rememberSaveable { mutableStateOf(settings.defaultUserName) }
                OutlinedTextField(
                    value = local,
                    onValueChange = {
                        local = it
                        onDefaultUserNameChange(it)
                    },
                    label = { Text("Default ident username") },
                    supportingText = {
                        Text("The USER command's username field. Networks see " +
                            "this prefixed with '~' since mobile clients can't " +
                            "run an ident server. Leave blank to use KICL's " +
                            "default. Per-network override available.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionLabel("Appearance") }
            item {
                ThemeRow(current = settings.themeMode, onChange = onThemeModeChange)
            }
            item {
                FontSizeRow(current = settings.fontSizeSp, onChange = onFontSizeChange)
            }
            item {
                var local by rememberSaveable { mutableStateOf(settings.timestampFormat) }
                OutlinedTextField(
                    value = local,
                    onValueChange = {
                        local = it
                        onTimestampFormatChange(it)
                    },
                    label = { Text("Timestamp format") },
                    supportingText = {
                        Text("Java SimpleDateFormat. Examples: HH:mm  ·  HH:mm:ss  ·  hh:mm a  ·  MMM d HH:mm")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                ToggleRow(
                    title = "Color nicknames",
                    description = "Tint each nick a unique color. Off by default.",
                    checked = settings.colorNicks,
                    onCheckedChange = onColorNicksChange,
                )
            }
            item {
                ToggleRow(
                    title = "Monospace font",
                    description = "Render chat in a fixed-width font.",
                    checked = settings.useMonospace,
                    onCheckedChange = onMonospaceChange,
                )
            }
            item {
                ToggleRow(
                    title = "Send on Enter",
                    description = "Pressing Enter sends the message. Use Shift+Enter or Ctrl+Enter to insert a new line. Off = Enter always inserts a new line; tap the send button to send.",
                    checked = settings.sendOnEnter,
                    onCheckedChange = onSendOnEnterChange,
                )
            }
            item {
                var local by rememberSaveable { mutableStateOf(settings.defaultQuitMessage) }
                OutlinedTextField(
                    value = local,
                    onValueChange = {
                        local = it
                        onQuitMessageChange(it)
                    },
                    label = { Text("Default quit message") },
                    supportingText = { Text("Used by /quit and the Disconnect menu when no reason is given.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionLabel("CTCP responses") }
            item {
                Text(
                    "How Pocket IRC replies when other users probe you with CTCP " +
                        "queries. For VERSION/FINGER/USERINFO, leave the field " +
                        "blank to use a default that auto-updates with the app's " +
                        "version or your current real name; type 'off' to refuse " +
                        "the request silently; or enter any text to send that " +
                        "text verbatim.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Resolved-default previews for the dynamic fields. Computed
            // here once and shown in supporting text so the user can see
            // what would be sent without us freezing the value into a
            // saved override.
            val versionDefault = "Pocket IRC ${com.pocketirc.app.BuildConfig.VERSION_NAME} - " +
                "https://github.com/inhahe/pocketirc"
            val realNameDefault = settings.defaultRealName.ifBlank {
                settings.defaultNicksCsv
                    .split(Regex("[\\s,]+"))
                    .firstOrNull { it.isNotBlank() }
                    ?: "(your nick)"
            }
            item {
                var local by rememberSaveable { mutableStateOf(settings.ctcpVersion) }
                OutlinedTextField(
                    value = local,
                    onValueChange = {
                        local = it
                        onCtcpVersionChange(it)
                    },
                    label = { Text("VERSION") },
                    supportingText = {
                        Text(
                            if (local.equals("off", ignoreCase = true))
                                "Refusing VERSION queries silently."
                            else if (local.isBlank())
                                "Currently sends: $versionDefault. Leave blank to " +
                                    "keep this updated automatically with each " +
                                    "app version."
                            else
                                "Override active. Clear the field to restore the " +
                                    "auto-updating default ($versionDefault)."
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                // TIME is one of three discrete states. The free-text
                // override is intentionally dropped from the UI — power
                // users who want a literal string can still /set ctcp_time foo.
                // Local state mirrors the selection so click feedback is
                // instant without waiting for the DataStore round-trip.
                var local by rememberSaveable { mutableStateOf(settings.ctcpTime) }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("TIME", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    val options = listOf(
                        "" to "Local",
                        "utc" to "UTC",
                        "off" to "Off",
                    )
                    val selectedIndex = options.indexOfFirst { it.first == local }
                        .let { if (it < 0) 0 else it }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        options.forEachIndexed { i, (value, label) ->
                            SegmentedButton(
                                selected = i == selectedIndex,
                                onClick = {
                                    local = value
                                    onCtcpTimeChange(value)
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = i, count = options.size,
                                ),
                            ) { Text(label) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (local) {
                            "off" -> "Refusing TIME queries silently."
                            "utc" -> "Replying with the current time in UTC. Hides " +
                                "your timezone."
                            else -> "Replying with your local time and timezone. " +
                                "May reveal your approximate location to anyone " +
                                "who CTCP-probes you."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            item {
                var local by rememberSaveable { mutableStateOf(settings.ctcpFinger) }
                OutlinedTextField(
                    value = local,
                    onValueChange = {
                        local = it
                        onCtcpFingerChange(it)
                    },
                    label = { Text("FINGER") },
                    supportingText = {
                        Text(
                            if (local.equals("off", ignoreCase = true))
                                "Refusing FINGER queries silently."
                            else if (local.isBlank())
                                "Currently sends: \"$realNameDefault\". Leave blank " +
                                    "to follow your real name automatically."
                            else
                                "Override active. Clear the field to follow your " +
                                    "real name (\"$realNameDefault\")."
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                var local by rememberSaveable { mutableStateOf(settings.ctcpUserinfo) }
                OutlinedTextField(
                    value = local,
                    onValueChange = {
                        local = it
                        onCtcpUserinfoChange(it)
                    },
                    label = { Text("USERINFO") },
                    supportingText = {
                        Text(
                            if (local.equals("off", ignoreCase = true))
                                "Refusing USERINFO queries silently."
                            else if (local.isBlank())
                                "Currently sends: \"$realNameDefault\". Leave blank " +
                                    "to follow your real name automatically."
                            else
                                "Override active. Clear the field to follow your " +
                                    "real name (\"$realNameDefault\")."
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                // SOURCE has a static default, so we initialize the local
                // field to the default URL when the saved value is blank.
                // From that point on the local field IS the source of
                // truth — the user sees an editable URL ready to go.
                val sourceUrl = "https://github.com/inhahe/pocketirc"
                var local by rememberSaveable {
                    mutableStateOf(settings.ctcpSource.ifBlank { sourceUrl })
                }
                OutlinedTextField(
                    value = local,
                    onValueChange = {
                        local = it
                        onCtcpSourceChange(it)
                    },
                    label = { Text("SOURCE") },
                    supportingText = {
                        Text(
                            if (local.equals("off", ignoreCase = true))
                                "Refusing SOURCE queries silently."
                            else
                                "URL sent in reply to CTCP SOURCE. Type 'off' to " +
                                    "refuse the query."
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionLabel("Notifications") }
            item {
                ToggleRow(
                    title = "Channel mentions",
                    description = "Show a notification when your nick is mentioned in a channel.",
                    checked = settings.mentionNotifications,
                    onCheckedChange = onMentionNotificationsChange,
                )
            }
            item {
                ToggleRow(
                    title = "Private messages",
                    description = "Show a notification when you receive a private message (PRIVMSG to your nick).",
                    checked = settings.privmsgNotifications,
                    onCheckedChange = onPrivmsgNotificationsChange,
                )
            }
            item {
                ToggleRow(
                    title = "Notices from users",
                    description = "Show a notification for NOTICEs sent to you by another user (e.g. NickServ). Server-sourced notices are always silent.",
                    checked = settings.noticeNotifications,
                    onCheckedChange = onNoticeNotificationsChange,
                )
            }
            item {
                ToggleRow(
                    title = "Inline reply action",
                    description = "Add a Reply button to mention notifications so you can respond from the notification shade without opening the app.",
                    checked = settings.notificationReplyAction,
                    onCheckedChange = onNotificationReplyChange,
                )
            }
            item {
                ToggleRow(
                    title = "Watched nick online/offline",
                    description = "Show a notification when a nick on a server's notify list comes online or goes offline.",
                    checked = settings.notifyListNotifications,
                    onCheckedChange = onNotifyListNotificationsChange,
                )
            }
            item {
                AlertChannelRow(
                    current = settings.defaultAlertChannel,
                    customNames = customAlertChannels.map { it.name },
                    onChange = onDefaultAlertChannelChange,
                )
            }
            item {
                var draft by remember(settings.replayNotificationThreshold) {
                    mutableStateOf(settings.replayNotificationThreshold.toString())
                }
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { v ->
                            draft = v.filter(Char::isDigit).take(3)
                            draft.toIntOrNull()?.let { onReplayThresholdChange(it) }
                        },
                        label = { Text("Replay notification threshold") },
                        supportingText = {
                            Text(
                                "When reconnecting to a bouncer, fire mention " +
                                "notifications only if the replay contains at " +
                                "most this many mentions. 0 = never. Higher = " +
                                "always. Default 3."
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Text(
                    "You can fine-tune sound, vibration, importance, lights, and " +
                    "badges for any alert channel under Android Settings → Apps → " +
                    "Pocket IRC → Notifications. Custom channels you add below " +
                    "appear there too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionLabel("Custom alert channels") }
            item {
                Text(
                    "Create your own /alert -c <name> targets with their own sound, " +
                    "importance, vibration, and badge. Each one becomes a real " +
                    "Android notification channel that the user can fine-tune in " +
                    "system settings later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (customAlertChannels.isEmpty()) {
                item {
                    Text("No custom channels yet.",
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.outline)
                }
            } else {
                items(customAlertChannels, key = { it.channelId }) { ch ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${ch.importance}" +
                                    (if (ch.vibration) " · vibrate" else "") +
                                    (if (ch.lights) " · light" else "") +
                                    (if (ch.badge) " · badge" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        IconButton(onClick = { onRemoveCustomChannel(ch.channelId) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    }
                }
            }
            item {
                var dialogOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { dialogOpen = true }) { Text("+ Add channel") }
                if (dialogOpen) {
                    AddCustomChannelDialog(
                        existingNames = customAlertChannels.map { it.name },
                        onDismiss = { dialogOpen = false },
                        onSave = { ch ->
                            onAddCustomChannel(ch)
                            dialogOpen = false
                        },
                    )
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionLabel("Startup script") }
            item {
                Text(
                    "One slash command per line. Runs at app startup before " +
                    "auto-connect. Use it to register /on hooks or to /server " +
                    "new networks. Lines starting with # are comments.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            item {
                var draft by remember(settings.startupScript) {
                    mutableStateOf(settings.startupScript)
                }
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Script") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        minLines = 6,
                        maxLines = 20,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            enabled = draft != settings.startupScript,
                            onClick = { onStartupScriptChange(draft) },
                        ) { Text("Save") }
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionLabel("Channel autojoin") }
            item {
                ToggleRow(
                    title = "Auto-add joined channels",
                    description = "When on, every /join is automatically added to " +
                        "that network's autojoin list (and /part removes it). " +
                        "Persisted networks only — ephemeral /server connections " +
                        "are unaffected. Any key you type when /joining a " +
                        "channel is remembered for the rest of the session and " +
                        "reused if the channel later gets added to autojoin. " +
                        "You can also toggle auto-join per channel from a " +
                        "channel's info screen or by long-pressing the channel " +
                        "in the network tree.",
                    checked = settings.autoAddJoinedChannels,
                    onCheckedChange = onAutoAddJoinedChannelsChange,
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Networks", modifier = Modifier.weight(1f))
                    TextButton(onClick = onAddNetwork) { Text("Add") }
                }
            }
            if (servers.isEmpty()) {
                item {
                    Text("No networks configured.",
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.outline)
                }
            } else {
                items(servers, key = { it.id }) { server ->
                    NetworkRow(
                        server = server,
                        onEdit = { onEditNetwork(server.id) },
                        onRemove = { onRemoveNetwork(server.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertChannelRow(
    current: String,
    customNames: List<String> = emptyList(),
    onChange: (String) -> Unit,
) {
    val builtIns = listOf(
        "silent" to "Silent (no sound, in shade overflow)",
        "quiet"  to "Quiet (in shade, no sound, no badge)",
        "normal" to "Normal (sound, in shade)",
        "loud"   to "Loud (sound + heads-up + screen wake)",
    )
    val options = builtIns + customNames.map { it to "Custom: $it" }
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first.equals(current, ignoreCase = true) }?.second
        ?: builtIns[2].second
    Column {
        Text(
            "Default /alert channel",
            style = MaterialTheme.typography.bodyMedium,
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                for ((value, label) in options) {
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onChange(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

@Composable
private fun ToggleRow(
    title: String, description: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeRow(current: ThemeMode, onChange: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value = current.name.lowercase().replaceFirstChar { it.uppercase() },
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ThemeMode.values().forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        onClick = { onChange(mode); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun FontSizeRow(current: Int, onChange: (Int) -> Unit) {
    Column {
        Text("Chat font size: ${current}sp",
             style = MaterialTheme.typography.titleSmall)
        Slider(
            value = current.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 12f..26f,
            steps = 26 - 12 - 1,
        )
    }
}

@Composable
private fun NetworkRow(
    server: ServerConfig,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(server.name, style = MaterialTheme.typography.titleSmall)
            Text(
                server.endpoints.joinToString(", ") { "${it.host}:${it.port}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove")
        }
    }
}
