package com.pocketirc.app.irc

/**
 * Parses a chat-input line into either a slash command or a regular message.
 *
 * Supported (Batch A):
 *   /join <#chan>            join channel
 *   /part [#chan] [reason]   leave current or named channel
 *   /msg <nick> <text>       open/send PM
 *   /me <text>               CTCP ACTION in current buffer
 *   /nick <newnick>          change nickname
 *   /quit [reason]           disconnect this server
 *   /raw <line>              send raw IRC line
 *   /whois <nick>            whois (reply routed back to current buffer)
 */
sealed interface ParsedInput {
    data class Message(val text: String) : ParsedInput
    data class Action(val text: String) : ParsedInput
    data class Join(val channel: String) : ParsedInput
    data class Part(val channel: String?, val reason: String?) : ParsedInput
    data class Msg(val target: String, val text: String) : ParsedInput
    data class Nick(val nick: String) : ParsedInput
    data class Quit(val reason: String?) : ParsedInput
    data class Raw(val line: String) : ParsedInput
    data class Whois(val target: String) : ParsedInput
    data class Away(val reason: String?) : ParsedInput
    data class Topic(val channel: String?, val text: String?) : ParsedInput
    data class Kick(val channel: String?, val nick: String, val reason: String?) : ParsedInput
    data class Mode(val target: String, val args: String) : ParsedInput
    data class Invite(val nick: String, val channel: String?) : ParsedInput
    data class Names(val channel: String?) : ParsedInput
    data class NoticeCmd(val target: String, val text: String) : ParsedInput
    data class Ctcp(val target: String, val tag: String, val data: String?) : ParsedInput
    data class ListCmd(val args: String) : ParsedInput
    object Save : ParsedInput
    data class ChanInfo(val channel: String?) : ParsedInput
    // Easy passthroughs / channel ops
    object Cycle : ParsedInput
    object Hop : ParsedInput
    data class Unban(val channel: String?, val mask: String) : ParsedInput
    data class Motd(val target: String?) : ParsedInput
    object Lusers : ParsedInput
    data class Admin(val target: String?) : ParsedInput
    data class Time(val target: String?) : ParsedInput
    data class Version(val target: String?) : ParsedInput
    data class Stats(val args: String) : ParsedInput
    data class Info(val target: String?) : ParsedInput
    data class Links(val args: String) : ParsedInput
    data class Who(val args: String) : ParsedInput
    data class Userhost(val nicks: String) : ParsedInput
    data class Ping(val target: String) : ParsedInput
    // Connection / server management
    data class Server(val args: String) : ParsedInput
    data class Connect(val args: String) : ParsedInput
    object Reconnect : ParsedInput
    /** /nextserver — cycle to the next endpoint in the current network's list. */
    object NextServer : ParsedInput
    // Local UI ops
    object Clear : ParsedInput
    data class Echo(val text: String) : ParsedInput
    data class Help(val command: String?) : ParsedInput
    /** /alert [-c <channel-name>] <text> — fires a system notification on a named channel. */
    data class Alert(val text: String, val channel: String?) : ParsedInput
    object Noop : ParsedInput
    /** /say <text> — send text to the current buffer (escapes leading slashes). */
    data class Say(val text: String) : ParsedInput
    /** /set [key [value]] — view or change a single AppSettings key. */
    data class Set(val key: String?, val value: String?) : ParsedInput
    /** /window [-k network] <name> — switch the active buffer. */
    data class Window(val target: String, val network: String?) : ParsedInput
    /** /notify list management. */
    sealed interface NotifyList : ParsedInput {
        object Show : NotifyList
        data class Add(val nicks: kotlin.collections.List<String>) : NotifyList
        data class Remove(val nicks: kotlin.collections.List<String>) : NotifyList
    }
    sealed interface OnCmd : ParsedInput {
        data class List(val event: String?) : OnCmd
        data class Remove(
            val event: String,
            val name: String,
            val alsoPersist: Boolean,
        ) : OnCmd
        data class Add(val hook: OnHook) : OnCmd
    }
    data class Error(val message: String) : ParsedInput
}

/**
 * Parses the argument list for /on. Supported syntax:
 *
 *   /on -l [event]                              List hooks (optionally filtered).
 *   /on -r [-p] <event> <name>                  Remove a hook by event+name.
 *   /on [-p] [-name N] [-n mask] [-c #ch]
 *       [-k network] [-pat pattern] <event> <command...>
 *
 * The trailing <command> is the action to run; it may contain {var} placeholders.
 */
