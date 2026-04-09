package com.pocketirc.app.irc

import kotlinx.serialization.Serializable

/**
 * Single /on event handler. Persisted as JSON in DataStore.
 *
 * Mirrors qtpyrc's `/on` semantics: an [event] type, optional [channelFilter],
 * [networkFilter], [nickMaskFilter], optional [textPattern] (glob "*foo*" or
 * regex "/re/[ims]"), and an [action] which is a slash command with `{var}`
 * substitution.
 *
 * Required fields are listed first; defaults all come after to avoid the
 * kotlinx.serialization compiler-plugin bug around non-default parameters
 * appearing after default ones.
 */
@Serializable
data class OnHook(
    val name: String,
    val event: String,            // canonical event name (lowercase)
    val action: String,
    val networkFilter: String? = null,
    val channelFilter: String? = null,
    val nickMaskFilter: String? = null,
    val textPattern: String? = null,
    val persistent: Boolean = false,
    /** Side effect: raise a system notification when this hook fires. */
    val desktop: Boolean = false,
    /** -c: notification channel name. Built-in: silent/quiet/normal/loud, or
     *  any user-created custom channel name. */
    val channel: String? = null,
    /** -x: suppress default handling — the event is dropped before ingest. */
    val suppressDefault: Boolean = false,
)

/** Canonical event identifiers used by [OnHookEngine.dispatch]. */
object OnHookEvents {
    const val CHANMSG = "chanmsg"
    const val PRIVMSG = "privmsg"
    const val ACTION = "action"
    const val NOTICE = "notice"
    const val JOIN = "join"
    const val PART = "part"
    const val QUIT = "quit"
    const val KICK = "kick"
    const val NICK = "nick"
    const val TOPIC = "topic"
    const val INVITE = "invite"
    const val CONNECT = "connect"
    const val DISCONNECT = "disconnect"
    const val NOTIFY_ONLINE = "notify_online"
    const val NOTIFY_OFFLINE = "notify_offline"
    const val NUMERIC = "numeric"

    val all: List<String> = listOf(
        CHANMSG, PRIVMSG, ACTION, NOTICE, JOIN, PART, QUIT, KICK,
        NICK, TOPIC, INVITE, CONNECT, DISCONNECT, NOTIFY_ONLINE,
        NOTIFY_OFFLINE, NUMERIC,
    )
}
