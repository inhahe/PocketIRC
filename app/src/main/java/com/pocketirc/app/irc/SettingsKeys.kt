package com.pocketirc.app.irc

/**
 * Catalog of user-settable settings for the /set slash command. Each entry
 * names a single key, the type, a one-line description, and the snake_case
 * name shown to users. The actual read/write happens in IrcViewModel; this
 * file is just the schema.
 */
object SettingsKeys {
    enum class Type { BOOL, INT, STRING, ENUM_THEME }

    data class Spec(
        val name: String,
        val type: Type,
        val description: String,
    )

    val all: List<Spec> = listOf(
        Spec("color_nicks", Type.BOOL,
            "Tint each nickname a unique color"),
        Spec("theme", Type.ENUM_THEME,
            "Theme: system, light, or dark"),
        Spec("font_size", Type.INT,
            "Chat font size in sp (10-28)"),
        Spec("timestamp_format", Type.STRING,
            "Java SimpleDateFormat string, e.g. HH:mm or hh:mm a"),
        Spec("monospace", Type.BOOL,
            "Render chat in a fixed-width font"),
        Spec("send_on_enter", Type.BOOL,
            "Pressing Enter sends; Shift/Ctrl+Enter inserts a newline"),
        Spec("quit_message", Type.STRING,
            "Default /quit reason when none is given"),
        Spec("mention_notifications", Type.BOOL,
            "Notify on channel mentions of your nick"),
        Spec("privmsg_notifications", Type.BOOL,
            "Notify on private messages"),
        Spec("notice_notifications", Type.BOOL,
            "Notify on user-sourced NOTICEs"),
        Spec("notification_reply", Type.BOOL,
            "Add an inline Reply action to mention notifications"),
        Spec("notify_list_notifications", Type.BOOL,
            "Notify when a watched nick comes online/offline"),
        Spec("ctcp_version", Type.STRING,
            "CTCP VERSION reply ('' = built-in default, 'off' = decline)"),
        Spec("ctcp_time", Type.STRING,
            "CTCP TIME reply ('' = local time, 'utc' = UTC, 'off' = decline)"),
        Spec("ctcp_finger", Type.STRING,
            "CTCP FINGER reply ('' = real name, 'off' = decline)"),
        Spec("ctcp_userinfo", Type.STRING,
            "CTCP USERINFO reply ('' = real name, 'off' = decline)"),
        Spec("ctcp_source", Type.STRING,
            "CTCP SOURCE reply ('' = github URL, 'off' = decline)"),
        Spec("default_nicks", Type.STRING,
            "Global default nick chain. Comma- or space-separated; first " +
                "is primary, rest are tried if it's taken. Per-network " +
                "override via /server -nick."),
        Spec("default_realname", Type.STRING,
            "Global default real name (per-network override via /server -realname)"),
        Spec("default_user", Type.STRING,
            "Global default ident username (the USER field; per-network " +
                "override via /server -user)"),
        Spec("auto_add_joined_channels", Type.BOOL,
            "When on, /join writes to the network's autojoin list and " +
                "/part removes it (persisted networks only)"),
    )

    fun find(key: String): Spec? {
        val k = key.lowercase().replace('-', '_').replace(' ', '_')
        return all.firstOrNull { it.name == k }
    }

    /** Best-effort parse of a value string for [type]. Returns null on failure. */
    fun parseValue(type: Type, raw: String): Any? {
        return when (type) {
            Type.BOOL -> when (raw.lowercase()) {
                "1", "true", "on", "yes", "y" -> true
                "0", "false", "off", "no", "n" -> false
                else -> null
            }
            Type.INT -> raw.toIntOrNull()
            Type.STRING -> raw
            Type.ENUM_THEME -> when (raw.lowercase()) {
                "system", "auto" -> "SYSTEM"
                "light" -> "LIGHT"
                "dark" -> "DARK"
                else -> null
            }
        }
    }
}