private fun parseOn(args: String): ParsedInput {
    if (args.isBlank()) return ParsedInput.OnCmd.List(null)
    val toks = tokenizeOn(args)
    var i = 0
    var listMode = false
    var removeMode = false
    var persist = false
    var name = ""
    var nickMask: String? = null
    var channelFilter: String? = null
    var network: String? = null
    var pattern: String? = null
    var desktop = false
    var alertChannel: String? = null
    var suppress = false
    var quiet = false
    val positional = mutableListOf<String>()
    while (i < toks.size) {
        when (val t = toks[i]) {
            "-l", "-list" -> { listMode = true; i++ }
            "-r", "-remove" -> { removeMode = true; i++ }
            "-p", "-persist" -> { persist = true; i++ }
            "-name" -> { name = toks.getOrNull(i + 1) ?: ""; i += 2 }
            "-n" -> { nickMask = toks.getOrNull(i + 1); i += 2 }
            "-c" -> { channelFilter = toks.getOrNull(i + 1); i += 2 }
            "-k" -> { network = toks.getOrNull(i + 1); i += 2 }
            "-pat" -> { pattern = toks.getOrNull(i + 1); i += 2 }
            "-d" -> { desktop = true; i++ }
            "-ac" -> { alertChannel = toks.getOrNull(i + 1)?.lowercase(); i += 2 }
            "-x" -> { suppress = true; i++ }
            "-q" -> { quiet = true; i++ }
            else -> {
                if (t.startsWith("-")) return ParsedInput.Error("/on: unknown flag $t")
                // First non-flag is event; rest is action.
                positional += t
                i++
                if (positional.size == 1 && !listMode && !removeMode) {
                    // Concatenate the rest of the line as the action verbatim.
                    val rest = toks.drop(i).joinToString(" ")
                    if (rest.isNotEmpty()) positional += rest
                    break
                }
            }
        }
    }
    if (listMode) return ParsedInput.OnCmd.List(positional.firstOrNull()?.lowercase())
    if (removeMode) {
        if (positional.size < 2) return ParsedInput.Error("Usage: /on -r [-p] <event> <name>")
        return ParsedInput.OnCmd.Remove(
            event = positional[0].lowercase(),
            name = positional[1],
            alsoPersist = persist,
        )
    }
    if (positional.size < 2)
        return ParsedInput.Error(
            "Usage: /on [-p] [-name N] [-n mask] [-c #ch] [-k net] [-pat pat] <event> <command>"
        )
    val event = positional[0].lowercase()
    val action = if (positional.size > 1) positional[1] else ""
    if (event !in OnHookEvents.all)
        return ParsedInput.Error("/on: unknown event '$event' (try /help on)")
    if (action.isBlank() && !desktop && alertChannel == null)
        return ParsedInput.Error("/on: hook needs at least an action, -d, or -ac")
    return ParsedInput.OnCmd.Add(
        OnHook(
            name = name,
            event = event,
            networkFilter = network,
            channelFilter = channelFilter,
            nickMaskFilter = nickMask,
            textPattern = pattern,
            action = action,
            persistent = persist,
            desktop = desktop,
            channel = alertChannel,
            suppressDefault = suppress,
            suppressNotification = quiet,
        )
    )
}

private fun tokenizeOn(s: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote: Char? = null
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            quote != null -> { if (c == quote) quote = null else cur.append(c) }
            c == '"' || c == '\'' -> { quote = c }
            c.isWhitespace() -> { if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() } }
            else -> cur.append(c)
        }
        i++
    }
    if (cur.isNotEmpty()) out += cur.toString()
    return out
}

