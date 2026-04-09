package com.pocketirc.app.data

import com.pocketirc.app.model.ServerEndpoint

/**
 * A pre-configured IRC network the user can pick from in the Add Server screen.
 *
 * Burst/refill values are conservative best-effort defaults based on each
 * network's published or commonly-observed flood limits. They favor avoiding
 * disconnects over absolute throughput.
 */
data class NetworkPreset(
    val name: String,
    val endpoint: ServerEndpoint,
    val burst: Int,
    val refillMs: Int,
    val notes: String = "",
)

object NetworkPresets {
    val all: List<NetworkPreset> = listOf(
        NetworkPreset(
            name = "Libera.Chat",
            endpoint = ServerEndpoint("irc.libera.chat", 6697, true),
            burst = 8, refillMs = 500,
            notes = "8 msgs / 4 sec.",
        ),
        NetworkPreset(
            name = "OFTC",
            endpoint = ServerEndpoint("irc.oftc.net", 6697, true),
            burst = 8, refillMs = 500,
        ),
        NetworkPreset(
            name = "EFnet",
            endpoint = ServerEndpoint("irc.efnet.org", 6697, true),
            burst = 4, refillMs = 1000,
            notes = "Strict; ~4 msgs / 4 sec.",
        ),
        NetworkPreset(
            name = "IRCnet",
            endpoint = ServerEndpoint("open.ircnet.net", 6697, true),
            burst = 4, refillMs = 1000,
        ),
        NetworkPreset(
            name = "Undernet",
            endpoint = ServerEndpoint("irc.undernet.org", 6697, true),
            burst = 5, refillMs = 1000,
        ),
        NetworkPreset(
            name = "DALnet",
            endpoint = ServerEndpoint("irc.dal.net", 6697, true),
            burst = 5, refillMs = 1000,
        ),
        NetworkPreset(
            name = "QuakeNet",
            endpoint = ServerEndpoint("irc.quakenet.org", 6697, true),
            burst = 5, refillMs = 1000,
        ),
        NetworkPreset(
            name = "Rizon",
            endpoint = ServerEndpoint("irc.rizon.net", 6697, true),
            burst = 6, refillMs = 700,
        ),
        NetworkPreset(
            name = "SwiftIRC",
            endpoint = ServerEndpoint("irc.swiftirc.net", 6697, true),
            burst = 5, refillMs = 1000,
        ),
        NetworkPreset(
            name = "Snoonet",
            endpoint = ServerEndpoint("irc.snoonet.org", 6697, true),
            burst = 8, refillMs = 500,
        ),
        NetworkPreset(
            name = "Tilde.chat",
            endpoint = ServerEndpoint("irc.tilde.chat", 6697, true),
            burst = 8, refillMs = 500,
        ),
        NetworkPreset(
            name = "GIMPNet",
            endpoint = ServerEndpoint("irc.gimp.org", 6697, true),
            burst = 5, refillMs = 1000,
        ),
        NetworkPreset(
            name = "Twitch",
            endpoint = ServerEndpoint("irc.chat.twitch.tv", 6697, true),
            burst = 20, refillMs = 1500,
            notes = "20 msgs / 30 sec for non-mods. SASL with OAuth token required.",
        ),
    )
}
