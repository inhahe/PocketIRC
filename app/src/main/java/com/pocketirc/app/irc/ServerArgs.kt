package com.pocketirc.app.irc

import com.pocketirc.app.model.SaslConfig
import com.pocketirc.app.model.ServerConfig
import com.pocketirc.app.model.ServerEndpoint

/**
 * Parses /server-style argument strings into a sparse [Patch] of fields the
 * user actually specified, plus three boolean modes (createNew, skipConnect,
 * persist). Callers can then either:
 *
 *   - apply the patch to an existing [ServerConfig] (the "/server <new-host>
 *     in a connected window" case — the entry stays put, the underlying host
 *     gets swapped), or
 *   - materialize a brand-new [ServerConfig] from the patch (the "/server -m"
 *     or no-current-context case).
 *
 * The patch carries only what the user typed. Fields the user didn't mention
 * are null and are inherited from the base config (or defaulted, when there
 * is no base).
 */
object ServerArgs {

    data class Patch(
        val host: String? = null,
        val port: Int? = null,
        val tls: Boolean? = null,
        /**
         * Per-network nick chain. The first entry is the primary; the rest
         * are tried in order if the primary is taken. `null` = user did not
         * pass `-nick` (so the saved network keeps its existing chain, or a
         * new network inherits the global default).
         */
        val nicks: List<String>? = null,
        val user: String? = null,
        val realName: String? = null,
        val displayName: String? = null,
        val serverPass: String? = null,
        val sasl: SaslConfig? = null,
        val createNew: Boolean = false,
        val skipConnect: Boolean = false,
        val persist: Boolean = false,
    )

    sealed interface Result {
        data class Ok(val patch: Patch) : Result
        data class Err(val message: String) : Result
    }