object SlashCommand {
    fun parse(input: String): ParsedInput {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/") || trimmed.startsWith("//")) {
            // "//" escape: send literal message starting with "/"
            val text = if (trimmed.startsWith("//")) trimmed.substring(1) else trimmed
            return ParsedInput.Message(text)
        }
        val rest = trimmed.substring(1)
        val cmd = rest.substringBefore(' ').lowercase()
        val args = rest.substringAfter(' ', missingDelimiterValue = "").trim()
        return when (cmd) {
            "join", "j" -> {
                if (args.isBlank()) ParsedInput.Error("Usage: /join <#channel>")
                else ParsedInput.Join(args.substringBefore(' '))
            }
            "part", "leave" -> {
                if (args.isBlank()) ParsedInput.Part(null, null)
                else if (args.startsWith("#") || args.startsWith("&")) {
                    val ch = args.substringBefore(' ')
                    val reason = args.substringAfter(' ', "").ifBlank { null }
                    ParsedInput.Part(ch, reason)
                } else ParsedInput.Part(null, args)
            }
            "msg", "query" -> {
                val target = args.substringBefore(' ')
                val text = args.substringAfter(' ', "")
                if (target.isBlank() || text.isBlank())
                    ParsedInput.Error("Usage: /msg <nick> <text>")
                else ParsedInput.Msg(target, text)
            }
            "me", "action" -> {
                if (args.isBlank()) ParsedInput.Error("Usage: /me <text>")
                else ParsedInput.Action(args)
            }
            "nick" -> {
                if (args.isBlank()) ParsedInput.Error("Usage: /nick <newnick>")
                else ParsedInput.Nick(args.substringBefore(' '))
            }
            "quit" -> ParsedInput.Quit(args.ifBlank { null })
            "raw", "quote" -> {
                if (args.isBlank()) ParsedInput.Error("Usage: /raw <line>")
                else ParsedInput.Raw(args)
            }
            "whois" -> {
                if (args.isBlank()) ParsedInput.Error("Usage: /whois <nick>")
                else ParsedInput.Whois(args.substringBefore(' '))
            }
            "away" -> ParsedInput.Away(args.ifBlank { null })
            "topic" -> {
                if (args.isBlank()) ParsedInput.Topic(null, null)
                else if (args.startsWith("#") || args.startsWith("&")) {
                    val ch = args.substringBefore(' ')
                    val text = args.substringAfter(' ', "").ifBlank { null }
                    ParsedInput.Topic(ch, text)
                } else ParsedInput.Topic(null, args)
            }
            "kick" -> {
                val parts = args.split(Regex("\\s+"), limit = 3)
                when {
                    parts.isEmpty() || parts[0].isBlank() ->
                        ParsedInput.Error("Usage: /kick [#chan] <nick> [reason]")
                    parts[0].startsWith("#") || parts[0].startsWith("&") -> {
                        if (parts.size < 2) ParsedInput.Error("Usage: /kick #chan <nick> [reason]")
                        else ParsedInput.Kick(parts[0], parts[1], parts.getOrNull(2))
                    }
                    else -> ParsedInput.Kick(null, parts[0], parts.drop(1).joinToString(" ").ifBlank { null })
                }
            }
            "mode" -> {
                val parts = args.split(' ', limit = 2)
                when {
                    args.isBlank() -> ParsedInput.Error("Usage: /mode <target> <flags...>")
                    else -> ParsedInput.Mode(parts[0], parts.getOrNull(1) ?: "")
                }
            }
            "invite" -> {
                val parts = args.split(' ', limit = 2)
                if (parts.isEmpty() || parts[0].isBlank())
                    ParsedInput.Error("Usage: /invite <nick> [#chan]")
                else ParsedInput.Invite(parts[0], parts.getOrNull(1))
            }
            "names" -> ParsedInput.Names(args.ifBlank { null })
            "notice" -> {
                val parts = args.split(' ', limit = 2)
                if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank())
                    ParsedInput.Error("Usage: /notice <target> <text>")
                else ParsedInput.NoticeCmd(parts[0], parts[1])
            }
            "ctcp" -> {
                val parts = args.split(' ', limit = 3)
                if (parts.size < 2 || parts[0].isBlank())
                    ParsedInput.Error("Usage: /ctcp <nick> <TAG> [data]")
                else ParsedInput.Ctcp(parts[0], parts[1].uppercase(), parts.getOrNull(2))
            }
            "list" -> ParsedInput.ListCmd(args)
            "save", "export" -> ParsedInput.Save
            "chaninfo", "chanmodes" -> ParsedInput.ChanInfo(args.ifBlank { null })
            "cycle", "rejoin" -> ParsedInput.Cycle
            "hop" -> ParsedInput.Hop
            "unban" -> {
                val parts = args.split(Regex("\\s+"), limit = 2)
                when {
                    args.isBlank() -> ParsedInput.Error("Usage: /unban [#chan] <mask>")
                    parts[0].startsWith("#") || parts[0].startsWith("&") -> {
                        if (parts.size < 2) ParsedInput.Error("Usage: /unban #chan <mask>")
                        else ParsedInput.Unban(parts[0], parts[1])
                    }
                    else -> ParsedInput.Unban(null, args)
                }
            }
            "motd" -> ParsedInput.Motd(args.ifBlank { null })
            "lusers" -> ParsedInput.Lusers
            "admin" -> ParsedInput.Admin(args.ifBlank { null })
            "time" -> ParsedInput.Time(args.ifBlank { null })
            "version" -> ParsedInput.Version(args.ifBlank { null })
            "stats" -> ParsedInput.Stats(args)
            "info" -> ParsedInput.Info(args.ifBlank { null })
            "links" -> ParsedInput.Links(args)
            "who" -> ParsedInput.Who(args)
            "userhost" -> {
                if (args.isBlank()) ParsedInput.Error("Usage: /userhost <nick> [<nick>...]")
                else ParsedInput.Userhost(args)
            }
            "ping" -> {
                if (args.isBlank()) ParsedInput.Error("Usage: /ping <nick>")
                else ParsedInput.Ping(args.substringBefore(' '))
            }
            "server" -> ParsedInput.Server(args)
            "connect" -> ParsedInput.Connect(args)
            "reconnect" -> ParsedInput.Reconnect
            "nextserver", "failover" -> ParsedInput.NextServer
            "clear" -> ParsedInput.Clear
            "echo" -> ParsedInput.Echo(args)
            "help", "?" -> ParsedInput.Help(
                args.ifBlank { null }?.removePrefix("/")?.lowercase()
            )
            "on" -> parseOn(args)
            "noop" -> ParsedInput.Noop
            "say" -> {
                if (args.isBlank()) ParsedInput.Error("Usage: /say <text>")
                else ParsedInput.Say(args)
            }
            "window", "win", "buffer" -> {
                // /window [-k network] <name>
                // Also accepts network/name shorthand (e.g. Libera/#pocketirc).
                val toks = tokenizeOn(args)
                var network: String? = null
                val rest = mutableListOf<String>()
                var i = 0
                while (i < toks.size) {
                    val t = toks[i]
                    if (t == "-k" && i + 1 < toks.size) { network = toks[i + 1]; i += 2 }
                    else { rest += t; i++ }
                }
                if (rest.isEmpty()) ParsedInput.Error("Usage: /window [-k network] <#chan|nick|status>")
                else {
                    var name = rest[0]
                    // Shorthand: "network/name"
                    if (network == null && name.contains('/')) {
                        val slash = name.indexOf('/')
                        network = name.substring(0, slash)
                        name = name.substring(slash + 1)
                    }
                    if (name.isBlank()) ParsedInput.Error("Usage: /window [-k network] <#chan|nick|status>")
                    else ParsedInput.Window(name, network)
                }
            }
            "set" -> {
                val parts = args.split(' ', limit = 2)
                when {
                    args.isBlank() -> ParsedInput.Set(null, null)
                    parts.size == 1 -> ParsedInput.Set(parts[0], null)
                    else -> ParsedInput.Set(parts[0], parts[1])
                }
            }
            "alert" -> {
                // /alert [-c <channel-name>] <text>
                val toks = tokenizeOn(args)
                var channel: String? = null
                var i = 0
                val rest = StringBuilder()
                while (i < toks.size) {
                    val t = toks[i]
                    when {
                        t == "-c" && i + 1 < toks.size -> { channel = toks[i + 1].lowercase(); i += 2 }
                        else -> { if (rest.isNotEmpty()) rest.append(' '); rest.append(t); i++ }
                    }
                }
                if (rest.isEmpty())
                    ParsedInput.Error("Usage: /alert [-c <channel-name>] <text>")
                else ParsedInput.Alert(rest.toString(), channel)
            }
            "notify" -> {
                // /notify                  list watched nicks
                // /notify <nick>...        add to notify list
                // /notify -r <nick>...     remove from notify list
                val toks = tokenizeOn(args)
                when {
                    toks.isEmpty() -> ParsedInput.NotifyList.Show
                    toks[0] == "-l" || toks[0] == "-list" -> ParsedInput.NotifyList.Show
                    toks[0] == "-r" || toks[0] == "-remove" -> {
                        if (toks.size < 2) ParsedInput.Error("Usage: /notify -r <nick>...")
                        else ParsedInput.NotifyList.Remove(toks.drop(1))
                    }
                    else -> ParsedInput.NotifyList.Add(toks)
                }
            }
            else -> ParsedInput.Error("Unknown command: /$cmd  (try /help)")
        }
    }
}
