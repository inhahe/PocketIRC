package com.pocketirc.app.irc

import com.pocketirc.app.model.ServerConfig
import com.pocketirc.app.model.ServerEndpoint
import com.pocketirc.app.model.TypingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.feature.auth.SaslPlain

/**
 * One IRC network. Owns the current Kitteh [Client], cycles through the
 * configured [ServerEndpoint]s on connect failure, and auto-reconnects with
 * exponential backoff until [disconnect] is called.
 */
class IrcConnection(
    initialConfig: ServerConfig,
    /**
     * Resolves the effective identity (primary nick, alt-nick chain, real
     * name) for this connection at the moment of use, folding in whatever
     * the global defaults currently are. Receives the *current* (possibly
     * mutated) [config] each call so identity-affecting edits like a
     * per-network nick override are picked up without rebuilding the
     * connection. Supplied by [ConnectionManager] so IrcConnection doesn't
     * need to know about AppSettings or DataStore.
     */
    private val identityResolver: (ServerConfig) -> EffectiveIdentity,
) {
    /**
     * The connection's current ServerConfig. Mutable so that edits which
     * don't require a reconnect (autojoin list updates, notify list
     * tweaks, identity overrides that take effect on the next reconnect)
     * can be applied in place. Updated only via [updateConfig].
     */
    var config: ServerConfig = initialConfig
        private set

    /**
     * Apply [transform] to the current [config]. Used by ConnectionManager
     * for in-place edits that shouldn't tear down the live socket — most
     * notably autojoin list mutations triggered by /join and /part when
     * "auto-add joined channels" is enabled.
     */
    fun updateConfig(transform: (ServerConfig) -> ServerConfig) {
        config = transform(config)
    }


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sink = Channel<IrcEvent>(capacity = 256)

    private val _events = MutableSharedFlow<IrcEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<IrcEvent> = _events

    private var client: Client? = null

    /**
     * The listener registered on [client]. Kept so the client can be retired
     * (silenced) before shutdown — see [retireClient].
     */
    private var clientListener: KitchenSinkListener? = null

    /**
     * Silence and shut down the current client, if any.
     *
     * Order matters: the listener is deactivated BEFORE shutdown, because
     * KICL fires a connection-ended event as part of shutting down. If that
     * reached the sink it would look like an unexpected disconnect and
     * schedule a competing reconnect.
     */
    private fun retireClient(reason: String) {
        val old = client ?: return
        clientListener?.active = false
        clientListener = null
        client = null
        runCatching { old.shutdown(reason) }
    }
    @Volatile var endpointIndex: Int = 0
        private set
    private var attempt: Int = 0
    @Volatile private var quitRequested: Boolean = false
    private var reconnectJob: Job? = null
    /** Monotonic: set to true the first time [connect] is called, never reset.
     *  Lets ConnectionManager distinguish "registered but never dialed" stubs
     *  from servers the user actually tried to connect to. */
    @Volatile var everDialed: Boolean = false
        private set
    /** The hostname of the server we're actually talking to right now. May
     *  differ from `config.endpoints[endpointIndex].host` after a 010
     *  RPL_BOUNCE redirect or after DNS round-robin lands us on a specific
     *  member of a load-balanced entrypoint. Read-only from outside. */
    @Volatile var actualHost: String? = null

    /**
     * Set when we receive a server numeric that indicates the connection
     * cannot succeed without user intervention (wrong password, K-line,
     * SASL failure, etc.). When non-null, the auto-reconnect path bails
     * out instead of pestering the server. Cleared by an explicit user
     * [connect] call.
     */
    @Volatile var permanentFailureReason: String? = null
        private set

    /** When the most recent connection successfully reached IrcEvent.Connected.
     *  Used to decide whether to reset the backoff counter on disconnect:
     *  a connection that died within 60 seconds is treated as a continuation
     *  of the same outage rather than a fresh attempt. */
    @Volatile private var lastConnectedAt: Long = 0L

    /** Called by [ConnectionManager.handleNumeric] when a "permanent failure"
     *  numeric arrives. The reason is shown in the (status) buffer when the
     *  connection drops, and auto-reconnect is suppressed. */
    fun markPermanentFailure(reason: String) {
        permanentFailureReason = reason
    }

    /**
     * Index into the nick fallback chain for the *current* registration
     * attempt. Reset whenever a fresh connection starts. The chain is
     * `[id.nick] + id.altNicks + [id.nick + "_", id.nick + "__", ...]`
     * up to [MAX_NICK_FALLBACK_LEVEL] underscore suffixes after the
     * altNicks list is exhausted.
     */
    @Volatile private var nickFallbackIndex: Int = 0

    /**
     * Called by [ConnectionManager.handleNumeric] on 433 ERR_NICKNAMEINUSE
     * before registration completes. Picks the next nick candidate and
     * sends a `NICK` line. Returns true if a candidate was sent, false if
     * the chain is exhausted (in which case registration will fail and the
     * connection will drop into the normal reconnect path).
     */
    fun tryNextNick(): Boolean {
        nickFallbackIndex += 1
        val candidate = nickCandidateAt(nickFallbackIndex) ?: return false
        client?.sendRawLine("NICK $candidate")
        return true
    }

    /** Resolve the candidate at [index] (0 = primary nick). */
    private fun nickCandidateAt(index: Int): String? {
        val id = identityResolver(config)
        if (index < id.nicks.size) return id.nicks[index]
        val underscores = index - id.nicks.size + 1
        if (underscores > MAX_NICK_FALLBACK_LEVEL) return null
        return id.nick + "_".repeat(underscores)
    }

    /** Lower-cased nicks currently believed online (notify list). */
    private val notifyOnline = mutableSetOf<String>()

    /**
     * Lower-cased channel names that were joined via an explicit user action
     * (e.g. /join), not via the auto-join list. The UI auto-switches to these
     * when the server's JOIN echo arrives; auto-joined channels are silent.
     */
    private val interactiveJoins = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Remembered keys for channels the user has /joined with a key during
     * this session, lower-cased channel name → key. Populated by
     * [joinChannel] whenever a non-blank key is supplied. Used by the
     * autojoin feature so that adding a channel to the autojoin list (via
     * any of the three trigger paths — auto-add hook, per-channel toggle,
     * retroactive flip) preserves the key the user originally typed,
     * making behavior consistent across keyed and unkeyed channels.
     *
     * Cleared on disconnect/reconnect — after a reconnect, the user has
     * to /join with the key again if they want it remembered. The
     * persisted [com.pocketirc.app.model.AutoJoinChannel.key] is the
     * permanent record once auto-add fires.
     */
    private val rememberedKeys = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())

    /** The remembered key for [channel], or null if none was entered this session. */
    fun rememberedKeyFor(channel: String): String? =
        rememberedKeys[channel.lowercase()]
    /** True once we've confirmed the server supports MONITOR via ISUPPORT. */
    @Volatile private var monitorSupported: Boolean = false
    private var isonJob: Job? = null

    init {
        scope.launch { for (event in sink) _events.emit(event) }
        // Bridge our own disconnect events into reconnect logic.
        scope.launch {
            events.collect { ev ->
                if (ev is IrcEvent.Disconnected) onDisconnected()
                if (ev is IrcEvent.Connected) {
                    // Stamp the connection time but don't reset the attempt
                    // counter yet — wait until onDisconnected to see whether
                    // the connection lasted long enough to count as "stable."
                    // This prevents connect-success-then-fail loops from
                    // resetting the backoff and storming the server.
                    lastConnectedAt = System.currentTimeMillis()
                    config.autoJoin.forEach { entry ->
                        val c = client ?: return@forEach
                        if (entry.key.isNullOrBlank()) c.addChannel(entry.name)
                        else c.sendRawLine("JOIN ${entry.name} ${entry.key}")
                    }
                    setupNotify()
                }
            }
        }
    }

    fun connect(startEndpointIndex: Int = 0) {
        everDialed = true
        quitRequested = false
        attempt = 0
        // A user-initiated connect overrides any prior permanent-failure flag
        // (e.g. the user fixed their password and wants to try again).
        permanentFailureReason = null
        // Restart the nick fallback chain from the primary nick.
        nickFallbackIndex = 0
        // Start from index 0 by default (the original entrypoint), or from
        // an explicit index when called by /nextserver / /failover. The
        // important property: a fresh user-initiated connect always starts
        // wherever the caller asks, never inherits a stale endpointIndex.
        endpointIndex = startEndpointIndex.coerceIn(0, (config.endpoints.size - 1).coerceAtLeast(0))
        startCurrent()
    }

    private fun startCurrent() {
        if (config.endpoints.isEmpty()) {
            sink.trySend(IrcEvent.Disconnected(config.id, "No endpoints configured"))
            return
        }
        // Retire any previous client before dialing a new one. Every Client
        // carries its own KitchenSinkListener registered against the shared
        // [sink], so an orphaned-but-still-connected Client keeps delivering a
        // full duplicate copy of every message. Any path that dials while a
        // client is live (/nextserver, /failover, a manual /connect, a
        // reconnect that races a late-arriving Disconnected event) would
        // otherwise leak one.
        retireClient("Reconnecting")
        val ep = config.endpoints[endpointIndex % config.endpoints.size]
        sink.trySend(
            IrcEvent.Status(
                config.id,
                "Connecting to ${ep.host}:${ep.port}${if (ep.tls) " (TLS)" else ""}…",
            )
        )
        try {
            val securityType = if (ep.tls)
                Client.Builder.Server.SecurityType.SECURE
            else
                Client.Builder.Server.SecurityType.INSECURE

            // Honor IPv4/IPv6 preference. The endpoint can override per-family,
            // but if it leaves a family unset (null), inherit the network's
            // default. If exactly one family is enabled, do a manual lookup
            // and pass an IP literal of that family to KICL. If neither is
            // enabled (only possible from a stale config — the editor blocks
            // it), fall back to "both" rather than making the server
            // unreachable.
            val effectiveV4 = ep.ipv4 ?: config.defaultIpv4
            val effectiveV6 = ep.ipv6 ?: config.defaultIpv6
            val allowV4 = effectiveV4 || (!effectiveV4 && !effectiveV6)
            val allowV6 = effectiveV6 || (!effectiveV4 && !effectiveV6)
            if (!effectiveV4 && !effectiveV6) {
                sink.trySend(IrcEvent.Status(config.id,
                    "${ep.host}: both IPv4 and IPv6 were disabled; ignoring and using both."))
            }
            var pinned = false
            val hostForKicl: String = if (allowV4 != allowV6) {
                try {
                    val all = java.net.InetAddress.getAllByName(ep.host)
                    val want = if (allowV4) java.net.Inet4Address::class.java
                               else java.net.Inet6Address::class.java
                    val match = all.firstOrNull { want.isInstance(it) }
                    if (match == null) {
                        sink.trySend(IrcEvent.Status(config.id,
                            "No ${if (allowV4) "IPv4" else "IPv6"} address found for ${ep.host}"))
                        ep.host
                    } else {
                        pinned = true
                        match.hostAddress ?: ep.host
                    }
                } catch (t: Throwable) {
                    sink.trySend(IrcEvent.Status(config.id,
                        "DNS lookup failed for ${ep.host}: ${t.message}"))
                    ep.host
                }
            } else ep.host

            val id = identityResolver(config)
            val c: Client = Client.builder()
                .nick(id.nick)
                .realName(id.realName)
                .also { b -> if (id.userName.isNotBlank()) b.user(id.userName) }
                .server()
                    .host(hostForKicl)
                    .port(ep.port, securityType)
                    .also { sb ->
                        config.serverPassword?.takeIf { it.isNotBlank() }?.let { sb.password(it) }
                        // When we pinned to an IP literal AND the connection is
                        // TLS, install a TrustManagerFactory that validates the
                        // peer cert against the original hostname rather than
                        // the IP we connected to. Without this, hostname
                        // verification would fail because no cert names an IP.
                        if (pinned && ep.tls) {
                            sb.secureTrustManagerFactory(SniSpoofTrustManagerFactory(ep.host))
                        }
                    }
                    .then()
                // Custom token-bucket outbound queue: 8 messages burst, then
                // 1 message every 400ms sustained. KICL's default 1200ms-per-msg
                // pacing means /join issued right after connect could sit behind
                // 8-10 queued CAP/NICK/USER/SASL messages and take 10+ seconds.
                // The token bucket lets normal interactive use feel instant
                // while still avoiding flood-kill on networks like Libera.
                .management()
                    .messageSendingQueueSupplier(TokenBucketSender.supplier(
                        burst = config.outBurst,
                        refillIntervalMs = config.outRefillMs.toLong(),
                    ))
                    .then()
                .build()

            // Disable KICL's automatic WHO queries. KICL sends WHO per
            // channel every 5 s until a full reply arrives; on networks that
            // rate-limit WHO this creates an infinite flood. PocketIRC only
            // needs nick names (populated by NAMES/353 on join) — not the
            // hostmask/account data that WHO provides.
            (c as? Client.WithManagement)?.actorTracker
                ?.setQueryChannelInformation(false)

            config.sasl?.let { sasl ->
                c.authManager.addProtocol(SaslPlain(c, sasl.username, sasl.password))
            }
            val listener = KitchenSinkListener(config.id, sink)
            c.eventManager.registerEventListener(listener)
            // KICL auto-negotiates IRCv3 message-tags if the server advertises
            // it during CAP LS, so no explicit request is needed here.
            c.connect()
            client = c
            clientListener = listener
        } catch (t: Throwable) {
            sink.trySend(IrcEvent.Disconnected(config.id, t.message ?: t.javaClass.simpleName))
        }
    }

    private fun onDisconnected() {
        if (quitRequested) return
        if (!config.autoReconnect) {
            sink.trySend(IrcEvent.Status(config.id, "Auto-reconnect disabled; staying offline."))
            return
        }
        // If a permanent-failure numeric arrived during this session, don't
        // pester the server. The user has to fix something and manually
        // /reconnect (or /server -p ... to update the saved config).
        permanentFailureReason?.let { reason ->
            sink.trySend(IrcEvent.Status(config.id,
                "Auto-reconnect disabled: $reason. Use /reconnect after fixing the issue."))
            return
        }
        if (config.reconnectMaxAttempts >= 0 && attempt >= config.reconnectMaxAttempts) {
            sink.trySend(IrcEvent.Status(config.id,
                "Reached max reconnect attempts (${config.reconnectMaxAttempts}); giving up."))
            return
        }
        // If the connection lasted long enough to count as "stable," reset
        // the backoff counter. Otherwise treat this as a continuation of an
        // ongoing outage and keep escalating.
        val now = System.currentTimeMillis()
        if (lastConnectedAt > 0 && now - lastConnectedAt > STABLE_THRESHOLD_MS) {
            attempt = 0
        }
        lastConnectedAt = 0L
        // Move to next endpoint on every failure so we cycle through them.
        endpointIndex = (endpointIndex + 1) % config.endpoints.size.coerceAtLeast(1)
        attempt += 1
        val delayMs = backoffMs(attempt)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!quitRequested) startCurrent()
        }
    }

    /**
     * Server-friendly exponential backoff:
     *   attempt 1 → 5s
     *   attempt 2 → 10s
     *   attempt 3 → 20s
     *   attempt 4 → 40s
     *   attempt 5 → 80s
     *   attempt 6 → 160s
     *   attempt 7+ → 300s (5-minute cap)
     * Plus ±25% random jitter to avoid synchronized reconnect storms across
     * NAT'd clients sharing the same external IP. The longer cap keeps us
     * well under any IRC network's connection-throttle threshold.
     */
    private fun backoffMs(attempt: Int): Long {
        val base = 5_000L * (1L shl (attempt - 1).coerceIn(0, 6))
        val capped = base.coerceAtMost(300_000L)
        // ±25% jitter
        val jitter = (capped * 0.25 * (Math.random() * 2 - 1)).toLong()
        return (capped + jitter).coerceAtLeast(1_000L)
    }

    private companion object {
        /** A connection that lasts at least this long counts as "stable" and
         *  resets the reconnect backoff counter on the next disconnect. */
        const val STABLE_THRESHOLD_MS = 60_000L
        /** Hard cap on how many trailing underscores we'll try after the
         *  configured altnicks list is exhausted. */
        const val MAX_NICK_FALLBACK_LEVEL = 5
    }

    fun sendMessage(target: String, text: String) {
        client?.sendMessage(target, text)
    }

    fun sendTyping(target: String, state: TypingState) {
        val c = client ?: return
        // Only send TAGMSG when message-tags is actually negotiated. Without it,
        // servers (especially +R/+m channels on Libera) treat the line as a
        // PRIVMSG attempt and bounce 415 errors back at us on every keystroke.
        val tagsEnabled = try {
            c.capabilityManager.capabilities.any {
                it.name.equals("message-tags", ignoreCase = true)
            }
        } catch (_: Throwable) { false }
        if (!tagsEnabled) return
        val value = when (state) {
            TypingState.ACTIVE -> "active"
            TypingState.PAUSED -> "paused"
            TypingState.DONE -> "done"
        }
        c.sendRawLine("@+typing=$value TAGMSG $target")
    }

    fun joinChannel(channel: String, key: String? = null) {
        // Mark this as an interactive join so the UI auto-switches to the
        // channel when the JOIN echo arrives. Auto-join entries don't go through
        // this method, so they remain silent.
        interactiveJoins.add(channel.lowercase())
        // Remember any explicit key so the autojoin feature can reuse it
        // later, even if auto-add isn't on yet at this exact moment.
        if (!key.isNullOrBlank()) rememberedKeys[channel.lowercase()] = key
        if (key.isNullOrBlank()) client?.addChannel(channel)
        else client?.sendRawLine("JOIN $channel $key")
    }

    /** Returns true (and clears the entry) if [channel] was joined interactively. */
    fun consumeInteractiveJoin(channel: String): Boolean =
        interactiveJoins.remove(channel.lowercase())
    fun partChannel(channel: String, reason: String? = null) {
        client?.removeChannel(channel, reason ?: "")
    }

    fun changeNick(newNick: String) { client?.setNick(newNick) }
    fun sendRaw(line: String) { client?.sendRawLine(line) }
    fun sendWhois(target: String) { client?.sendRawLine("WHOIS $target") }
    fun sendWhowas(target: String) { client?.sendRawLine("WHOWAS $target") }
    fun sendAction(target: String, text: String) { client?.sendCtcpMessage(target, "ACTION $text") }
    fun sendNotice(target: String, text: String) { client?.sendRawLine("NOTICE $target :$text") }
    fun sendCtcp(target: String, tag: String, data: String?) {
        val payload = if (data.isNullOrBlank()) tag else "$tag $data"
        client?.sendCtcpMessage(target, payload)
    }
    /**
     * Reply to an inbound CTCP query. KICL wraps this in a NOTICE with the
     * \x01...\x01 delimiters automatically.
     */
    fun sendCtcpReply(target: String, command: String, response: String) {
        val payload = if (response.isEmpty()) command else "$command $response"
        client?.sendCtcpReply(target, payload)
    }
    fun setAway(reason: String?) {
        if (reason.isNullOrBlank()) client?.sendRawLine("AWAY")
        else client?.sendRawLine("AWAY :$reason")
    }
    fun setTopic(channel: String, topic: String?) {
        if (topic == null) client?.sendRawLine("TOPIC $channel")
        else client?.sendRawLine("TOPIC $channel :$topic")
    }
    fun kick(channel: String, nick: String, reason: String?) {
        if (reason.isNullOrBlank()) client?.sendRawLine("KICK $channel $nick")
        else client?.sendRawLine("KICK $channel $nick :$reason")
    }
    fun mode(target: String, args: String) {
        if (args.isBlank()) client?.sendRawLine("MODE $target")
        else client?.sendRawLine("MODE $target $args")
    }
    fun invite(nick: String, channel: String) { client?.sendRawLine("INVITE $nick $channel") }
    fun names(channel: String) { client?.sendRawLine("NAMES $channel") }

    /** Returns the current nick list for [channel] from KICL's tracked state, or empty if unknown. */
    fun nicksIn(channel: String): List<String> {
        val c = client ?: return emptyList()
        return c.getChannel(channel).orElse(null)?.nicknames?.toList() ?: emptyList()
    }

    /**
     * Returns the names of channels we are currently joined to according
     * to KICL's tracked state. Differs from "channel buffers in the UI"
     * because the user may keep a buffer around after /part-ing — those
     * are not in this list. Used by the auto-add-joined-channels feature
     * to take a snapshot when the user flips the global setting on.
     */
    fun joinedChannelNames(): List<String> {
        val c = client ?: return emptyList()
        return runCatching { c.channels.map { it.name } }.getOrDefault(emptyList())
    }

    fun disconnect(reason: String? = null) {
        quitRequested = true
        reconnectJob?.cancel()
        isonJob?.cancel()
        retireClient(reason ?: "Pocket IRC")
    }

    /**
     * Configure online-watching for [config.notifyList] using MONITOR if the server
     * advertises support via ISUPPORT, otherwise periodic ISON polling.
     */
    private fun setupNotify() {
        if (config.notifyList.isEmpty()) return
        val c = client ?: return

        // KICL parses ISUPPORT into ServerInfo; we check for the MONITOR token.
        monitorSupported = runCatching {
            c.serverInfo.iSupportParameters.containsKey("MONITOR")
        }.getOrDefault(false)

        if (monitorSupported) {
            // MONITOR + nick1,nick2,...  — server will push 730/731 events.
            val joined = config.notifyList.joinToString(",")
            c.sendRawLine("MONITOR + $joined")
        } else {
            // Fall back to polling. Single-shot first poll + 60s interval.
            isonJob?.cancel()
            isonJob = scope.launch {
                while (true) {
                    sendIson()
                    delay(60_000)
                }
            }
        }
    }

    private fun sendIson() {
        val c = client ?: return
        val args = config.notifyList.joinToString(" ")
        c.sendRawLine("ISON $args")
    }

    /** Compare the freshly-reported online set with our cache and emit deltas. */
    fun applyOnlineSet(reported: Set<String>) {
        val reportedLower = reported.map { it.lowercase() }.toSet()
        synchronized(notifyOnline) {
            // Newly online
            for (nick in config.notifyList) {
                val low = nick.lowercase()
                val wasOnline = notifyOnline.contains(low)
                val isOnline = reportedLower.contains(low)
                if (isOnline && !wasOnline) {
                    notifyOnline += low
                    sink.trySend(IrcEvent.NotifyState(config.id, nick, online = true))
                } else if (!isOnline && wasOnline) {
                    notifyOnline -= low
                    sink.trySend(IrcEvent.NotifyState(config.id, nick, online = false))
                }
            }
        }
    }

    /** MONITOR push — these nicks are now online. Emit deltas. */
    fun markNotifyOnline(nicks: List<String>) {
        synchronized(notifyOnline) {
            for (nick in nicks) {
                val low = nick.lowercase()
                if (notifyOnline.add(low)) {
                    sink.trySend(IrcEvent.NotifyState(config.id, nick, online = true))
                }
            }
        }
    }

    /** MONITOR push — these nicks went offline. Emit deltas. */
    fun markNotifyOffline(nicks: List<String>) {
        synchronized(notifyOnline) {
            for (nick in nicks) {
                val low = nick.lowercase()
                if (notifyOnline.remove(low)) {
                    sink.trySend(IrcEvent.NotifyState(config.id, nick, online = false))
                }
            }
        }
    }
}
