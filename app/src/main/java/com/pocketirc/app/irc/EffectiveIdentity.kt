package com.pocketirc.app.irc

/**
 * The fully-resolved identity for a connection: the values that actually go
 * out on the wire after merging the per-network [com.pocketirc.app.model.ServerConfig]
 * overrides with the global [com.pocketirc.app.data.AppSettings] defaults.
 *
 * Produced by [ConnectionManager.resolveIdentity]. All call sites that need
 * "the user's nick" or "the user's real name" should go through that method
 * rather than reading [com.pocketirc.app.model.ServerConfig.nicks] directly,
 * which is now nullable and represents only the per-network override.
 *
 * [nicks] is guaranteed non-empty in any caller-meaningful sense — if both
 * the per-network override and the global default are blank the resolver
 * returns a list with a single empty string and the connect attempt will
 * fail registration with a clear error. The first entry is the primary
 * nick; subsequent entries are tried in order if the primary is taken.
 */
data class EffectiveIdentity(
    val nicks: List<String>,
    val realName: String,
    val userName: String,
) {
    /** Convenience: the primary nickname (the one we try first). */
    val nick: String get() = nicks.firstOrNull().orEmpty()
}
