package com.pocketirc.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketirc.app.data.NetworkPreset
import com.pocketirc.app.data.NetworkPresets
import com.pocketirc.app.model.AutoJoinChannel
import com.pocketirc.app.model.SaslConfig
import com.pocketirc.app.model.ServerConfig
import com.pocketirc.app.model.ServerEndpoint
import java.util.UUID

/**
 * Used both for adding a new server and editing an existing one. If [initial]
 * is non-null we keep its [ServerConfig.id] so DataStore upserts the same row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    initial: ServerConfig? = null,
    /**
     * Current global identity defaults from AppSettings. Used as
     * placeholder text in the per-network identity fields, and to decide
     * whether the per-network nick field is required (it is only when the
     * global default is empty — otherwise leaving it blank means "inherit
     * the global nick", which is the recommended path).
     */
    globalDefaultNicksCsv: String = "",
    globalDefaultRealName: String = "",
    globalDefaultUserName: String = "",
    onCancel: () -> Unit,
    onSave: (ServerConfig) -> Unit,
) {
    val isEdit = initial != null
    var name by remember { mutableStateOf(initial?.name ?: "") }
    val endpoints = remember {
        mutableStateListOf<EndpointDraft>().apply {
            if (initial != null) addAll(initial.endpoints.map {
                EndpointDraft(it.host, it.port.toString(), it.tls, it.ipv4, it.ipv6)
            })
            // Fresh new server: one empty endpoint that inherits network defaults.
            else add(EndpointDraft("", "6697", true, ipv4 = null, ipv6 = null))
        }
    }
    var defaultIpv4 by remember { mutableStateOf(initial?.defaultIpv4 ?: true) }
    var defaultIpv6 by remember { mutableStateOf(initial?.defaultIpv6 ?: true) }
    // For all identity fields: empty string in the input = "inherit the
    // global default", which is the desirable common case once a global is
    // set. Edit mode preloads any existing per-network override.
    var nicksText by remember {
        mutableStateOf(initial?.nicks?.joinToString(", ") ?: "")
    }
    var realName by remember { mutableStateOf(initial?.realName ?: "") }
    var userName by remember { mutableStateOf(initial?.userName ?: "") }
    val nicksRequired = globalDefaultNicksCsv.isBlank()

    // Nick validation. Each entry in the chain is validated; the first
    // invalid one (if any) is reported. Recomputed on every recomposition
    // (cheap — pure function on a short string). Empty list is treated as
    // "incomplete" rather than "invalid" so we don't flash an error before
    // the user has typed anything.
    val parsedNicks: List<String> = nicksText
        .split(Regex("[\\s,]+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val firstInvalidNick: Pair<String, String>? = parsedNicks
        .firstNotNullOfOrNull { n ->
            com.pocketirc.app.irc.NickValidator.reasonInvalid(n)?.let { reason -> n to reason }
        }
    val nickFieldsValid = firstInvalidNick == null
    var saslEnabled by remember { mutableStateOf(initial?.sasl != null) }
    var saslUser by remember { mutableStateOf(initial?.sasl?.username ?: "") }
    var saslPass by remember { mutableStateOf(initial?.sasl?.password ?: "") }
    var serverPassword by remember { mutableStateOf(initial?.serverPassword ?: "") }
    var autoJoinText by remember {
        mutableStateOf(
            initial?.autoJoin?.joinToString("\n") {
                if (it.key.isNullOrBlank()) it.name else "${it.name} ${it.key}"
            } ?: ""
        )
    }
    var outBurst by remember { mutableStateOf((initial?.outBurst ?: 5).toString()) }
    var outRefillMs by remember { mutableStateOf((initial?.outRefillMs ?: 1000).toString()) }
    var autoReconnect by remember { mutableStateOf(initial?.autoReconnect ?: true) }
    var maxAttempts by remember { mutableStateOf((initial?.reconnectMaxAttempts ?: -1).toString()) }
    var notifyListText by remember {
        mutableStateOf(initial?.notifyList?.joinToString(" ") ?: "")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "Edit server" else "Add server") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("Cancel") } },
                actions = {
                    val canSave = (parsedNicks.isNotEmpty() || !nicksRequired) &&
                                  nickFieldsValid &&
                                  endpoints.isNotEmpty() &&
                                  endpoints.all { it.host.isNotBlank() && it.port.toIntOrNull() != null }
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            onSave(
                                ServerConfig(
                                    id = initial?.id ?: UUID.randomUUID().toString(),
                                    name = name.ifBlank { endpoints.first().host },
                                    endpoints = endpoints.map {
                                        ServerEndpoint(
                                            host = it.host.trim(),
                                            port = it.port.toInt(),
                                            tls = it.tls,
                                            ipv4 = it.ipv4,  // null = inherit
                                            ipv6 = it.ipv6,
                                        )
                                    },
                                    defaultIpv4 = defaultIpv4,
                                    defaultIpv6 = defaultIpv6,
                                    // Empty fields stay null so the resolver
                                    // inherits the global default at connect
                                    // time. This means changing the global
                                    // later automatically propagates to every
                                    // network that hasn't overridden.
                                    nicks = parsedNicks.takeIf { it.isNotEmpty() },
                                    realName = realName.trim().ifBlank { null },
                                    userName = userName.trim().ifBlank { null },
                                    sasl = if (saslEnabled)
                                        SaslConfig(saslUser.trim(), saslPass) else null,
                                    serverPassword = serverPassword.takeIf { it.isNotBlank() },
                                    autoJoin = parseAutoJoin(autoJoinText),
                                    outBurst = outBurst.toIntOrNull()?.coerceIn(1, 100) ?: 5,
                                    outRefillMs = outRefillMs.toIntOrNull()?.coerceIn(0, 10_000) ?: 1000,
                                    autoReconnect = autoReconnect,
                                    reconnectMaxAttempts = maxAttempts.toIntOrNull() ?: -1,
                                    notifyList = notifyListText
                                        .split(Regex("[\\s,]+"))
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() },
                                )
                            )
                        },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Preset picker — only useful when creating a new server.
            if (!isEdit) {
                PresetPicker(onPick = { preset ->
                    name = preset.name
                    endpoints.clear()
                    endpoints.add(EndpointDraft(
                        host = preset.endpoint.host,
                        port = preset.endpoint.port.toString(),
                        tls = preset.endpoint.tls,
                    ))
                    outBurst = preset.burst.toString()
                    outRefillMs = preset.refillMs.toString()
                })
                HorizontalDivider()
            }

            Field("Display name", name) { name = it }

            Text("Network defaults", style = MaterialTheme.typography.labelLarge)
            Text(
                "Address families to use when resolving hostnames. New endpoints " +
                "below inherit these. Disabling both is not allowed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = defaultIpv4,
                    onCheckedChange = { v -> if (v || defaultIpv6) defaultIpv4 = v },
                )
                Text("IPv4")
                Spacer(Modifier.width(16.dp))
                Checkbox(
                    checked = defaultIpv6,
                    onCheckedChange = { v -> if (v || defaultIpv4) defaultIpv6 = v },
                )
                Text("IPv6")
            }

            HorizontalDivider()
            Text("Servers (tried in order, cycled on failure)",
                 style = MaterialTheme.typography.labelLarge)
            endpoints.forEachIndexed { i, ep ->
                EndpointRow(
                    ep = ep,
                    canDelete = endpoints.size > 1,
                    defaultIpv4 = defaultIpv4,
                    defaultIpv6 = defaultIpv6,
                    onChange = { endpoints[i] = it },
                    onDelete = { endpoints.removeAt(i) },
                )
            }
            TextButton(onClick = {
                // New endpoints inherit (null = follow network defaults).
                endpoints.add(EndpointDraft("", "6697", true,
                    ipv4 = null, ipv6 = null))
            }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp)); Text("Add server")
            }

            HorizontalDivider()
            Text("Identity", style = MaterialTheme.typography.labelLarge)
            Text(
                if (nicksRequired)
                    "No global default nick is set in Settings, so this network " +
                        "needs its own. (Tip: set a global nick in Settings → " +
                        "Identity to share it across networks.)"
                else
                    "Leave blank to inherit the global defaults from Settings → " +
                        "Identity. Set a value here only when this network needs " +
                        "to differ (registered nick on a specific network, " +
                        "family-friendly real name on a work network, etc.).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            OutlinedTextField(
                value = nicksText,
                onValueChange = { nicksText = it },
                label = {
                    Text(if (nicksRequired) "Nicks" else "Nicks (override, optional)")
                },
                placeholder = {
                    if (globalDefaultNicksCsv.isNotBlank()) Text(globalDefaultNicksCsv)
                },
                singleLine = true,
                isError = firstInvalidNick != null,
                supportingText = {
                    val invalid = firstInvalidNick
                    if (invalid != null) {
                        Text("'${invalid.first}': ${invalid.second}")
                    } else {
                        Text(
                            "Comma- or space-separated. The first entry is the " +
                            "primary nick; the rest are tried in order if it's " +
                            "taken. After the list runs out, '_' is appended to " +
                            "the primary (and doubled, etc.) until something is free."
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = realName,
                onValueChange = { realName = it },
                label = { Text("Real name (override, optional)") },
                placeholder = {
                    if (globalDefaultRealName.isNotBlank())
                        Text(globalDefaultRealName)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = userName,
                onValueChange = { userName = it },
                label = { Text("Ident username (override, optional)") },
                placeholder = {
                    if (globalDefaultUserName.isNotBlank())
                        Text(globalDefaultUserName)
                },
                supportingText = {
                    Text("The USER field. Mobile clients can't run an ident " +
                        "server, so networks see this prefixed with '~'.")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = saslEnabled, onCheckedChange = { saslEnabled = it })
                Spacer(Modifier.width(8.dp)); Text("SASL PLAIN")
            }
            if (saslEnabled) {
                Field("SASL username", saslUser) { saslUser = it }
                Field("SASL password", saslPass, isPassword = true) { saslPass = it }
                Text(
                    "For Twitch and other OAuth-token networks, paste the full token " +
                    "(e.g. \"oauth:abc123…\") into the SASL password field.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            HorizontalDivider()
            Field("Server password (optional, for PASS-style auth)", serverPassword, isPassword = true) {
                serverPassword = it
            }
            Text(
                "Used by networks like Twitch IRC that expect an oauth token via PASS " +
                "instead of SASL. Most networks leave this blank.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            HorizontalDivider()
            OutlinedTextField(
                value = autoJoinText,
                onValueChange = { autoJoinText = it },
                label = { Text("Auto-join channels (one per line: \"#chan\" or \"#chan key\")") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )

            HorizontalDivider()
            Text(
                "Outbound rate limit",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "Burst = max messages sent instantly before throttling. " +
                "Refill = ms between additional tokens after burst is empty. " +
                "Defaults (8 / 400ms) work for Libera.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field("Burst", outBurst, Modifier.weight(1f)) {
                    outBurst = it.filter(Char::isDigit)
                }
                Spacer(Modifier.width(12.dp))
                Field("Refill (ms)", outRefillMs, Modifier.weight(1f)) {
                    outRefillMs = it.filter(Char::isDigit)
                }
            }

            HorizontalDivider()
            Text("Reconnect", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoReconnect, onCheckedChange = { autoReconnect = it })
                Spacer(Modifier.width(8.dp))
                Text("Auto-reconnect on connection loss")
            }
            if (autoReconnect) {
                Field("Max attempts (-1 = unlimited)", maxAttempts) {
                    maxAttempts = it.filter { c -> c.isDigit() || c == '-' }
                }
                Text(
                    "Backoff is exponential: 2s, 4s, 8s, 16s, 32s, then 60s cap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            HorizontalDivider()
            Text("Notify list", style = MaterialTheme.typography.labelLarge)
            Text(
                "Watch these nicks for online/offline transitions. Uses MONITOR if " +
                "the server supports it, otherwise polls with ISON every 60 seconds. " +
                "Status changes are logged to the server's status window.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            OutlinedTextField(
                value = notifyListText,
                onValueChange = { notifyListText = it },
                label = { Text("Nicks (space- or comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
            )
        }
    }
}

private data class EndpointDraft(
    val host: String,
    val port: String,
    val tls: Boolean,
    /** null = inherit the network's defaultIpv4. */
    val ipv4: Boolean? = null,
    /** null = inherit the network's defaultIpv6. */
    val ipv6: Boolean? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPicker(onPick: (NetworkPreset) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<NetworkPreset?>(null) }
    Column {
        Text("Preset (optional)", style = MaterialTheme.typography.labelLarge)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = selected?.name ?: "Custom",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                // "Custom" deselects the preset indicator without
                // touching the form fields the user has already typed
                // into. Picking it again later doesn't reset anything.
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Custom")
                            Text(
                                "Don't apply a preset; edit fields freely.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    },
                    onClick = {
                        selected = null
                        expanded = false
                    },
                )
                NetworkPresets.all.forEach { preset ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(preset.name)
                                Text(
                                    "${preset.endpoint.host}:${preset.endpoint.port}" +
                                        " · burst ${preset.burst} / ${preset.refillMs}ms" +
                                        if (preset.notes.isNotBlank()) " · ${preset.notes}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        },
                        onClick = {
                            selected = preset
                            onPick(preset)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EndpointRow(
    ep: EndpointDraft,
    canDelete: Boolean,
    defaultIpv4: Boolean,
    defaultIpv6: Boolean,
    onChange: (EndpointDraft) -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = ep.host,
                onValueChange = { onChange(ep.copy(host = it)) },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = ep.port,
                onValueChange = { v -> onChange(ep.copy(port = v.filter(Char::isDigit))) },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TLS", style = MaterialTheme.typography.labelSmall)
                Switch(checked = ep.tls, onCheckedChange = { tls ->
                    val newPort = when {
                        tls && ep.port == "6667" -> "6697"
                        !tls && ep.port == "6697" -> "6667"
                        else -> ep.port
                    }
                    onChange(ep.copy(tls = tls, port = newPort))
                })
            }
            IconButton(onClick = onDelete, enabled = canDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Remove endpoint")
            }
        }
        // Tri-state per-endpoint IPv4/IPv6: on / off / inherit-network-default.
        // The "inherit" state (Indeterminate) means changing the network
        // defaults later will propagate to this endpoint, which is the
        // sensible default for freshly added entries.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp),
        ) {
            TriStateCheckbox(
                state = toToggleableState(ep.ipv4),
                onClick = {
                    val nextV4 = cycleTriState(ep.ipv4)
                    val effV4 = nextV4 ?: defaultIpv4
                    val effV6 = ep.ipv6 ?: defaultIpv6
                    if (effV4 || effV6) onChange(ep.copy(ipv4 = nextV4))
                },
            )
            Text("IPv4", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(12.dp))
            TriStateCheckbox(
                state = toToggleableState(ep.ipv6),
                onClick = {
                    val nextV6 = cycleTriState(ep.ipv6)
                    val effV4 = ep.ipv4 ?: defaultIpv4
                    val effV6 = nextV6 ?: defaultIpv6
                    if (effV4 || effV6) onChange(ep.copy(ipv6 = nextV6))
                },
            )
            Text("IPv6", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                "Indeterminate = inherit network default",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** Boolean? → ToggleableState mapping. Null is "inherit" → Indeterminate. */
private fun toToggleableState(b: Boolean?): androidx.compose.ui.state.ToggleableState =
    when (b) {
        true -> androidx.compose.ui.state.ToggleableState.On
        false -> androidx.compose.ui.state.ToggleableState.Off
        null -> androidx.compose.ui.state.ToggleableState.Indeterminate
    }

/** Click cycle: inherit → on → off → inherit. */
private fun cycleTriState(b: Boolean?): Boolean? = when (b) {
    null -> true
    true -> false
    false -> null
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    onChange: (String) -> Unit,
) {
    // Per-field reveal state for password inputs. Local to the field
    // and not persisted across composition trees, so navigating away
    // and back hides the password again — matches the convention
    // every other Android form uses.
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isPassword && !revealed)
            PasswordVisualTransformation()
        else VisualTransformation.None,
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed)
                            Icons.Filled.VisibilityOff
                        else
                            Icons.Filled.Visibility,
                        contentDescription = if (revealed) "Hide password" else "Show password",
                    )
                }
            }
        } else null,
        modifier = modifier.fillMaxWidth(),
    )
}

private fun parseAutoJoin(text: String): List<AutoJoinChannel> =
    text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            val name = parts[0].let { if (it.startsWith("#") || it.startsWith("&")) it else "#$it" }
            val key = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            AutoJoinChannel(name = name, key = key)
        }