    fun parse(input: String): Result {
        val tokens = tokenize(input)
        if (tokens.isEmpty()) return Result.Err("Usage: /server [flags] <host[:port]> [pass]")

        var nicks: List<String>? = null
        var user: String? = null
        var realName: String? = null
        var serverPass: String? = null
        var saslUser: String? = null
        var saslPass: String? = null
        var displayName: String? = null
        var forceTls = false
        var createNew = false
        var skipConnect = false
        var persist = false
        val positional = mutableListOf<String>()

        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            when {
                tok == "-e" || tok == "-t" -> { forceTls = true; i++ }
                tok == "-m" -> { createNew = true; i++ }
                tok == "-n" -> { skipConnect = true; i++ }
                tok == "-p" -> { persist = true; i++ }
                // Recognize but ignore qtpyrc flags that don't map to us:
                tok == "-d" || tok == "-o" || tok == "-c" || tok == "-u" ||
                tok == "-z" || tok == "-4" || tok == "-6" || tok == "-46" -> { i++ }
                tok == "-nick" || tok == "-altnick" -> {
                    // Single flag for the whole nick chain. Comma- or
                    // whitespace-separated; the first entry is the primary,
                    // the rest are alt-nicks tried in order. -altnick is
                    // accepted as an alias for muscle-memory carryover from
                    // the previous two-flag form.
                    if (i + 1 >= tokens.size) return Result.Err("$tok needs a value")
                    val list = tokens[i + 1]
                        .split(Regex("[\\s,]+"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (list.isEmpty()) return Result.Err("$tok: empty list")
                    for (n in list) {
                        NickValidator.reasonInvalid(n)?.let {
                            return Result.Err("$tok '$n': $it")
                        }
                    }
                    nicks = list
                    i += 2
                }
                tok == "-user" -> {
                    if (i + 1 >= tokens.size) return Result.Err("-user needs a value")
                    user = tokens[i + 1]; i += 2
                }
                tok == "-realname" -> {
                    if (i + 1 >= tokens.size) return Result.Err("-realname needs a value")
                    realName = tokens[i + 1]; i += 2
                }
                tok == "-name" -> {
                    if (i + 1 >= tokens.size) return Result.Err("-name needs a value")
                    displayName = tokens[i + 1]; i += 2
                }
                tok == "-w" -> {
                    if (i + 1 >= tokens.size) return Result.Err("-w needs a password")
                    serverPass = tokens[i + 1]; i += 2
                }
                tok == "-l" -> {
                    if (i + 2 >= tokens.size) return Result.Err("-l needs <user> <pass>")
                    saslUser = tokens[i + 1]
                    saslPass = tokens[i + 2]
                    i += 3
                }
                tok.startsWith("-") -> return Result.Err("Unknown flag: $tok")
                else -> { positional += tok; i++ }
            }
        }

        if (positional.isEmpty()) return Result.Err("Usage: /server [flags] <host[:port]> [pass]")

        val hostArg = positional[0]
        val (host, port, tlsFromPort) = parseHostPort(hostArg)
        val tls = forceTls || tlsFromPort
        if (positional.size > 1 && serverPass == null) {
            serverPass = positional[1]
        }

        val sasl = if (saslUser != null && saslPass != null)
            SaslConfig(username = saslUser, password = saslPass) else null

        return Result.Ok(
            Patch(
                host = host,
                port = port,
                tls = tls,
                nicks = nicks,
                user = user,
                realName = realName,
                displayName = displayName,
                serverPass = serverPass,
                sasl = sasl,
                createNew = createNew,
                skipConnect = skipConnect,
                persist = persist,
            )
        )
    }

    /**
     * Build a brand-new [ServerConfig] from a patch. Identity fields the
     * user didn't specify are left null so they inherit from the global
     * defaults at resolution time via [com.pocketirc.app.irc.ConnectionManager.resolveIdentity].
     */
    fun toNewConfig(patch: Patch): ServerConfig {
        val h = patch.host ?: "localhost"
        val p = patch.port ?: 6697
        val t = patch.tls ?: true
        return ServerConfig(
            id = "$h:$p",
            name = patch.displayName ?: h,
            displayNameLocked = patch.displayName != null,
            endpoints = listOf(ServerEndpoint(host = h, port = p, tls = t)),
            nicks = patch.nicks,
            realName = patch.realName,
            userName = patch.user,
            sasl = patch.sasl,
            serverPassword = patch.serverPass,
            autoReconnect = !(patch.persist && patch.skipConnect),
        )
    }

    /**
     * Apply a patch to [base], producing a modified copy. Fields the user
     * didn't specify are inherited from base unchanged. The id is preserved
     * — the entry stays at the same position in the network tree, with the
     * same buffers, history, autojoin list, etc. Only what the user actually
     * typed is changed.
     */
    fun applyTo(patch: Patch, base: ServerConfig): ServerConfig {
        val newEndpoint = if (patch.host != null || patch.port != null || patch.tls != null) {
            val baseEp = base.endpoints.firstOrNull()
            ServerEndpoint(
                host = patch.host ?: baseEp?.host ?: "localhost",
                port = patch.port ?: baseEp?.port ?: 6697,
                tls = patch.tls ?: baseEp?.tls ?: true,
                ipv4 = baseEp?.ipv4 ?: true,
                ipv6 = baseEp?.ipv6 ?: true,
            )
        } else null

        // Replace the *first* endpoint with the new one (so failover via
        // endpoint cycling still has the rest of the list as fallback). If
        // the user didn't change the host at all, leave endpoints untouched.
        val newEndpoints = if (newEndpoint != null) {
            val rest = base.endpoints.drop(1)
            listOf(newEndpoint) + rest
        } else base.endpoints

        return base.copy(
            // Keep the same id! That's the whole point — the entry doesn't
            // move in the tree.
            name = patch.displayName ?: base.name,
            displayNameLocked = if (patch.displayName != null) true else base.displayNameLocked,
            endpoints = newEndpoints,
            nicks = patch.nicks ?: base.nicks,
            realName = patch.realName ?: base.realName,
            userName = patch.user ?: base.userName,
            sasl = patch.sasl ?: base.sasl,
            serverPassword = patch.serverPass ?: base.serverPassword,
        )
    }

    /** Parses host[:port] / host[:+port]. Returns (host, port, tlsFromPort). */
    private fun parseHostPort(s: String): Triple<String, Int, Boolean> {
        val idx = s.lastIndexOf(':')
        if (idx < 0) return Triple(s, 6697, true)
        val host = s.substring(0, idx)
        var portStr = s.substring(idx + 1)
        var tls = false
        if (portStr.startsWith("+")) { tls = true; portStr = portStr.drop(1) }
        else if (portStr.startsWith("*")) { tls = true; portStr = portStr.drop(1) }
        val port = portStr.toIntOrNull() ?: 6697
        return Triple(host, port, tls)
    }

    /** Simple shell-style tokenizer that respects single and double quotes. */
    private fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null -> {
                    if (c == quote) { quote = null }
                    else cur.append(c)
                }
                c == '"' || c == '\'' -> { quote = c }
                c.isWhitespace() -> {
                    if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
