package com.pocketirc.app.irc

import com.pocketirc.app.model.TypingState
import kotlinx.coroutines.channels.Channel
import net.engio.mbassy.listener.Handler
import org.kitteh.irc.client.library.event.channel.ChannelJoinEvent
import org.kitteh.irc.client.library.event.channel.ChannelKickEvent
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent
import org.kitteh.irc.client.library.event.channel.ChannelNoticeEvent
import org.kitteh.irc.client.library.event.channel.ChannelPartEvent
import org.kitteh.irc.client.library.event.channel.ChannelTopicEvent
import org.kitteh.irc.client.library.event.user.UserNickChangeEvent
import org.kitteh.irc.client.library.event.user.UserQuitEvent
import org.kitteh.irc.client.library.event.client.ClientNegotiationCompleteEvent
import org.kitteh.irc.client.library.event.client.ClientReceiveCommandEvent
import org.kitteh.irc.client.library.event.client.ClientReceiveNumericEvent
import org.kitteh.irc.client.library.event.connection.ClientConnectionEndedEvent
import org.kitteh.irc.client.library.event.user.PrivateMessageEvent
import org.kitteh.irc.client.library.event.user.PrivateNoticeEvent
import org.kitteh.irc.client.library.event.user.PrivateCtcpQueryEvent
import org.kitteh.irc.client.library.event.channel.ChannelCtcpEvent

/**
 * Kitteh @Handler-based listener that funnels events into a coroutine [Channel]
 * for [IrcConnection] to forward to its SharedFlow.
 *
 * Kitteh uses MBassador, so handler methods must be annotated with @Handler and
 * the listener instance must be registered via client.eventManager.registerEventListener.
 */
