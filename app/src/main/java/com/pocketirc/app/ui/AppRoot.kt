package com.pocketirc.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.pocketirc.app.model.TreeNode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@Composable
private fun ChannelTitle(
    buffer: com.pocketirc.app.irc.BufferStore.Buffer?,
    userCount: Int?,
) {
    val name = buffer?.name ?: "Pocket IRC"
    val header = if (userCount != null) "$name  ($userCount)" else name
    Text(
        header,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** Topic strip rendered below the TopAppBar so it can wrap to as many lines as needed. */
private val topicUrlRegex = Regex("""\bhttps?://[^\s<>"']+""")

private fun annotateTopic(topic: String): androidx.compose.ui.text.AnnotatedString {
    val linkStyle = androidx.compose.ui.text.TextLinkStyles(
        style = androidx.compose.ui.text.SpanStyle(
            color = androidx.compose.ui.graphics.Color(0xFF64B5F6),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        )
    )
    return androidx.compose.ui.text.buildAnnotatedString {
        var cursor = 0
        for (m in topicUrlRegex.findAll(topic)) {
            if (m.range.first > cursor) append(topic.substring(cursor, m.range.first))
            val url = topic.substring(m.range.first, m.range.last + 1)
            val start = length
            append(url)
            addLink(
                androidx.compose.ui.text.LinkAnnotation.Url(url = url, styles = linkStyle),
                start,
                length,
            )
            cursor = m.range.last + 1
        }
        if (cursor < topic.length) append(topic.substring(cursor))
    }
}

@Composable
private fun TopicStrip(topic: String?) {
    if (topic.isNullOrBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            annotateTopic(topic),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            softWrap = true,
        )
    }
}

private enum class BanAction { BAN, UNBAN, KICK_BAN, QUIET, UNQUIET }
private data class BanDialogRequest(
    val serverId: String,
    val channel: String,
    val nick: String,
    val action: BanAction,
)
private data class KickReasonRequest(
    val serverId: String,
    val channel: String,
    val nick: String,
)
private data class InviteRequest(
    val serverId: String,
    val nick: String,
    val defaultChannel: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: IrcViewModel = viewModel()) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        vm.bind(ctx)
        onDispose { vm.unbind(ctx) }
    }

    val tree by vm.tree.collectAsStateWithLifecycle()
    val buffers by vm.buffers.collectAsStateWithLifecycle()
    val selectedId by vm.selectedBufferId.collectAsStateWithLifecycle()
    val selectedBuffer = buffers[selectedId]

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val nickDrawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var showAddServer by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var editServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var joinDialogServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var serverMenuId by rememberSaveable { mutableStateOf<String?>(null) }
    // The channel buffer the user long-pressed in the network tree. Non-
    // null means the per-channel quick-actions sheet is open. Stored as
    // (serverId, bufferId, channelLabel) so the sheet's actions can route
    // to whichever vm method needs which key. Triple is Serializable so
    // rememberSaveable handles persistence across config changes.
    var bufferMenu by rememberSaveable {
        mutableStateOf<Triple<String, String, String>?>(null)
    }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nickListVersion by vm.nickListVersion.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    var searchActive by rememberSaveable { mutableStateOf(false) }

    val nicks = remember(selectedId, nickListVersion, nickDrawerState.isOpen) {
        val buf = buffers[selectedId]
        if (buf?.kind == TreeNode.Buffer.Kind.CHANNEL)
            vm.nicksFor(buf.serverId, buf.name) else emptyList()
    }

    // Predictive back: when the drawer is open, the back gesture closes it
    // instead of leaving the app.
    BackHandler(enabled = drawerState.isOpen || nickDrawerState.isOpen) {
        scope.launch {
            if (nickDrawerState.isOpen) nickDrawerState.close()
            if (drawerState.isOpen) drawerState.close()
        }
    }

    if (showAddServer) {
        PocketIrcTheme(themeMode = settings.themeMode, fontSizeSp = settings.fontSizeSp) {
            AddServerScreen(
                globalDefaultNicksCsv = settings.defaultNicksCsv,
                globalDefaultRealName = settings.defaultRealName,
                globalDefaultUserName = settings.defaultUserName,
                onCancel = { showAddServer = false },
                onSave = { cfg ->
                    vm.addServer(cfg)
                    showAddServer = false
                },
            )
        }
        return
    }

    val serversList by vm.servers.collectAsStateWithLifecycle()
    val customChannels by vm.customAlertChannels.collectAsStateWithLifecycle()
    val helpOpen by vm.helpDialogOpen.collectAsStateWithLifecycle()
    val helpRequest by vm.helpRequest.collectAsStateWithLifecycle()

    // Crash / error reporting wiring. Two paths feed the same dialog:
    //   1) caught in-process exceptions surfaced by ErrorReporter (immediate)
    //   2) the persisted file from the previous fatal crash, read on startup
    var pendingReport by remember {
        mutableStateOf<Pair<String, String>?>(null)
    }
    val reportCtx = LocalContext.current
    LaunchedEffect(Unit) {
        // Check for a pre-existing crash file from the last session.
        val saved = com.pocketirc.app.error.CrashFile.read(reportCtx)
        if (saved != null) {
            pendingReport = "Pocket IRC crashed last session" to saved
            com.pocketirc.app.error.CrashFile.clear(reportCtx)
        }
        // Subscribe to in-process error events.
        com.pocketirc.app.error.ErrorReporter.events.collect { ev ->
            pendingReport = ev.title to (ev.context + "\n\n" + ev.fullTrace())
        }
    }
    pendingReport?.let { (title, body) ->
        CrashReportDialog(
            title = title,
            fullText = body,
            onDismiss = { pendingReport = null },
        )
    }
    if (helpOpen) {
        HelpDialog(
            initialCommand = helpRequest,
            onDismiss = vm::closeHelp,
        )
    }
    if (showSettings) {
        PocketIrcTheme(themeMode = settings.themeMode, fontSizeSp = settings.fontSizeSp) {
            SettingsScreen(
                settings = settings,
                servers = serversList,
                onClose = { showSettings = false },
                onColorNicksChange = vm::setColorNicks,
                onThemeModeChange = vm::setThemeMode,
                onFontSizeChange = vm::setFontSize,
                onTimestampFormatChange = vm::setTimestampFormat,
                onMonospaceChange = vm::setUseMonospace,
                onSendOnEnterChange = vm::setSendOnEnter,
                onQuitMessageChange = vm::setDefaultQuitMessage,
                onMentionNotificationsChange = vm::setMentionNotifications,
                onPrivmsgNotificationsChange = vm::setPrivmsgNotifications,
                onNoticeNotificationsChange = vm::setNoticeNotifications,
                onNotificationReplyChange = vm::setNotificationReplyAction,
                onNotifyListNotificationsChange = vm::setNotifyListNotifications,
                onDefaultAlertChannelChange = vm::setDefaultAlertChannel,
                onReplayThresholdChange = vm::setReplayNotificationThreshold,
                customAlertChannels = customChannels,
                onAddCustomChannel = vm::addCustomAlertChannel,
                onRemoveCustomChannel = vm::removeCustomAlertChannel,
                onStartupScriptChange = vm::setStartupScript,
                onCtcpVersionChange = vm::setCtcpVersion,
                onCtcpTimeChange = vm::setCtcpTime,
                onCtcpFingerChange = vm::setCtcpFinger,
                onCtcpUserinfoChange = vm::setCtcpUserinfo,
                onCtcpSourceChange = vm::setCtcpSource,
                onDefaultNicksCsvChange = vm::setDefaultNicksCsv,
                onDefaultRealNameChange = vm::setDefaultRealName,
                onDefaultUserNameChange = vm::setDefaultUserName,
                onAutoAddJoinedChannelsChange = vm::setAutoAddJoinedChannels,
                onAddNetwork = { showSettings = false; showAddServer = true },
                onEditNetwork = { id ->
                    showSettings = false
                    editServerId = id
                },
                onRemoveNetwork = vm::removeServer,
            )
        }
        return
    }

    val channelListServerId by vm.channelListServerId.collectAsStateWithLifecycle()
    val channelLists by vm.channelLists.collectAsStateWithLifecycle()
    val channelInfoTarget by vm.channelInfoTarget.collectAsStateWithLifecycle()
    val channelInfoMap by vm.channelInfo.collectAsStateWithLifecycle()
    if (channelInfoTarget != null) {
        val (sid, ch) = channelInfoTarget!!
        val infoState = channelInfoMap["$sid::$ch"]
        val buf = buffers["$sid::$ch"]
        // Op detection: check the channel's nick list for our nick with @ or higher prefix.
        val ourNick = vm.ourNickFor(sid)
        val nickList = vm.nicksFor(sid, ch)
        val isOp = ourNick != null && nickList.any {
            it.dropWhile { c -> c in "@+%&~" }.equals(ourNick, ignoreCase = true) &&
                it.firstOrNull() in setOf('@', '&', '~')
        }
        PocketIrcTheme(themeMode = settings.themeMode, fontSizeSp = settings.fontSizeSp) {
            ChannelInfoScreen(
                channel = ch,
                state = infoState,
                isOp = isOp,
                initialTopic = buf?.topic ?: "",
                onClose = vm::closeChannelInfo,
                onRefresh = { vm.openChannelInfo(sid, ch) },
                onSetTopic = { vm.setChannelTopic(sid, ch, it) },
                onSetMode = { args -> vm.setChannelMode(sid, ch, args) },
                onAddMask = { mode, mask -> vm.setChannelMode(sid, ch, "+$mode $mask") },
                onRemoveMask = { mode, mask -> vm.setChannelMode(sid, ch, "-$mode $mask") },
                autoJoinEnabled = vm.isAutoJoined(sid, ch),
                onAutoJoinChange = { enabled -> vm.setChannelAutoJoin(sid, ch, enabled) },
            )
        }
        return
    }
    if (channelListServerId != null) {
        val sid = channelListServerId!!
        val state = channelLists[sid]
            ?: com.pocketirc.app.irc.ConnectionManager.ChannelListState(emptyList(), loading = true)
        val serverName = vm.serverById(sid)?.name ?: sid
        PocketIrcTheme(themeMode = settings.themeMode, fontSizeSp = settings.fontSizeSp) {
            ChannelListScreen(
                serverName = serverName,
                state = state,
                onClose = vm::closeChannelList,
                onJoin = { channel ->
                    vm.joinChannel(sid, channel)
                    vm.closeChannelList()
                },
            )
        }
        return
    }

    val editing = editServerId?.let { vm.serverById(it) }
    if (editing != null) {
        PocketIrcTheme(themeMode = settings.themeMode, fontSizeSp = settings.fontSizeSp) {
            AddServerScreen(
                initial = editing,
                globalDefaultNicksCsv = settings.defaultNicksCsv,
                globalDefaultRealName = settings.defaultRealName,
                globalDefaultUserName = settings.defaultUserName,
                onCancel = { editServerId = null },
                onSave = { cfg ->
                    vm.updateServer(cfg)
                    editServerId = null
                },
            )
        }
        return
    }

    joinDialogServerId?.let { serverId ->
        JoinChannelDialog(
            onCancel = { joinDialogServerId = null },
            onJoin = { ch, key ->
                vm.joinChannel(serverId, ch, key)
                joinDialogServerId = null
                scope.launch { drawerState.close() }
            },
        )
    }

    val nickMenuRequest by vm.nickMenu.collectAsStateWithLifecycle()
    var banDialog by remember { mutableStateOf<BanDialogRequest?>(null) }
    var kickReasonDialog by remember { mutableStateOf<KickReasonRequest?>(null) }
    var inviteDialog by remember { mutableStateOf<InviteRequest?>(null) }

    nickMenuRequest?.let { req ->
        NickActionSheet(
            nick = req.nick,
            channelContext = req.channelContext,
            onDismiss = vm::closeNickMenu,
            onOpenQuery = { vm.openQuery(req.serverId, req.nick) },
            onWhois = { vm.runWhoisOnNick(req.serverId, req.nick) },
            onWhowas = { vm.runWhowasOnNick(req.serverId, req.nick) },
            onInviteRequest = {
                inviteDialog = InviteRequest(req.serverId, req.nick, req.channelContext)
            },
            onCtcp = { tag -> vm.sendCtcp(req.serverId, req.nick, tag) },
            onMode = { modes ->
                val ch = req.channelContext ?: return@NickActionSheet
                vm.setMode(req.serverId, ch, modes, req.nick)
            },
            onKick = {
                val ch = req.channelContext ?: return@NickActionSheet
                vm.kickFrom(req.serverId, ch, req.nick)
            },
            onKickWithReasonRequest = {
                val ch = req.channelContext ?: return@NickActionSheet
                kickReasonDialog = KickReasonRequest(req.serverId, ch, req.nick)
            },
            onBanRequest = {
                val ch = req.channelContext ?: return@NickActionSheet
                banDialog = BanDialogRequest(req.serverId, ch, req.nick, BanAction.BAN)
            },
            onUnbanRequest = {
                val ch = req.channelContext ?: return@NickActionSheet
                banDialog = BanDialogRequest(req.serverId, ch, req.nick, BanAction.UNBAN)
            },
            onKickBanRequest = {
                val ch = req.channelContext ?: return@NickActionSheet
                banDialog = BanDialogRequest(req.serverId, ch, req.nick, BanAction.KICK_BAN)
            },
            onQuietRequest = {
                val ch = req.channelContext ?: return@NickActionSheet
                banDialog = BanDialogRequest(req.serverId, ch, req.nick, BanAction.QUIET)
            },
            onUnquietRequest = {
                val ch = req.channelContext ?: return@NickActionSheet
                banDialog = BanDialogRequest(req.serverId, ch, req.nick, BanAction.UNQUIET)
            },
        )
    }

    banDialog?.let { req ->
        val (title, confirm) = when (req.action) {
            BanAction.BAN -> "Ban hostmask" to "Ban"
            BanAction.UNBAN -> "Unban hostmask" to "Unban"
            BanAction.KICK_BAN -> "Kick + ban hostmask" to "Kick + ban"
            BanAction.QUIET -> "Quiet hostmask" to "Quiet"
            BanAction.UNQUIET -> "Unquiet hostmask" to "Unquiet"
        }
        BanHostmaskDialog(
            title = title,
            confirmLabel = confirm,
            nick = req.nick,
            onCancel = { banDialog = null },
            onConfirm = { mask ->
                when (req.action) {
                    BanAction.BAN -> vm.setMode(req.serverId, req.channel, "+b", mask)
                    BanAction.UNBAN -> vm.setMode(req.serverId, req.channel, "-b", mask)
                    BanAction.KICK_BAN -> {
                        vm.setMode(req.serverId, req.channel, "+b", mask)
                        vm.kickFrom(req.serverId, req.channel, req.nick)
                    }
                    BanAction.QUIET -> vm.setMode(req.serverId, req.channel, "+q", mask)
                    BanAction.UNQUIET -> vm.setMode(req.serverId, req.channel, "-q", mask)
                }
                banDialog = null
            },
        )
    }

    kickReasonDialog?.let { req ->
        KickReasonDialog(
            nick = req.nick,
            channel = req.channel,
            onCancel = { kickReasonDialog = null },
            onConfirm = { reason ->
                vm.kickFrom(req.serverId, req.channel, req.nick, reason.ifBlank { null })
                kickReasonDialog = null
            },
        )
    }

    inviteDialog?.let { req ->
        InviteToChannelDialog(
            nick = req.nick,
            defaultChannel = req.defaultChannel,
            onCancel = { inviteDialog = null },
            onConfirm = { channel ->
                vm.inviteToChannel(req.serverId, req.nick, channel)
                inviteDialog = null
            },
        )
    }

    bufferMenu?.let { (sid, bufId, channel) ->
        ModalBottomSheet(onDismissRequest = { bufferMenu = null }) {
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(
                    channel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                )

                // Auto-join switch — local state mirrors the manager's
                // flag at sheet open time so toggling is responsive
                // without making ConnectionManager observable.
                var autoJoin by remember(sid, channel) {
                    mutableStateOf(vm.isAutoJoined(sid, channel))
                }
                ListItem(
                    headlineContent = { Text("Auto-join on connect") },
                    supportingContent = {
                        Text("Add to this network's autojoin list.")
                    },
                    leadingContent = {
                        Icon(Icons.Default.Bookmark, null)
                    },
                    trailingContent = {
                        Switch(
                            checked = autoJoin,
                            onCheckedChange = {
                                autoJoin = it
                                vm.setChannelAutoJoin(sid, channel, it)
                            },
                        )
                    },
                    modifier = Modifier.clickable {
                        val next = !autoJoin
                        autoJoin = next
                        vm.setChannelAutoJoin(sid, channel, next)
                    },
                )

                ListItem(
                    headlineContent = { Text("Channel info") },
                    leadingContent = { Icon(Icons.Default.Info, null) },
                    modifier = Modifier.clickable {
                        bufferMenu = null
                        vm.openChannelInfo(sid, channel)
                    },
                )

                ListItem(
                    headlineContent = { Text("Find in backscroll") },
                    leadingContent = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.clickable {
                        bufferMenu = null
                        vm.selectBuffer(bufId)
                        scope.launch { drawerState.close() }
                        searchActive = true
                    },
                )

                ListItem(
                    headlineContent = { Text("Part") },
                    leadingContent = { Icon(Icons.Default.ExitToApp, null) },
                    colors = ListItemDefaults.colors(
                        headlineColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.clickable {
                        bufferMenu = null
                        vm.leaveBuffer(bufId)
                    },
                )
            }
        }
    }

    serverMenuId?.let { serverId ->
        ModalBottomSheet(onDismissRequest = { serverMenuId = null }) {
            Column(Modifier.padding(bottom = 8.dp)) {
                ListItem(
                    headlineContent = { Text("Edit") },
                    leadingContent = {
                        Icon(Icons.Default.Edit, null)
                    },
                    modifier = Modifier.clickable {
                        editServerId = serverId
                        serverMenuId = null
                        scope.launch { drawerState.close() }
                    },
                )
                ListItem(
                    headlineContent = { Text("Disconnect") },
                    leadingContent = {
                        Icon(Icons.Default.CloudOff, null)
                    },
                    modifier = Modifier.clickable {
                        vm.disconnectServer(serverId)
                        serverMenuId = null
                    },
                )
                ListItem(
                    headlineContent = { Text("Remove") },
                    leadingContent = {
                        Icon(Icons.Default.Delete, null)
                    },
                    colors = ListItemDefaults.colors(
                        headlineColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.clickable {
                        vm.removeServer(serverId)
                        serverMenuId = null
                    },
                )
            }
        }
    }

    // ----- WIDE LAYOUT (tablet / landscape) -----
    val isWide = LocalConfiguration.current.screenWidthDp >= 720
    if (isWide) {
        PocketIrcTheme(themeMode = settings.themeMode, fontSizeSp = settings.fontSizeSp) {
            val ctxForExport = LocalContext.current
            var topicExpanded by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxSize()) {
                // Left sidebar: persistent network tree
                Surface(
                    modifier = Modifier.width(300.dp).fillMaxHeight(),
                    tonalElevation = 1.dp,
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text("Networks", style = MaterialTheme.typography.titleMedium,
                                 modifier = Modifier.weight(1f))
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, "Settings")
                            }
                            IconButton(onClick = { showAddServer = true }) {
                                Icon(Icons.Default.Add, "Add server")
                            }
                        }
                        HorizontalDivider()
                        NetworkTree(
                            nodes = tree,
                            selectedBufferId = selectedId,
                            onBufferClick = vm::selectBuffer,
                            onJoinClick = { joinDialogServerId = it },
                            onServerLongPress = { serverMenuId = it },
                            onBufferLongPress = { buf ->
                                if (buf.kind == com.pocketirc.app.model.TreeNode.Buffer.Kind.CHANNEL) {
                                    bufferMenu = Triple(buf.serverId, buf.id, buf.label)
                                }
                            },
                            onLeaveBuffer = { vm.leaveBuffer(it) },
                        )
                    }
                }
                VerticalDivider()
                // Center: buffer view with its own scaffold
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Scaffold(
                        // The inner Column uses Modifier.imePadding() to lift the
                        // input bar above the keyboard. Don't double-apply that here.
                        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                        topBar = {
                            Column {
                            TopAppBar(
                                title = {
                                    if (searchActive) {
                                        val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                                        LaunchedEffect(Unit) { searchFocus.requestFocus() }
                                        OutlinedTextField(
                                            value = searchQuery,
                                            onValueChange = vm::setSearchQuery,
                                            placeholder = { Text("Search this buffer…") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                                        )
                                    } else {
                                        ChannelTitle(
                                            buffer = selectedBuffer,
                                            userCount = if (selectedBuffer?.kind == TreeNode.Buffer.Kind.CHANNEL)
                                                nicks.size else null,
                                        )
                                    }
                                },
                                actions = {
                                    if (searchActive) {
                                        IconButton(onClick = {
                                            searchActive = false
                                            vm.setSearchQuery("")
                                        }) { Icon(Icons.Default.Clear, "Close search") }
                                    } else {
                                        if (selectedBuffer?.kind == TreeNode.Buffer.Kind.CHANNEL) {
                                            IconButton(onClick = {
                                                vm.openChannelInfo(selectedBuffer.serverId, selectedBuffer.name)
                                            }) {
                                                Icon(Icons.Default.Tune, "Channel info")
                                            }
                                        }
                                        if (selectedBuffer != null) {
                                            IconButton(onClick = { searchActive = true }) {
                                                Icon(Icons.Default.Search, "Search buffer")
                                            }
                                            IconButton(onClick = {
                                                selectedBuffer.let { BufferExport.share(ctxForExport, it) }
                                            }) {
                                                Icon(Icons.Default.Share, "Export buffer")
                                            }
                                        }
                                        IconButton(onClick = { vm.openHelp() }) {
                                            Icon(Icons.AutoMirrored.Filled.Help, "Help")
                                        }
                                        IconButton(onClick = { showSettings = true }) {
                                            Icon(Icons.Default.Settings, "Settings")
                                        }
                                    }
                                },
                            )
                            TopicStrip(selectedBuffer?.topic)
                            }
                        },
                    ) { padding ->
                        Box(
                            Modifier
                                .padding(top = padding.calculateTopPadding())
                                .fillMaxSize()
                        ) {
                            val pendingInsert by vm.inputInsert.collectAsStateWithLifecycle()
                            BufferView(
                                buffer = selectedBuffer,
                                ourNick = selectedBuffer?.serverId?.let { vm.ourNickFor(it) },
                                colorNicks = settings.colorNicks,
                                timestampFormat = settings.timestampFormat,
                                useMonospace = settings.useMonospace,
                                sendOnEnter = settings.sendOnEnter,
                                searchQuery = searchQuery,
                                channelNicks = nicks,
                                pendingInsert = pendingInsert,
                                onInsertConsumed = vm::consumeInputInsert,
                                onSenderClick = { vm.requestInputInsert(it) },
                                onSenderDoubleClick = { nick ->
                                    selectedBuffer?.let { vm.openQuery(it.serverId, nick) }
                                },
                                onSenderLongClick = { nick ->
                                    selectedBuffer?.let {
                                        val chCtx = if (it.kind == TreeNode.Buffer.Kind.CHANNEL) it.name else null
                                        vm.openNickMenu(it.serverId, nick, chCtx)
                                    }
                                },
                                onSend = vm::submitInput,
                                onTyping = vm::sendTyping,
                            )
                        }
                    }
                }
                // Right sidebar: persistent nick list (channels only)
                if (selectedBuffer?.kind == TreeNode.Buffer.Kind.CHANNEL) {
                    VerticalDivider()
                    Surface(
                        modifier = Modifier.width(260.dp).fillMaxHeight(),
                        tonalElevation = 1.dp,
                    ) {
                        NickListPanel(
                            nicks = nicks,
                            colorNicks = settings.colorNicks,
                            onNickClick = { vm.requestInputInsert(it) },
                            onNickDoubleClick = { nick ->
                                selectedBuffer?.let { vm.openQuery(it.serverId, nick) }
                            },
                            onNickLongClick = { nick ->
                                selectedBuffer?.let {
                                    val chCtx = if (it.kind == TreeNode.Buffer.Kind.CHANNEL) it.name else null
                                    vm.openNickMenu(it.serverId, nick, chCtx)
                                }
                            },
                        )
                    }
                }
            }
        }
        return
    }

    // ----- NARROW LAYOUT (phone / portrait) -----
    ModalNavigationDrawer(
        modifier = Modifier.fillMaxSize(),
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text("Networks", style = MaterialTheme.typography.titleMedium,
                         modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        scope.launch { drawerState.close() }
                        showSettings = true
                    }) { Icon(Icons.Default.Settings, "Settings") }
                    IconButton(onClick = {
                        scope.launch { drawerState.close() }
                        showAddServer = true
                    }) { Icon(Icons.Default.Add, "Add server") }
                }
                HorizontalDivider()
                NetworkTree(
                    nodes = tree,
                    selectedBufferId = selectedId,
                    onBufferClick = {
                        vm.selectBuffer(it)
                        scope.launch { drawerState.close() }
                    },
                    onJoinClick = { joinDialogServerId = it },
                    onServerLongPress = { serverMenuId = it },
                    onBufferLongPress = { buf ->
                        if (buf.kind == com.pocketirc.app.model.TreeNode.Buffer.Kind.CHANNEL) {
                            bufferMenu = Triple(buf.serverId, buf.id, buf.label)
                        }
                    },
                    onLeaveBuffer = { vm.leaveBuffer(it) },
                )
            }
        },
    ) {
      PocketIrcTheme(themeMode = settings.themeMode, fontSizeSp = settings.fontSizeSp) {
      // Right-side nick list drawer: implemented by flipping LayoutDirection
      // for the wrapping ModalNavigationDrawer so its drawer slides from the
      // right instead of the left, then flipping it back inside.
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            modifier = Modifier.fillMaxSize(),
            drawerState = nickDrawerState,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ModalDrawerSheet {
                        NickListPanel(
                            nicks = nicks,
                            colorNicks = settings.colorNicks,
                            onNickClick = { nick ->
                                vm.requestInputInsert(nick)
                                scope.launch { nickDrawerState.close() }
                            },
                            onNickDoubleClick = { nick ->
                                selectedBuffer?.let { vm.openQuery(it.serverId, nick) }
                                scope.launch { nickDrawerState.close() }
                            },
                            onNickLongClick = { nick ->
                                selectedBuffer?.let {
                                    val chCtx = if (it.kind == TreeNode.Buffer.Kind.CHANNEL) it.name else null
                                    vm.openNickMenu(it.serverId, nick, chCtx)
                                }
                            },
                        )
                    }
                }
            },
        ) {
          CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.fillMaxSize()) {
            var topicExpanded by remember { mutableStateOf(false) }
            val ctxForExport = LocalContext.current
            Scaffold(
                topBar = {
                    Column {
                    TopAppBar(
                        title = {
                            if (searchActive) {
                                val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                                LaunchedEffect(Unit) { searchFocus.requestFocus() }
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = vm::setSearchQuery,
                                    placeholder = { Text("Search this buffer…") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(searchFocus),
                                )
                            } else {
                                ChannelTitle(
                                    buffer = selectedBuffer,
                                    userCount = if (selectedBuffer?.kind == TreeNode.Buffer.Kind.CHANNEL)
                                        nicks.size else null,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Open networks")
                            }
                        },
                        actions = {
                            if (searchActive) {
                                IconButton(onClick = {
                                    searchActive = false
                                    vm.setSearchQuery("")
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Close search")
                                }
                            } else {
                                if (selectedBuffer?.kind == TreeNode.Buffer.Kind.CHANNEL) {
                                    IconButton(onClick = {
                                        vm.openChannelInfo(selectedBuffer.serverId, selectedBuffer.name)
                                    }) {
                                        Icon(Icons.Default.Tune, contentDescription = "Channel info")
                                    }
                                }
                                if (selectedBuffer != null) {
                                    IconButton(onClick = { searchActive = true }) {
                                        Icon(Icons.Default.Search, contentDescription = "Search buffer")
                                    }
                                    IconButton(onClick = {
                                        selectedBuffer.let { BufferExport.share(ctxForExport, it) }
                                    }) {
                                        Icon(Icons.Default.Share, contentDescription = "Export buffer")
                                    }
                                }
                                if (selectedBuffer?.kind == TreeNode.Buffer.Kind.CHANNEL) {
                                    IconButton(onClick = { scope.launch { nickDrawerState.open() } }) {
                                        Icon(Icons.Default.Person, contentDescription = "Users")
                                    }
                                }
                                IconButton(onClick = { vm.openHelp() }) {
                                    Icon(Icons.AutoMirrored.Filled.Help, contentDescription = "Help")
                                }
                                IconButton(onClick = { showSettings = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                                }
                            }
                        },
                    )
                    TopicStrip(selectedBuffer?.topic)
                    }
                },
            ) { padding ->
                Box(
                    Modifier
                        .padding(top = padding.calculateTopPadding())
                        .fillMaxSize()
                ) {
                    val pendingInsert by vm.inputInsert.collectAsStateWithLifecycle()
                    BufferView(
                        buffer = selectedBuffer,
                        ourNick = selectedBuffer?.serverId?.let { vm.ourNickFor(it) },
                        colorNicks = settings.colorNicks,
                        timestampFormat = settings.timestampFormat,
                        useMonospace = settings.useMonospace,
                        sendOnEnter = settings.sendOnEnter,
                        searchQuery = searchQuery,
                        channelNicks = nicks,
                        pendingInsert = pendingInsert,
                        onInsertConsumed = vm::consumeInputInsert,
                        onSenderClick = { vm.requestInputInsert(it) },
                        onSend = vm::submitInput,
                        onTyping = vm::sendTyping,
                    )
                }
            }
            // Sits flush at the window's left edge (outside Scaffold padding) so
            // Android actually honors the gesture-exclusion rect. Capped at 200dp
            // (the OS-imposed limit) — anything larger would be clipped anyway.
            Box(
                Modifier
                    .align(androidx.compose.ui.Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(48.dp)
                    .systemGestureExclusion()
            )

            // Visible drag handle pill — gives the user a clearly in-app target
            // that the system back-gesture won't steal. Tap to open, or drag
            // right to peek/open the drawer.
            DrawerHandle(
                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart),
                onOpen = { scope.launch { drawerState.open() } },
            )
            }
          }
        }
      }
      }
    }
}

@Composable
private fun DrawerHandle(
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    var dragX by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .padding(start = 4.dp)
            // Tall handle: spans most of the screen height so even if the IME
            // floating contextual toolbar covers part of it, there's still
            // tappable area above and below.
            .fillMaxHeight(0.6f)
            .width(14.dp)
            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
            .background(Color(0x66888888))
            .clickable(onClick = onOpen)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragX = 0f },
                    onDragEnd = {
                        if (dragX > 24f) onOpen()
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        dragX += dragAmount
                        if (dragX > 24f) {
                            onOpen()
                            dragX = -10_000f // latch so we don't fire repeatedly
                        }
                        change.consume()
                    },
                )
            }
    )
}
