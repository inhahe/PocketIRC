package com.pocketirc.app.irc

import java.util.Locale

/**
 * Tracks where command-reply output should be sent.
 *
 * When the user runs e.g. `/whois nick` from buffer X, we record
 * (serverId, "whois", nick) -> bufferId X. When matching numerics arrive
 * (311/312/313/317/318/319/330/671), the listener queries this map and
 * appends the formatted reply into buffer X. After the terminating numeric
 * (318 ENDOFWHOIS), the entry is cleared.
 *
 * Inspired by qtpyrc's _ctcp_windows / _msg_windows / do_whois(target, window)
 * pattern, but stores buffer IDs instead of live objects so closed buffers
 * fall back gracefully to (status).
 */
class ReplyRouter {
    private data class Key(val serverId: String, val kind: String, val target: String)

    private val map = mutableMapOf<Key, String>()

    @Synchronized
    fun register(serverId: String, kind: String, target: String, bufferId: String) {
        map[Key(serverId, kind, target.lowercase(Locale.ROOT))] = bufferId
    }

    @Synchronized
    fun lookup(serverId: String, kind: String, target: String): String? =
        map[Key(serverId, kind, target.lowercase(Locale.ROOT))]

    @Synchronized
    fun clear(serverId: String, kind: String, target: String) {
        map.remove(Key(serverId, kind, target.lowercase(Locale.ROOT)))
    }
}
