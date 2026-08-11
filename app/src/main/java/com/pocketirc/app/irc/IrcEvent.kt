package com.pocketirc.app.irc

import com.pocketirc.app.model.TypingState

/** Normalized events emitted by [IrcConnection], decoupled from Kitteh types. */
sealed interface IrcEvent {
    val serverId: String

    data class Connected(override val serverId: String) : IrcEvent
    data class Disconnected(override val serverId: String, val reason: String?) : IrcEvent
    data class Status(override val serverId: String, val text: String) : IrcEvent
    data class Joined(override val serverId: String, val channel: String, val nick: String) : IrcEvent
    data class Parted(override val serverId: String, val channel: String, val nick: String) : IrcEvent
    data class TopicChanged(
        override val serverId: String,
        val channel: String,
        val topic: String,
        val setter: String?,
    ) : IrcEvent
    data class Quit(
        override val serverId: String,
        val nick: String,
        val reason: String?,
        /**
         * Channels the quitting user was in, per the client's own tracking at
         * the moment the QUIT arrived. QUIT carries no channel of its own, so
         * without this the line has nowhere to go but "every channel" — which
         * spams a channel with quits from people who were never in it.
         */
        val channels: List<String> = emptyList(),
    ) : IrcEvent
    data class NickChanged(
        override val serverId: String,
        val oldNick: String,
        val newNick: String,
        /** Channels the renaming user is in. See [Quit.channels]. */
        val channels: List<String> = emptyList(),
    ) : IrcEvent
    data class Kicked(
        override val serverId: String,
        val channel: String,
        val target: String,
        val by: String,
        val reason: String?,
    ) : IrcEvent
    data class Message(
        override val serverId: String,
        val target: String,   // channel or our nick (PM)
        val sender: String,
        val text: String,
        val isAction: Boolean = false,
        val isNotice: Boolean = false,
        val timestampMs: Long,
        /**
         * True if this message arrived as part of a history replay (ZNC playback,
         * IRCv3 chathistory, server-time tag in the past, etc.). The buffer
         * store will skip notification firing for these so the user isn't
         * woken up by 200 mentions when reconnecting to a bouncer.
         */
        val fromHistory: Boolean = false,
    ) : IrcEvent
    data class Typing(
        override val serverId: String,
        val target: String,
        val sender: String,
        val state: TypingState,
        val receivedAtMs: Long,
    ) : IrcEvent
    data class Raw(override val serverId: String, val line: String) : IrcEvent

    /** A server numeric reply (e.g. 311, 318). Parameters are the post-target args. */
    data class Numeric(
        override val serverId: String,
        val numeric: Int,
        val parameters: List<String>,
        /** The hostname the message came from (the IRC server's identity). */
        val source: String = "",
    ) : IrcEvent

    /**
     * A CTCP query (PRIVMSG with body wrapped in \x01...\x01) was received.
     * Replies are dispatched in [com.pocketirc.app.irc.ConnectionManager]
     * based on the user's CTCP settings.
     */
    data class CtcpQuery(
        override val serverId: String,
        val sender: String,
        val target: String,
        val command: String,
        val args: String,
    ) : IrcEvent

    /** A watched nick changed online state. */
    data class NotifyState(
        override val serverId: String,
        val nick: String,
        val online: Boolean,
    ) : IrcEvent
}
