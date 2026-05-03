package com.pocketirc.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketirc.app.data.AppSettings
import com.pocketirc.app.data.ServerRepository
import com.pocketirc.app.data.SettingsRepository
import com.pocketirc.app.data.ThemeMode
import com.pocketirc.app.ui.BufferExport
import com.pocketirc.app.irc.BufferStore
import com.pocketirc.app.irc.ParsedInput
import com.pocketirc.app.irc.SlashCommand
import com.pocketirc.app.model.ServerConfig
import com.pocketirc.app.model.TreeNode
import com.pocketirc.app.model.TypingState
import com.pocketirc.app.service.IrcService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import com.pocketirc.app.irc.IrcConnection
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class IrcViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServerRepository(app)
    private val settingsRepo = SettingsRepository(app)

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun setColorNicks(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setColorNicks(enabled) }
    }
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    }
    fun setFontSize(sp: Int) {
        viewModelScope.launch { settingsRepo.setFontSize(sp) }
    }
    fun setTimestampFormat(fmt: String) {
        viewModelScope.launch { settingsRepo.setTimestampFormat(fmt) }
    }
    fun setUseMonospace(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setUseMonospace(enabled) }
    }
    fun setDefaultQuitMessage(msg: String) {
        viewModelScope.launch { settingsRepo.setDefaultQuitMessage(msg) }
    }
    fun setMentionNotifications(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setMentionNotifications(enabled) }
    }
    fun setNotificationReplyAction(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setNotificationReplyAction(enabled) }
    }
    fun setNotifyListNotifications(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setNotifyListNotifications(enabled) }
    }
    fun setPrivmsgNotifications(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setPrivmsgNotifications(enabled) }
    }
    fun setNoticeNotifications(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setNoticeNotifications(enabled) }
    }
    fun setSendOnEnter(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setSendOnEnter(enabled) }
    }
    fun setCtcpVersion(s: String) { viewModelScope.launch { settingsRepo.setCtcpVersion(s) } }
    fun setCtcpTime(s: String) { viewModelScope.launch { settingsRepo.setCtcpTime(s) } }
    fun setCtcpFinger(s: String) { viewModelScope.launch { settingsRepo.setCtcpFinger(s) } }
    fun setCtcpUserinfo(s: String) { viewModelScope.launch { settingsRepo.setCtcpUserinfo(s) } }
    fun setCtcpSource(s: String) { viewModelScope.launch { settingsRepo.setCtcpSource(s) } }
    fun setDefaultNicksCsv(s: String) { viewModelScope.launch { settingsRepo.setDefaultNicksCsv(s) } }
    fun setDefaultRealName(s: String) { viewModelScope.launch { settingsRepo.setDefaultRealName(s) } }
    fun setDefaultUserName(s: String) { viewModelScope.launch { settingsRepo.setDefaultUserName(s) } }

    /**
     * True if [channel] is currently in [serverId]'s autojoin list. Used by
     * the per-channel "Auto-join on connect" toggle in the channel info
     * screen and the network-tree long-press menu.
     */
    fun isAutoJoined(serverId: String, channel: String): Boolean =
        _service.value?.manager?.isAutoJoined(serverId, channel) ?: false

    /**
     * Manually toggle a single channel's autojoin membership. Independent
     * of the global auto-add setting — this is the per-channel knob users
     * reach for when they want one specific channel to come back without
     * opting into the all-or-nothing global behavior.
     */
    fun setChannelAutoJoin(serverId: String, channel: String, enabled: Boolean) {
        _service.value?.manager?.setAutoJoinEntry(
            serverId = serverId,
            channel = channel,
            key = null,
            enabled = enabled,
        )
    }

    /**
     * Toggle the global "auto-add joined channels to autojoin" setting.
     * When flipping from off → on, we *retroactively* add every currently
     * joined channel on every persisted network to its autojoin list, so
     * the user's intent ("I want my current set of channels to come back
     * next launch") is honored without making them /cycle each one. Keys
     * are picked up from the per-connection remembered-key map populated
     * by [com.pocketirc.app.irc.IrcConnection.joinChannel], so any keyed
     * channel the user has /joined this session retains its key in the
     * autojoin entry.
     */
    fun setAutoAddJoinedChannels(enabled: Boolean) {
        viewModelScope.launch {
            val wasOn = settings.value.autoAddJoinedChannels
            settingsRepo.setAutoAddJoinedChannels(enabled)
            if (enabled && !wasOn) {
                val mgr = _service.value?.manager ?: return@launch
                val persistedIds = servers.value.map { it.id }.toSet()
                for (id in persistedIds) {
                    val joined = mgr.joinedChannelsFor(id)
                    for (ch in joined) {
                        mgr.setAutoJoinEntry(id, ch, key = null, enabled = true)
                    }
                }
            }
        }
    }

    data class NickMenuRequest(val serverId: String, val nick: String, val channelContext: String?)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery
    fun setSearchQuery(q: String) { _searchQuery.value = q }

    /** Set to a serverId to open the channel-list browser screen for that server. */
    private val _channelListServerId = MutableStateFlow<String?>(null)
    val channelListServerId: StateFlow<String?> = _channelListServerId
    fun openChannelList(serverId: String) { _channelListServerId.value = serverId }
    fun closeChannelList() { _channelListServerId.value = null }

    private val _service = MutableStateFlow<IrcService?>(null)
    val service: StateFlow<IrcService?> = _service

    val servers: StateFlow<List<ServerConfig>> = repo.servers
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val buffers: StateFlow<Map<String, BufferStore.Buffer>> =
        _service.flatMapLatest { svc ->
            svc?.manager?.store?.buffers ?: flowOf(emptyMap())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val connections: StateFlow<Map<String, IrcConnection>> =
        _service.flatMapLatest { svc ->
            svc?.manager?.connections ?: flowOf(emptyMap())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val channelLists: StateFlow<Map<String, com.pocketirc.app.irc.ConnectionManager.ChannelListState>> =
        _service.flatMapLatest { svc ->
            svc?.manager?.channelLists ?: flowOf(emptyMap())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val channelInfo: StateFlow<Map<String, com.pocketirc.app.irc.ChannelInfoState>> =
        _service.flatMapLatest { svc ->
            svc?.manager?.channelInfo ?: flowOf(emptyMap())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _channelInfoTarget = MutableStateFlow<Pair<String, String>?>(null)
    val channelInfoTarget: StateFlow<Pair<String, String>?> = _channelInfoTarget

    fun openChannelInfo(serverId: String, channel: String) {
        _service.value?.manager?.requestChannelInfo(serverId, channel)
        _channelInfoTarget.value = serverId to channel
    }

    fun closeChannelInfo() { _channelInfoTarget.value = null }

    fun setChannelMode(serverId: String, channel: String, args: String) {
        _service.value?.manager?.mode(serverId, channel, args)
    }

    fun addBanMask(serverId: String, channel: String, mask: String) =
        setChannelMode(serverId, channel, "+b $mask")
    fun removeBanMask(serverId: String, channel: String, mask: String) =
        setChannelMode(serverId, channel, "-b $mask")
    fun setChannelTopic(serverId: String, channel: String, topic: String) {
        _service.value?.manager?.setTopic(serverId, channel, topic)
    }

    /**
     * Per-server transient state from the live ConnectionManager (connected
     * set + announced network names). Combined here into a single Pair so
     * the [tree] flow stays at 4 combine arguments — Kotlin 2.0.21 has known
     * type-inference issues with 5-argument combine() that can crash the
     * compiler with no error message.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val managerState: kotlinx.coroutines.flow.Flow<Pair<Set<String>, Map<String, String>>> =
        _service.flatMapLatest { svc ->
            if (svc == null) flowOf(emptySet<String>() to emptyMap<String, String>())
            else combine(svc.manager.connectedIds, svc.manager.networkNames) { c, n -> c to n }
        }

    val tree: StateFlow<List<TreeNode.Server>> =
        combine(servers, buffers, connections, managerState) { servs, bufs, conns, mgrState ->
            val connectedIds = mgrState.first
            val networkNames = mgrState.second

            // Saved servers first (in saved order), then any in-memory stubs
            // that don't yet have a persisted entry.
            val savedIds = servs.map { it.id }.toSet()
            val ephemeral = conns.values
                .map { it.config }
                .filter { it.id !in savedIds }
            val all = servs + ephemeral

            // Resolve display label for each entry using the three-level
            // fallback (explicit user name → ISUPPORT NETWORK= → host).
            // Then suffix duplicates with " (2)", " (3)", etc.
            val rawLabels = all.map { s ->
                val label = if (s.displayNameLocked) s.name
                            else networkNames[s.id] ?: s.name
                s.id to label
            }
            val labelCounts = mutableMapOf<String, Int>()
            val resolvedLabels = rawLabels.associate { (id, label) ->
                val n = (labelCounts[label] ?: 0) + 1
                labelCounts[label] = n
                id to if (n == 1) label else "$label ($n)"
            }

            all.map { s ->
                val children = bufs.values
                    .filter { it.serverId == s.id }
                    .sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
                    .map {
                        TreeNode.Buffer(
                            id = it.id,
                            serverId = it.serverId,
                            label = it.name,
                            kind = it.kind,
                            unread = it.unread,
                            highlighted = it.highlighted,
                        )
                    }
                TreeNode.Server(
                    id = s.id,
                    label = resolvedLabels[s.id] ?: s.name,
                    connected = s.id in connectedIds,
                    saved = s.id in savedIds,
                    children = children,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedBufferId = MutableStateFlow<String?>(null)
    val selectedBufferId: StateFlow<String?> = _selectedBufferId

    /**
     * One-shot text insertion request from external UI (e.g. tap a nick in the
     * userlist drawer). [BufferView]'s input bar observes this and consumes it.
     */
    private val _inputInsert = MutableStateFlow<String?>(null)
    val inputInsert: StateFlow<String?> = _inputInsert

    fun requestInputInsert(text: String) { _inputInsert.value = text }
    fun consumeInputInsert() { _inputInsert.value = null }

    fun selectBuffer(id: String) {
        val store = _service.value?.manager?.store ?: run {
            _selectedBufferId.value = id; return
        }
        // Clear the divider on whatever buffer we're leaving.
        _selectedBufferId.value?.let { store.clearUnreadMarker(it) }
        _selectedBufferId.value = id
        store.activeBufferId = id
        store.markRead(id)
        // Hydrate persisted history on first activation of this buffer.
        val parts = id.split("::", limit = 2)
        if (parts.size == 2) {
            viewModelScope.launch { store.hydrate(parts[0], parts[1]) }
            // If this is a channel, request a fresh NAMES so the nick list
            // panel reflects current membership even when KICL's tracked state
            // hasn't been updated since join.
            val target = parts[1]
            if (target.startsWith("#") || target.startsWith("&")) {
                _service.value?.manager?.let { mgr ->
                    runCatching { mgr.sendRaw(parts[0], "NAMES $target") }
                }
            }
        }
    }

    fun addServer(config: ServerConfig) {
        viewModelScope.launch {
            repo.upsert(config, servers.value)
            _service.value?.manager?.add(config)
        }
    }

    /**
     * Save edits to a server config. If the server is currently dialed (i.e.
     * the user has actually been connected to it at any point this session),
     * the live connection is dropped and re-dialed with the new config. If
     * the server is just a stub (`/server -n` style — registered in the tree
     * but never dialed), the stub is replaced with a fresh stub on the new
     * config so the user's "I haven't dialed yet" intent is preserved.
     *
     * Note: the remove+add cycle implicitly clears any
     * [com.pocketirc.app.irc.IrcConnection.permanentFailureReason] flag,
     * because mgr.add() creates a brand-new IrcConnection whose flag starts
     * as null. So editing credentials after a SASL/PASSWD/K-line failure
     * automatically re-arms auto-reconnect.
     */
    fun updateServer(config: ServerConfig) {
        viewModelScope.launch {
            repo.upsert(config, servers.value)
            _service.value?.manager?.let { mgr ->
                val wasDialed = mgr.isDialed(config.id)
                mgr.remove(config.id)
                if (wasDialed) mgr.add(config) else mgr.register(config)
            }
        }
    }

    /**
     * Look up a server config by id, checking both saved configs and live
     * (possibly unsaved/stub) connections. The saved list takes precedence.
     */
    fun serverById(id: String): ServerConfig? =
        servers.value.firstOrNull { it.id == id }
            ?: _service.value?.manager?.configFor(id)

    fun joinChannel(serverId: String, channel: String, key: String? = null) {
        val name = channel.trim().let { if (it.startsWith("#") || it.startsWith("&")) it else "#$it" }
        _service.value?.manager?.joinChannel(serverId, name, key?.takeIf { it.isNotBlank() })
    }

    fun ourNickFor(serverId: String): String? {
        val cfg = servers.value.firstOrNull { it.id == serverId } ?: return null
        // Resolve through the manager so callers see the effective nick
        // (per-network override → global default), not the raw override.
        return _service.value?.manager?.resolveIdentity(cfg)?.nick
    }

    fun nicksFor(serverId: String, channel: String): List<String> =
        _service.value?.manager?.nicksIn(serverId, channel)?.sorted() ?: emptyList()

    /** Open (and select) a query window with [nick] on [serverId]. Idempotent. */
    fun openQuery(serverId: String, nick: String) {
        val mgr = _service.value?.manager ?: return
        mgr.store.openBuffer(serverId, nick, TreeNode.Buffer.Kind.QUERY)
        selectBuffer("$serverId::$nick")
    }

    /** Currently-open nick action menu, if any. */
    private val _nickMenu = MutableStateFlow<NickMenuRequest?>(null)
    val nickMenu: StateFlow<NickMenuRequest?> = _nickMenu
    fun openNickMenu(serverId: String, nick: String, channelContext: String?) {
        _nickMenu.value = NickMenuRequest(serverId, nick, channelContext)
    }
    fun closeNickMenu() { _nickMenu.value = null }

    fun runWhoisOnNick(serverId: String, nick: String) {
        val mgr = _service.value?.manager ?: return
        val originBuf = _selectedBufferId.value ?: "$serverId::(status)"
        mgr.sendWhois(serverId, nick, originBuf)
    }

    fun runWhowasOnNick(serverId: String, nick: String) {
        val mgr = _service.value?.manager ?: return
        val originBuf = _selectedBufferId.value ?: "$serverId::(status)"
        mgr.sendWhowas(serverId, nick, originBuf)
    }

    fun inviteToChannel(serverId: String, nick: String, channel: String) {
        val mgr = _service.value?.manager ?: return
        val originBuf = _selectedBufferId.value ?: "$serverId::(status)"
        mgr.invite(serverId, nick, channel, originBufferId = originBuf)
    }

    fun sendCtcp(serverId: String, nick: String, tag: String) {
        _service.value?.manager?.sendCtcp(serverId, nick, tag, null)
    }

    fun setMode(serverId: String, channel: String, modes: String, target: String) {
        _service.value?.manager?.mode(serverId, channel, "$modes $target")
    }

    fun kickFrom(serverId: String, channel: String, nick: String, reason: String? = null) {
        _service.value?.manager?.kick(serverId, channel, nick, reason)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val nickListVersion: StateFlow<Long> =
        _service.flatMapLatest { svc ->
            svc?.manager?.nickListVersion ?: flowOf(0L)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun leaveCurrentBuffer(reason: String? = null) {
        val bufId = _selectedBufferId.value ?: return
        leaveBuffer(bufId, reason)
    }

    fun leaveBuffer(bufferId: String, reason: String? = null) {
        val buf = buffers.value[bufferId] ?: return
        val mgr = _service.value?.manager ?: return
        when (buf.kind) {
            TreeNode.Buffer.Kind.CHANNEL -> {
                mgr.partChannel(buf.serverId, buf.name, reason)
                mgr.store.removeBuffer(buf.id)  // optimistic; server PART echo would no-op
            }
            TreeNode.Buffer.Kind.QUERY -> mgr.store.removeBuffer(buf.id)
            TreeNode.Buffer.Kind.STATUS -> { /* no-op: don't close status this way */ }
        }
        if (_selectedBufferId.value == bufferId) _selectedBufferId.value = null
    }

    fun disconnectServer(serverId: String) {
        _service.value?.manager?.remove(serverId, settings.value.defaultQuitMessage)
    }

    fun removeServer(serverId: String) {
        viewModelScope.launch {
            _service.value?.manager?.let { mgr ->
                mgr.remove(serverId)
                mgr.store.allBufferIdsForServer(serverId).forEach { mgr.store.removeBuffer(it) }
            }
            repo.remove(serverId, servers.value)
            // Drop persisted history for the removed server.
            _service.value?.let {
                com.pocketirc.app.data.db.PocketIrcDatabase
                    .get(getApplication())
                    .chatLines()
                    .deleteServer(serverId)
            }
        }
    }

    /** Top-level entry point from the input bar. Parses slash commands. */
    fun submitInput(text: String) {
        try { submitInputInner(text) } catch (t: Throwable) {
            com.pocketirc.app.error.ErrorReporter.report(t, "submitInput($text)")
        }
    }

    private fun submitInputInner(text: String) {
        val bufId = _selectedBufferId.value ?: return
        val buf = buffers.value[bufId] ?: return
        val mgr = _service.value?.manager ?: return
        when (val p = SlashCommand.parse(text)) {
            is ParsedInput.Message -> mgr.sendMessage(buf.serverId, buf.name, p.text)
            is ParsedInput.Action -> mgr.sendAction(buf.serverId, buf.name, p.text)
            is ParsedInput.Join -> mgr.joinChannel(buf.serverId, p.channel)
            is ParsedInput.Msg -> mgr.sendMessage(buf.serverId, p.target, p.text)
            is ParsedInput.Part -> {
                val target = p.channel ?: buf.name.takeIf {
                    buf.kind == TreeNode.Buffer.Kind.CHANNEL
                }
                if (target != null) mgr.partChannel(buf.serverId, target, p.reason)
                else mgr.store.appendSystemTo(buf.serverId, bufId, "/part: not in a channel")
            }
            is ParsedInput.Nick -> mgr.changeNick(buf.serverId, p.nick)
            is ParsedInput.Quit -> mgr.remove(buf.serverId, p.reason ?: settings.value.defaultQuitMessage)
            is ParsedInput.Raw -> mgr.sendRaw(buf.serverId, p.line)
            is ParsedInput.Whois -> mgr.sendWhois(buf.serverId, p.target, bufId)
            is ParsedInput.Away -> mgr.setAway(buf.serverId, p.reason, originBufferId = bufId)
            is ParsedInput.Topic -> {
                val target = p.channel ?: buf.name.takeIf {
                    buf.kind == TreeNode.Buffer.Kind.CHANNEL
                }
                if (target == null) {
                    mgr.store.appendSystemTo(buf.serverId, bufId, "/topic: not in a channel")
                } else {
                    mgr.setTopic(buf.serverId, target, p.text)
                    val echo = if (p.text == null) "Querying topic for $target..."
                               else "Setting topic for $target..."
                    mgr.store.appendSystemTo(buf.serverId, "${buf.serverId}::$target", echo)
                }
            }
            is ParsedInput.Kick -> {
                val target = p.channel ?: buf.name.takeIf {
                    buf.kind == TreeNode.Buffer.Kind.CHANNEL
                }
                if (target != null) mgr.kick(buf.serverId, target, p.nick, p.reason)
                else mgr.store.appendSystemTo(buf.serverId, bufId, "/kick: not in a channel")
            }
            is ParsedInput.Mode -> mgr.mode(buf.serverId, p.target, p.args, originBufferId = bufId)
            is ParsedInput.Invite -> {
                val ch = p.channel ?: buf.name.takeIf {
                    buf.kind == TreeNode.Buffer.Kind.CHANNEL
                }
                if (ch != null) mgr.invite(buf.serverId, p.nick, ch, originBufferId = bufId)
                else mgr.store.appendSystemTo(buf.serverId, bufId, "/invite: specify a channel")
            }
            is ParsedInput.Names -> {
                val ch = p.channel ?: buf.name.takeIf {
                    buf.kind == TreeNode.Buffer.Kind.CHANNEL
                }
                if (ch != null) mgr.names(buf.serverId, ch)
                else mgr.store.appendSystemTo(buf.serverId, bufId, "/names: specify a channel")
            }
            is ParsedInput.NoticeCmd -> {
                mgr.sendNotice(buf.serverId, p.target, p.text)
                mgr.store.appendSystemTo(buf.serverId, bufId, "-> -${p.target}- ${p.text}")
            }
            is ParsedInput.Ctcp -> {
                mgr.sendCtcp(buf.serverId, p.target, p.tag, p.data)
                mgr.store.appendSystemTo(buf.serverId, bufId,
                    "[CTCP ${p.tag} → ${p.target}${if (p.data != null) ": ${p.data}" else ""}]")
            }
            is ParsedInput.ListCmd -> {
                // Modern extended LIST: pass through user-supplied filter args.
                // Common syntaxes: /list >50  /list <500  /list T<60  /list C>1440  /list *python*
                mgr.sendList(buf.serverId, p.args)
                openChannelList(buf.serverId)
            }
            is ParsedInput.Save -> {
                BufferExport.share(getApplication(), buf)
            }
            is ParsedInput.ChanInfo -> {
                val ch = p.channel ?: buf.name.takeIf {
                    buf.kind == TreeNode.Buffer.Kind.CHANNEL
                }
                if (ch != null) openChannelInfo(buf.serverId, ch)
                else mgr.store.appendSystemTo(buf.serverId, bufId,
                    "/chaninfo: not in a channel (specify a channel name)")
            }
            is ParsedInput.Cycle, is ParsedInput.Hop -> {
                if (buf.kind != TreeNode.Buffer.Kind.CHANNEL) {
                    mgr.store.appendSystemTo(buf.serverId, bufId, "/cycle: not in a channel")
                } else {
                    mgr.partChannel(buf.serverId, buf.name, "cycling")
                    mgr.joinChannel(buf.serverId, buf.name)
                }
            }
            is ParsedInput.Unban -> {
                val ch = p.channel ?: buf.name.takeIf {
                    buf.kind == TreeNode.Buffer.Kind.CHANNEL
                }
                if (ch == null) mgr.store.appendSystemTo(buf.serverId, bufId,
                    "/unban: not in a channel")
                else mgr.mode(buf.serverId, ch, "-b ${p.mask}")
            }
            is ParsedInput.Motd -> mgr.sendRaw(buf.serverId,
                if (p.target != null) "MOTD ${p.target}" else "MOTD")
            is ParsedInput.Lusers -> mgr.sendRaw(buf.serverId, "LUSERS")
            is ParsedInput.Admin -> mgr.sendRaw(buf.serverId,
                if (p.target != null) "ADMIN ${p.target}" else "ADMIN")
            is ParsedInput.Time -> mgr.sendRaw(buf.serverId,
                if (p.target != null) "TIME ${p.target}" else "TIME")
            is ParsedInput.Version -> mgr.sendRaw(buf.serverId,
                if (p.target != null) "VERSION ${p.target}" else "VERSION")
            is ParsedInput.Stats -> mgr.sendRaw(buf.serverId,
                if (p.args.isBlank()) "STATS" else "STATS ${p.args}")
            is ParsedInput.Info -> mgr.sendRaw(buf.serverId,
                if (p.target != null) "INFO ${p.target}" else "INFO")
            is ParsedInput.Links -> mgr.sendRaw(buf.serverId,
                if (p.args.isBlank()) "LINKS" else "LINKS ${p.args}")
            is ParsedInput.Who -> mgr.sendRaw(buf.serverId,
                if (p.args.isBlank()) "WHO" else "WHO ${p.args}")
            is ParsedInput.Userhost -> mgr.sendRaw(buf.serverId, "USERHOST ${p.nicks}")
            is ParsedInput.Ping -> {
                val ts = System.currentTimeMillis().toString()
                mgr.sendCtcp(buf.serverId, p.target, "PING", ts)
                mgr.store.appendSystemTo(buf.serverId, bufId, "[CTCP PING → ${p.target}]")
            }
            is ParsedInput.Server -> handleServerCommand(buf.serverId, bufId, p.args, alwaysNew = false)
            is ParsedInput.Connect -> handleServerCommand(buf.serverId, bufId, p.args, alwaysNew = false)
            is ParsedInput.Reconnect -> {
                val cfg = serverById(buf.serverId)
                if (cfg == null) mgr.store.appendSystemTo(buf.serverId, bufId,
                    "/reconnect: no server config")
                else {
                    mgr.remove(buf.serverId, "reconnecting")
                    mgr.add(cfg)
                }
            }
            is ParsedInput.NextServer -> {
                val cfg = serverById(buf.serverId)
                if (cfg == null) {
                    mgr.store.appendSystemTo(buf.serverId, bufId,
                        "/nextserver: no server config")
                } else if (cfg.endpoints.size <= 1) {
                    mgr.store.appendSystemTo(buf.serverId, bufId,
                        "/nextserver: only one endpoint configured for ${cfg.name}. " +
                            "Add more in Settings → Edit network, or use /server <host> to hop manually.")
                } else {
                    val newHost = mgr.cycleToNextEndpoint(buf.serverId)
                    mgr.store.appendSystemTo(buf.serverId, bufId,
                        if (newHost != null) "Cycling to next endpoint: $newHost…"
                        else "Cycling to next endpoint…")
                }
            }
            is ParsedInput.Clear -> mgr.store.clearLines(bufId)
            is ParsedInput.Echo -> mgr.store.appendSystemTo(buf.serverId, bufId, p.text)
            is ParsedInput.Help -> {
                if (p.command == null) {
                    _helpRequest.value = null  // null target = "show all"
                    _helpDialogOpen.value = true
                } else {
                    mgr.store.appendSystemTo(buf.serverId, bufId,
                        com.pocketirc.app.irc.HelpRegistry.lookup(p.command))
                }
            }
            is ParsedInput.OnCmd -> handleOnCmd(buf.serverId, bufId, p)
            is ParsedInput.Noop -> { /* intentional no-op */ }
            is ParsedInput.Set -> handleSet(buf.serverId, bufId, p)
            is ParsedInput.Window -> {
                val raw = p.target.trim()
                val name = if (raw.equals("status", ignoreCase = true) ||
                               raw.equals("(status)", ignoreCase = true)) "(status)"
                           else raw
                val candidates = buffers.value.values
                // Resolve target server. If -k was given, match by display name
                // OR id; otherwise default to the current buffer's server.
                val targetServerId: String? = if (p.network != null) {
                    servers.value.firstOrNull {
                        it.name.equals(p.network, ignoreCase = true) ||
                        it.id.equals(p.network, ignoreCase = true)
                    }?.id
                } else buf.serverId
                if (p.network != null && targetServerId == null) {
                    mgr.store.appendSystemTo(buf.serverId, bufId,
                        "/window: no server named '${p.network}'")
                    return
                }
                // Try the chosen server first, then fall back to any server
                // (only when -k wasn't specified — explicit -k is strict).
                val onChosenServer = candidates.firstOrNull {
                    it.serverId == targetServerId &&
                    it.name.equals(name, ignoreCase = true)
                }
                val match = if (p.network != null) onChosenServer
                            else onChosenServer ?: candidates.firstOrNull {
                                it.name.equals(name, ignoreCase = true)
                            }
                if (match != null) selectBuffer(match.id)
                else mgr.store.appendSystemTo(buf.serverId, bufId,
                    "/window: no open buffer named '$raw'" +
                    (p.network?.let { " on $it" } ?: ""))
            }
            is ParsedInput.Say -> {
                if (buf.kind == TreeNode.Buffer.Kind.STATUS)
                    mgr.store.appendSystemTo(buf.serverId, bufId,
                        "/say: cannot send text to (status)")
                else mgr.sendMessage(buf.serverId, buf.name, p.text)
            }
            is ParsedInput.Alert -> {
                mgr.fireUserNotification(
                    title = "Pocket IRC",
                    body = p.text,
                    channel = p.channel ?: settings.value.defaultAlertChannel,
                )
            }
            is ParsedInput.NotifyList -> handleNotifyList(buf.serverId, bufId, p)
            is ParsedInput.Error -> mgr.store.appendSystemTo(buf.serverId, bufId, p.message)
        }
    }

    /** Shared dispatcher for /server, /connect. */
    private fun handleServerCommand(
        currentServerId: String,
        bufId: String,
        args: String,
        alwaysNew: Boolean,
    ) {
        val mgr = _service.value?.manager ?: return
        // No-args form: dial the current buffer's server. Matches /connect's
        // no-args branch so /server and /connect feel like true aliases.
        if (args.isBlank()) {
            val cfg = serverById(currentServerId)
            if (cfg == null) {
                mgr.store.appendSystemTo(currentServerId, bufId,
                    "/server: no server context for this buffer.")
            } else if (mgr.connectedIds.value.contains(currentServerId)) {
                mgr.store.appendSystemTo(currentServerId, bufId,
                    "/server: already connected. Use /reconnect to force a redial.")
            } else {
                mgr.remove(currentServerId)
                mgr.add(cfg)
            }
            return
        }
        // Single-token name lookup: match saved configs and live connections
        // by display name (including the ISUPPORT NETWORK= name), config
        // name, server id, or hostname. Reconnects whatever it finds.
        if (!args.contains('-') && !args.contains(':')) {
            val needle = args.trim()
            val savedMatch = servers.value.firstOrNull {
                it.name.equals(needle, ignoreCase = true) ||
                it.id.equals(needle, ignoreCase = true)
            }
            val networkNames = mgr.networkNames.value
            val liveMatch = mgr.connections.value.values.firstOrNull { conn ->
                val cfg = conn.config
                cfg.name.equals(needle, ignoreCase = true) ||
                cfg.id.equals(needle, ignoreCase = true) ||
                cfg.endpoints.firstOrNull()?.host.equals(needle, ignoreCase = true) ||
                networkNames[cfg.id]?.equals(needle, ignoreCase = true) == true
            }?.config
            val match = savedMatch ?: liveMatch
            if (match != null) {
                mgr.remove(match.id)
                mgr.add(match)
                mgr.store.appendSystemTo(currentServerId, bufId,
                    "Reconnecting to ${match.name}…")
                return
            }
        }
        val current = serverById(currentServerId)
        when (val r = com.pocketirc.app.irc.ServerArgs.parse(args)) {
            is com.pocketirc.app.irc.ServerArgs.Result.Err ->
                mgr.store.appendSystemTo(currentServerId, bufId, r.message)
            is com.pocketirc.app.irc.ServerArgs.Result.Ok -> {
                val patch = r.patch
                val forceNew = alwaysNew || patch.createNew || current == null
                if (forceNew) {
                    handleServerCreateNew(currentServerId, bufId, patch)
                } else {
                    handleServerModifyCurrent(currentServerId, bufId, patch, current!!)
                }
            }
        }
    }

    /** /server -m or /server-from-script: build a fresh entry from the patch. */
    private fun handleServerCreateNew(
        currentServerId: String,
        bufId: String,
        patch: com.pocketirc.app.irc.ServerArgs.Patch,
    ) {
        val mgr = _service.value?.manager ?: return
        val baseCfg = com.pocketirc.app.irc.ServerArgs.toNewConfig(patch)
        // Even with -m, append a timestamp suffix so the new id can't collide
        // with any existing entry. Without -m, /server in this branch is only
        // reached when there's no current-buffer context (script case), so
        // collision is also possible there — same suffix logic applies.
        val cfg = if (servers.value.any { it.id == baseCfg.id } ||
                      mgr.configFor(baseCfg.id) != null) {
            baseCfg.copy(id = "${baseCfg.id}#${System.currentTimeMillis()}")
        } else baseCfg
        viewModelScope.launch {
            if (patch.persist) repo.upsert(cfg, servers.value)
            if (patch.skipConnect) mgr.register(cfg) else mgr.add(cfg)
            val verb = when {
                patch.persist && patch.skipConnect ->
                    "Saved ${cfg.name} and added to the network tree (not connected)."
                patch.persist ->
                    "Saved and connecting to ${cfg.name}…"
                patch.skipConnect ->
                    "Added ${cfg.name} to the network tree (not connected, not saved)."
                else ->
                    "Connecting to ${cfg.name} (ephemeral, not saved)…"
            }
            mgr.store.appendSystemTo(currentServerId, bufId, verb)
        }
    }

    /**
     * /server <new-host> in a connected window: redial the current entry
     * against the new host, preserving id, buffers, channels, history,
     * autojoin, notify list, and SASL settings. The whole point is that the
     * user stays on the same network entry — only the underlying socket
     * target changes. Designed for the netsplit / "lurklurk is laggy, hop to
     * cherryh" case.
     *
     * As in [updateServer], the remove+add cycle here implicitly clears
     * any prior permanent-failure flag on the connection.
     */
    private fun handleServerModifyCurrent(
        currentServerId: String,
        bufId: String,
        patch: com.pocketirc.app.irc.ServerArgs.Patch,
        current: ServerConfig,
    ) {
        val mgr = _service.value?.manager ?: return
        val newCfg = com.pocketirc.app.irc.ServerArgs.applyTo(patch, current)
        viewModelScope.launch {
            val wasSaved = servers.value.any { it.id == current.id }
            if (patch.persist && wasSaved) repo.upsert(newCfg, servers.value)
            mgr.remove(current.id)
            if (patch.skipConnect) mgr.register(newCfg) else mgr.add(newCfg)
            // Build a "changed: ..." summary so the user can see at a glance
            // what was actually modified by this /server invocation.
            val changes = buildList {
                if (patch.host != null) add("host=${patch.host}")
                if (patch.port != null) add("port=${patch.port}")
                if (patch.tls != null) add("tls=${patch.tls}")
                if (patch.nicks != null) add("nicks=${patch.nicks.joinToString(",")}")
                if (patch.user != null) add("user=${patch.user}")
                if (patch.realName != null) add("realname=${patch.realName}")
                if (patch.displayName != null) add("name=${patch.displayName}")
                if (patch.serverPass != null) add("pass=***")
                if (patch.sasl != null) add("sasl=${patch.sasl.username}:***")
            }
            val verb = buildString {
                append("Reconnecting ${current.name}")
                if (changes.isNotEmpty()) append(" (changed: ${changes.joinToString(", ")})")
                if (patch.persist && wasSaved) append(" — saved")
                else if (patch.persist && !wasSaved) append(" — not saved (use /server -p on a saved entry)")
                else append(" — ephemeral, original config preserved on disk")
                append("…")
            }
            mgr.store.appendSystemTo(currentServerId, bufId, verb)
        }
    }

    private fun handleOnCmd(serverId: String, bufId: String, p: ParsedInput.OnCmd) {
        val mgr = _service.value?.manager ?: return
        when (p) {
            is ParsedInput.OnCmd.List -> {
                val list = mgr.onHooks.list(p.event)
                if (list.isEmpty()) {
                    mgr.store.appendSystemTo(serverId, bufId,
                        if (p.event == null) "No /on hooks defined."
                        else "No /on hooks for ${p.event}.")
                } else {
                    mgr.store.appendSystemTo(serverId, bufId, "/on hooks:")
                    for (h in list) {
                        val parts = buildList {
                            add("[${h.event}]")
                            add(h.name)
                            h.networkFilter?.let { add("-k $it") }
                            h.channelFilter?.let { add("-c $it") }
                            h.nickMaskFilter?.let { add("-n $it") }
                            h.textPattern?.let { add("-pat $it") }
                            if (h.desktop) add("-d")
                            h.channel?.let { add("-c $it") }
                            if (h.suppressDefault) add("-x")
                            if (h.suppressNotification) add("-q")
                            if (h.persistent) add("(persisted)")
                            add("→ ${h.action}")
                        }
                        mgr.store.appendSystemTo(serverId, bufId, "  ${parts.joinToString(" ")}")
                    }
                }
            }
            is ParsedInput.OnCmd.Remove -> {
                val ok = mgr.onHooks.remove(p.event, p.name)
                if (ok) {
                    persistHooks()
                    mgr.store.appendSystemTo(serverId, bufId,
                        "Removed /on hook ${p.event}/${p.name}")
                } else {
                    mgr.store.appendSystemTo(serverId, bufId,
                        "/on -r: no hook ${p.event}/${p.name}")
                }
            }
            is ParsedInput.OnCmd.Add -> {
                val name = mgr.onHooks.add(p.hook)
                if (p.hook.persistent) persistHooks()
                mgr.store.appendSystemTo(serverId, bufId,
                    "Added /on ${p.hook.event} '$name'" +
                    (if (p.hook.persistent) " (persisted)" else ""))
            }
        }
    }

    private fun persistHooks() {
        val mgr = _service.value?.manager ?: return
        // Only persist hooks marked persistent.
        val keep = mgr.onHooks.list().filter { it.persistent }
        val json = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<List<com.pocketirc.app.irc.OnHook>>(),
            keep,
        )
        viewModelScope.launch { settingsRepo.setOnHooksJson(json) }
    }

    private fun handleNotifyList(serverId: String, bufId: String, p: ParsedInput.NotifyList) {
        val mgr = _service.value?.manager ?: return
        val cfg = serverById(serverId)
        if (cfg == null) {
            mgr.store.appendSystemTo(serverId, bufId, "/notify: no server config")
            return
        }
        when (p) {
            is ParsedInput.NotifyList.Show -> {
                if (cfg.notifyList.isEmpty())
                    mgr.store.appendSystemTo(serverId, bufId, "Notify list is empty.")
                else
                    mgr.store.appendSystemTo(serverId, bufId,
                        "Notify list: ${cfg.notifyList.joinToString(" ")}")
            }
            is ParsedInput.NotifyList.Add -> {
                val merged = (cfg.notifyList + p.nicks)
                    .map { it.trim() }.filter { it.isNotEmpty() }
                    .distinctBy { it.lowercase() }
                updateServer(cfg.copy(notifyList = merged))
                mgr.store.appendSystemTo(serverId, bufId,
                    "Added to notify list: ${p.nicks.joinToString(" ")}")
            }
            is ParsedInput.NotifyList.Remove -> {
                val drop = p.nicks.map { it.lowercase() }.toSet()
                val next = cfg.notifyList.filterNot { it.lowercase() in drop }
                updateServer(cfg.copy(notifyList = next))
                mgr.store.appendSystemTo(serverId, bufId,
                    "Removed from notify list: ${p.nicks.joinToString(" ")}")
            }
        }
    }

    fun setStartupScript(text: String) {
        viewModelScope.launch { settingsRepo.setStartupScript(text) }
    }

    fun setDefaultAlertChannel(name: String) {
        viewModelScope.launch { settingsRepo.setDefaultAlertChannel(name) }
    }

    fun setReplayNotificationThreshold(n: Int) {
        viewModelScope.launch { settingsRepo.setReplayNotificationThreshold(n) }
    }

    /** Decoded list of user-created alert channels. */
    val customAlertChannels: kotlinx.coroutines.flow.StateFlow<List<com.pocketirc.app.notif.CustomAlertChannel>> =
        settings.map { s ->
            if (s.customAlertChannelsJson.isBlank()) emptyList()
            else runCatching {
                kotlinx.serialization.json.Json.decodeFromString<List<com.pocketirc.app.notif.CustomAlertChannel>>(
                    s.customAlertChannelsJson
                )
            }.getOrDefault(emptyList())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addCustomAlertChannel(ch: com.pocketirc.app.notif.CustomAlertChannel) {
        viewModelScope.launch {
            val cur = customAlertChannels.value
            // Reject duplicate name (case-insensitive) and reject built-in names.
            if (com.pocketirc.app.PocketIrcApp.USER_CHANNEL_NAMES.containsKey(ch.name.lowercase()))
                return@launch
            if (cur.any { it.name.equals(ch.name, ignoreCase = true) }) return@launch
            // Create the Android channel synchronously.
            com.pocketirc.app.notif.CustomChannelsManager.create(getApplication(), ch)
            val next = cur + ch
            persistCustomAlertChannels(next)
        }
    }

    fun removeCustomAlertChannel(channelId: String) {
        viewModelScope.launch {
            val cur = customAlertChannels.value
            val target = cur.firstOrNull { it.channelId == channelId } ?: return@launch
            com.pocketirc.app.notif.CustomChannelsManager.delete(getApplication(), target.channelId)
            persistCustomAlertChannels(cur - target)
        }
    }

    private suspend fun persistCustomAlertChannels(list: List<com.pocketirc.app.notif.CustomAlertChannel>) {
        val json = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer<List<com.pocketirc.app.notif.CustomAlertChannel>>(),
            list,
        )
        settingsRepo.setCustomAlertChannelsJson(json)
    }

    /** Read the current value of a [com.pocketirc.app.irc.SettingsKeys] key as a display string. */
    private fun readSettingValue(name: String): String {
        val s = settings.value
        return when (name) {
            "color_nicks" -> s.colorNicks.toString()
            "theme" -> s.themeMode.name.lowercase()
            "font_size" -> s.fontSizeSp.toString()
            "timestamp_format" -> s.timestampFormat
            "monospace" -> s.useMonospace.toString()
            "send_on_enter" -> s.sendOnEnter.toString()
            "quit_message" -> s.defaultQuitMessage
            "mention_notifications" -> s.mentionNotifications.toString()
            "privmsg_notifications" -> s.privmsgNotifications.toString()
            "notice_notifications" -> s.noticeNotifications.toString()
            "notification_reply" -> s.notificationReplyAction.toString()
            "notify_list_notifications" -> s.notifyListNotifications.toString()
            "ctcp_version" -> s.ctcpVersion
            "ctcp_time" -> s.ctcpTime
            "ctcp_finger" -> s.ctcpFinger
            "ctcp_userinfo" -> s.ctcpUserinfo
            "ctcp_source" -> s.ctcpSource
            "default_nicks" -> s.defaultNicksCsv
            "default_realname" -> s.defaultRealName
            "default_user" -> s.defaultUserName
            "auto_add_joined_channels" -> s.autoAddJoinedChannels.toString()
            else -> "?"
        }
    }

    private fun applySettingValue(name: String, parsed: Any) {
        when (name) {
            "color_nicks" -> setColorNicks(parsed as Boolean)
            "theme" -> setThemeMode(
                com.pocketirc.app.data.ThemeMode.valueOf(parsed as String)
            )
            "font_size" -> setFontSize(parsed as Int)
            "timestamp_format" -> setTimestampFormat(parsed as String)
            "monospace" -> setUseMonospace(parsed as Boolean)
            "send_on_enter" -> setSendOnEnter(parsed as Boolean)
            "quit_message" -> setDefaultQuitMessage(parsed as String)
            "mention_notifications" -> setMentionNotifications(parsed as Boolean)
            "privmsg_notifications" -> setPrivmsgNotifications(parsed as Boolean)
            "notice_notifications" -> setNoticeNotifications(parsed as Boolean)
            "notification_reply" -> setNotificationReplyAction(parsed as Boolean)
            "notify_list_notifications" -> setNotifyListNotifications(parsed as Boolean)
            "ctcp_version" -> viewModelScope.launch { settingsRepo.setCtcpVersion(parsed as String) }
            "ctcp_time" -> viewModelScope.launch { settingsRepo.setCtcpTime(parsed as String) }
            "ctcp_finger" -> viewModelScope.launch { settingsRepo.setCtcpFinger(parsed as String) }
            "ctcp_userinfo" -> viewModelScope.launch { settingsRepo.setCtcpUserinfo(parsed as String) }
            "ctcp_source" -> viewModelScope.launch { settingsRepo.setCtcpSource(parsed as String) }
            "default_nicks" -> viewModelScope.launch { settingsRepo.setDefaultNicksCsv(parsed as String) }
            "default_realname" -> viewModelScope.launch { settingsRepo.setDefaultRealName(parsed as String) }
            "default_user" -> viewModelScope.launch { settingsRepo.setDefaultUserName(parsed as String) }
            "auto_add_joined_channels" -> setAutoAddJoinedChannels(parsed as Boolean)
        }
    }

    private fun handleSet(serverId: String, bufId: String, p: ParsedInput.Set) {
        val mgr = _service.value?.manager ?: return
        if (p.key == null) {
            mgr.store.appendSystemTo(serverId, bufId, "Settings:")
            for (spec in com.pocketirc.app.irc.SettingsKeys.all) {
                mgr.store.appendSystemTo(serverId, bufId,
                    "  ${spec.name} = ${readSettingValue(spec.name)}")
            }
            return
        }
        val spec = com.pocketirc.app.irc.SettingsKeys.find(p.key)
        if (spec == null) {
            mgr.store.appendSystemTo(serverId, bufId,
                "/set: unknown key '${p.key}'  (try /set with no args)")
            return
        }
        if (p.value == null) {
            mgr.store.appendSystemTo(serverId, bufId,
                "${spec.name} = ${readSettingValue(spec.name)}  — ${spec.description}")
            return
        }
        val parsed = com.pocketirc.app.irc.SettingsKeys.parseValue(spec.type, p.value)
        if (parsed == null) {
            val hint = when (spec.type) {
                com.pocketirc.app.irc.SettingsKeys.Type.BOOL ->
                    "expected true/false (or on/off, yes/no, 1/0)"
                com.pocketirc.app.irc.SettingsKeys.Type.INT -> "expected an integer"
                com.pocketirc.app.irc.SettingsKeys.Type.STRING -> "expected a string"
                com.pocketirc.app.irc.SettingsKeys.Type.ENUM_THEME ->
                    "expected one of: system, light, dark"
            }
            mgr.store.appendSystemTo(serverId, bufId,
                "/set ${spec.name}: invalid value '${p.value}'  ($hint)")
            return
        }
        applySettingValue(spec.name, parsed)
        mgr.store.appendSystemTo(serverId, bufId,
            "${spec.name} = ${if (spec.type == com.pocketirc.app.irc.SettingsKeys.Type.ENUM_THEME) (parsed as String).lowercase() else parsed}")
    }

    /** When non-null, the help dialog is open. The string is an optional command filter. */
    private val _helpDialogOpen = MutableStateFlow(false)
    val helpDialogOpen: StateFlow<Boolean> = _helpDialogOpen
    private val _helpRequest = MutableStateFlow<String?>(null)
    val helpRequest: StateFlow<String?> = _helpRequest
    fun openHelp(command: String? = null) {
        _helpRequest.value = command
        _helpDialogOpen.value = true
    }
    fun closeHelp() { _helpDialogOpen.value = false }

    fun sendTyping(state: TypingState) {
        val bufId = _selectedBufferId.value ?: return
        val buf = buffers.value[bufId] ?: return
        _service.value?.manager?.sendTyping(buf.serverId, buf.name, state)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as IrcService.LocalBinder).service
            svc.selfJoinListener = { sid, ch -> selectBuffer("$sid::$ch") }
            _service.value = svc
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            _service.value?.selfJoinListener = null
            _service.value = null
        }
    }

    fun bind(ctx: Context) {
        val intent = Intent(ctx, IrcService::class.java)
        ctx.startForegroundService(intent)
        ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    fun unbind(ctx: Context) {
        runCatching { ctx.unbindService(conn) }
        _service.value = null
    }
}
