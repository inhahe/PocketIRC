package com.pocketirc.app.irc

/** A single entry in a mode list (ban, except, invite, quiet). */
data class MaskEntry(
    val mask: String,
    val setter: String?,
    val timestamp: Long?,
)

/** Live state for the Channel Info screen, populated from server numerics. */
data class ChannelInfoState(
    val serverId: String,
    val channel: String,
    /** Current channel modes string (no leading +). */
    val modes: String = "",
    /** Args for parameterized modes (k, l, etc.) — index aligned with modes by type. */
    val key: String? = null,
    val userLimit: Int? = null,
    val bans: List<MaskEntry> = emptyList(),
    val bansLoading: Boolean = false,
    val excepts: List<MaskEntry> = emptyList(),
    val exceptsLoading: Boolean = false,
    val invites: List<MaskEntry> = emptyList(),
    val invitesLoading: Boolean = false,
    val quiets: List<MaskEntry> = emptyList(),
    val quietsLoading: Boolean = false,
)

/** Common type-D mode characters and their human descriptions. */
object ChannelModeDescriptions {
    val descriptions: Map<Char, String> = mapOf(
        'i' to "Invite only",
        'm' to "Moderated",
        'n' to "No external messages",
        's' to "Secret",
        'p' to "Private",
        't' to "Ops set topic",
        'c' to "No colors",
        'C' to "No CTCPs",
        'g' to "Free invite",
        'r' to "Registered only",
        'R' to "Registered speak",
        'S' to "SSL only",
        'z' to "SSL only",
        'D' to "Delayed join",
        'O' to "Opers only",
        'Q' to "No kicks",
        'N' to "No nick changes",
    )

    /** The common type-D ("flag") modes shown as checkboxes in the UI. */
    val flagOrder: List<Char> = listOf('i', 'm', 'n', 's', 'p', 't', 'c', 'C', 'r', 'R', 'S', 'D', 'Q', 'N')
}
