package com.pocketirc.app.irc

import com.pocketirc.app.BuildConfig
import com.pocketirc.app.data.ChatLineRepository
import com.pocketirc.app.model.AutoJoinChannel
import com.pocketirc.app.model.ServerConfig
import com.pocketirc.app.model.TypingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns all [IrcConnection]s + the shared [BufferStore] + [ReplyRouter].
 */
class ConnectionManager(history: ChatLineRepository? = null) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connections = MutableStateFlow<Map<String, IrcConnection>>(emptyMap())
    val connections: StateFlow<Map<String, IrcConnection>> = _connections

    val store = BufferStore(history)
    val router = ReplyRouter()

    /** /on event-hook engine. Loaded by the service from settings on startup. */
    val onHooks = OnHookEngine()

    /**
     * Set by IrcService so dispatched hook actions can be run as slash commands.
     * [contextTarget] is the channel or nick the originating event was bound to
     * (or empty when there is none — e.g. CONNECT). It's how /say knows where
     * to send.
     */
    @Volatile var onHookExecutor: ((serverId: String, command: String, contextTarget: String) -> Unit)? = null

    /** Set by IrcService to actually post a system notification on hook -d/-c. */
    @Volatile var onHookNotifier:
        ((title: String, body: String, channel: String?) -> Unit)? = null

    /** Public entry point for the /alert slash command. */
    fun fireUserNotification(title: String, body: String, channel: String?) {
        onHookNotifier?.invoke(title, body, channel)
    }

    /** Bumped whenever channel membership might have changed, so the nick list UI re-queries. */
    private val _nickListVersion = MutableStateFlow(0L)
    val nickListVersion: StateFlow<Long> = _nickListVersion

    /**
     * Server ids that are currently in the "connected" state — i.e. we've
     * received an IrcEvent.Connected for them and not yet a Disconnected.
     * The tree UI uses this to render the cloud-vs-cloud-off icon, since
     * `_connections` membership alone doesn't distinguish idle stubs from
     * dialed connections.
     */
    private val _connectedIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedIds: StateFlow<Set<String>> = _connectedIds

    /**
     * NETWORK= ISUPPORT tokens learned from each server's RPL_ISUPPORT (005)
     * numeric. Keyed by serverId. Used by the tree builder to display the
     * server's announced network name in place of the bare hostname when the
     * config has [com.pocketirc.app.model.ServerConfig.displayNameLocked] off.
     */
    private val _networkNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val networkNames: StateFlow<Map<String, String>> = _networkNames

    /**
     * Per-server actual host (the name of the IRC server we ended up actually
     * talking to). Distinct from the configured endpoint host because of:
     *   1) DNS round-robin entrypoints (irc.libera.chat → cherryh.libera.chat)
     *   2) RPL_BOUNCE 010 redirects from the server itself
     * Used purely for display. The configured `ServerConfig.endpoints` is
     * never updated from this — `/server` (no args) always re-dials the
     * configured entrypoint.
     */
    private val _actualHosts = MutableStateFlow<Map<String, String>>(emptyMap())
    val actualHosts: StateFlow<Map<String, String>> = _actualHosts

    private fun setActualHost(serverId: String, host: String) {
        if (host.isBlank()) return
        _connections.value[serverId]?.actualHost = host
        _actualHosts.value = _actualHosts.value + (serverId to host)
    }

    data class ChannelListEntry(val name: String, val users: Int, val topic: String)
    data class ChannelListState(val entries: List<ChannelListEntry>, val loading: Boolean)
    /** Per-server channel-list state, populated by /list (321/322/323 numerics). */
    private val _channelLists = MutableStateFlow<Map<String, ChannelListState>>(emptyMap())
    val channelLists: StateFlow<Map<String, ChannelListState>> = _channelLists

    /** Channel-info state keyed by "serverId::channel" — populated by mode/list numerics. */
    private val _channelInfo = MutableStateFlow<Map<String, ChannelInfoState>>(emptyMap())
    val channelInfo: StateFlow<Map<String, ChannelInfoState>> = _channelInfo

    private fun updateChannelInfo(
        serverId: String, channel: String,
        transform: (ChannelInfoState) -> ChannelInfoState,
    ) {
        val key = "$serverId::$channel"
        val current = _channelInfo.value
        val existing = current[key] ?: ChannelInfoState(serverId, channel)
        _channelInfo.value = current + (key to transform(existing))
    }

    fun requestChannelInfo(serverId: String, channel: String) {
        // Reset list-loading flags and send the queries.
        updateChannelInfo(serverId, channel) {
            it.copy(
                bansLoading = true, bans = emptyList(),
                exceptsLoading = true, excepts = emptyList(),
                invitesLoading = true, invites = emptyList(),
                quietsLoading = true, quiets = emptyList(),
            )
        }
        val conn = _connections.value[serverId] ?: return
        conn.sendRaw("MODE $channel")
        conn.sendRaw("MODE $channel +b")
        conn.sendRaw("MODE $channel +e")
        conn.sendRaw("MODE $channel +I")
        conn.sendRaw("MODE $channel +q")  // some servers use 728/729 quiet list
    }

    private fun updateChannelList(serverId: String, transform: (ChannelListState) -> ChannelListState) {
        val current = _channelLists.value
        val existing = current[serverId] ?: ChannelListState(emptyList(), loading = false)
        _channelLists.value = current + (serverId to transform(existing))
    }

    private val _mentions = MutableSharedFlow<IrcEvent.Message>(extraBufferCapacity = 64)
    val mentions: SharedFlow<IrcEvent.Message> = _mentions

    /**
     * Threshold for the bouncer-replay notification batcher: when a replayed
     * message would normally fire a mention notification, it's deferred and
     * coalesced with other replay mentions arriving in the same burst (within
     * REPLAY_DEBOUNCE_MS). When the burst settles, if the total deferred
     * count is at most this threshold, all of them fire as system
     * notifications. Otherwise all are dropped silently. Set externally by
     * IrcService from settings.
     */
    @Volatile var replayNotificationThreshold: Int = 3

    /**
     * CTCP response overrides, mirrored from settings by IrcService. Empty
     * means "use the built-in default", "off" means "ignore the request",
     * "utc" (TIME only) means "format the current time in UTC". Anything
     * else is sent verbatim.
     */
    @Volatile var ctcpVersion: String = ""
    @Volatile var ctcpTime: String = ""
    @Volatile var ctcpFinger: String = ""
    @Volatile var ctcpUserinfo: String = ""
    @Volatile var ctcpSource: String = ""

    /**
     * Global identity defaults mirrored from [com.pocketirc.app.data.AppSettings]
     * by IrcService. Per-network [ServerConfig] fields override these via
     * [resolveIdentity]; null/empty per-network values inherit. The
     * fallback chain is: per-network override → global default → (for
     * realName only) the resolved nick.
     */
    @Volatile var defaultNicks: List<String> = emptyList()
    @Volatile var defaultRealName: String = ""
    @Volatile var defaultUserName: String = ""

    /**
     * When true, every successful self-join is added to the network's
     * autojoin list and every self-part removes it. Mirrored from
     * [com.pocketirc.app.data.AppSettings.autoAddJoinedChannels] by
     * IrcService. Per-channel manual toggles work regardless of this flag.
     */
    @Volatile var autoAddJoinedChannels: Boolean = false

    /**
     * Optional callback invoked whenever an in-place [IrcConnection.config]
     * mutation should be persisted to DataStore. Receives the [serverId]
     * (not a captured ServerConfig snapshot) so the implementation reads
     * the live `conn.config` at write time, avoiding races where two
     * back-to-back mutations would otherwise persist stale snapshots in
     * out-of-order coroutines. The callback is responsible for deciding
     * whether the network is persisted in the first place (ephemeral
     * /server connections shouldn't be auto-promoted) and for doing the
     * actual write. Set by IrcService.
     */
    @Volatile var configPersister: ((serverId: String) -> Unit)? = null

    /**
     * Add or remove [channel] from [serverId]'s autojoin list. Idempotent
     * — adding an already-present channel or removing an absent one is a
     * no-op (and skips the persist write). [key] is only meaningful when
     * adding; ignored on remove. Channel comparison is case-insensitive
     * because IRC channel names are.
     *
     * The mutation is applied to the live [IrcConnection.config] in place
     * (so an immediate /reconnect picks up the new list) and then handed
     * to [configPersister] for DataStore writeback.
     */
    fun setAutoJoinEntry(serverId: String, channel: String, key: String?, enabled: Boolean) {
        val conn = _connections.value[serverId] ?: return
        val current = conn.config.autoJoin
        val already = current.any { it.name.equals(channel, ignoreCase = true) }
        // When adding without an explicit key, fall back to any key the
        // user typed when /joining this channel during the session. That's
        // what makes keyed channels work consistently across all three
        // trigger paths (auto-add hook, per-channel toggle, retroactive).
        val effectiveKey = key?.takeIf { it.isNotBlank() }
            ?: conn.rememberedKeyFor(channel)
        val updated: List<AutoJoinChannel> = when {
            enabled && !already ->
                current + AutoJoinChannel(name = channel, key = effectiveKey)
            !enabled && already ->
                current.filterNot { it.name.equals(channel, ignoreCase = true) }
            else -> return
        }
        conn.updateConfig { it.copy(autoJoin = updated) }
        configPersister?.invoke(serverId)
    }

    /** True if [channel] is currently in [serverId]'s autojoin list. */
    fun isAutoJoined(serverId: String, channel: String): Boolean {
        val conn = _connections.value[serverId] ?: return false
        return conn.config.autoJoin.any { it.name.equals(channel, ignoreCase = true) }
    }

    /** Snapshot of channels currently joined on [serverId] (per KICL state). */
    fun joinedChannelsFor(serverId: String): List<String> {
        val conn = _connections.value[serverId] ?: return emptyList()
        return conn.joinedChannelNames()
    }

    /** Resolve the effective identity for [cfg], folding in current globals. */
    fun resolveIdentity(cfg: ServerConfig): EffectiveIdentity {
        val nicks = cfg.nicks?.takeIf { it.isNotEmpty() }
            ?: defaultNicks.takeIf { it.isNotEmpty() }
            ?: listOf("")
        val realName = cfg.realName?.takeIf { it.isNotBlank() }
            ?: defaultRealName.takeIf { it.isNotBlank() }
            ?: nicks.first()
        val userName = cfg.userName?.takeIf { it.isNotBlank() }
            ?: defaultUserName.takeIf { it.isNotBlank() }
            ?: ""
        return EffectiveIdentity(nicks = nicks, realName = realName, userName = userName)
    }

    /** Per-server event-collector jobs started by [register], cancelled by [remove]. */
    private val eventCollectors =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    private val replayBuffer =
        java.util.concurrent.ConcurrentHashMap<String, MutableList<IrcEvent.Message>>()
    private val replayDrainJobs =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    private fun bufferReplayMention(serverId: String, ev: IrcEvent.Message) {
        val list = replayBuffer.getOrPut(serverId) { mutableListOf() }
        synchronized(list) { list.add(ev) }
        replayDrainJobs[serverId]?.cancel()
        replayDrainJobs[serverId] = scope.launch {
            kotlinx.coroutines.delay(750L)
            val drained = synchronized(list) {
                val copy = list.toList()
                list.clear()
                copy
            }
            if (drained.isNotEmpty() && drained.size <= replayNotificationThreshold) {
                drained.forEach { _mentions.tryEmit(it) }
            }
            // else: silently dropped — too many to be worth interrupting the user.
        }
    }

    /** Emits (serverId, channel) when *we* join a channel — UI uses this to auto-switch. */
    private val _selfJoins = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 32)
    val selfJoins: SharedFlow<Pair<String, String>> = _selfJoins

    /** Emits notify-list state changes for the service to surface as notifications. */
    private val _notifyEvents = MutableSharedFlow<IrcEvent.NotifyState>(extraBufferCapacity = 32)
    val notifyEvents: SharedFlow<IrcEvent.NotifyState> = _notifyEvents

    /**
     * Register a server in the network tree without dialing it. Creates the
     * [IrcConnection] and the (status) buffer, hydrates persisted history,
     * and starts the event collector. The connection sits idle until
     * something calls [IrcConnection.connect] (e.g. via /reconnect).
     *
     * Idempotent: if the id is already registered, this is a no-op.
     */
    fun register(config: ServerConfig) {
        if (_connections.value.containsKey(config.id)) return
        val conn = IrcConnection(config) { cfg -> resolveIdentity(cfg) }
        _connections.value = _connections.value + (config.id to conn)
        // Tracked so [remove] can cancel it. conn.events is a SharedFlow and
        // never completes, so an uncancelled collector outlives the connection
        // it was created for — and re-registering the same id (as
        // cycleEndpoint does via remove+register) would stack another one.
        eventCollectors[config.id]?.cancel()
        eventCollectors[config.id] = scope.launch {
            conn.events.collect { ev ->
                try { processEvent(config, conn, ev) } catch (t: Throwable) {
                    com.pocketirc.app.error.ErrorReporter.report(
                        t, "ConnectionManager.events for ${config.name}: $ev")
                }
            }
        }
        // Hydrate (status) so the user has something to see when they
        // navigate to the new entry, and stamp a one-line note explaining
        // the entry's idle state.
        scope.launch {
            store.hydrate(config.id, "(status)")
            store.appendSystemTo(config.id, "${config.id}::(status)",
                "Server '${config.name}' added to the network tree (not connected). " +
                    "Use /reconnect on this window to dial.")
        }
    }

    /** Register and immediately dial the server. The standard "connect now" path. */
    fun add(config: ServerConfig) {
        if (_connections.value.containsKey(config.id)) return
        register(config)
        _connections.value[config.id]?.let { conn ->
            scope.launch { conn.connect() }
        }
    }

    /**
     * Force-cycle the live connection of [serverId] to the *next* endpoint
     * in its `endpoints` list. Used by /nextserver. If the list has only one
     * endpoint, behaves like /reconnect (re-dial the same one).
     *
     * Returns the human-readable host the next dial will target, or null if
     * the server isn't currently registered.
     */
    fun cycleToNextEndpoint(serverId: String): String? {
        val conn = _connections.value[serverId] ?: return null
        val cfg = conn.config
        val nEndpoints = cfg.endpoints.size.coerceAtLeast(1)
        val nextIndex = (conn.endpointIndex + 1) % nEndpoints
        // Tear down current and re-register, then dial starting at nextIndex.
        remove(serverId)
        register(cfg)
        _connections.value[serverId]?.let { fresh ->
            scope.launch { fresh.connect(startEndpointIndex = nextIndex) }
        }
        return cfg.endpoints.getOrNull(nextIndex)?.host
    }

    /** Per-event handler, separated so the collector loop can wrap it in try/catch. */
    private suspend fun processEvent(
        config: ServerConfig,
        conn: IrcConnection,
        ev: IrcEvent,
    ) {
                when (ev) {
                    is IrcEvent.CtcpQuery -> {
                        handleCtcpQuery(config, conn, ev)
                    }
                    is IrcEvent.Numeric -> {
                        handleNumeric(config.id, ev)
                        // 353 NAMES, 366 endofnames, JOIN/PART/QUIT/KICK numerics may shift membership
                        if (ev.numeric in setOf(353, 366)) _nickListVersion.value++
                    }
                    else -> {
                        if (ev is IrcEvent.Connected)
                            _connectedIds.value = _connectedIds.value + config.id
                        if (ev is IrcEvent.Disconnected)
                            _connectedIds.value = _connectedIds.value - config.id
                        // Run /on hooks first so a -x hook can suppress the
                        // default ingest (and thus the rendering + mention
                        // notification + nick-list bump for that event).
                        val outcome = dispatchOnHooks(config, ev)
                        if (!outcome.suppress) {
                            val notify = store.ingest(ev, ourNick = resolveIdentity(config).nick)
                            if (notify && !outcome.quiet && ev is IrcEvent.Message) {
                                if (ev.fromHistory) {
                                    // Replay mention — buffer for debounced
                                    // threshold-aware delivery so reconnecting
                                    // to a bouncer doesn't blow up the user's
                                    // phone.
                                    bufferReplayMention(config.id, ev)
                                } else {
                                    _mentions.emit(ev)
                                }
                            }
                            if (ev is IrcEvent.Joined || ev is IrcEvent.Parted ||
                                ev is IrcEvent.Connected) {
                                _nickListVersion.value++
                            }
                            // Auto-add to autojoin: any time WE join a
                            // channel (interactive or autojoin replay), if
                            // the global setting is on, ensure the channel
                            // is in the network's autojoin list. Idempotent
                            // — replay joins are no-ops because the channel
                            // is already there. Run BEFORE the interactive-
                            // join consume so that the regular self-join
                            // path still triggers the UI auto-switch.
                            val ourNick = resolveIdentity(conn.config).nick
                            if (autoAddJoinedChannels &&
                                ev is IrcEvent.Joined &&
                                ev.nick.equals(ourNick, ignoreCase = true)
                            ) {
                                setAutoJoinEntry(config.id, ev.channel,
                                    key = null, enabled = true)
                            }
                            if (autoAddJoinedChannels &&
                                ev is IrcEvent.Parted &&
                                ev.nick.equals(ourNick, ignoreCase = true)
                            ) {
                                setAutoJoinEntry(config.id, ev.channel,
                                    key = null, enabled = false)
                            }
                            if (ev is IrcEvent.Joined &&
                                ev.nick.equals(ourNick, ignoreCase = true) &&
                                conn.consumeInteractiveJoin(ev.channel)
                            ) {
                                _selfJoins.emit(config.id to ev.channel)
                            }
                            if (ev is IrcEvent.NotifyState) {
                                _notifyEvents.emit(ev)
                            }
                        }
                    }
                }
    }

    /** Map an IrcEvent to /on event types and dispatch through [onHooks].
     *  Returns true if any matching hook asked to suppress default handling. */
    data class HookOutcome(val suppress: Boolean, val quiet: Boolean)

    private fun dispatchOnHooks(config: ServerConfig, ev: IrcEvent): HookOutcome {
        val exec = onHookExecutor ?: return HookOutcome(false, false)
        val net = config.name
        val notify = onHookNotifier
        var suppress = false
        var quiet = false
        fun fire(eventName: String, vars: Map<String, String>) {
            val target = vars["target"]?.takeIf { it.isNotBlank() }
                ?: vars["channel"]?.takeIf { it.isNotBlank() }
                ?: vars["nick"]
                ?: ""
            val r = onHooks.dispatch(
                event = eventName,
                networkName = net,
                vars = vars,
                executor = { cmd -> exec(config.id, cmd, target) },
                notifier = notify,
            )
            if (r.suppress) suppress = true
            if (r.quiet) quiet = true
        }
        when (ev) {
            is IrcEvent.Connected -> fire(OnHookEvents.CONNECT, mapOf("server" to net))
            is IrcEvent.Disconnected -> fire(OnHookEvents.DISCONNECT,
                mapOf("server" to net, "reason" to (ev.reason ?: "")))
            is IrcEvent.Joined -> fire(OnHookEvents.JOIN,
                mapOf("nick" to ev.nick, "channel" to ev.channel))
            is IrcEvent.Parted -> fire(OnHookEvents.PART,
                mapOf("nick" to ev.nick, "channel" to ev.channel))
            is IrcEvent.Quit -> fire(OnHookEvents.QUIT,
                mapOf("nick" to ev.nick, "reason" to (ev.reason ?: "")))
            is IrcEvent.NickChanged -> fire(OnHookEvents.NICK,
                mapOf("oldnick" to ev.oldNick, "newnick" to ev.newNick, "nick" to ev.oldNick))
            is IrcEvent.Kicked -> fire(OnHookEvents.KICK,
                mapOf("nick" to ev.by, "channel" to ev.channel,
                      "target" to ev.target, "reason" to (ev.reason ?: "")))
            is IrcEvent.TopicChanged -> fire(OnHookEvents.TOPIC,
                mapOf("nick" to (ev.setter ?: ""), "channel" to ev.channel,
                      "text" to ev.topic, "topic" to ev.topic))
            is IrcEvent.Message -> {
                val isChan = ev.target.startsWith("#") || ev.target.startsWith("&")
                val event = when {
                    ev.isNotice -> OnHookEvents.NOTICE
                    ev.isAction -> OnHookEvents.ACTION
                    isChan -> OnHookEvents.CHANMSG
                    else -> OnHookEvents.PRIVMSG
                }
                val v = mapOf(
                    "nick" to ev.sender,
                    "channel" to (if (isChan) ev.target else ""),
                    "target" to ev.target,
                    "text" to ev.text,
                    "message" to ev.text,
                )
                fire(event, v)
            }
            is IrcEvent.NotifyState -> fire(
                if (ev.online) OnHookEvents.NOTIFY_ONLINE else OnHookEvents.NOTIFY_OFFLINE,
                mapOf("nick" to ev.nick),
            )
            else -> { /* Status, Typing, Raw, Numeric handled elsewhere */ }
        }
        return HookOutcome(suppress, quiet)
    }

    fun remove(id: String, reason: String? = null) {
        _connections.value[id]?.disconnect(reason)
        eventCollectors.remove(id)?.cancel()
        _connections.value = _connections.value - id
        _connectedIds.value = _connectedIds.value - id
        _networkNames.value = _networkNames.value - id
        _actualHosts.value = _actualHosts.value - id
        replayDrainJobs.remove(id)?.cancel()
        replayBuffer.remove(id)
    }

    fun sendMessage(serverId: String, target: String, text: String) {
        val conn = _connections.value[serverId] ?: return
        conn.sendMessage(target, text)
        store.appendOwnMessage(serverId, target, resolveIdentity(conn.config).nick, text, action = false)
    }

    fun sendAction(serverId: String, target: String, text: String) {
        val conn = _connections.value[serverId] ?: return
        conn.sendAction(target, text)
        store.appendOwnMessage(serverId, target, resolveIdentity(conn.config).nick, text, action = true)
    }

    fun nicksIn(serverId: String, channel: String): List<String> =
        _connections.value[serverId]?.nicksIn(channel) ?: emptyList()

    /** Returns the live ServerConfig for [serverId] (whether saved or stub). */
    fun configFor(serverId: String): ServerConfig? =
        _connections.value[serverId]?.config

    /** True if [serverId] has ever been explicitly dialed (not just registered). */
    fun isDialed(serverId: String): Boolean =
        _connections.value[serverId]?.everDialed ?: false

    fun sendTyping(serverId: String, target: String, state: TypingState) {
        _connections.value[serverId]?.sendTyping(target, state)
    }

    fun joinChannel(serverId: String, channel: String, key: String? = null) {
        _connections.value[serverId]?.joinChannel(channel, key)
    }

    fun partChannel(serverId: String, channel: String, reason: String? = null) {
        _connections.value[serverId]?.partChannel(channel, reason)
    }

    fun changeNick(serverId: String, nick: String) {
        _connections.value[serverId]?.changeNick(nick)
    }

    fun sendRaw(serverId: String, line: String) {
        _connections.value[serverId]?.sendRaw(line)
    }

    fun sendWhois(serverId: String, target: String, originBufferId: String) {
        router.register(serverId, "whois", target, originBufferId)
        _connections.value[serverId]?.sendWhois(target)
    }

    fun sendWhowas(serverId: String, target: String, originBufferId: String) {
        router.register(serverId, "whowas", target, originBufferId)
        _connections.value[serverId]?.sendWhowas(target)
    }

    fun sendNotice(serverId: String, target: String, text: String) {
        _connections.value[serverId]?.sendNotice(target, text)
    }
    fun sendCtcp(serverId: String, target: String, tag: String, data: String?) {
        _connections.value[serverId]?.sendCtcp(target, tag, data)
    }
    fun setAway(serverId: String, reason: String?, originBufferId: String? = null) {
        if (originBufferId != null) router.register(serverId, "away", "", originBufferId)
        _connections.value[serverId]?.setAway(reason)
    }
    fun setTopic(serverId: String, channel: String, topic: String?) {
        _connections.value[serverId]?.setTopic(channel, topic)
    }
    fun kick(serverId: String, channel: String, nick: String, reason: String?) {
        _connections.value[serverId]?.kick(channel, nick, reason)
    }
    fun mode(serverId: String, target: String, args: String, originBufferId: String? = null) {
        if (originBufferId != null) router.register(serverId, "mode", target, originBufferId)
        _connections.value[serverId]?.mode(target, args)
    }
    fun invite(serverId: String, nick: String, channel: String, originBufferId: String? = null) {
        if (originBufferId != null) router.register(serverId, "invite", nick, originBufferId)
        _connections.value[serverId]?.invite(nick, channel)
    }
    fun names(serverId: String, channel: String) {
        _connections.value[serverId]?.names(channel)
    }

    fun sendList(serverId: String, args: String) {
        // Reset the per-server channel list and mark as loading; populated as
        // 321/322/323 numerics arrive.
        _channelLists.value = _channelLists.value +
            (serverId to ChannelListState(emptyList(), loading = true))
        val line = if (args.isBlank()) "LIST" else "LIST $args"
        _connections.value[serverId]?.sendRaw(line)
    }

    fun shutdownAll() {
        _connections.value.values.forEach { it.disconnect() }
        _connections.value = emptyMap()
    }

    /**
     * Numeric router. Handles WHOIS responses for now; broader numerics
     * (NAMES, TOPIC, MOTD, error replies) will be added in batch B.
     */
    /**
     * Compose and send a reply to a CTCP query, honoring the user's
     * configured overrides. The reply is addressed back to [ev.sender]
     * regardless of whether the original CTCP was sent to a channel or to
     * us directly — that's the convention every other client follows so
     * channel CTCP probes don't generate replies to the channel itself.
     *
     * Sentinels (per setting): "" = built-in default, "off" = ignore
     * silently, "utc" (TIME only) = format current time as UTC.
     */
    private fun handleCtcpQuery(
        config: ServerConfig,
        conn: IrcConnection,
        ev: IrcEvent.CtcpQuery,
    ) {
        val cmd = ev.command.uppercase()
        val replyTarget = ev.sender
        val identity = resolveIdentity(config)
        val response: String? = when (cmd) {
            "VERSION" -> when (ctcpVersion) {
                "off" -> null
                "" -> "Pocket IRC ${BuildConfig.VERSION_NAME} - https://github.com/inhahe/pocketirc"
                else -> ctcpVersion
            }
            "TIME" -> when (ctcpTime) {
                "off" -> null
                "" -> {
                    // Local timezone, ISO-ish — same shape as most clients.
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss zzz")
                        .format(java.util.Date())
                }
                "utc" -> {
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'")
                    fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    fmt.format(java.util.Date())
                }
                else -> ctcpTime
            }
            "FINGER" -> when (ctcpFinger) {
                "off" -> null
                "" -> identity.realName
                else -> ctcpFinger
            }
            "USERINFO" -> when (ctcpUserinfo) {
                "off" -> null
                "" -> identity.realName
                else -> ctcpUserinfo
            }
            "SOURCE" -> when (ctcpSource) {
                "off" -> null
                "" -> "https://github.com/inhahe/pocketirc"
                else -> ctcpSource
            }
            "CLIENTINFO" -> "VERSION TIME FINGER USERINFO SOURCE CLIENTINFO PING ACTION"
            else -> null
        }
        if (response != null) {
            conn.sendCtcpReply(replyTarget, cmd, response)
        }
    }

    private fun handleNumeric(serverId: String, ev: IrcEvent.Numeric) {
        val params = ev.parameters
        // First handle "permanent failure" numerics — server signals that
        // mean re-trying with the same config will get the same result.
        // We mark the connection so its onDisconnected handler skips the
        // auto-reconnect cycle. The user has to fix something and manually
        // /reconnect.
        val permanentReason: String? = when (ev.numeric) {
            464 -> "wrong server password (PASS rejected)"
            465 -> "you are banned from this server" +
                (params.lastOrNull()?.let { ": $it" } ?: "")
            466 -> "the server has warned that you will be banned"
            432 -> "nickname is invalid (ERR_ERRONEUSNICKNAME)"
            463 -> "your host is not allowed to connect"
            904 -> "SASL authentication failed" +
                (params.lastOrNull()?.let { ": $it" } ?: "")
            906 -> "SASL aborted by the server"
            else -> null
        }
        if (permanentReason != null) {
            _connections.value[serverId]?.markPermanentFailure(permanentReason)
            store.appendSystemTo(serverId, "$serverId::(status)",
                "Permanent failure: $permanentReason. Auto-reconnect will be disabled.")
        }
        // 433 ERR_NICKNAMEINUSE during pre-registration: walk the nick
        // fallback chain. After registration is complete (we've received
        // 001), KICL handles its own nick collisions and we leave it alone.
        if (ev.numeric == 433 && serverId !in _connectedIds.value) {
            val conn = _connections.value[serverId]
            val triedNext = conn?.tryNextNick() ?: false
            if (triedNext) {
                store.appendSystemTo(serverId, "$serverId::(status)",
                    "Nick already in use; trying next…")
            } else {
                store.appendSystemTo(serverId, "$serverId::(status)",
                    "Nick already in use and the fallback chain is exhausted.")
            }
        }
        when (ev.numeric) {
            // RPL_WELCOME 001 — first numeric after registration. The source
            // is the actual server we landed on, which is what we want to
            // show as "actually connected to" (DNS round-robin transparency).
            1 -> {
                if (ev.source.isNotBlank()) {
                    setActualHost(serverId, ev.source)
                    val configured = _connections.value[serverId]
                        ?.config?.endpoints?.firstOrNull()?.host
                    val msg = if (configured != null && configured != ev.source)
                        "Connected to ${ev.source} (configured: $configured)"
                    else "Connected to ${ev.source}"
                    store.appendSystemTo(serverId, "$serverId::(status)", msg)
                }
            }
            // RPL_BOUNCE 010 — :server 010 nick try.host port :Try this server
            // The server is telling us to redial somewhere else.
            10 -> {
                if (params.size >= 2) {
                    val newHost = params[1]
                    setActualHost(serverId, newHost)
                    store.appendSystemTo(serverId, "$serverId::(status)",
                        "Server bounced us to $newHost. (The original entrypoint is preserved; /server with no args goes back to it.)")
                }
            }
            // RPL_ISUPPORT 005 — feature/capability tokens. Walk the params
            // looking for NETWORK=<name> so the tree can show "Libera" instead
            // of "irc.libera.chat" for /server-created (unlocked) entries.
            5 -> {
                for (p in params) {
                    if (p.startsWith("NETWORK=", ignoreCase = true)) {
                        val value = p.substringAfter('=').trim()
                        if (value.isNotEmpty()) {
                            _networkNames.value = _networkNames.value + (serverId to value)
                        }
                        break
                    }
                }
            }
            // MOTD: 372 :- text  /  375 :- start  /  376 :- end
            372, 375, 376, 422 ->
                store.appendSystemTo(serverId, null, params.lastOrNull() ?: "")

            // RPL_NOTOPIC 331 <chan> :No topic is set
            331 -> if (params.size >= 2) {
                val chan = params[1]
                store.setTopic(serverId, chan, "")
                store.appendSystemTo(serverId, "$serverId::$chan", "No topic is set.")
            }
            // RPL_TOPIC 332 <chan> :topic
            332 -> if (params.size >= 3) {
                val chan = params[1]
                store.setTopic(serverId, chan, params[2])
                store.appendSystemTo(serverId, "$serverId::$chan", "Topic: ${params[2]}")
            }
            // RPL_TOPICWHOTIME 333 <chan> <nick> <ts>
            333 -> if (params.size >= 4) {
                val chan = params[1]
                store.appendSystemTo(serverId, "$serverId::$chan",
                    "Topic set by ${params[2]}")
            }
            // ERR_CHANOPRIVSNEEDED 482 <chan> :You're not channel operator
            482 -> if (params.size >= 3) {
                val chan = params[1]
                store.appendSystemTo(serverId, "$serverId::$chan",
                    "${params[2]}")
            }
            // NAMES reply 353 = <chan> :nick1 nick2 ... and end-of-names 366
            353 -> if (params.size >= 4) {
                val chan = params[2]
                store.appendSystemTo(serverId, "$serverId::$chan",
                    "Users: ${params[3]}")
            }
            366 -> { /* end of names; KICL has the list now, no UI noise */ }

            // RPL_WHOISUSER  311 <nick> <user> <host> * :<realname>
            311 -> {
                if (params.size >= 6) {
                    val nick = params[1]
                    val text = "${nick} (${params[2]}@${params[3]}): ${params[5]}"
                    routeWhois(serverId, nick, text)
                }
            }
            // RPL_WHOISSERVER 312 <nick> <server> :<info>
            312 -> if (params.size >= 4) routeWhois(serverId, params[1],
                "${params[1]} on ${params[2]} (${params[3]})")
            // RPL_WHOISOPERATOR 313
            313 -> if (params.size >= 3) routeWhois(serverId, params[1],
                "${params[1]} ${params[2]}")
            // RPL_WHOISIDLE 317 <nick> <idle> [<signon>] :seconds idle...
            317 -> if (params.size >= 3) routeWhois(serverId, params[1],
                "${params[1]} idle ${params[2]}s")
            // RPL_WHOISCHANNELS 319 <nick> :<channels>
            319 -> if (params.size >= 3) routeWhois(serverId, params[1],
                "${params[1]} on: ${params[2]}")
            // RPL_WHOISACCOUNT 330 <nick> <account> :is logged in as
            330 -> if (params.size >= 4) routeWhois(serverId, params[1],
                "${params[1]} logged in as ${params[2]}")
            // RPL_WHOISSECURE 671 <nick> :is using a secure connection
            671 -> if (params.size >= 3) routeWhois(serverId, params[1],
                "${params[1]} ${params[2]}")
            // RPL_ENDOFWHOIS 318 <nick> :End of /WHOIS list.
            318 -> if (params.size >= 2) {
                val nick = params[1]
                routeWhois(serverId, nick, "End of /WHOIS for $nick.")
                router.clear(serverId, "whois", nick)
            }

            // ----- WHOWAS replies
            // 314 RPL_WHOWASUSER <client> <nick> <user> <host> * :<realname>
            314 -> if (params.size >= 6) {
                val nick = params[1]
                routeKind(serverId, "whowas", nick,
                    "${nick} (${params[2]}@${params[3]}): ${params[5]}")
            }
            // 369 RPL_ENDOFWHOWAS
            369 -> if (params.size >= 2) {
                val nick = params[1]
                routeKind(serverId, "whowas", nick, "End of /WHOWAS for $nick.")
                router.clear(serverId, "whowas", nick)
            }
            // 406 ERR_WASNOSUCHNICK
            406 -> if (params.size >= 3) {
                val nick = params[1]
                routeKind(serverId, "whowas", nick, "${nick}: ${params[2]}")
                router.clear(serverId, "whowas", nick)
            }

            // ----- MODE replies (route by channel/nick to the buffer where /mode ran)
            // 324 RPL_CHANNELMODEIS <client> <chan> <modes> [args]
            324 -> if (params.size >= 3) {
                val target = params[1]
                val modesField = params[2]  // e.g. "+nt" or "+nkl"
                val modeArgs = params.drop(3)
                routeKind(serverId, "mode", target,
                    "Modes for $target: ${params.drop(2).joinToString(" ")}")
                router.clear(serverId, "mode", target)
                // Also update channel info state if a dialog is interested.
                if (target.startsWith("#") || target.startsWith("&")) {
                    val cleaned = modesField.removePrefix("+")
                    var argIdx = 0
                    var key: String? = null
                    var limit: Int? = null
                    for (ch in cleaned) {
                        when (ch) {
                            'k' -> if (argIdx < modeArgs.size) key = modeArgs[argIdx++]
                            'l' -> if (argIdx < modeArgs.size)
                                limit = modeArgs[argIdx++].toIntOrNull()
                        }
                    }
                    updateChannelInfo(serverId, target) {
                        it.copy(modes = cleaned, key = key, userLimit = limit)
                    }
                }
            }
            // 329 RPL_CREATIONTIME <client> <chan> <ts>
            329 -> if (params.size >= 3) {
                val target = params[1]
                routeKind(serverId, "mode", target, "Channel $target created at ${params[2]}")
            }
            // 367 RPL_BANLIST <client> <chan> <mask> [setter] [ts]
            367 -> if (params.size >= 3) {
                val target = params[1]
                val entry = MaskEntry(
                    mask = params[2],
                    setter = params.getOrNull(3),
                    timestamp = params.getOrNull(4)?.toLongOrNull(),
                )
                updateChannelInfo(serverId, target) { it.copy(bans = it.bans + entry) }
            }
            // 368 RPL_ENDOFBANLIST
            368 -> if (params.size >= 2) {
                val target = params[1]
                updateChannelInfo(serverId, target) { it.copy(bansLoading = false) }
            }
            // 348 RPL_EXCEPTLIST <client> <chan> <mask> [setter] [ts]
            348 -> if (params.size >= 3) {
                val target = params[1]
                val entry = MaskEntry(
                    mask = params[2],
                    setter = params.getOrNull(3),
                    timestamp = params.getOrNull(4)?.toLongOrNull(),
                )
                updateChannelInfo(serverId, target) { it.copy(excepts = it.excepts + entry) }
            }
            // 349 RPL_ENDOFEXCEPTLIST
            349 -> if (params.size >= 2) {
                val target = params[1]
                updateChannelInfo(serverId, target) { it.copy(exceptsLoading = false) }
            }
            // 346 RPL_INVITELIST <client> <chan> <mask> [setter] [ts]
            346 -> if (params.size >= 3) {
                val target = params[1]
                val entry = MaskEntry(
                    mask = params[2],
                    setter = params.getOrNull(3),
                    timestamp = params.getOrNull(4)?.toLongOrNull(),
                )
                updateChannelInfo(serverId, target) { it.copy(invites = it.invites + entry) }
            }
            // 347 RPL_ENDOFINVITELIST
            347 -> if (params.size >= 2) {
                val target = params[1]
                updateChannelInfo(serverId, target) { it.copy(invitesLoading = false) }
            }
            // 728 RPL_QUIETLIST <client> <chan> q <mask> [setter] [ts]
            728 -> if (params.size >= 4) {
                val target = params[1]
                val entry = MaskEntry(
                    mask = params[3],
                    setter = params.getOrNull(4),
                    timestamp = params.getOrNull(5)?.toLongOrNull(),
                )
                updateChannelInfo(serverId, target) { it.copy(quiets = it.quiets + entry) }
            }
            // 729 RPL_ENDOFQUIETLIST
            729 -> if (params.size >= 2) {
                val target = params[1]
                updateChannelInfo(serverId, target) { it.copy(quietsLoading = false) }
            }

            // ----- INVITE replies
            // 341 RPL_INVITING <client> <nick> <chan>
            341 -> if (params.size >= 3) {
                val nick = params[1]
                routeKind(serverId, "invite", nick, "Invited ${nick} to ${params[2]}.")
                router.clear(serverId, "invite", nick)
            }
            // 443 ERR_USERONCHANNEL <client> <nick> <chan> :is already on channel
            443 -> if (params.size >= 4) {
                val nick = params[1]
                routeKind(serverId, "invite", nick,
                    "${nick} is already on ${params[2]}.")
                router.clear(serverId, "invite", nick)
            }

            // ----- LIST replies — populate the channel list browser state.
            321 -> updateChannelList(serverId) { it.copy(loading = true) }
            // 322 RPL_LIST <client> <channel> <#users> :<topic>
            322 -> if (params.size >= 4) {
                val entry = ChannelListEntry(
                    name = params[1],
                    users = params[2].toIntOrNull() ?: 0,
                    topic = params[3],
                )
                updateChannelList(serverId) { it.copy(entries = it.entries + entry) }
            }
            323 -> updateChannelList(serverId) { it.copy(loading = false) }

            // ----- ISON / MONITOR replies (notify list)
            // 303 RPL_ISON <client> :nick1 nick2 ...
            303 -> if (params.size >= 2) {
                val online = params[1].split(' ').filter { it.isNotBlank() }.toSet()
                _connections.value[serverId]?.applyOnlineSet(online)
            }
            // 730 RPL_MONONLINE <client> :target1!user!host,target2!user!host,...
            730 -> if (params.size >= 2) {
                val nicks = params[1].split(',')
                    .map { it.substringBefore('!') }
                    .filter { it.isNotBlank() }
                _connections.value[serverId]?.markNotifyOnline(nicks)
            }
            // 731 RPL_MONOFFLINE
            731 -> if (params.size >= 2) {
                val nicks = params[1].split(',')
                    .map { it.substringBefore('!') }
                    .filter { it.isNotBlank() }
                _connections.value[serverId]?.markNotifyOffline(nicks)
            }
            // 732 RPL_MONLIST / 733 RPL_ENDOFMONLIST — silent
            732, 733 -> Unit
            // 734 ERR_MONLISTFULL <client> <limit> <targets> :Monitor list is full.
            734 -> if (params.size >= 4) {
                store.appendSystemTo(serverId, null,
                    "MONITOR list full (limit ${params[1]}); ${params[2]} could not be added.")
            }

            // ----- AWAY replies
            // 305 RPL_UNAWAY :You are no longer marked as away
            305 -> {
                routeKind(serverId, "away", "", "You are no longer marked as away.")
                router.clear(serverId, "away", "")
            }
            // 306 RPL_NOWAWAY :You have been marked as away
            306 -> {
                routeKind(serverId, "away", "", "You have been marked as away.")
                router.clear(serverId, "away", "")
            }

            else -> {
                // 4xx errors are useful to see; suppress chatty bookkeeping numerics.
                if (ev.numeric in 400..599 || ev.numeric in 200..299) {
                    val text = "[${ev.numeric}] ${params.drop(1).joinToString(" ")}"
                    // Try to route the message to the buffer that's the subject of
                    // the error: if the first parameter (after the client name) is
                    // a known channel or query buffer, use that. Otherwise status.
                    val candidate = params.getOrNull(1)
                    val routed = candidate?.let { name ->
                        val id = "$serverId::$name"
                        if (store.buffers.value.containsKey(id)) id else null
                    }
                    store.appendSystemTo(serverId, routed, text)
                }
            }
        }
    }

    private fun routeWhois(serverId: String, nick: String, text: String) {
        val bufferId = router.lookup(serverId, "whois", nick)
        store.appendSystemTo(serverId, bufferId, text)
    }

    private fun routeKind(serverId: String, kind: String, target: String, text: String) {
        val bufferId = router.lookup(serverId, kind, target)
        store.appendSystemTo(serverId, bufferId, text)
    }
}
