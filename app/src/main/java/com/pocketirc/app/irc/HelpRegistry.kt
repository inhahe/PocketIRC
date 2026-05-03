package com.pocketirc.app.irc

/**
 * Static help text for every slash command. Used by `/help` and the help icon
 * in the top app bar. Keys are the canonical command name (no slash, lowercase).
 */
object HelpRegistry {
    data class Entry(val usage: String, val summary: String)

    val entries: Map<String, Entry> = linkedMapOf(
        "join" to Entry("/join <#channel>", "Join a channel."),
        "part" to Entry("/part [#chan] [reason]",
            "Leave the current or named channel."),
        "cycle" to Entry("/cycle", "Part and rejoin the current channel."),
        "hop" to Entry("/hop", "Alias for /cycle."),
        "msg" to Entry("/msg <nick> <text>",
            "Send a private message; opens a query buffer."),
        "query" to Entry("/query <nick> <text>", "Alias for /msg."),
        "me" to Entry("/me <text>",
            "CTCP ACTION in the current buffer (/* nick text)."),
        "say" to Entry("/say <text>",
            "Send <text> to the current buffer as a normal message. " +
                "Useful when text starts with '/' and would otherwise parse " +
                "as a command."),
        "nick" to Entry("/nick <newnick>", "Change your nickname on this server."),
        "quit" to Entry("/quit [reason]", "Disconnect this server."),
        "raw" to Entry("/raw <line>", "Send a raw IRC line. Use with care."),
        "quote" to Entry("/quote <line>", "Alias for /raw."),
        "whois" to Entry("/whois <nick>",
            "Look up nick info; reply routed back to the current buffer."),
        "away" to Entry("/away [reason]",
            "Mark yourself away (or back if reason is empty)."),
        "topic" to Entry("/topic [#chan] [text]",
            "Show or set the channel topic."),
        "kick" to Entry("/kick [#chan] <nick> [reason]",
            "Kick a user from the channel."),
        "mode" to Entry("/mode <target> <flags...>",
            "Set channel or user modes."),
        "unban" to Entry("/unban [#chan] <mask>",
            "Remove a ban (-b) on the given mask."),
        "invite" to Entry("/invite <nick> [#chan]",
            "Invite a user to a channel."),
        "names" to Entry("/names [#chan]",
            "Refresh the nick list for a channel."),
        "notice" to Entry("/notice <target> <text>",
            "Send a NOTICE to a nick or channel."),
        "ctcp" to Entry("/ctcp <nick> <TAG> [data]",
            "Send a raw CTCP request."),
        "ping" to Entry("/ping <nick>",
            "CTCP PING with current timestamp (latency check)."),
        "list" to Entry("/list [filter]",
            "Open the channel list browser. Filters: >50, <500, T<60, *python*"),
        "save" to Entry("/save", "Export the current buffer."),
        "export" to Entry("/export", "Alias for /save."),
        "chaninfo" to Entry("/chaninfo [#chan]",
            "Open the channel info screen for the current or named channel."),
        "chanmodes" to Entry("/chanmodes [#chan]", "Alias for /chaninfo."),
        "motd" to Entry("/motd [target]", "Show the message of the day."),
        "lusers" to Entry("/lusers", "Show network user counts."),
        "admin" to Entry("/admin [target]", "Show server admin information."),
        "time" to Entry("/time [target]", "Show server time."),
        "version" to Entry("/version [target]", "Show server software version."),
        "stats" to Entry("/stats <query> [target]", "Server statistics."),
        "info" to Entry("/info [target]", "Show server build info."),
        "links" to Entry("/links [args]", "Show server-to-server links."),
        "who" to Entry("/who <mask|#chan>", "WHO query for users matching a pattern."),
        "userhost" to Entry("/userhost <nick> [<nick>...]",
            "Look up user@host for up to five nicks."),
        "server" to Entry(
            "/server [name | host[:port]] [flags...]",
            "Connect to or reconfigure an IRC server. /connect is an alias.\n" +
                "  No args: dial the server of the current buffer.\n" +
                "  A name:  reconnect a saved or live server by display name.\n" +
                "           Matches the configured name, the hostname, the\n" +
                "           server id, or the ISUPPORT NETWORK= name the\n" +
                "           server announced (all case-insensitive).\n" +
                "  Host + flags in a current network's window:\n" +
                "           Redial the SAME network entry against the new\n" +
                "           host. The id, buffers, channels, history, nick,\n" +
                "           SASL, autojoin, and notify list all stay put —\n" +
                "           only the underlying socket changes. Designed for\n" +
                "           netsplits and 'this server is laggy, hop to a\n" +
                "           sibling' cases. Add -p to persist the change to\n" +
                "           DataStore (default is ephemeral and reverts on\n" +
                "           next launch). Add -m to instead create a brand-\n" +
                "           new entry alongside the current one.\n" +
                "  Host + flags with no current network: create a new\n" +
                "           ephemeral connection. Use -p to persist.\n" +
                "Flags:\n" +
                "  -e          Force TLS\n" +
                "  -t          STARTTLS (treated as TLS)\n" +
                "  -nick LIST  Nick chain for THIS network. Comma- or\n" +
                "              space-separated; the first entry is the primary,\n" +
                "              the rest are tried in order if it's taken (e.g.\n" +
                "              -nick alice,alice_,alice__). After the list runs\n" +
                "              out '_' is appended to the primary up to 5 levels.\n" +
                "              Without -nick, the network inherits the global\n" +
                "              default from /set default_nicks (also editable in\n" +
                "              Settings → Identity). -altnick is accepted as an\n" +
                "              alias.\n" +
                "  -user U     Ident username override for this network. Without\n" +
                "              it, /set default_user is used (or KICL's default\n" +
                "              if that is also blank). Mobile clients can't run\n" +
                "              an ident server so this appears prefixed with '~'.\n" +
                "  -realname R Real name override for this network. Without it,\n" +
                "              the network inherits /set default_realname (or\n" +
                "              the resolved nick if that is also blank). Use\n" +
                "              quotes for spaces.\n" +
                "  -name N     Display name shown in the network tree. If\n" +
                "              omitted, the tree shows the host until the\n" +
                "              server announces NETWORK= via ISUPPORT, then\n" +
                "              switches to that.\n" +
                "  -w PASS     Server password (PASS command)\n" +
                "  -l USER P   SASL PLAIN credentials\n" +
                "  -p          PERSIST: save the server to DataStore so it\n" +
                "              auto-connects on next launch.\n" +
                "  -n          Don't connect now. The server is still added\n" +
                "              to the network tree as an idle entry — you can\n" +
                "              navigate to its (status) window and use\n" +
                "              /reconnect to dial it whenever. Combined with\n" +
                "              -p, the entry is also saved to disk with\n" +
                "              autoReconnect off so it stays dormant across\n" +
                "              app restarts.\n" +
                "  -m          Force creating a NEW network entry instead of\n" +
                "              modifying the current one. Use this when you\n" +
                "              want a parallel second connection to the same\n" +
                "              or a different host, rather than redialing the\n" +
                "              current entry against a new host.\n" +
                "Port can also be prefixed: +6697 = TLS.\n" +
                "Examples:\n" +
                "  (in the (status) buffer of network 'libera':)\n" +
                "  /server cherryh.libera.chat                    # netsplit hop, ephemeral\n" +
                "  /server -p cherryh.libera.chat                 # netsplit hop, persisted\n" +
                "  /server -m -e irc.oftc.net:6697                # spawn parallel OFTC entry\n" +
                "  /server -m -p -e irc.oftc.net:6697             # spawn AND save it\n" +
                "  /server -p -nick newnick                       # change just the saved nick\n" +
                "  /server                                        # redial current",
        ),
        "connect" to Entry("/connect [name | host[:port]] [flags...]",
            "Alias for /server with identical behavior. Use whichever feels " +
                "more natural.\n" +
                "  No args: dial the server of the current buffer.\n" +
                "  A name:  reconnect by display name (matches saved name, host,\n" +
                "           or ISUPPORT NETWORK= announcement, case-insensitive).\n" +
                "  Flags:   see /help server for the full flag list.\n"),
        "reconnect" to Entry("/reconnect",
            "Reconnect the current server using its first endpoint."),
        "nextserver" to Entry("/nextserver",
            "Cycle the current network's connection to the next endpoint in " +
                "its endpoint list, wrapping around to the first when it runs " +
                "out. Useful for netsplits and laggy servers when you've " +
                "configured multiple endpoints. Aliases: /failover. The same " +
                "network entry is reused — buffers, channels, history, nick, " +
                "and SASL all stay put."),
        "failover" to Entry("/failover",
            "Alias for /nextserver."),
        "clear" to Entry("/clear", "Clear the current buffer."),
        "echo" to Entry("/echo <text>", "Print a local-only line in this buffer."),
        "help" to Entry("/help [command]",
            "List commands, or show usage for a single command."),
        "window" to Entry(
            "/window [-k network] <#chan|nick|status>",
            "Switch the active buffer. Aliases: /win, /buffer.\n" +
                "  -k network   Restrict the lookup to a specific server (by name or id).\n" +
                "Without -k, the current server is tried first, then any server.\n" +
                "Use 'status' to jump to the server's status window.\n" +
                "Shorthand: /window Libera/#pocketirc  is the same as\n" +
                "          /window -k Libera #pocketirc",
        ),
        "win" to Entry("/win [-k network] <#chan|nick|status>", "Alias for /window."),
        "buffer" to Entry("/buffer [-k network] <#chan|nick|status>", "Alias for /window."),
        "set" to Entry(
            "/set | /set <key> | /set <key> <value>",
            "View or change an app setting. With no args, lists every key " +
                "and its current value. With one arg, prints that key. With " +
                "two, updates it.\n" +
                "Keys:\n" +
                "  color_nicks                bool   tint each nick a unique color\n" +
                "  theme                      enum   system | light | dark\n" +
                "  font_size                  int    chat font size sp (10-28)\n" +
                "  timestamp_format           string Java SimpleDateFormat (HH:mm, hh:mm a, ...)\n" +
                "  monospace                  bool   fixed-width chat font\n" +
                "  send_on_enter              bool   Enter sends; Shift/Ctrl+Enter newline\n" +
                "  quit_message               string default /quit reason\n" +
                "  mention_notifications      bool   notify on channel mentions\n" +
                "  privmsg_notifications      bool   notify on private messages\n" +
                "  notice_notifications       bool   notify on user-sourced NOTICEs\n" +
                "  notification_reply         bool   inline Reply on mention notifications\n" +
                "  notify_list_notifications  bool   notify on watched-nick state changes\n" +
                "  default_alert_sound        string content:// URI for /alert default sound\n" +
                "Bool values: true|false|on|off|yes|no|1|0\n" +
                "Examples:\n" +
                "  /set send_on_enter on\n" +
                "  /set theme dark\n" +
                "  /set font_size 18",
        ),
        "alert" to Entry(
            "/alert [-c <channel-name>] <text>",
            "Fire a system notification on a named notification channel.\n" +
                "Built-in channels:\n" +
                "  silent  IMPORTANCE_MIN, no sound, no badge, in shade overflow\n" +
                "  quiet   IMPORTANCE_LOW, in shade, no sound, no badge\n" +
                "  normal  IMPORTANCE_DEFAULT, sound, badge (the default)\n" +
                "  loud    IMPORTANCE_HIGH, sound + vibration + heads-up + screen wake\n" +
                "You can also create your own channels in Pocket IRC Settings →\n" +
                "Custom alert channels, with a name, importance, sound, vibration,\n" +
                "lights, and badge of your choosing. Use that name as the value\n" +
                "of -c. Once a channel exists, the user can fine-tune any of its\n" +
                "settings from Android Settings → Apps → Pocket IRC → Notifications.\n" +
                "Default channel is set in Pocket IRC Settings → Default /alert channel.",
        ),
        "notify" to Entry(
            "/notify | /notify <nick>... | /notify -r <nick>...",
            "Manage this server's notify list (the watched-for-online nicks). " +
                "No args = show current list. Names = add. -r names = remove. " +
                "Uses MONITOR if the server supports it, ISON polling otherwise."),
        "noop" to Entry("/noop",
            "Do nothing. Useful as a placeholder action for /on hooks that " +
                "only want to trigger -d/-s side effects."),
        "on" to Entry(
            "/on [-p] [-name N] [-n mask] [-c #ch] [-k network] [-pat pattern] " +
                "[-d] [-s sound] <event> [command]",
            "Register an event hook. Events: chanmsg, privmsg, action, notice, " +
                "join, part, quit, kick, nick, topic, invite, connect, disconnect, " +
                "notify_online, notify_offline, numeric.\n" +
                "Filters:\n" +
                "  -n mask     nick!user@host wildcard ('*' '?')\n" +
                "  -c #chan    channel filter (literal)\n" +
                "  -k network  match only on this network name\n" +
                "  -pat P      glob (*foo*) or /regex/[ims] over the message text\n" +
                "  -name N     give the hook an explicit name\n" +
                "  -p          persist across restarts (saved to settings)\n" +
                "  -d          raise a system notification when the hook fires\n" +
                "  -c NAME     notification channel: silent, quiet, normal, loud,\n" +
                "              or any custom channel you've created in Settings\n" +
                "  -x          suppress default handling — drop the event so it\n" +
                "              isn't rendered, doesn't notify, doesn't bump unread\n" +
                "  -q          quiet: suppress only the mention/PM notification;\n" +
                "              the event still ingests into its buffer normally\n" +
                "Action variables: {nick} {user} {channel} {target} {text} " +
                "{server} {oldnick} {newnick} {reason}\n" +
                "List:    /on -l [event]\n" +
                "Remove:  /on -r [-p] <event> <name>\n" +
                "Example: /on -p -c #pocketirc chanmsg /echo {nick} said: {text}",
        ),
    )

    fun listAll(): String {
        return entries.entries.joinToString("\n") { (name, e) ->
            "  /$name — ${e.summary.lineSequence().first()}"
        }
    }

    fun lookup(name: String): String {
        val key = name.removePrefix("/").lowercase()
        val e = entries[key] ?: return "No help for /$key"
        return "${e.usage}\n${e.summary}"
    }
}
