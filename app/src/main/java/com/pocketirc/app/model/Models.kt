package com.pocketirc.app.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val endpoints: List<ServerEndpoint>,
    /**
     * Per-network override for the nickname chain. The first entry is the
     * primary nick; subsequent entries are tried in order if the primary is
     * already in use during connection registration. After the list is
     * exhausted the client appends `_` characters to the resolved primary
     * (e.g. `inhahe_`, `inhahe__`, ...) up to a small fixed limit.
     *
     * `null` (or empty) means "inherit [com.pocketirc.app.data.AppSettings.defaultNicksCsv]".
     */
    val nicks: List<String>? = null,
    /**
     * Per-network override for the real name. `null` (or empty) = inherit
     * [com.pocketirc.app.data.AppSettings.defaultRealName]. If the global
     * default is also empty, the resolver falls back to the resolved nick.
     */
    val realName: String? = null,
    /**
     * Per-network override for the USER command's username field (the part
     * that becomes the ident, prefixed with `~` when no ident server is
     * available — which on mobile is essentially always). `null`/empty =
     * inherit [com.pocketirc.app.data.AppSettings.defaultUserName]. If the
     * global is also blank, KICL's own default is used.
     */
    val userName: String? = null,
    val sasl: SaslConfig? = null,
    /** Server password (PASS command). Used by networks like Twitch IRC that
     *  expect an oauth token via PASS instead of SASL. */
    val serverPassword: String? = null,
    val autoJoin: List<AutoJoinChannel> = emptyList(),
    /** Outbound rate limiting: max messages sent before throttling kicks in. */
    val outBurst: Int = 5,
    /** Outbound rate limiting: ms between refilled tokens once the burst is exhausted. */
    val outRefillMs: Int = 1000,
    /** Whether to automatically reconnect on connection loss. */
    val autoReconnect: Boolean = true,
    /** Max reconnect attempts before giving up. -1 = unlimited. */
    val reconnectMaxAttempts: Int = -1,
    /** Nicks to watch for online/offline transitions via MONITOR or ISON. */
    val notifyList: List<String> = emptyList(),
    /** Network-level default: allow IPv4 addresses for new endpoints. */
    val defaultIpv4: Boolean = true,
    /** Network-level default: allow IPv6 addresses for new endpoints. */
    val defaultIpv6: Boolean = true,
    /**
     * If true, [name] is treated as a user-chosen display label that wins over
     * any auto-detected network name. If false (the case for /server lines
     * without -name, where [name] just defaults to the hostname), the tree
     * label can be replaced by the server's announced NETWORK= ISUPPORT token.
     * Defaults to true so all existing serialized configs and GUI/preset
     * entries are treated as locked and don't get overridden after upgrading.
     */
    val displayNameLocked: Boolean = true,
)

@Serializable
data class ServerEndpoint(
    val host: String,
    val port: Int = 6697,
    val tls: Boolean = true,
    /**
     * Allow IPv4 addresses when resolving [host]. `null` means "inherit
     * the network's [ServerConfig.defaultIpv4]" — the typical state for a
     * freshly added endpoint, so changing the network default later still
     * propagates to all endpoints that haven't been explicitly overridden.
     * Existing serialized configs default to `true` (the previous behavior).
     */
    val ipv4: Boolean? = true,
    /** Allow IPv6 addresses when resolving [host]. See [ipv4]. */
    val ipv6: Boolean? = true,
)

@Serializable
data class AutoJoinChannel(
    val name: String,
    val key: String? = null,
)

@Serializable
data class SaslConfig(
    val username: String,
    val password: String,
    val mechanism: String = "PLAIN",
)

/** A node in the left-drawer tree: server -> channels/queries. */
sealed interface TreeNode {
    val id: String
    val label: String
    val unread: Int
    val highlighted: Boolean

    data class Server(
        override val id: String,
        override val label: String,
        val connected: Boolean,
        val children: List<Buffer>,
        /** True if this server is persisted in DataStore (not just an in-memory stub). */
        val saved: Boolean = true,
        override val unread: Int = children.sumOf { it.unread },
        override val highlighted: Boolean = children.any { it.highlighted },
    ) : TreeNode

    data class Buffer(
        override val id: String,
        val serverId: String,
        override val label: String,
        val kind: Kind,
        override val unread: Int = 0,
        override val highlighted: Boolean = false,
    ) : TreeNode {
        enum class Kind { CHANNEL, QUERY, STATUS }
    }
}

data class ChatLine(
    val id: Long,
    val timestamp: Long,
    val sender: String?,
    val text: String,
    val kind: Kind,
    /** Nicks embedded in [text] that should render as clickable spans. */
    val subjectNicks: List<String> = emptyList(),
) {
    enum class Kind { MESSAGE, ACTION, NOTICE, JOIN, PART, QUIT, NICK, SYSTEM }
}

/** IRCv3 +typing client tag state. */
enum class TypingState { ACTIVE, PAUSED, DONE }