class KitchenSinkListener(
    private val serverId: String,
    private val sink: Channel<IrcEvent>,
) {
    /**
     * Cleared by [IrcConnection] when the owning Client is retired, before it
     * is shut down. A retired listener must go silent: MBassador keeps it
     * registered on the dead Client's event manager, and KICL fires a
     * connection-ended event during shutdown. Forwarding that event would
     * re-enter IrcConnection.onDisconnected() and schedule yet another
     * reconnect for a client we deliberately replaced — a reconnect storm.
     *
     * It also backstops the duplicate-message bug this guard was added for:
     * even if a Client somehow outlives its retirement, it can no longer
     * push a second copy of every message into the shared sink.
     */
    @Volatile
    var active: Boolean = true

    private fun send(ev: IrcEvent) {
        if (active) sink.trySend(ev)
    }

    /**
     * Resolves a message's effective timestamp and historical-replay flag.
     *
     * Reads the IRCv3 `time` tag (the "server-time" cap, used by both ZNC
     * playback and IRCv3 chathistory) when present, falling back to the
     * local clock when not.
     *
     * Returns (timestampMs, fromHistory). [fromHistory] is true when **any**
     * of these signals fire:
     *   1) The message carries a `batch` tag (it's part of a server-initiated
     *      batch — virtually always a replay batch in practice for messages
     *      the user can read; netsplit/netjoin batches don't wrap chat).
     *   2) The `time` tag is more than 30 seconds in the past — a fallback
     *      for misbehaving servers that emit replay messages without a batch.
     */
    private fun resolveMessageTime(event: Any): Pair<Long, Boolean> {
        val now = System.currentTimeMillis()
        val tagged = extractServerTimeMillis(event)
        val inBatch = hasBatchTag(event)
        val ts = tagged ?: now
        val timeIsOld = tagged != null && tagged < now - 30_000
        return ts to (inBatch || timeIsOld)
    }

    /** Returns true if the event's message tags include a `batch=...` tag. */
    private fun hasBatchTag(event: Any): Boolean {
        return runCatching {
            val tags = findTagList(event) ?: return@runCatching false
            tags.any { tag ->
                if (tag == null) return@any false
                val name = invoke0(tag, "getName") as? String ?: return@any false
                name.equals("batch", ignoreCase = true)
            }
        }.getOrDefault(false)
    }

    /**
     * Defensive reflection-based reader for the IRCv3 `time` tag. Tries
     * several common KICL API shapes so we don't compile-bind to any one
     * of them and survive future KICL upgrades. The tag value is unwrapped
     * from String, Optional<String>, or whatever KICL hands back. Returns
     * null if no usable time tag was found.
     */
    private fun extractServerTimeMillis(event: Any): Long? {
        return runCatching {
            val tags = findTagList(event) ?: return@runCatching null
            for (tag in tags) {
                if (tag == null) continue
                val name = invoke0(tag, "getName") as? String ?: continue
                if (!name.equals("time", ignoreCase = true)) continue
                val rawValue = invoke0(tag, "getValue")
                val str = when (rawValue) {
                    is String -> rawValue
                    is java.util.Optional<*> -> rawValue.orElse(null) as? String
                    null -> null
                    else -> rawValue.toString()
                } ?: continue
                return@runCatching runCatching {
                    java.time.Instant.parse(str).toEpochMilli()
                }.getOrNull()
            }
            null
        }.getOrNull()
    }

    private fun findTagList(event: Any): List<*>? {
        for (m in arrayOf("getMessageTags", "getTags")) {
            val list = invoke0(event, m) as? List<*>
            if (list != null) return list
        }
        val origMsgs = invoke0(event, "getOriginalMessages") as? List<*>
        val first = origMsgs?.firstOrNull() ?: return null
        for (m in arrayOf("getTags", "getMessageTags")) {
            val list = invoke0(first, m) as? List<*>
            if (list != null) return list
        }
        return null
    }

    private fun invoke0(obj: Any, methodName: String): Any? {
        return runCatching {
            obj.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 0
            }?.invoke(obj)
        }.getOrNull()
    }

    @Handler
    fun onConnected(e: ClientNegotiationCompleteEvent) {
        send(IrcEvent.Connected(serverId))
    }

    @Handler
    fun onDisconnected(e: ClientConnectionEndedEvent) {
        // Take KICL's own reconnect engine out of the picture. KICL defaults
        // attemptReconnect to true, so without this it silently revives THIS
        // Client while IrcConnection.onDisconnected() independently dials a
        // brand-new Client. Both stay alive, both keep a KitchenSinkListener
        // registered against the same sink, and every incoming message is
        // delivered once per surviving client — the connection count (and the
        // duplicate count) doubles on each outage: 1 → 2 → 4 → 8.
        //
        // IrcConnection owns reconnect policy exclusively: exponential backoff
        // with jitter, endpoint cycling, nick fallback, and permanent-failure
        // detection. None of that exists in KICL's version.
        e.setAttemptReconnect(false)
        val reason = e.cause.map { it.message ?: it.javaClass.simpleName }.orElse(null)
        send(IrcEvent.Disconnected(serverId, reason))
    }

    @Handler
    fun onChannelMessage(e: ChannelMessageEvent) {
        val (ts, fromHistory) = resolveMessageTime(e)
        send(
            IrcEvent.Message(
                serverId = serverId,
                target = e.channel.name,
                sender = e.actor.nick,
                text = e.message,
                isAction = false,
                isNotice = false,
                timestampMs = ts,
                fromHistory = fromHistory,
            )
        )
    }

    @Handler
    fun onPrivateMessage(e: PrivateMessageEvent) {
        val (ts, fromHistory) = resolveMessageTime(e)
        send(
            IrcEvent.Message(
                serverId = serverId,
                target = e.actor.nick,   // PM buffer keyed by sender nick
                sender = e.actor.nick,
                text = e.message,
                timestampMs = ts,
                fromHistory = fromHistory,
            )
        )
    }

    @Handler
    fun onChannelNotice(e: ChannelNoticeEvent) {
        val (ts, fromHistory) = resolveMessageTime(e)
        send(
            IrcEvent.Message(
                serverId = serverId,
                target = e.channel.name,
                sender = e.actor.nick,
                text = e.message,
                isNotice = true,
                timestampMs = ts,
                fromHistory = fromHistory,
            )
        )
    }

    @Handler
    fun onPrivateNotice(e: PrivateNoticeEvent) {
        val (ts, fromHistory) = resolveMessageTime(e)
        send(
            IrcEvent.Message(
                serverId = serverId,
                target = e.actor.nick,
                sender = e.actor.nick,
                text = e.message,
                isNotice = true,
                timestampMs = ts,
                fromHistory = fromHistory,
            )
        )
    }

    @Handler
    fun onJoin(e: ChannelJoinEvent) {
        send(IrcEvent.Joined(serverId, e.channel.name, e.actor.nick))
    }

    @Handler
    fun onPart(e: ChannelPartEvent) {
        send(IrcEvent.Parted(serverId, e.channel.name, e.actor.nick))
    }

    @Handler
    fun onTopic(e: ChannelTopicEvent) {
        val topic = e.newTopic.value.orElse("")
        val setter = e.newTopic.setter.map { it.name.substringBefore('!') }.orElse(null)
        send(IrcEvent.TopicChanged(serverId, e.channel.name, topic, setter))
    }

    @Handler
    fun onQuit(e: UserQuitEvent) {
        // Snapshot the user's channels NOW. KICL's DefaultQuitListener fires
        // this event before calling trackUserQuit(), so the snapshot still
        // knows which channels the user was in; a moment later it won't.
        val channels = runCatching { e.user.channels.toList() }.getOrDefault(emptyList())
        send(IrcEvent.Quit(serverId, e.user.nick, e.message, channels))
    }

    @Handler
    fun onNickChange(e: UserNickChangeEvent) {
        val channels = runCatching { e.oldUser.channels.toList() }
            .getOrDefault(emptyList())
            .ifEmpty { runCatching { e.newUser.channels.toList() }.getOrDefault(emptyList()) }
        send(IrcEvent.NickChanged(serverId, e.oldUser.nick, e.newUser.nick, channels))
    }

    @Handler
    fun onKick(e: ChannelKickEvent) {
        val reason = runCatching { e.message }.getOrNull()
        // e.user is the kicker (KICL exposes the actor as a User on this event);
        // e.target is the kicked user.
        send(IrcEvent.Kicked(serverId, e.channel.name, e.target.nick, e.user.nick, reason))
    }

    @Handler
    fun onNumeric(e: ClientReceiveNumericEvent) {
        // KICL exposes the source of a server-originated numeric (the
        // welcome 001 in particular) via getActor(). For DNS-round-robin
        // entrypoints like irc.libera.chat, the actor's name is the actual
        // server we landed on (e.g. cherryh.libera.chat). We capture it so
        // ConnectionManager can show the user where they really are.
        //
        // Defensive: KICL 9.0.0's Actor.getName() returns String, but a
        // hypothetical future KICL could change that to Optional<String>.
        // Type-erase to Any? first so the elvis operator doesn't bind to a
        // concrete type, then unwrap whichever shape arrives at runtime.
        // This handles String, Optional<String>, null, and "method missing"
        // (via the outer runCatching) all gracefully without ever failing
        // to compile.
        val src = runCatching {
            val raw: Any? = e.actor.name
            when (raw) {
                is String -> raw
                is java.util.Optional<*> -> (raw.orElse(null) as? String).orEmpty()
                null -> ""
                else -> raw.toString()
            }
        }.getOrDefault("")
        send(IrcEvent.Numeric(serverId, e.numeric, e.parameters, src))
    }

    /**
     * Private CTCP query (e.g. VERSION, TIME, FINGER). We suppress KICL's
     * default reply by clearing it, then forward the query to
     * [ConnectionManager] which composes a reply from the user's settings
     * and sends it asynchronously via [Client.sendCtcpReply]. PING is left
     * to KICL since the standard echo-back is what every client does and
     * the user has nothing to configure.
     */
    @Handler
    fun onPrivateCtcp(e: PrivateCtcpQueryEvent) {
        val msg = e.message
        val space = msg.indexOf(' ')
        val cmd = (if (space < 0) msg else msg.substring(0, space)).uppercase()
        val args = if (space < 0) "" else msg.substring(space + 1)
        if (cmd == "PING") return  // let KICL auto-reply
        if (cmd == "ACTION") return // never a query
        e.setReply(null)            // disable KICL's default sync reply
        send(
            IrcEvent.CtcpQuery(
                serverId = serverId,
                sender = e.actor.nick,
                target = e.actor.nick, // PMs reply to the sender
                command = cmd,
                args = args,
            )
        )
    }

    /**
     * Channel CTCPs are unusual but legal (a CTCP sent to a #channel rather
     * than to a nick). KICL never auto-replies to these. We surface them so
     * the manager can choose to respond — though by convention most clients
     * ignore channel-targeted CTCPs entirely.
     */
    @Handler
    fun onChannelCtcp(e: ChannelCtcpEvent) {
        val msg = e.message
        val space = msg.indexOf(' ')
        val cmd = (if (space < 0) msg else msg.substring(0, space)).uppercase()
        val args = if (space < 0) "" else msg.substring(space + 1)
        if (cmd == "ACTION") return
        send(
            IrcEvent.CtcpQuery(
                serverId = serverId,
                sender = e.actor.nick,
                target = e.channel.name,
                command = cmd,
                args = args,
            )
        )
    }

    /**
     * Catch raw TAGMSG so we can decode the IRCv3 +typing client tag, which Kitteh
     * doesn't model with a dedicated event.
     */
    @Handler
    fun onRaw(e: ClientReceiveCommandEvent) {
        if (!e.command.equals("TAGMSG", ignoreCase = true)) return
        val target = e.parameters.firstOrNull() ?: return
        val sender = e.actor.name.substringBefore('!')
        val typingTag = e.messageTags
            .firstOrNull { it.name == "+typing" }
            ?.value?.orElse(null)
            ?: return
        val state = when (typingTag.lowercase()) {
            "active" -> TypingState.ACTIVE
            "paused" -> TypingState.PAUSED
            "done" -> TypingState.DONE
            else -> return
        }
        send(
            IrcEvent.Typing(
                serverId = serverId,
                target = target,
                sender = sender,
                state = state,
                receivedAtMs = System.currentTimeMillis(),
            )
        )
    }
}
